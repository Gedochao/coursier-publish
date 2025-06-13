package coursier.publish.sonatype

import java.nio.charset.StandardCharsets
import java.util as ju

import scala.util.Try
import scala.util.hashing.MurmurHash3

// adapted from https://github.com/plokhotnyuk/jsoniter-scala/blob/209d918a030b188f064ee55505a6c47257731b4b/jsoniter-scala-macros/src/test/scala/com/github/plokhotnyuk/jsoniter_scala/macros/JsonCodecMakerSpec.scala#L645-L666
final case class RawJson(value: Array[Byte]) {
  override lazy val hashCode: Int        = MurmurHash3.arrayHash(value)
  override def equals(obj: Any): Boolean = obj match {
    case that: RawJson => ju.Arrays.equals(value, that.value)
    case _             => false
  }
  override def toString: String =
    Try(new String(value, StandardCharsets.UTF_8))
      .toOption
      .getOrElse(value.toString)
}

object RawJson {
  import com.github.plokhotnyuk.jsoniter_scala.core.*

  implicit val codec: JsonValueCodec[RawJson] = new JsonValueCodec[RawJson] {
    def decodeValue(in: JsonReader, default: RawJson): RawJson =
      new RawJson(in.readRawValAsBytes())
    def encodeValue(x: RawJson, out: JsonWriter): Unit =
      out.writeRawVal(x.value)
    val nullValue: RawJson =
      new RawJson(new Array[Byte](0))
  }

  val emptyObj: RawJson =
    RawJson("{}".getBytes(StandardCharsets.UTF_8))
}
