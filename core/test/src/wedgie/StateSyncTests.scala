package wedgie

import upickle.default.ReadWriter

case class Counter(count: Int, label: String) derives ReadWriter
case class Clashing(_esm: String, count: Int) derives ReadWriter

class StateSyncTests extends munit.FunSuite:

  test("encode produces a flat object whose keys are the field names"):
    assertEquals(
      StateSync.encode(Counter(1, "hi")),
      ujson.Obj("count" -> 1, "label" -> "hi")
    )

  test("encode rejects state that is not a JSON object"):
    // Traitlet keys come from field names, so a bare Int has nowhere to live.
    intercept[StateSync.NotAnObject](StateSync.encode(42))

  test("validate rejects fields colliding with widget traitlets"):
    val e = intercept[StateSync.ReservedKey](
      StateSync.validate(StateSync.encode(Clashing("x", 1)))
    )
    assert(e.getMessage.contains("_esm"), e.getMessage)

  test("merge applies a partial patch and leaves other fields alone"):
    // This is the shape that actually arrives: {"method":"update","state":{"count":3}}
    assertEquals(
      StateSync.merge(Counter(0, "hi"), ujson.Obj("count" -> 3)),
      Counter(3, "hi")
    )

  test("merge ignores reserved keys — the frontend may not rewrite _esm"):
    assertEquals(
      StateSync.merge(Counter(0, "hi"), ujson.Obj("_esm" -> "evil", "count" -> 2)),
      Counter(2, "hi")
    )

  test("diff emits only the keys that changed"):
    assertEquals(
      StateSync.diff(
        StateSync.encode(Counter(1, "hi")),
        StateSync.encode(Counter(2, "hi"))
      ),
      ujson.Obj("count" -> 2)
    )

  test("diff of identical states is empty, so nothing is sent"):
    val s = StateSync.encode(Counter(7, "same"))
    assert(StateSync.diff(s, s).value.isEmpty)

  test("a diff round-trips through merge to the same state"):
    val before = Counter(1, "hi")
    val after  = Counter(9, "bye")
    val delta  = StateSync.diff(StateSync.encode(before), StateSync.encode(after))
    assertEquals(StateSync.merge(before, delta), after)
