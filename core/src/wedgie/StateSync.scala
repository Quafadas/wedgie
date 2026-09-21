package wedgie

import upickle.default.{ReadWriter, Reader, Writer, read, writeJs}

/** ipywidgets sync is per-key and flat.
  *
  * The frontend does `model.get("count")` and `model.on("change:count")`, and an
  * inbound `update` carries only the keys that changed. So `S` must serialise to
  * a flat JSON object whose field names are traitlet keys, and both directions
  * are object-level operations rather than whole-value decodes.
  *
  * Doing that at the `ujson` level rather than in a macro means it works for any
  * `ReadWriter[S]` with no derivation of our own.
  */
object StateSync:

  final class NotAnObject(message: String) extends RuntimeException(message)
  final class ReservedKey(message: String) extends RuntimeException(message)

  def encode[S: Writer](state: S): ujson.Obj =
    writeJs(state) match
      case obj: ujson.Obj => obj
      case other =>
        throw NotAnObject(
          s"Widget state must serialise to a JSON object, because its fields become traitlet keys. Got: ${other.getClass.getSimpleName}"
        )

  def decode[S: Reader](obj: ujson.Obj): S = read[S](obj)

  /** Rejects state whose field names would collide with the widget machinery. */
  def validate(obj: ujson.Obj): ujson.Obj =
    val clashes = obj.value.keys.filter(_.startsWith(Protocol.ReservedPrefix)).toSeq.sorted
    if clashes.nonEmpty then
      throw ReservedKey(
        s"Widget state fields may not start with '${Protocol.ReservedPrefix}': they share a flat namespace with the widget's own traitlets. Offending: ${clashes.mkString(", ")}"
      )
    obj

  /** Inbound: overlay a partial `update` onto the current state.
    *
    * Reserved keys in the patch are dropped rather than trusted — the frontend
    * has no business rewriting `_esm`. */
  def merge[S: ReadWriter](current: S, patch: ujson.Obj): S =
    val base = encode(current)
    patch.value.foreach: (key, value) =>
      if !key.startsWith(Protocol.ReservedPrefix) then base.value(key) = value
    decode[S](base)

  /** Outbound: only the keys that actually changed. */
  def diff(before: ujson.Obj, after: ujson.Obj): ujson.Obj =
    val out = ujson.Obj()
    after.value.foreach: (key, value) =>
      if !before.value.get(key).contains(value) then out.value(key) = value
    out
