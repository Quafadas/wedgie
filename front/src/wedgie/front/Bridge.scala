package wedgie.front

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import org.scalajs.dom
import upickle.default.ReadWriter
import wedgie.StateSync

import scala.scalajs.js

/** Wires an anywidget model to a Laminar app over a shared state type.
  *
  * This is the mirror of `wedgie.kernel.Widget`: the kernel holds `S` in an
  * `AtomicReference` and syncs it by diffing, and this holds the same `S` in a
  * `Var` and syncs it the same way. `S` is compiled from one source for both
  * ends, so the two cannot disagree about the shape of the state.
  *
  * Built as the AFM **factory form**: the default export is a function, the host
  * calls it once per widget instance, and everything below lives in that call's
  * closure. That is what makes the `Var` per-instance rather than global.
  */
object Bridge:

  /** @param prototype
    *   only its **key set** is used, never its values — every field is read from
    *   the model. It exists because the traitlet namespace is flat and untyped,
    *   so something has to say which keys belong to `S`.
    * @param view
    *   the Laminar app, driven by the shared `Var`.
    */
  def instance[S: ReadWriter](prototype: S, view: Var[S] => HtmlElement): js.Dynamic =

    val template = StateSync.encode(prototype)
    val keys     = template.value.keys.toVector

    var state: Option[Var[S]] = None

    /** Model -> S. Reads every key of `S`; anything the model does not carry
      * falls back to the prototype, so a partial model cannot fail the decode. */
    def readModel(model: js.Dynamic): S =
      val carrier = js.Dynamic.literal()
      keys.foreach(k => carrier.updateDynamic(k)(model.get(k).asInstanceOf[js.Any]))
      // Round-tripping through JSON.stringify is the one conversion that handles
      // every traitlet value shape without a hand-written js.Any matcher.
      val parsed = ujson.read(js.JSON.stringify(carrier)).obj
      val merged = ujson.Obj()
      template.value.foreach: (k, fallback) =>
        merged.value(k) = parsed.getOrElse(k, fallback)
      StateSync.decode[S](merged)

    /** S -> model, sending only what changed.
      *
      * The diff is taken against what the model **currently holds**, not against
      * a remembered value, and that is what stops the feedback loop: a change
      * that arrived *from* the model diffs to empty and writes nothing. It is
      * the same rule as the kernel's "inbound updates are never echoed", and it
      * needs no flag to enforce. */
    def writeModel(model: js.Dynamic, next: S): Unit =
      val delta = StateSync.diff(StateSync.encode(readModel(model)), StateSync.encode(next))
      if delta.value.nonEmpty then
        delta.value.foreach: (k, v) =>
          model.set(k, js.JSON.parse(ujson.write(v)))
        model.save_changes()

    /** Runs once per widget instance, before any view exists. */
    def initialize(ctx: js.Dynamic): Unit =
      val model = ctx.model
      val v     = Var(readModel(model))
      state = Some(v)
      // Per-key subscriptions rather than a bare "change": `change:<key>` is what
      // the AFM examples use, so it is the safer contract to rely on.
      keys.foreach: k =>
        model.on(s"change:$k", (() => v.set(readModel(model))): js.Function0[Unit])

    /** Runs once per view. May run more than once per instance. */
    def render(ctx: js.Dynamic): js.Function0[Unit] =
      val model = ctx.model
      val el    = ctx.el.asInstanceOf[dom.Element]
      val v     = state.getOrElse(sys.error("wedgie: render ran before initialize"))

      val root = L.render(
        el,
        div(
          view(v),
          // Bound to the element's owner, so the subscription dies with the view
          // rather than leaking for the life of the page.
          v.signal --> Observer[S](next => writeModel(model, next))
        )
      )
      () => root.unmount()

    js.Dynamic.literal(
      initialize = ((ctx: js.Dynamic) => initialize(ctx)): js.Function1[js.Dynamic, Unit],
      render = ((ctx: js.Dynamic) => render(ctx)): js.Function1[js.Dynamic, js.Function0[Unit]]
    )
