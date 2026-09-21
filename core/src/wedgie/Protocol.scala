package wedgie

/** The entire coupling to the outside world.
  *
  * Three of these are load-bearing values rather than names we chose:
  * [[ModuleName]], [[ModuleVersion]] and [[ProtocolVersion]]. anywidget is
  * pre-1.0, so a minor bump may break the payload. When that happens the change
  * should be one line here plus a re-run of `notebooks/reference.ipynb`.
  */
object Protocol:

  /** anywidget's npm package name. The host resolves this from a CDN; in VS Code
    * that is the `jupyter.widgetScriptSources` allowlist, which is closed to
    * jsdelivr and unpkg and cannot be extended with our own domain. */
  val ModuleName = "anywidget"

  /** A semver *range*, sent verbatim — do not resolve it to a concrete patch.
    * anywidget publishes `~major.minor.*` deliberately so that hosts which
    * cannot bump the frontend still resolve something. */
  val ModuleVersion = "~0.11.*"

  /** `comm_open` metadata. Validated on major version only: `2.0.0` and `2.1.0`
    * both pass, `1.0.0` is rejected. `2.1.0` buys only `echo_update`, which we
    * have deliberately not implemented. */
  val ProtocolVersion = "2.0.0"

  val TargetName   = "jupyter.widget"
  val ModelName    = "AnyModel"
  val ViewName     = "AnyView"
  val ViewMimeType = "application/vnd.jupyter.widget-view+json"

  /** `data.method` values on an open comm. */
  object Method:
    val Update       = "update"
    val RequestState = "request_state"
    val Custom       = "custom"

    /** Protocol 2.1.0+, rebroadcasts one frontend's change to all others.
      * Not implemented: two engineers, one notebook each. */
    val EchoUpdate = "echo_update"

  /** Synced application state shares a flat namespace with the widget's own
    * traitlets, so anything starting with this is ours, not the user's. */
  val ReservedPrefix = "_"

  /** The widget-machinery half of the `comm_open` state.
    *
    * Everything Python's `AnyWidget.get_state()` emits beyond this is droppable:
    * `_dom_classes`, `_view_count`, `tabbable`, `tooltip`, `_anywidget_id` and —
    * the important one — `layout`. `layout` points at a *separate* `Layout`
    * widget with its own comm, serialised as `IPY_MODEL_<comm_id>`. Omitting it
    * is what keeps child model references, a second comm, and the whole
    * `@jupyter-widgets/base` dependency out of scope.
    */
  def modelMeta(esm: String, css: Option[String]): Seq[(String, ujson.Value)] =
    Seq(
      "_model_module"         -> ujson.Str(ModuleName),
      "_model_module_version" -> ujson.Str(ModuleVersion),
      "_model_name"           -> ujson.Str(ModelName),
      "_view_module"          -> ujson.Str(ModuleName),
      "_view_module_version"  -> ujson.Str(ModuleVersion),
      "_view_name"            -> ujson.Str(ViewName),
      "_esm"                  -> ujson.Str(esm)
    ) ++ css.map(c => "_css" -> ujson.Str(c))
