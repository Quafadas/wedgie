package wedgie.kernel

import almond.interpreter.api.{CommHandler, CommTarget, DisplayData, OutputHandler}
import java.nio.charset.StandardCharsets.UTF_8
import scala.collection.mutable

/** Records everything a `Widget` does to the kernel, and lets a test push
  * messages back in as though they came from a frontend. */
final class FakeComms extends CommHandler:

  /** Call order matters: the handler must be registered *before* the comm is
    * opened, or an immediate frontend reply has no target to land on. */
  val calls = mutable.ArrayBuffer.empty[String]

  val targets  = mutable.Map.empty[String, CommTarget]
  val opened   = mutable.ArrayBuffer.empty[(String, String, ujson.Value, ujson.Value)]
  val sent     = mutable.ArrayBuffer.empty[ujson.Value]
  val closed   = mutable.ArrayBuffer.empty[String]

  def registerCommTarget(name: String, target: CommTarget): Unit = ()
  def unregisterCommTarget(name: String): Unit                   = ()

  def registerCommId(id: String, target: CommTarget): Unit =
    calls += s"register:$id"
    targets(id) = target

  def unregisterCommId(id: String): Unit =
    calls += s"unregister:$id"
    targets -= id

  def commOpen(targetName: String, id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    calls += s"open:$id"
    opened += ((targetName, id, ujson.read(new String(data, UTF_8)), ujson.read(new String(metadata, UTF_8))))

  def commMessage(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    calls += s"message:$id"
    sent += ujson.read(new String(data, UTF_8))

  def commClose(id: String, data: Array[Byte], metadata: Array[Byte]): Unit =
    calls += s"close:$id"
    closed += id

  def updateDisplay(displayData: DisplayData): Unit = ()

  /** Deliver a message as though the frontend had sent it. */
  def fromFrontend(id: String, payload: ujson.Value): Unit =
    targets(id).message(id, ujson.write(payload).getBytes(UTF_8))

final class FakeOutput(val allowed: Boolean = true) extends OutputHandler:
  val displayed = mutable.ArrayBuffer.empty[DisplayData]
  def stdout(s: String): Unit                       = ()
  def stderr(s: String): Unit                       = ()
  def display(displayData: DisplayData): Unit       = displayed += displayData
  def updateDisplay(displayData: DisplayData): Unit = ()
  def canOutput(): Boolean                          = allowed
  def messageIdOpt: Option[String]                  = None
  // Added to OutputHandler in 0.14.x; Jupyter "payloads" (set_next_input and
  // friends) are nothing to do with widgets.
  def addPayload(payload: String): Unit             = ()
