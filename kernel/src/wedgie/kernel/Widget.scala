package wedgie.kernel

import almond.interpreter.api.{CommHandler, CommTarget, DisplayData, OutputHandler}
import upickle.default.ReadWriter
import wedgie.{EsmSource, Payload, Protocol, StateSync}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** One anywidget model, its comm, and a value of `S` held in sync across the two.
  *
  * Nothing here assumes a notebook contains only one of these. A widget is a
  * root model with its own comm and its own `display_data`; N of them involve no
  * parent/child relationship and therefore no `IPY_MODEL_` references. Whether
  * you *should* put N in a notebook is a question about `_esm` size, which is
  * [[wedgie.EsmSource]]'s problem, not this class's.
  */
final class Widget[S: ReadWriter] private (
    val modelId: String,
    esm: EsmSource,
    initial: S,
    comms: CommHandler
):

  /** Serialises mutate-and-send.
    *
    * An `AtomicReference` alone is not enough: the diff has to be computed and
    * put on the wire atomically with the swap, or two concurrent updates can
    * reach the frontend in an order that contradicts the final state. Reads stay
    * lock-free. */
  private val writeLock = new Object

  private val current        = AtomicReference[S](initial)
  private val observers      = AtomicReference(Vector.empty[Widget.Change[S] => Unit])
  private val customHandlers = AtomicReference(Vector.empty[ujson.Value => Unit])

  /** Handler failures have nowhere sensible to go. `onMessage` runs on a kernel
    * thread outside any cell execution, so there is no parent message to attach
    * output to, and an escaping exception may take the comm down silently.
    *
    * Until probe 1 settles where handler output actually lands, failures are
    * collected here for inspection from a cell and mirrored to stderr so they at
    * least reach the kernel log. This posture is provisional by design. */
  private val failures = ConcurrentLinkedQueue[Throwable]()

  /** The current state. Safe to read from cell code at any time. */
  def state: S = current.get()

  /** Failures swallowed by inbound handlers or observers since the last clear. */
  def errors: List[Throwable] = failures.iterator().asScala.toList

  def clearErrors(): Unit = failures.clear()

  def set(next: S): S = modify(_ => next)

  /** Applies `f`, sends only the keys that changed, and returns the new state. */
  def modify(f: S => S): S =
    val next = writeLock.synchronized:
      val before = current.get()
      val after  = f(before)
      val delta  = StateSync.diff(StateSync.encode(before), StateSync.encode(after))
      current.set(after)
      if delta.value.nonEmpty then send(Payload.update(delta))
      after
    // Observers run outside the lock. A control driving a real recalculation is
    // the entire point of this library, and that must not block the wire.
    fire(Widget.Change(next, fromFrontend = false))
    next

  /** Observe state changes, from either direction. Handlers run on a kernel
    * thread, not a cell thread, and are wrapped — an exception is recorded in
    * [[errors]] rather than escaping. */
  def onChange(f: Widget.Change[S] => Unit): Unit =
    val _ = observers.updateAndGet(_ :+ f)

  /** Observe `custom` messages that are not wedgie's own bundle protocol. */
  def onCustom(f: ujson.Value => Unit): Unit =
    val _ = customHandlers.updateAndGet(_ :+ f)

  def sendCustom(content: ujson.Value): Unit =
    guard("sendCustom")(send(Payload.custom(content)))

  def close(): Unit = guard("close"):
    comms.commClose(modelId, EmptyJson, EmptyJson)
    comms.unregisterCommId(modelId)

  private def EmptyJson: Array[Byte] = "{}".getBytes(UTF_8)

  /** Metadata is `{}` here deliberately — the protocol version goes on
    * `comm_open` only. */
  private def send(payload: ujson.Obj): Unit =
    comms.commMessage(modelId, ujson.write(payload).getBytes(UTF_8), EmptyJson)

  private def fire(change: Widget.Change[S]): Unit =
    observers.get().foreach(o => guard("observer")(o(change)))

  private def guard(what: String)(body: => Unit): Unit =
    try body
    catch
      case NonFatal(e) =>
        failures.add(e)
        Console.err.println(s"[wedgie] $what failed on model $modelId: $e")

  private[kernel] def onMessage(raw: Array[Byte]): Unit = guard("inbound message"):
    val msg = ujson.read(new String(raw, UTF_8))
    msg.obj.get("method").flatMap(_.strOpt) match

      case Some(Protocol.Method.Update) =>
        val patch = msg.obj.get("state").collect { case o: ujson.Obj => o }.getOrElse(ujson.Obj())
        val next = writeLock.synchronized:
          val merged = StateSync.merge(current.get(), patch)
          current.set(merged)
          merged
        // Deliberately no outbound echo. The frontend already holds this value;
        // sending the diff back is how you get a loop.
        fire(Widget.Change(next, fromFrontend = true))

      case Some(Protocol.Method.RequestState) =>
        writeLock.synchronized(send(Payload.update(StateSync.encode(current.get()))))

      case Some(Protocol.Method.Custom) =>
        handleCustom(msg.obj.getOrElse("content", ujson.Null))

      // echo_update and anything unrecognised: not implemented, ignored.
      case _ => ()

  private def handleCustom(content: ujson.Value): Unit =
    val tag = content.objOpt.flatMap(_.get(EsmSource.Tag)).flatMap(_.strOpt)
    if tag.contains(EsmSource.BundleRequest) then
      esm.servedBundle.foreach: source =>
        send(Payload.custom(ujson.Obj(EsmSource.Tag -> EsmSource.BundleReply, "source" -> source)))
    else customHandlers.get().foreach(h => guard("custom handler")(h(content)))

object Widget:

  /** `fromFrontend` distinguishes a user interacting with the widget from cell
    * code calling [[Widget.set]] — usually the thing an observer wants to know. */
  final case class Change[S](state: S, fromFrontend: Boolean)

  /** Opens the comm, registers the inbound handler, and displays the view.
    *
    * Uses the `CommHandler` primitives rather than its `sender` helper, for two
    * reasons. `sender` returns a `Comm` exposing only `message` and `close` —
    * there is no accessor for the id in any published version — so the id has to
    * be generated and held here regardless. And `sender` calls `commOpen` before
    * `registerCommId`, leaving a window in which an immediate frontend reply has
    * no target to land on; registering first closes it.
    */
  def apply[S: ReadWriter](
      initial: S,
      esm: EsmSource,
      css: Option[String] = None
  )(using comms: CommHandler, out: OutputHandler): Widget[S] =

    if !out.canOutput() then
      throw IllegalStateException(
        "No cell is currently running, so the widget view cannot be displayed. Build the widget from a cell."
      )

    // Built first: validation failures should surface before a comm exists.
    val data    = Payload.commOpen(initial, esm, css)
    val modelId = UUID.randomUUID().toString
    val widget  = new Widget[S](modelId, esm, initial, comms)

    comms.registerCommId(modelId, CommTarget((_, bytes) => widget.onMessage(bytes)))
    comms.commOpen(
      Protocol.TargetName,
      modelId,
      ujson.write(data).getBytes(UTF_8),
      ujson.write(Payload.openMetadata).getBytes(UTF_8)
    )
    out.display(DisplayData(Map(Protocol.ViewMimeType -> ujson.write(Payload.view(modelId)))))
    widget
