// Theme tokens in css/app.css: text contrast (WCAG 4.5:1) in both themes, and bundled fonts.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';

const css = readFileSync(new URL('../css/app.css', import.meta.url), 'utf8');

function tokens(selector) {
  const start = css.indexOf(selector);
  assert.ok(start >= 0, 'missing ' + selector);
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start));
  return Object.fromEntries([...body.matchAll(/(--[\w-]+):\s*([^;]+);/g)].map(m => [m[1], m[2].trim()]));
}

const shared = tokens(':root {');
const dark = shared;
const light = { ...shared, ...tokens(':root[data-theme="light"] {') };

function luminance(hex) {
  const [r, g, b] = hex.match(/[0-9a-f]{2}/gi).map(h => parseInt(h, 16) / 255)
    .map(c => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4));
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
function contrast(a, b) {
  const [hi, lo] = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
}

const TEXT = ['--text', '--text2', '--muted', '--accent-fg', '--k-service', '--k-data', '--k-async', '--k-sub',
  '--k-decision-text', '--k-start', '--k-error', '--diff-added', '--diff-removed', '--diff-changed', '--edge-label'];
const ON_FILL = [['--op-select', '--op-select-bg'], ['--op-write', '--op-write-bg'], ['--op-update', '--op-update-bg'],
  ['--op-delete', '--op-delete-bg'], ['--k-service', '--k-service-bg'], ['--k-data', '--k-data-bg'],
  ['--k-async', '--k-async-bg'], ['--k-sub', '--k-sub-bg'], ['--k-decision-text', '--k-decision-bg'],
  ['--diff-added', '--diff-added-bg'], ['--diff-changed', '--diff-changed-bg'], ['--code-text', '--code-bg']];
const WHITE_ON = ['--controller', '--service', '--repository', '--other', '--branch', '--throw', '--external',
  '--db-read', '--db-write', '--db-update', '--db-delete'];

for (const [name, t] of [['dark', dark], ['light', light]]) {
  test(`${name} theme: text tokens reach 4.5:1 on every surface`, () => {
    const low = [];
    for (const fg of TEXT) {
      for (const bg of ['--bg', '--panel', '--panel2']) {
        const c = contrast(t[fg], t[bg]);
        if (c < 4.5) low.push(`${fg} on ${bg}: ${c.toFixed(2)}`);
      }
    }
    for (const [fg, bg] of ON_FILL) {
      const c = contrast(t[fg], t[bg]);
      if (c < 4.5) low.push(`${fg} on ${bg}: ${c.toFixed(2)}`);
    }
    assert.deepEqual(low, []);
  });
}

test('white labels reach 4.5:1 on the call-graph fills and the primary button', () => {
  const low = [...WHITE_ON.map(f => [f, light[f]]), ['--accent (light)', light['--accent']], ['--accent (dark)', dark['--accent']]]
    .map(([f, hex]) => [f, contrast('#ffffff', hex)]).filter(([, c]) => c < 4.5).map(([f, c]) => `${f}: ${c.toFixed(2)}`);
  assert.deepEqual(low, []);
});

test('the auto (system) light theme and the forced light theme are the same', () => {
  assert.deepEqual(tokens(':root:not([data-theme="dark"]) {'), tokens(':root[data-theme="light"] {'));
});

test('every @font-face file is bundled', () => {
  const files = [...css.matchAll(/url\("\.\.\/(fonts\/[^"]+)"\)/g)].map(m => m[1]);
  assert.equal(files.length, 6);
  for (const f of files) assert.ok(existsSync(new URL('../' + f, import.meta.url)), f);
  assert.ok(existsSync(new URL('../fonts/OFL.txt', import.meta.url)));
});
