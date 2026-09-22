# wedgie

Jupyter widgets for the [Almond](https://almond.sh) Scala kernel, via
[anywidget](https://anywidget.dev) — no JupyterLab extension to build, package or
install.

Almond declares itself an anywidget model and ships the frontend as a synced
string. anywidget's own frontend arrives from a CDN, so nothing needs installing
in the notebook environment, including the `anywidget` Python package.

The point of doing this in Scala rather than wrapping ipywidgets: the **state type
is compiled once and used at both ends**. `example/src/wedgie/example/Params.scala`
is compiled into the kernel library and into the browser bundle, so the two cannot
disagree about the shape of the state. Neither end has a hand-written JSON mapping.

## Status

Verified on a live kernel (Almond 0.14.x, VS Code, WSL2):

| | |
| --- | --- |
| `reference.ipynb` | ✅ Widget renders, clicks reach Scala, kernel pushes state back. |
| `counter.ipynb` | ✅ `Widget[S]` works against a real `CommHandler`. |
| `own-bundle.ipynb` | ✅ A Scala.js + Laminar bundle inlined into `_esm` renders in the webview. |
| `sync.ipynb` | ✅ Sync works in both directions over a cross-compiled type — **with the minified bundle**. |

The bridge is verified headlessly by `scripts/afm-check.mjs`, which drives it
against a fake anywidget model under node.

## Layout

| Module | Platform | What |
| --- | --- | --- |
| `core` | JVM + JS | Wire constants, state merge/diff, `_esm` delivery strategies. No Almond dependency, so all of it is unit-testable. |
| `kernel` | JVM | `Widget[S]` over Almond's `CommHandler`. |
| `front` | JS | `Bridge`: Laminar ↔ traitlet sync. A library with **no entry point**, so dependants may define the one `default` export an AFM allows. |
| `smoke` | JS | A model-free Laminar mount. Kept as a bisection tool: "Laminar broke" and "sync broke" fail for different reasons. |
| `example` | JVM + JS | The shared `Params` type and the Laminar panel that renders it. |

```
./mill __.compile
./mill __.test
./mill smoke.fullLinkJS          # model-free bundle
./mill example.js.bundle         # the real one: fullLinkJS + esbuild
npm install                      # jsdom, for the render checks
node scripts/afm-check.mjs out/example/js/bundle.dest/wedgie.js
python3 scripts/check-notebooks.py

./mill core.jvm.publishLocal
./mill kernel.publishLocal
./mill example.jvm.publishLocal
```

`./mill` is a checked-in launcher pinned by `.mill-version`. Mill takes one target
per invocation.

## Notebooks

| Notebook | Purpose |
| --- | --- |
| `reference.ipynb` | The minimal round trip, depending on nothing but `ujson`. **Run this first** when anything in the environment moves. |
| `counter.ipynb` | The same counter rebuilt on `Widget[S]`. |
| `own-bundle.ipynb` | Does our own Scala.js bundle render in the webview? |
| `sync.ipynb` | State sync in both directions over a cross-compiled type. |
| `probes.ipynb` | Five environment questions. Not on the critical path — see below. |

## Design notes

**State is flat and per-key.** ipywidgets sync is `model.get("count")` /
`model.on("change:count")`, and an inbound `update` carries only what changed. So
`S` must serialise to a flat JSON object whose field names are traitlet keys —
sharing a namespace with `_esm`, hence the rejection of `_`-prefixed fields. Merge
and diff happen at the `ujson` level, so they work for any `ReadWriter[S]` with no
derivation of our own.

**Neither end echoes, and neither needs a flag to avoid it.** Both diff against
what the *other* side already holds, so a change that arrived from the far end
produces an empty diff and sends nothing. The kernel merges an inbound `update`
without replying; `Bridge` diffs against the model's current attributes. If a
feedback loop ever appears, this is the invariant that broke.

**One lock on the kernel side, not a bare `AtomicReference`.** The diff must be
computed and put on the wire atomically with the state swap, or concurrent updates
can reach the frontend in an order that contradicts the final state. Reads stay
lock-free, and observers run outside the lock — a control driving a recalculation
must not block the wire.

**We do not use `CommHandler.sender`.** It returns a `Comm` exposing only
`message` and `close`, with no id accessor in any published Almond (checked
0.13.14 through 0.14.5), so the id must be generated and held anyway. It also
calls `commOpen` before `registerCommId`, leaving a window in which an immediate
frontend reply has no target to land on. Registering first closes it.

**`layout` is omitted from `comm_open`.** It points at a separate `Layout` widget
with its own comm, serialised as `IPY_MODEL_<id>`. Dropping it keeps child model
references, a second comm, and `@jupyter-widgets/base` out of scope entirely.

**Nothing assumes one widget per notebook.** N widgets are N root models with no
parent/child relationship and therefore no `IPY_MODEL_` plumbing. Whether N is
advisable is a question about bundle size, below.

**The AFM factory form.** `@JSExportTopLevel("default")` on a zero-arg method
emits the factory shape the spec allows — a function returning `{ initialize,
render }` — so a linked bundle is a valid anywidget module with no `_esm` wrapper.
It also gives each widget instance its own closure, which is where the `Var` lives.

## Bundle size

This is the sharpest constraint on the project, and it fails in the worst possible
way. `_esm` is ordinary synced model state, so the bundle travels **inside
`comm_open`**. Past some size it does not arrive, and nothing reports it: the
cells run clean, the webview console is silent, and no widget appears.

Observed: **1414 KB does not render. 549 KB does.** The real ceiling is somewhere
between and has not been bisected. `scripts/afm-check.mjs` guards at 800 KB.

### Where the bytes go

All figures esbuild-minified, which is the honest comparison:

| Bundle | Raw | gzip |
| --- | ---: | ---: |
| `smoke` — Laminar + the Scala.js runtime | 126 KB | 34 KB |
| `example` — the above plus upickle | **549 KB** | 146 KB |

**upickle is 77% of the bundle** — about 423 KB to serialise three fields. The
linked output contains `upack` (MessagePack, 227 identifiers) and a `java.nio`
ByteBuffer emulation (331 identifiers), neither of which this project uses.
They survive dead-code elimination because upickle's `ReadWriter` machinery
references them.

Scala.js itself is not the problem here: Laminar and the whole runtime come to
126 KB, which is unremarkable for a reactive UI framework.

### Two compounding causes

1. **`ModuleKind.ESModule` forfeits Closure.** The AFM contract requires an ES
   module, and the Scala.js Closure integration does not support that output.
   `scalaJSMinify` is on and recovers far less — hence `./mill example.js.bundle`,
   which pipes `fullLinkJS` through esbuild (1414 → 549 KB). This is the only part
   of the build that needs `npx` on PATH.
2. **The browser is parsing JSON text it never needed.** Traitlets arrive from
   `model.get(k)` as *JavaScript values*. `Bridge` currently converts them
   `js.Any → JSON.stringify → ujson → upickle → S`, which drags in a complete JSON
   parser, a MessagePack codec and `java.nio` — to read values that were already
   structured data.

### Ways out, in order of leverage

| Approach | Effect | Cost |
| --- | --- | --- |
| **Drop upickle on the JS side** — a `Mirror`-derived `js.Any ↔ S` mapper | ~549 → ~150 KB | ~150 lines; the shared *type* stays shared, only the codec differs per platform |
| `EsmSource.CommDelivered` | Bundle leaves `comm_open` and the `.ipynb` entirely | Gated on probes 4 + 5 |
| `EsmSource.RemoteImport` | Same, plus a reachable host | Gated on probe 3 |
| Bisect the actual ceiling | Makes the 800 KB guard principled rather than guessed | An afternoon of notebook runs |

The first is the real fix and is architecturally *more* correct, not a compromise:
nothing in the browser should be parsing JSON text when the host already handed it
JS values. It also keeps the argument for doing this in Scala — the case class is
still compiled once for both ends; only the derivation differs.

Tempting but probably broken: linking `ModuleKind.NoModule` to regain Closure and
appending an `export` line. Scala.js's no-module output opens with
`$fileLevelThis = this`, and `this` is `undefined` at the top level of an ES
module. Test before betting on it.

### Delivery strategies

| Strategy | `_esm` size | In the `.ipynb`? | Status |
| --- | --- | --- | --- |
| `Inline` | full bundle | yes | **works, if minified** |
| `RemoteImport` | ~120 B | no | needs probe 3 |
| `CommDelivered` | ~300 B | no | needs probes 4 + 5 |

`CommDelivered` sends the bundle as a transient `custom` message, needing neither
a CDN nor a reachable host, and a `globalThis` promise cache means N widgets fetch
it once. Unverified — do not ship it before probes 4 and 5 pass.

## CI

`.github/workflows/ci.yml` compiles, tests, links both bundles, and then runs two
checks that exist because of bugs actually hit here:

- `scripts/afm-check.mjs` — loads each bundle under node, mounts it with jsdom,
  and asserts the AFM contract: that `initialize` and `render` do not write to the
  model, that a kernel-driven change reaches the view without being echoed back,
  that `abort` removes the model listeners, and that the bundle is small enough to
  survive `comm_open`. Every one of those corresponds to a bug hit here.
- `scripts/check-notebooks.py` — rejects a top-level `val _ = …`, which Almond
  cannot compile because it emits the binding's name backquoted.

Bundle sizes are reported to the job summary rather than enforced.

## Deliberately not implemented

- `echo_update` / multi-frontend sync. Protocol `2.0.0` predates it.
- State restoration on reload. VS Code does not do it regardless; re-run the cell.
- Binary buffers. `buffer_paths` is always `[]`.
- The `@jupyter-widgets/controls` model set. This is the trap that stranded
  IHaskell's widget support, and avoiding it is the point of the anywidget route.
- JupyterLab as a target. It will probably work; it is not tested.

## Versions

Scala 3.8.4 · Scala.js 1.22.0 (3.8.4 emits `linkTimeIf`, needs ≥ 1.20) ·
Laminar 17.2.1 · Mill 1.1.9 · Almond `interpreter-api` 0.14.5, minimum 0.14.2
(`provided`) · anywidget `~0.11.*` · widget protocol `2.0.0`.
