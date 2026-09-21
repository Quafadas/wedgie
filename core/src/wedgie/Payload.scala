package wedgie

import upickle.default.Writer

/** Every JSON payload that crosses the wire, constructed in one place.
  *
  * Pure, so the shape of the protocol can be tested without a running kernel.
  */
object Payload:

  /** `comm_open` data.
    *
    * Widget meta keys and user state share one flat namespace, which is why
    * [[StateSync.validate]] exists and runs here — before any comm is opened,
    * so a bad state type fails loudly instead of producing a silent no-widget. */
  def commOpen[S: Writer](state: S, esm: EsmSource, css: Option[String]): ujson.Obj =
    val stateObj = StateSync.validate(StateSync.encode(state))
    val merged   = ujson.Obj()
    Protocol.modelMeta(esm.esm, css).foreach: (key, value) =>
      merged.value(key) = value
    stateObj.value.foreach: (key, value) =>
      merged.value(key) = value
    ujson.Obj("state" -> merged, "buffer_paths" -> ujson.Arr())

  /** `comm_open` metadata. Not optional: a missing or wrong version produces no
    * widget at all, and the only trace is a line in the webview console. */
  def openMetadata: ujson.Obj =
    ujson.Obj("version" -> Protocol.ProtocolVersion)

  /** State sync, used in both directions. No reply expected. */
  def update(state: ujson.Obj): ujson.Obj =
    ujson.Obj(
      "method"       -> Protocol.Method.Update,
      "state"        -> state,
      "buffer_paths" -> ujson.Arr()
    )

  def custom(content: ujson.Value): ujson.Obj =
    ujson.Obj("method" -> Protocol.Method.Custom, "content" -> content)

  /** The `display_data` payload that actually puts a view on screen.
    * `modelId` must be the comm id — this is the join that, when wrong, renders
    * nothing and reports nothing. */
  def view(modelId: String): ujson.Obj =
    ujson.Obj("model_id" -> modelId, "version_major" -> 2, "version_minor" -> 0)
