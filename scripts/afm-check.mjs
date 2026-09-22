#!/usr/bin/env node
// Integration test for the anywidget front-end module, without a browser.
//
// Catches the failures a notebook reports only as "nothing rendered":
//   - not a valid AFM (no default export, wrong shape)
//   - two modules on one classpath both claiming `default`
//   - initialize or render throwing
//   - the bridge writing to the model during initialize or render
//   - the bridge echoing a kernel-driven change back at the kernel
//   - a bundle too large to survive comm_open
//
// Usage: node scripts/afm-check.mjs [--max-kb N] <bundle.js> [...]

import { pathToFileURL } from "node:url";
import { statSync } from "node:fs";
import { resolve } from "node:path";

// `_esm` is synced model state, so the bundle travels inside comm_open. A 1.4 MB
// bundle was observed to vanish silently in VS Code; a 549 KB one arrives. The
// real ceiling is somewhere between, so this sits conservatively below it.
let maxKb = 800;
const args = [];
for (let i = 2; i < process.argv.length; i++) {
  if (process.argv[i] === "--max-kb") maxKb = Number(process.argv[++i]);
  else args.push(process.argv[i]);
}
if (args.length === 0) {
  console.error("usage: node scripts/afm-check.mjs [--max-kb N] <bundle.js> [...]");
  process.exit(2);
}

let failed = 0;
const check = (name, cond, detail = "") => {
  console.log(`  ${cond ? "✓" : "✗"} ${name}${cond || !detail ? "" : ` — ${detail}`}`);
  if (!cond) failed++;
};

// jsdom is what lets us call render at all. Optional so the other checks still
// run without a node_modules.
let JSDOM = null;
try {
  ({ JSDOM } = await import("jsdom"));
} catch {
  console.log("note: jsdom not installed — render checks skipped (`npm install`)");
}

/** Minimal stand-in for an anywidget model: flat traitlets, per-key change events. */
function fakeModel(attrs) {
  const handlers = {};
  const calls = { save: 0, sets: [] };
  return {
    calls,
    handlers,
    get: (k) => attrs[k],
    set: (k, v) => {
      calls.sets.push([k, v]);
      if (attrs[k] !== v) {
        attrs[k] = v;
        (handlers[`change:${k}`] || []).forEach((f) => f());
      }
    },
    on: (ev, f) => (handlers[ev] = handlers[ev] || []).push(f),
    off: (ev, f) => (handlers[ev] = (handlers[ev] || []).filter((g) => g !== f)),
    save_changes: () => calls.save++,
  };
}

function installDom() {
  const jsdom = new JSDOM("<!doctype html><html><body><div id='host'></div></body></html>", {
    pretendToBeVisual: true,
  });
  for (const k of ["window","document","HTMLElement","Element","Node","Event","CustomEvent",
                   "MutationObserver","DocumentFragment","SVGElement","Text","Comment"]) {
    try { globalThis[k] = jsdom.window[k]; } catch { /* read-only global */ }
  }
  globalThis.requestAnimationFrame ??= (f) => setTimeout(f, 0);
  return jsdom.window.document.getElementById("host");
}

for (const path of args) {
  const abs = resolve(path);
  const kb = statSync(abs).size / 1024;
  console.log(`\n${path}  (${kb.toFixed(1)} KB)`);

  check(`fits in comm_open (< ${maxKb} KB)`, kb < maxKb,
        `${kb.toFixed(0)} KB — a bundle this size disappears silently; minify it`);

  let mod;
  try {
    mod = await import(pathToFileURL(abs).href);
  } catch (e) {
    check("module loads", false, e.message.split("\n")[0]);
    continue;
  }
  check("module loads", true);

  const isFactory = typeof mod.default === "function";
  const isObject = mod.default && typeof mod.default === "object";
  check("has a default export", isFactory || isObject, `got ${typeof mod.default}`);
  if (!isFactory && !isObject) continue;

  const inst = isFactory ? mod.default() : mod.default;
  check("exposes render", typeof inst.render === "function");
  if (isFactory) check("factory yields distinct instances", mod.default() !== inst);

  const model = fakeModel({ n: 7, label: "hi", enabled: true });
  const instanceAbort = new AbortController();
  const signal = () => new AbortController().signal;
  const listeners = () => Object.values(model.handlers).flat().length;

  if (typeof inst.initialize === "function") {
    try {
      await inst.initialize({ model, signal: instanceAbort.signal });
      check("initialize runs", true);
    } catch (e) {
      check("initialize runs", false, e.message.split("\n")[0]);
      continue;
    }
    check("initialize subscribes to traitlets", Object.keys(model.handlers).length > 0);
    // Writing during initialize would fight the kernel's opening state.
    check("initialize does not write to the model", model.calls.save === 0,
          `save_changes called ${model.calls.save}x`);
  } else {
    console.log("  · no initialize — model-free module");
  }

  if (!JSDOM) continue;

  const el = installDom();
  let cleanup;
  try {
    cleanup = await inst.render({ model, el, signal: signal() });
    check("render runs", true);
  } catch (e) {
    check("render runs", false, e.stack.split("\n").slice(0, 3).join(" | "));
    continue;
  }
  check("render mounts something", el.innerHTML.length > 0, "produced an empty element");
  // Mounting must not look like a user edit, or every view would write on open.
  check("render does not write to the model", model.calls.save === 0,
        `save_changes called ${model.calls.save}x`);
  check("render returns a cleanup function", typeof cleanup === "function",
        `got ${typeof cleanup}`);

  if (typeof inst.initialize === "function") {
    // The invariant that stops the feedback loop: a change that arrived FROM the
    // model must diff to empty on the way back out.
    model.set("n", 42);
    check("a kernel-driven change is not echoed back", model.calls.save === 0,
          `save_changes called ${model.calls.save}x after an inbound change`);
    check("a kernel-driven change reaches the view", el.innerHTML.includes("42"),
          "the rendered DOM did not update");

    // AFM gives initialize an AbortSignal so subscriptions can be undone. Without
    // it, re-running a cell stacks another full set of listeners on the model.
    const before = listeners();
    instanceAbort.abort();
    check("abort removes model listeners", listeners() === 0,
          `${listeners()} of ${before} remain after abort`);
  }
}

console.log(failed === 0 ? "\nAFM checks passed" : `\n${failed} AFM check(s) failed`);
process.exit(failed === 0 ? 0 : 1);
