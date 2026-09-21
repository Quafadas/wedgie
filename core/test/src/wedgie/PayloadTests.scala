package wedgie

class PayloadTests extends munit.FunSuite:

  private val esm = EsmSource.Inline("export default { render() {} };")

  test("comm_open puts widget meta and user state in one flat namespace"):
    val data  = Payload.commOpen(Counter(0, "hi"), esm, None)
    val state = data("state").obj

    assertEquals(state("_model_module").str, "anywidget")
    assertEquals(state("_model_name").str, "AnyModel")
    assertEquals(state("_view_name").str, "AnyView")
    assertEquals(state("_model_module_version").str, "~0.11.*")
    assertEquals(state("count").num.toInt, 0)
    assertEquals(state("label").str, "hi")
    assertEquals(data("buffer_paths").arr.length, 0)

  test("comm_open omits layout, which is what keeps IPY_MODEL_ out of scope"):
    val state = Payload.commOpen(Counter(0, "hi"), esm, None)("state").obj
    // layout points at a separate Layout widget with its own comm, serialised
    // as IPY_MODEL_<id>. Dropping it means we never open a second comm.
    assert(!state.contains("layout"))
    assert(!state.contains("_dom_classes"))
    assert(!state.contains("_anywidget_id"))

  test("_css is only present when supplied"):
    assert(!Payload.commOpen(Counter(0, "x"), esm, None)("state").obj.contains("_css"))
    assertEquals(
      Payload.commOpen(Counter(0, "x"), esm, Some("p{color:red}"))("state")("_css").str,
      "p{color:red}"
    )

  test("comm_open validates state before any comm could be opened"):
    intercept[StateSync.ReservedKey](Payload.commOpen(Clashing("x", 1), esm, None))

  test("open metadata carries the protocol version — a missing one renders nothing"):
    assertEquals(Payload.openMetadata("version").str, "2.0.0")

  test("update messages carry method, state and empty buffer_paths"):
    val m = Payload.update(ujson.Obj("count" -> 5))
    assertEquals(m("method").str, "update")
    assertEquals(m("state")("count").num.toInt, 5)
    assertEquals(m("buffer_paths").arr.length, 0)

  test("the view payload joins display_data to the comm by id"):
    val v = Payload.view("abc-123")
    assertEquals(v("model_id").str, "abc-123")
    assertEquals(v("version_major").num.toInt, 2)
