package wedgie.kernel

import almond.interpreter.api.{CommHandler, OutputHandler}
import upickle.default.ReadWriter
import wedgie.{EsmSource, Protocol, StateSync}

case class Counter(count: Int, label: String) derives ReadWriter
case class Clashing(_esm: String) derives ReadWriter

class WidgetTests extends munit.FunSuite:

  private val esm = EsmSource.Inline("export default { render() {} };")

  private def fixture(initial: Counter = Counter(0, "hi")) =
    val comms = FakeComms()
    val out   = FakeOutput()
    given CommHandler   = comms
    given OutputHandler = out
    (comms, out, Widget(initial, esm))

  // ── opening ──────────────────────────────────────────────────────────────

  test("the handler is registered before the comm is opened"):
    // CommHandler.sender does this the other way round, leaving a window in
    // which an immediate frontend reply is dropped. This is why we do not use it.
    val (comms, _, w) = fixture()
    assertEquals(comms.calls.take(2).toList, List(s"register:${w.modelId}", s"open:${w.modelId}"))

  test("comm_open goes to the right target with the protocol version"):
    val (comms, _, _) = fixture()
    val (target, _, data, metadata) = comms.opened.head
    assertEquals(target, Protocol.TargetName)
    assertEquals(metadata("version").str, Protocol.ProtocolVersion)
    assertEquals(data("state")("_model_name").str, "AnyModel")
    assertEquals(data("state")("count").num.toInt, 0)

  test("the displayed view joins to the comm by id"):
    val (_, out, w) = fixture()
    val payload = ujson.read(out.displayed.head.data(Protocol.ViewMimeType))
    assertEquals(payload("model_id").str, w.modelId)

  test("state that cannot be a flat object is rejected before a comm exists"):
    val comms = FakeComms()
    given CommHandler   = comms
    given OutputHandler = FakeOutput()
    intercept[StateSync.ReservedKey](Widget(Clashing("x"), esm))
    assert(comms.opened.isEmpty, "a comm was opened for invalid state")

  test("building a widget outside a running cell fails loudly"):
    given CommHandler   = FakeComms()
    given OutputHandler = FakeOutput(allowed = false)
    intercept[IllegalStateException](Widget(Counter(0, "hi"), esm))

  // ── outbound ─────────────────────────────────────────────────────────────

  test("set sends only the keys that changed"):
    val (comms, _, w) = fixture()
    w.set(Counter(5, "hi"))
    assertEquals(comms.sent.size, 1)
    val msg = comms.sent.head
    assertEquals(msg("method").str, "update")
    assertEquals(msg("state").obj.keys.toList, List("count"))
    assertEquals(msg("state")("count").num.toInt, 5)

  test("a no-op set puts nothing on the wire"):
    val (comms, _, w) = fixture()
    w.set(Counter(0, "hi"))
    assert(comms.sent.isEmpty)

  test("modify is read-modify-write and returns the new state"):
    val (comms, _, w) = fixture()
    assertEquals(w.modify(c => c.copy(count = c.count + 3)), Counter(3, "hi"))
    assertEquals(w.state, Counter(3, "hi"))
    assertEquals(comms.sent.head("state")("count").num.toInt, 3)

  // ── inbound ──────────────────────────────────────────────────────────────

  test("an inbound update merges into state"):
    val (comms, _, w) = fixture()
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "update", "state" -> ujson.Obj("count" -> 7)))
    assertEquals(w.state, Counter(7, "hi"))

  test("an inbound update is not echoed back"):
    // The frontend already holds this value. Sending the diff back is a loop.
    val (comms, _, w) = fixture()
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "update", "state" -> ujson.Obj("count" -> 7)))
    assert(comms.sent.isEmpty, s"echoed: ${comms.sent.toList}")

  test("request_state replies with the full state, not a diff"):
    val (comms, _, w) = fixture()
    w.set(Counter(4, "hi"))
    comms.sent.clear()
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "request_state"))
    val state = comms.sent.head("state").obj
    assertEquals(state.keys.toSet, Set("count", "label"))
    assertEquals(state("count").num.toInt, 4)

  test("echo_update and unknown methods are ignored"):
    val (comms, _, w) = fixture()
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "echo_update", "state" -> ujson.Obj("count" -> 9)))
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "nonsense"))
    assertEquals(w.state, Counter(0, "hi"))
    assert(comms.sent.isEmpty)

  // ── observers ────────────────────────────────────────────────────────────

  test("observers distinguish the frontend from cell code"):
    val (comms, _, w) = fixture()
    val seen = scala.collection.mutable.ArrayBuffer.empty[(Int, Boolean)]
    w.onChange(c => seen += ((c.state.count, c.fromFrontend)))

    w.set(Counter(1, "hi"))
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "update", "state" -> ujson.Obj("count" -> 2)))

    assertEquals(seen.toList, List((1, false), (2, true)))

  test("an observer that throws is recorded, and the comm keeps working"):
    val (comms, _, w) = fixture()
    w.onChange(_ => throw RuntimeException("boom"))

    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "update", "state" -> ujson.Obj("count" -> 1)))
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "update", "state" -> ujson.Obj("count" -> 2)))

    assertEquals(w.state, Counter(2, "hi"))
    assertEquals(w.errors.size, 2)
    w.clearErrors()
    assert(w.errors.isEmpty)

  test("malformed inbound JSON is recorded rather than escaping"):
    val (comms, _, w) = fixture()
    comms.targets(w.modelId).message(w.modelId, "not json".getBytes("UTF-8"))
    assertEquals(w.errors.size, 1)

  // ── custom messages ──────────────────────────────────────────────────────

  test("CommDelivered serves its bundle when the shim asks for it"):
    val bundle = "export function render() {}"
    val comms  = FakeComms()
    given CommHandler   = comms
    given OutputHandler = FakeOutput()
    val w = Widget(Counter(0, "hi"), EsmSource.CommDelivered(bundle))

    comms.fromFrontend(
      w.modelId,
      ujson.Obj("method" -> "custom", "content" -> ujson.Obj("wedgie" -> "bundle_request"))
    )
    val reply = comms.sent.head
    assertEquals(reply("method").str, "custom")
    assertEquals(reply("content")("wedgie").str, "bundle")
    assertEquals(reply("content")("source").str, bundle)

  test("an Inline widget has no bundle to serve"):
    val (comms, _, w) = fixture()
    comms.fromFrontend(
      w.modelId,
      ujson.Obj("method" -> "custom", "content" -> ujson.Obj("wedgie" -> "bundle_request"))
    )
    assert(comms.sent.isEmpty)

  test("other custom messages reach user handlers"):
    val (comms, _, w) = fixture()
    val seen = scala.collection.mutable.ArrayBuffer.empty[ujson.Value]
    w.onCustom(seen += _)
    comms.fromFrontend(w.modelId, ujson.Obj("method" -> "custom", "content" -> ujson.Obj("hi" -> "there")))
    assertEquals(seen.head("hi").str, "there")

  // ── teardown ─────────────────────────────────────────────────────────────

  test("close shuts the comm and unregisters the handler"):
    val (comms, _, w) = fixture()
    w.close()
    assertEquals(comms.closed.toList, List(w.modelId))
    assert(!comms.targets.contains(w.modelId))
