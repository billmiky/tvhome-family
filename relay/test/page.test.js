// The phone page is one template literal: its script may hold no backtick and no dollar-brace, and must parse.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync, writeFileSync, mkdtempSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { PAGE } from "../src/page.js";

const SRC = join(dirname(fileURLToPath(import.meta.url)), "..", "src", "page.js");

test("page.js: only the two backticks that make the template, no dollar-brace", () => {
  const src = readFileSync(SRC, "utf8");
  assert.equal(src.split("`").length - 1, 2);
  assert.ok(!src.includes("$" + "{"));
});

test("the page script parses", () => {
  const m = /<script>([\s\S]*)<\/script>/.exec(PAGE);
  assert.ok(m, "a script");
  const dir = mkdtempSync(join(tmpdir(), "tv-page-"));
  try {
    const file = join(dir, "page-script.js");
    writeFileSync(file, m[1]);
    const r = spawnSync(process.execPath, ["--check", file], { encoding: "utf8" });
    assert.equal(r.status, 0, r.stderr);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

test("the relay fills in the agent permissions", () => {
  assert.equal(PAGE.split("/*AGENT_PERMS*/null").length - 1, 1);
  for (const id of ["agentsCard", "agentCreate", "agentKey", "agentCopy", "agentList", "agentLog"]) assert.ok(PAGE.includes('id="' + id + '"'), id);
});
