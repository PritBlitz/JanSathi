// Runs the shared rule fixtures in rules/ through the JS engine (frontend/src/lib/rules.js).
// Usage: node --test tools/   (or npm test)
// Fixture format: docs/RULES_ENGINE.md §9.7.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { evaluateScheme, match, validateLogic } from '../frontend/src/lib/rules.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const readJson = (p) => JSON.parse(readFileSync(join(root, p), 'utf8'));
const fields = readJson('content/fields.json');
const schemeFiles = readdirSync(join(root, 'content/schemes')).filter((f) => f.endsWith('.json'));
const schemes = Object.fromEntries(schemeFiles.map((f) => [f.slice(0, -5), readJson(`content/schemes/${f}`)]));

function checkScheme(scheme, c) {
  const got = evaluateScheme(scheme, c.profile, fields.fields, { as_of: c.as_of });
  const { expect } = c;
  if ('result' in expect) assert.equal(got.result, expect.result, 'result');
  if ('missing_fields' in expect) assert.deepEqual(got.missing_fields, expect.missing_fields, 'missing_fields');
  if ('stale' in expect) assert.equal(got.stale, expect.stale, 'stale');
  if ('label' in expect) assert.equal(got.label, expect.label, 'label');
  for (const [code, outcome] of Object.entries(expect.rules || {})) {
    const r = got.rules.find((x) => x.code === code);
    assert.ok(r, `rule ${code} exists`);
    assert.equal(r.outcome, outcome, `rule ${code}`);
  }
}

function checkMulti(list, c) {
  const got = match({ version: 'test', fields, schemes: list }, c.profile, { skipped: c.skipped, as_of: c.as_of });
  if ('next_question' in c.expect) assert.equal(got.next_question, c.expect.next_question, 'next_question');
  for (const [slug, result] of Object.entries(c.expect.results || {})) {
    assert.equal(got.results.find((r) => r.scheme === slug)?.result, result, `result for ${slug}`);
  }
}

for (const c of readJson('rules/_engine.cases.json')) {
  test(`engine: ${c.name}`, () => (c.schemes ? checkMulti(c.schemes, c) : checkScheme(c.scheme, c)));
}

for (const [slug, scheme] of Object.entries(schemes)) {
  test(`${slug}: rules are valid`, () => {
    for (const rule of scheme.rules) {
      assert.deepEqual(validateLogic(rule.logic, fields.fields), [], `rule ${rule.code}`);
    }
  });
  const file = `rules/${slug}.cases.json`;
  test(`${slug}: has fixtures`, () => assert.ok(existsSync(join(root, file)), `${file} is missing`));
  if (!existsSync(join(root, file))) continue;
  for (const c of readJson(file)) {
    test(`${slug}: ${c.name}`, () => checkScheme(scheme, c));
  }
}
