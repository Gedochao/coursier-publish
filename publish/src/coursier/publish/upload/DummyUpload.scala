package coursier.publish.upload

import coursier.core.Authentication
import coursier.publish.upload.logger.UploadLogger

final case class DummyUpload(underlying: Upload) extends Upload {
  def upload(
    url: String,
    authentication: Option[Authentication],
    content: Array[Byte],
    logger: UploadLogger,
    loggingId: Option[Object]
  ): Option[Upload.Error] = None
}
