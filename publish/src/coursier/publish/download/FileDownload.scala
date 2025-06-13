package coursier.publish.download

import coursier.core.Authentication
import coursier.publish.download.logger.DownloadLogger

import java.nio.file.{Files, Path}
import java.time.Instant

import scala.util.control.NonFatal

/** Copies
  * @param base
  */
final case class FileDownload(base: Path) extends Download {
  private val base0 = base.normalize()
  def downloadIfExists(
    url: String,
    authentication: Option[Authentication],
    logger: DownloadLogger
  ): Option[(Option[Instant], Array[Byte])] = {

    val p = base0.resolve(url).normalize()
    if p.startsWith(base0) then {
      logger.downloadingIfExists(url)
      val res =
        try if Files.isRegularFile(p) then {
            val lastModified = Files.getLastModifiedTime(p).toInstant
            Right(Some((Some(lastModified), Files.readAllBytes(p))))
          }
          else Right(None)
        catch { case NonFatal(e) => Left(e) }
      logger.downloadedIfExists(
        url,
        res.toOption.flatMap(_.map(_._2.length)),
        res.left.toOption.map(e => new Download.Error.FileException(e))
      )

      res match {
        case Left(e)  => throw new Exception(e)
        case Right(v) => v
      }
    }
    else
      throw new Exception(s"Invalid path: $url (base: $base0, p: $p)")
  }
}
