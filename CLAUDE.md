# CLAUDE.md

Jupyter widgets for Almond via anywidget. Read `README.md` for the design; this
file is the stuff that will otherwise cost you an hour.

## Build

```
./mill __.compile / __.test
./mill example.js.fullLinkJS        # the real bundle
./mill smoke.fullLinkJS             # model-free, for bisecting
node scripts/afm-check.mjs out/example/js/fullLinkJS.dest/main.js
python3 scripts/check-notebooks.py
./mill {core.jvm,kernel,example.jvm}.publishLocal    # needed before any notebook
```

Mill takes **one target per invocation**. `./mill` is checked in and pinned by
`.mill-version`; Mill 1.x ships a launcher script, not a jar, so pre-1.x
bootstraps 404.

## Invariants — break these and it loops or silently dies

- **Neither end echoes.** Both diff against what the *far* side already holds, so
  a change arriving from the other end diffs to empty and sends nothing. No flags,
  no mutexes. `Widget.onMessage` merges without replying; `Bridge.writeModel`
  diffs against the model's current attributes. A feedback loop means this broke.
- **State `S` must serialise to a flat JSON object**, fields = traitlet keys, none
  starting with `_` (they share a namespace with `_esm` et al). Enforced in
  `StateSync.validate`, at construction, before a comm exists.
- **Exactly one `default` export per linked bundle.** AFM requires one; two
  modules claiming it is a Scala.js link error. This is why `front` has no entry
  point and `smoke` is its own module.
- **Kernel side: one lock around mutate-and-send.** The diff must hit the wire
  atomically with the state swap. Observers run *outside* it — they may recompute.

## Traps already paid for

- **Maven `solrsearch` returns stale `latestVersion`.** Wrong three times here
  (upickle, Scala.js, Almond). Use `maven-metadata.xml`.
- **`CommHandler.Comm` has no `commId`** in any published Almond (0.13.14–0.14.5).
  Generate the UUID and own it. Also `sender()` calls `commOpen` *before*
  `registerCommId` — register first or an immediate reply is dropped. We use the
  primitives, not `sender`.
- **Almond cell codegen cannot handle a top-level `val _ = …`** — it emits the
  binding name backquoted and `` `_` `` is rejected. Indented/local is fine.
  Guarded by `scripts/check-notebooks.py`.
- **Context bounds share the first `using` clause.** `Widget[S: ReadWriter](...)(using
  CommHandler, OutputHandler)` cannot be called as `(using comms, out)`. Bind
  `given`s instead.
- **Scala 3.8.4 emits `linkTimeIf`** → Scala.js must be ≥ 1.20.
- **Minify or the widget silently does not render.** `ModuleKind.ESModule`
  forfeits Closure; `scalaJSMinify` does not recover it. `_esm` rides inside
  `comm_open`, and the 1414 KB unminified bundle never arrives — cells run clean,
  console silent, nothing rendered. 549 KB works. Use `./mill example.js.bundle`
  (fullLinkJS + esbuild), never raw `fullLinkJS`, for anything you inline.
- **`DisplayData(Map(...))` is deprecated** since Almond 0.14.2 and produces
  `Value.String`; the widget view payload needs `addStringifiedJson`.

## Verifying a change

Notebooks form a ladder — each changes exactly one variable, so a failure
localises. Run in order, and run `reference.ipynb` first whenever the environment
moves (VS Code, Almond, anywidget):

`reference` (raw ujson + handwritten JS) → `counter` (`Widget[S]` + handwritten JS)
→ `own-bundle` (`Widget[S]` + our bundle, no model) → `sync` (full bridge).

First three are verified on a live kernel. **`sync.ipynb` has not been run yet.**

Widget failures are silent in the notebook. The webview console (Command Palette →
"Open Webview Developer Tools") is the only place errors appear, and a genuine
widget error always has an `ipywidgets.js` frame in its stack. `scripts/afm-check.mjs`
catches most of this class headlessly — prefer it to guessing.

## Still open

- Probes 1 and 2 (handler output routing, handler exceptions) have never been run.
  `Widget`'s error posture is provisional on them.
- Bundle delivery: `Inline` works *if minified*. `CommDelivered` would take the
  bundle out of the `.ipynb` and out of `comm_open` entirely — gated on probes 4
  and 5, and now more attractive than it looked.
- Cross-compiled `S` means changing a widget's state type is a rebuild +
  `publishLocal` + kernel restart, not a cell edit. Unresolved ergonomics.
