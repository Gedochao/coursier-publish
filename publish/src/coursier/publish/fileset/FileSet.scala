package coursier.publish.fileset

import coursier.core.{ModuleName, Organization}
import coursier.publish.Content
import coursier.publish.Pom.{Developer, License}
import coursier.util.Task

import java.time.Instant
import java.util.concurrent.ExecutorService

final case class FileSet(elements: Seq[(Path, Content)]) {
  def ++(other: FileSet): FileSet = {
    // complexity possibly not too optimal… (removeAll iterates on all elements)
    val cleanedUp = other.elements.map(_._1).foldLeft(this)(_.removeAll(_))
    FileSet(cleanedUp.elements ++ other.elements)
  }
  def filterOutExtension(extension: String): FileSet = {
    val suffix = "." + extension
    FileSet(elements.filter(_._1.elements.lastOption.forall(!_.endsWith(suffix))))
  }
  def isEmpty: Boolean =
    elements.isEmpty

  /** Removes anything looking like a checksum or signature related to `path` */
  def removeAll(path: Path): FileSet = {

    val prefix         = path.repr + "."
    val (remove, keep) = elements.partition {
      case (p, _) =>
        p == path || p.repr.startsWith(prefix)
    }

    if remove.isEmpty then this else FileSet(keep)
  }

  def update(path: Path, content: Content): FileSet =
    ++(FileSet(Seq(path -> content)))

  def updateMetadata(
    org: Option[Organization],
    name: Option[ModuleName],
    version: Option[String],
    licenses: Option[Seq[License]],
    developers: Option[Seq[Developer]],
    homePage: Option[String],
    gitDomainPath: Option[(String, String)],
    distMgmtRepo: Option[(String, String, String)],
    now: Instant,
    pool: ExecutorService
  ): Task[FileSet] = {
    val split = Group.split(this)

    val adjustOrgName =
      if org.isEmpty && name.isEmpty then Task.point(split)
      else {
        val map = split.map {
          case m: Group.Module =>
            (m.organization, m.name) -> (org.getOrElse(m.organization), name.getOrElse(m.name))
          case m: Group.MavenMetadata =>
            (m.organization, m.name) -> (org.getOrElse(m.organization), name.getOrElse(m.name))
        }.toMap

        Task.gather.gather {
          split.map { m =>
            Task.schedule(pool)(m.transform(map, now))
          }
        }
      }

    val adjustVersion: Task[Seq[Group]] =
      version match {
        case Some(ver) =>
          adjustOrgName.flatMap { groups =>
            val map = groups
              .collect {
                case m: Group.Module => (m.organization, m.name) -> (m.version -> ver)
              }
              .toMap
            Task.sync.gather {
              groups.map { group =>
                Task.schedule(pool)(group.transformVersion(map, now))
              }
            }
          }
        case None =>
          adjustOrgName
      }

    adjustVersion.flatMap { l =>
      Task.gather.gather {
        l.map {
          case m: Group.Module =>
            Task.schedule(pool) {
              m.updateMetadata(
                org,
                name,
                version,
                licenses,
                developers,
                homePage,
                gitDomainPath,
                distMgmtRepo,
                now
              )
            }
          case m: Group.MavenMetadata =>
            Task.schedule(pool) {
              m.updateContent(
                org,
                name,
                version,
                version.filter(!_.endsWith("SNAPSHOT")),
                version.toSeq,
                now
              )
            }
        }
      }
    }.flatMap { groups =>
      Group.merge(groups) match {
        case Left(e)   => Task.fail(new Exception(e))
        case Right(fs) => Task.point(fs)
      }
    }
  }

  def order(pool: ExecutorService): Task[FileSet] = {
    val split = Group.split(this)

    def order(m: Map[Group.Module, Seq[coursier.core.Module]]): LazyList[Group.Module] =
      if m.isEmpty then LazyList.empty
      else {
        val (now, later) = m.partition(_._2.isEmpty)

        // FIXME Report that properly
        if now.isEmpty then throw new Exception(s"Found cycle in input modules\n$m")

        val prefix = now
          .keys
          .toVector
          .sortBy(_.module.toString) // sort to make output deterministic
          .to(LazyList)

        val done = now.keySet.map(_.module)

        val later0 = later.view.mapValues(_.filterNot(done)).iterator.toMap

        prefix #::: order(later0)
      }

    val sortedModulesTask = Task.gather
      .gather {
        split.collect {
          case m: Group.Module =>
            Task.schedule(pool)(m.dependenciesOpt).map((m, _))
        }
      }
      .map { l =>
        val m                 = l.toMap
        val current           = m.keySet.map(_.module)
        val interDependencies = m.view.mapValues(_.filter(current)).iterator.toMap
        order(interDependencies).toVector
      }

    val mavenMetadataMap = split
      .collect {
        case m: Group.MavenMetadata =>
          m.module -> m
      }
      .toMap // shouldn't discard values… assert it?

    sortedModulesTask.map { sortedModules =>

      val modules = sortedModules.map(_.module).toSet

      val unknownMavenMetadata = mavenMetadataMap
        .view
        .filterKeys(!modules(_))
        .values
        .toVector
        .sortBy(_.module.toString) // sort to make output deterministic

      val modulesWithMavenMetadata = sortedModules.flatMap { m =>
        m +: mavenMetadataMap.get(m.module).toSeq
      }

      val sortedGroups = (modulesWithMavenMetadata ++ unknownMavenMetadata)
        .map(_.ordered)
      Group.mergeUnsafe(sortedGroups)
    }
  }
}

object FileSet {
  val empty: FileSet = FileSet(Nil)
}
