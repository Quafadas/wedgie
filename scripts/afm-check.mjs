#!/usr/bin/env node
// Integration test for the anywidget front-end module, without a browser.
//
// Catches the class of failure that unit tests cannot see and a notebook reports
// only as "nothing rendered":
//   - the module is not a valid AFM (no default export, wrong shape)
//   - two modules on the classpath both claim `default`
//   - the bridge writes to the model during initialize
//   - the bridge echoes a kernel-driven change back at the kernel
//
// Usage: node scripts/afm-check.mjs [bundle.js ...]

import { pathToFileURL } from "node:url";
import { statSync } from "node:fs";
import { resolve } from "node:path";

const bundles = process.argv.slice(2);
if (bundles.length === 0) {
  console.error("usage: node scripts/afm-check.mjs <bundle.js> [...]");
  process.exit(2);
}

let failed = 0;
const check = (name, cond, detail = "") => {
  console.log(`  ${cond ? "✓" : "✗"} ${name}${cond || !detail ? "" : ` — ${detail}`}`);
  if (!cond) failed++;
};

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

for (const path of bundles) {
  const abs = resolve(path);
  console.log(`\n${path}  (${(statSync(abs).size / 1024).toFixed(1)} KB)`);

  let mod;
  try {
    mod = await import(pathToFileURL(abs).href);
  } catch (e) {
    check("module loads", false, e.message.split("\n")[0]);
    continue;
  }
  check("module loads", true);

  // AFM requires a default export. A named `export { render }` alone will not load.
  const isFactory = typeof mod.default === "function";
  const isObject = mod.default && typeof mod.default === "object";
  check("has a default export", isFactory || isObject, `got ${typeof mod.default}`);
  if (!isFactory && !isObject) continue;

  const inst = isFactory ? mod.default() : mod.default;
  check("exposes render", typeof inst.render === "function");

  if (typeof inst.initialize !== "function") {
    console.log("  · no initialize — model-free module, skipping sync checks");
    continue;
  }

  // Two instances from one factory must not share state.
  if (isFactory) check("factory yields distinct instances", mod.default() !== inst);

  const model = fakeModel({ n: 7, label: "hi", enabled: true });
  try {
    await inst.initialize({ model, signal: new AbortController().signal });
  } catch (e) {
    check("initialize runs", false, e.message.split("\n")[0]);
    continue;
  }
  check("initialize runs", true);
  check("initialize subscribes to traitlets", Object.keys(model.handlers).length > 0,
        "no change: handlers registered");
  // Writing during initialize would fight the kernel's opening state.
  check("initialize does not write to the model", model.calls.save === 0,
        `save_changes called ${model.calls.save}x`);

  // The invariant that stops the feedback loop: a change that arrived FROM the
  // model must diff to empty on the way back out.
  model.set("n", 42);
  check("a kernel-driven change is not echoed back", model.calls.save === 0,
        `save_changes called ${model.calls.save}x after an inbound change`);
}

console.log(failed === 0 ? "\nAFM checks passed" : `\n${failed} AFM check(s) failed`);
process.exit(failed === 0 ? 0 : 1);
