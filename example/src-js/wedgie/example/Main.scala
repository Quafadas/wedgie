package wedgie.example

import com.raquo.laminar.api.L.*
import wedgie.front.Bridge

import scala.scalajs.js
import scala.scalajs.js.annotation.JSExportTopLevel

/** The anywidget entry point: a parameter panel driving whatever the kernel
  * wants to recompute.
  *
  * The default export is the AFM factory. Each call builds one `Bridge.instance`
  * with its own `Var[Params]`, so two widgets in a notebook do not share state.
  */
object Main:

  @JSExportTopLevel("default")
  def afm(): js.Dynamic =
    Bridge.instance(Params.prototype, panel)

  private def panel(state: Var[Params]): HtmlElement =
    div(
      div(
        label("n: "),
        input(
          typ   := "range",
          minAttr := "0",
          maxAttr := "100",
          value <-- state.signal.map(_.n.toString),
          onInput.mapToValue --> { v => state.update(_.copy(n = v.toIntOption.getOrElse(0))) }
        ),
        span(child.text <-- state.signal.map(_.n.toString))
      ),
      div(
        label("label: "),
        input(
          typ := "text",
          value <-- state.signal.map(_.label),
          onInput.mapToValue --> { v => state.update(_.copy(label = v)) }
        )
      ),
      div(
        label(
          input(
            typ := "checkbox",
            checked <-- state.signal.map(_.enabled),
            onInput.mapToChecked --> { b => state.update(_.copy(enabled = b)) }
          ),
          " enabled"
        )
      )
    )
