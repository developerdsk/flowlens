#!/usr/bin/env node
// The app version: $FLOWLENS_VERSION (set by release builds) or <revision> in analyzer/pom.xml.
//   node scripts/version.mjs         prints it
//   node scripts/version.mjs sync    writes it into tauri.conf.json, package.json and Cargo.toml
import { readFileSync, writeFileSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const desktop = resolve(here, '..');
const root = resolve(desktop, '..');

export function version() {
  if (process.env.FLOWLENS_VERSION) return process.env.FLOWLENS_VERSION;
  const v = /<revision>([^<]+)<\/revision>/.exec(readFileSync(join(root, 'analyzer/pom.xml'), 'utf8'))?.[1];
  if (!v) throw new Error('could not read <revision> from analyzer/pom.xml');
  return v;
}

export function sync(v = version()) {
  for (const f of ['src-tauri/tauri.conf.json', 'package.json']) {
    const path = join(desktop, f);
    const json = JSON.parse(readFileSync(path, 'utf8'));
    if (json.version !== v) { json.version = v; writeFileSync(path, JSON.stringify(json, null, 2) + '\n'); }
  }
  const cargo = join(desktop, 'src-tauri/Cargo.toml');
  const toml = readFileSync(cargo, 'utf8');
  const updated = toml.replace(/^version = "[^"]+"/m, () => `version = "${v}"`);
  if (updated !== toml) writeFileSync(cargo, updated);
  return v;
}

if (resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  console.log(process.argv[2] === 'sync' ? sync() : version());
}
