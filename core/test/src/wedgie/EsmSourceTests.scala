package wedgie

class EsmSourceTests extends munit.FunSuite:

  // AFM requires a default export; a named `export { render }` alone will not
  // load. Inline therefore assumes its source already provides one, which a
  // linked `front` bundle does via @JSExportTopLevel("default").
  test("Inline carries the module verbatim and serves nothing"):
    val src = "export default { render() {} };"
    assertEquals(EsmSource.Inline(src).esm, src)
    assertEquals(EsmSource.Inline(src).servedBundle, None)

  test("RemoteImport keeps _esm tiny and the import absolute"):
    val e = EsmSource.RemoteImport("https://host.example/wedgie.js")
    // _esm is evaluated from a blob URL, so a relative import cannot resolve.
    assert(e.esm.contains("""import { render } from "https://host.example/wedgie.js""""))
    assert(e.esm.contains("export default { render }"))
    assert(e.esm.length < 200, s"shim grew to ${e.esm.length} bytes")
    assertEquals(e.servedBundle, None)

  test("CommDelivered keeps the bundle out of model state"):
    val bundle = "export function render() {}"
    val e      = EsmSource.CommDelivered(bundle)
    // The point of this strategy: the bundle is served on request, so it never
    // appears in _esm and never reaches the .ipynb.
    assert(!e.esm.contains(bundle))
    assertEquals(e.servedBundle, Some(bundle))

  test("CommDelivered caches on globalThis so N widgets fetch once"):
    val e = EsmSource.CommDelivered("x", cacheKey = "__test_key")
    assert(e.esm.contains("""const KEY = "__test_key""""))
    assert(e.esm.contains("globalThis[KEY]"))
    assert(e.esm.contains("bundle_request"))
