package wedgie

/** How the anywidget front-end module reaches the browser.
  *
  * `_esm` is ordinary synced model state, so whatever goes in it travels in
  * every `comm_open` and is serialised into the `.ipynb`. A release-mode
  * Laminar + upickle bundle measures around 388 KB, so this choice is what
  * decides whether a notebook can reasonably hold more than one widget.
  *
  * Kept as a sealed seam precisely because the decision is not yet made: it
  * turns on whether the VS Code webview can import from an internal URL, which
  * is probe 3 in `notebooks/probes.ipynb`.
  */
sealed trait EsmSource:
  /** The literal `_esm` traitlet value. */
  def esm: String

  /** Source the kernel should serve on request, for strategies that fetch the
    * bundle over the comm instead of putting it in model state. */
  def servedBundle: Option[String] = None

object EsmSource:

  /** The whole module inline.
    *
    * Simple, works everywhere, needs no network at all — and costs its full size
    * in every `comm_open` and in the saved notebook. Fine for the handwritten
    * JavaScript of a reference widget; untenable for a Scala.js bundle at more
    * than one widget per notebook.
    *
    * The source must carry its own default export: AFM requires one, and a
    * named `export { render }` alone is not enough. A linked `front` bundle
    * satisfies this on its own — `@JSExportTopLevel("default")` emits the
    * factory form — so a Scala.js bundle needs no wrapper here. */
  final case class Inline(source: String) extends EsmSource:
    def esm: String = source

  /** A shim that imports the real bundle from `url`, leaving `_esm` at ~120 bytes.
    *
    * Requires probe 3 to pass. Two constraints specific to this setup: `_esm` is
    * evaluated from a blob URL, so relative imports cannot resolve and `url` must
    * be absolute; and the webview runs on the Windows side while the kernel is in
    * WSL2, so a `localhost` bind inside WSL2 will not be reachable. */
  final case class RemoteImport(url: String) extends EsmSource:
    def esm: String =
      s"""import { render } from "$url";
         |export default { render };
         |""".stripMargin

  /** A shim that asks the *kernel* for the bundle over a `custom` message.
    *
    * The bundle then travels as transient message content rather than model
    * state, so it never reaches the `.ipynb`, and this needs neither a CDN nor a
    * reachable internal host. A promise cached on `globalThis` means the first
    * widget to render fetches it and every later one awaits the same promise —
    * so there is no ordering race and no per-widget cost.
    *
    * UNVERIFIED. This leans on two things that have not been round-tripped yet:
    * `custom` messages in both directions, and anywidget accepting an async
    * `render`. Those are probes 4 and 5. Do not ship this strategy before both
    * pass. */
  final case class CommDelivered(
      source: String,
      cacheKey: String = "__wedgie_bundle_v1"
  ) extends EsmSource:

    override def servedBundle: Option[String] = Some(source)

    def esm: String =
      s"""const KEY = "$cacheKey";
         |
         |function boot(model) {
         |  if (!globalThis[KEY]) {
         |    globalThis[KEY] = new Promise((resolve, reject) => {
         |      const onMsg = (msg) => {
         |        if (!msg || msg.wedgie !== "bundle") return;
         |        model.off("msg:custom", onMsg);
         |        const blob = new Blob([msg.source], { type: "text/javascript" });
         |        import(URL.createObjectURL(blob)).then(resolve, reject);
         |      };
         |      model.on("msg:custom", onMsg);
         |      model.send({ wedgie: "bundle_request" });
         |    });
         |  }
         |  return globalThis[KEY];
         |}
         |
         |export default {
         |  async render(ctx) {
         |    const mod = await boot(ctx.model);
         |    return mod.render(ctx);
         |  }
         |};
         |""".stripMargin

  /** The literal request a [[CommDelivered]] shim sends, and the reply it waits for. */
  private[wedgie] val BundleRequest = "bundle_request"
  private[wedgie] val BundleReply   = "bundle"
  private[wedgie] val Tag           = "wedgie"
