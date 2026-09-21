package wedgie.front

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** Step 5: prove a Laminar element mounts inside the webview.
  *
  * Deliberately *not* the model bridge. anywidget hands `render` a context
  * object carrying `model` and `el`; this ignores `model` entirely, so that
  * "Laminar mounts at all" and "sync works" stay separable failure modes. Wiring
  * `model` to a `Var` is the next step, and is much easier to debug once this
  * one is known good.
  *
  * Exported as a *named* top-level `render` because that is what Scala.js emits;
  * the `export default { render }` wrapper the anywidget AFM contract wants is
  * supplied by the `_esm` shim in [[wedgie.EsmSource]].
  */
object Entry:

  @JSExportTopLevel("render")
  def render(ctx: js.Dynamic): Unit =
    val el    = ctx.el.asInstanceOf[dom.Element]
    val ticks = Var(0)

    val _ = L.render(
      el,
      div(
        p("wedgie: Laminar is mounted."),
        button(
          "local clicks: ",
          child.text <-- ticks.signal.map(_.toString),
          onClick --> { _ => ticks.update(_ + 1) }
        ),
        p(
          fontSize := "0.8em",
          "This counter is browser-local. Nothing here talks to the kernel yet — ",
          "a working button proves only that the JavaScript runs."
        )
      )
    )
