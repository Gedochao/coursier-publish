package coursier.publish.upload

import coursier.cache.CacheUrl
import coursier.core.Authentication
import coursier.publish.upload.logger.UploadLogger

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import java.net.{HttpURLConnection, URL}
import java.nio.charset.StandardCharsets

import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal

final case class HttpURLConnectionUpload(
  urlSuffix: String,
  readTimeoutMsOpt: Option[Int],
  connectTimeoutMsOpt: Option[Int]
) extends Upload {
  def upload(
    url: String,
    authentication: Option[Authentication],
    content: Array[Byte],
    logger: UploadLogger,
    loggingIdOpt: Option[Object]
  ): Option[Upload.Error] = {
    logger.uploading(url, loggingIdOpt, Some(content.length))

    val res = Try {
      val url0 = new URL(url + urlSuffix)

      val conn = url0.openConnection().asInstanceOf[HttpURLConnection]

      conn.setRequestMethod("PUT")
      readTimeoutMsOpt
        .foreach(conn.setReadTimeout)
      connectTimeoutMsOpt
        .foreach(conn.setConnectTimeout)

      for (auth <- authentication; (k, v) <- auth.allHttpHeaders)
        conn.setRequestProperty(k, v)

      conn.setDoOutput(true)

      conn.setRequestProperty("Content-Type", "application/octet-stream")
      conn.setRequestProperty("Content-Length", content.length.toString)

      var is: InputStream  = null
      var es: InputStream  = null
      var os: OutputStream = null

      try {
        os = conn.getOutputStream
        os.write(content)
        os.close()

        val code = conn.getResponseCode
        if code == 401 then {
          val realmOpt = Option(conn.getRequestProperty("WWW-Authenticate")).collect {
            case CacheUrl.BasicRealm(r) => r
          }
          Some(new Upload.Error.Unauthorized(url, realmOpt))
        }
        else if code / 100 == 2 then None
        else {
          val content = {
            es = Option(conn.getErrorStream)
              .orElse(Try(conn.getInputStream).toOption).orNull

            if es != null then {
              val buf  = Array.ofDim[Byte](16384)
              val baos = new ByteArrayOutputStream
              var read = -1
              while ({
                read = es.read(buf)
                read >= 0
              })
                baos.write(buf, 0, read)
              es.close()
              // FIXME Adjust charset with headers?
              Try(new String(baos.toByteArray, StandardCharsets.UTF_8))
                .getOrElse("")
            }
            else ""
          }

          Some(
            new Upload.Error.HttpError(
              code,
              conn.getHeaderFields.asScala.view.mapValues(_.asScala.toList).iterator.toMap,
              content
            )
          )
        }
      }
      finally {
        // Trying to ensure the same connection is being re-used across requests
        // see https://docs.oracle.com/javase/8/docs/technotes/guides/net/http-keepalive.html
        try {
          if os == null then os = conn.getOutputStream
          if os != null then os.close()
        }
        catch {
          case NonFatal(_) =>
        }
        try {
          if is == null then is = conn.getInputStream
          if is != null then {
            val buf = Array.ofDim[Byte](16384)
            while (is.read(buf) > 0) {}
            is.close()
          }
        }
        catch {
          case NonFatal(_) =>
        }
        try {
          if es == null then es = conn.getErrorStream
          if es != null then {
            val buf = Array.ofDim[Byte](16384)
            while (es.read(buf) > 0) {}
            es.close()
          }
        }
        catch {
          case NonFatal(_) =>
        }
      }
    }

    logger.uploaded(
      url,
      loggingIdOpt,
      res.fold(e => Some(new Upload.Error.UploadError(url, e)), x => x)
    )

    res.get
  }
}

object HttpURLConnectionUpload {
  def create(): Upload                  = HttpURLConnectionUpload("", None, None)
  def create(urlSuffix: String): Upload = HttpURLConnectionUpload(urlSuffix, None, None)

  /** Create a HttpURLConnectionUpload with a read timeout
    * @param readTimeoutMs
    *   the response read timeout in miliseconds
    * @param connetionTimeoutMs
    *   the connection timeout in miliseconds
    * @param urlSuffix
    *   the suffix to append to the url
    */
  def create(
    readTimeoutMs: Option[Int],
    connectionTimeoutMs: Option[Int],
    urlSuffix: String = ""
  ): Upload =
    HttpURLConnectionUpload(urlSuffix, readTimeoutMs, connectionTimeoutMs)
}
