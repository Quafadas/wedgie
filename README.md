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
| `sync.ipynb` | ⏳ Written, not yet run on a kernel. |

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
./mill example.js.fullLinkJS     # the real one
node scripts/afm-check.mjs out/example/js/fullLinkJS.dest/main.js
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

`_esm` is ordinary synced state, so it travels in every `comm_open` and is
serialised into the `.ipynb`.

| Bundle | Raw | gzip |
| --- | ---: | ---: |
| `smoke` — Laminar only | 325 KB | 52 KB |
| `example` — plus the cross-compiled codec | 1414 KB | 226 KB |
| `example` through `esbuild --minify` | 549 KB | 149 KB |

The jump is upickle's derivation machinery, and the reason it is not smaller is
that **`ModuleKind.ESModule` forfeits Closure** — which is exactly the optimiser
that shrinks that code. `scalaJSMinify` is on and does not recover it. So the
honest cost of the shared-codec design is about 1 MB of bundle, or 220 KB
compressed, unless an external minifier is added to the build:

```
npx esbuild out/example/js/fullLinkJS.dest/main.js --minify --format=esm \
  --outfile=out/example.min.js
```

| Strategy | `_esm` size | In the `.ipynb`? | Gated on |
| --- | --- | --- | --- |
| `Inline` | full bundle | yes | nothing — **verified working** |
| `RemoteImport` | ~120 B | no | probe 3 |
| `CommDelivered` | ~300 B | no | probes 4 + 5 |

`Inline` is proven, so the other two are size optimisations rather than unblocks.
`CommDelivered` sends the bundle as a transient `custom` message, needing neither
a CDN nor a reachable internal host; a `globalThis` promise cache means N widgets
fetch it once. It is unverified — do not ship it before probes 4 and 5 pass.

## CI

`.github/workflows/ci.yml` compiles, tests, links both bundles, and then runs two
checks that exist because of bugs actually hit here:

- `scripts/afm-check.mjs` — loads each bundle under node and asserts the AFM
  contract, that `initialize` does not write to the model, and that a kernel-driven
  change is not echoed back. Catches failures a notebook reports only as "nothing
  rendered".
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
