# Next steps

Read `CLAUDE.md` first — it has the build commands, the invariants, and the traps
already paid for. This file is the live task list.

**State:** all four notebooks verified on a live kernel. Sync works both ways over
a cross-compiled state type. CI green. The thesis holds; what remains is cost and
polish.

---

## 1. Run probes 1 and 2 — ten minutes, never done

`notebooks/probes.ipynb`, first two probes only.

- **Probe 1**: where does `println`/`Console.err` from a comm handler land? The
  handler runs on a kernel thread outside any cell, so there is no obvious parent
  message to attach output to.
- **Probe 2**: does an exception escaping a handler *kill the comm*? Click three
  times; if only one message arrives, the first throw killed it.

Why it matters: `Widget` currently collects handler failures in a queue and
mirrors them to stderr. That posture was chosen blind and is explicitly
provisional. Now that controls drive real work, how failures surface is no longer
academic. Fill in the answer tables in the notebook and adjust `Widget.guard`.

---

## 2. Get upickle out of the frontend — the big one

**This is the highest-leverage work available.** See "Bundle size" in `README.md`
for the measurements.

The problem in one line: the browser parses JSON text it never needed. Traitlets
arrive from `model.get(k)` as JavaScript values, and `Bridge` converts them
`js.Any → JSON.stringify → ujson → upickle → S`. That drags in a full JSON parser,
a MessagePack codec (`upack`) and a `java.nio` ByteBuffer emulation — none of which
this project uses — for **423 KB of a 549 KB bundle**.

### The shape of the fix

A small typeclass, JS-side only, deriving straight between `S` and JS values:

```scala
trait TraitletCodec[S]:
  def fields: List[String]
  def decode(model: js.Dynamic): S        // model.get per field
  def encode(value: S): Map[String, js.Any]
```

- Derive with a Scala 3 `Mirror.ProductOf`, plus a `TraitletValue[A]` for the leaf
  types (`Int`, `Double`, `String`, `Boolean`, `Option`, `Seq`).
- Replace the `StateSync.encode`/`decode`/`diff` calls **inside `Bridge` only**.
  The kernel keeps upickle — JVM bundle size is irrelevant.
- The shared case class stays shared. Only the derivation differs per platform, so
  the argument for doing this in Scala is untouched.

### Watch for

- **The wire format must not drift.** The kernel still speaks upickle JSON. Both
  ends must agree on how each leaf type maps to a traitlet value. `Option` and
  collections are where this will bite.
- **Confirm the DCE actually happens.** `Params derives ReadWriter` stays for the
  kernel; on JS it should become dead once `Bridge` stops calling it. Verify the
  size actually drops — if upickle is still linked, something still references it.
- Expected: ~549 KB → ~150 KB. If it lands nearer 400 KB, upickle is still in
  there; grep the linked output for `upickle`/`upack`/`java_nio`.

### Verifying

`./mill example.js.bundle && node scripts/afm-check.mjs out/example/js/bundle.dest/wedgie.js`
covers the contract, both sync directions and listener cleanup. Then run
`notebooks/sync.ipynb` on a live kernel — the headless checks cannot see the
webview.

---

## 3. Bisect the real `comm_open` ceiling

Known: 1414 KB does not arrive, 549 KB does. `scripts/afm-check.mjs` guards at
800 KB, which is a guess sitting between two data points.

Pad a bundle to fixed sizes and binary-search the boundary. Then set the guard
just under it with a comment saying where the number came from. Worth doing once,
because the failure mode is silent and the guard is the only thing standing
between a future change and an afternoon of confused debugging.

---

## 4. Probes 4 and 5, then `CommDelivered`

`EsmSource.CommDelivered` is written but unverified. It sends the bundle as a
transient `custom` message, so the bundle leaves both `comm_open` and the `.ipynb`
— which removes the size ceiling entirely rather than merely staying under it.

It needs two things confirmed, both in `probes.ipynb`:

- **Probe 4**: `custom` messages round-trip in both directions.
- **Probe 5**: anywidget accepts an `async render`.

Lower priority than (2): shrinking the bundle helps every delivery strategy,
whereas this only removes one constraint.

---

## 5. Deferred: the notebook inner loop

Unresolved and deliberately so. Because `S` is cross-compiled, changing a widget's
state type is a rebuild + `publishLocal` + kernel restart, not a cell edit. That is
the cost of the shared-type design and it is paid every time you explore.

Two directions, not yet chosen:

- **Keep typed state** (current). Purest expression of the thesis; worst inner loop.
- **A generic control renderer.** A fixed frontend bundle, built once and never
  rebuilt, interpreting a control description written in a cell (`Slider("n", 1 to
  100)`). The Scala-side builder stays typed; the wire becomes a value map. Notebook
  ergonomics get much better; per-widget compile-time state agreement is lost.

The bridge machinery is identical either way, which is why this did not need
deciding to get here. **Decide it from experience, not in advance** — use the thing
for real work first and notice how often the rebuild cycle actually hurts.

---

## Housekeeping

- **Probe 3** (can the webview import from an internal URL?) is still unanswered.
  Only needed for `RemoteImport`. Not on the critical path.
- The notebooks carry committed execution outputs from live runs. Small (largest
  3.5 KB, no bundle leaked in) and useful as documentation of correct output, but
  they churn on every run. Strip them if that becomes annoying.
- `front` must stay entry-point-free. An AFM may declare exactly one `default`
  export, and two modules on one classpath claiming it is a link error.
