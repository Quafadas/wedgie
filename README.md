# wedgie

Jupyter widgets for the [Almond](https://almond.sh) Scala kernel, via
[anywidget](https://anywidget.dev) — no JupyterLab extension to build, package or
install.

Almond declares itself an anywidget model and ships the frontend as a synced
string. anywidget's own frontend arrives from a CDN, so nothing needs installing
in the notebook environment, including the `anywidget` Python package.

**Status: Phase 0/1.** The kernel-side library works and is tested. The frontend
is a smoke test, not yet a bridge. Five environment questions are still open — see
`notebooks/probes.ipynb`.

## Layout

| Module | Platform | What |
| --- | --- | --- |
| `core` | JVM + JS | Wire constants, state merge/diff, `_esm` delivery strategies. No Almond dependency, so all of it is unit-testable. |
| `kernel` | JVM | `Widget[S]` over Almond's `CommHandler`. Thin — everything worth testing lives in `core`. |
| `front` | JS | Laminar frontend. Currently a smoke test. |

```
./mill __.compile          # everything
./mill core.jvm.test       # wire logic
./mill kernel.test         # Widget, against a fake kernel
./mill front.fullLinkJS    # release bundle
./mill core.jvm.publishLocal && ./mill kernel.publishLocal
```

`./mill` is a checked-in launcher pinned by `.mill-version`.

## Notebooks

| Notebook | Purpose |
| --- | --- |
| `reference.ipynb` | The minimal round trip, depending on nothing but `ujson`. **Run this first** when anything in the environment moves. |
| `probes.ipynb` | The five open Phase 0 questions, each as a runnable cell. |
| `counter.ipynb` | The same counter rebuilt on `Widget[S]`. |
| `own-bundle.ipynb` | Does **our own** Scala.js bundle render in the webview? The first real test of the premise. |

## Design notes

**State is flat and per-key.** ipywidgets sync is `model.get("count")` /
`model.on("change:count")`, and an inbound `update` carries only what changed. So
`S` must serialise to a flat JSON object whose field names are traitlet keys —
sharing a namespace with `_esm`, hence the rejection of `_`-prefixed fields. Merge
and diff happen at the `ujson` level, so they work for any `ReadWriter[S]` with no
derivation of our own.

**Inbound updates are never echoed.** The frontend already holds the value it just
sent; returning the diff is how you get a loop.

**One lock, not a bare `AtomicReference`.** The diff must be computed and put on
the wire atomically with the state swap, or concurrent updates can reach the
frontend in an order that contradicts the final state. Reads stay lock-free, and
observers run outside the lock — a control driving a recalculation must not block
the wire.

**We do not use `CommHandler.sender`.** It returns a `Comm` exposing only
`message` and `close`, with no id accessor in any published Almond, so the id must
be generated and held anyway. It also calls `commOpen` before `registerCommId`,
leaving a window in which an immediate frontend reply has no target to land on.
Registering first closes it.

**`layout` is omitted from `comm_open`.** It points at a separate `Layout` widget
with its own comm, serialised as `IPY_MODEL_<id>`. Dropping it keeps child model
references, a second comm, and `@jupyter-widgets/base` out of scope entirely.

**Nothing assumes one widget per notebook.** N widgets are N root models with no
parent/child relationship and therefore no `IPY_MODEL_` plumbing. Whether N is
advisable is a question about `_esm` size, which is `EsmSource`'s problem — see
below.

## The `_esm` delivery question

`_esm` is ordinary synced state, so it travels in every `comm_open` and is
serialised into the `.ipynb`. A release Laminar + upickle bundle is ~324–388 KB,
which is what makes this a real decision rather than a detail.

| Strategy | `_esm` size | In the `.ipynb`? | Gated on |
| --- | --- | --- | --- |
| `Inline` | full bundle | yes | nothing |
| `RemoteImport` | ~120 B | no | probe 3 |
| `CommDelivered` | ~300 B | no | probes 4 + 5 |

`CommDelivered` sends the bundle as a transient `custom` message, so it needs
neither a CDN nor a reachable internal host, and a `globalThis` promise cache means
N widgets fetch it once. It is unverified — do not ship it before probes 4 and 5
pass.

Scala.js with `ModuleSplitStyle.FewestModules` emits a **single** file, so no
bundler step is needed for the inline strategies.

The linked `front` bundle is a **valid AFM on its own**: AFM requires a default
export, and `@JSExportTopLevel("default")` emits the factory form (a function
returning `{ render }`). Verified by importing the release bundle under node. So
`EsmSource.Inline(<the bundle>)` needs no wrapper, and `RemoteImport` only needs
one because it is re-exporting across a module boundary.

## Deliberately not implemented

- `echo_update` / multi-frontend sync. Protocol `2.0.0` predates it.
- State restoration on reload. VS Code does not do it regardless; re-run the cell.
- Binary buffers. `buffer_paths` is always `[]`.
- The `@jupyter-widgets/controls` model set. This is the trap that stranded
  IHaskell's widget support, and avoiding it is the point of the anywidget route.
- JupyterLab as a target. It will probably work; it is not tested.

## Versions

Scala 3.8.4 · Scala.js 1.22.0 (3.8.4 emits `linkTimeIf`, needs ≥ 1.20) ·
Mill 1.1.9 · Almond `interpreter-api` 0.14.1 (`provided`) · anywidget `~0.11.*` ·
widget protocol `2.0.0`.
