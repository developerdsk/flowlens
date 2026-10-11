#!/usr/bin/env node
// Runs before `tauri dev` / `tauri build`:
//  1. syncs the app version from analyzer/pom.xml (the single source of truth)
//  2. copies the viewer into desktop/dist (without tests / node_modules)
//  3. builds the engine if it is missing
import { readFileSync, writeFileSync, rmSync, cpSync, existsSync } from 'node:fs';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url));
const desktop = resolve(here, '..');
const root = resolve(desktop, '..');

// 1. version
const pom = readFileSync(join(root, 'analyzer/pom.xml'), 'utf8');
const version = /<artifactId>flowlens-analyzer<\/artifactId>\s*(?:<!--[\s\S]*?-->\s*)?<version>([^<]+)<\/version>/.exec(pom)?.[1];
if (!version) throw new Error('could not read the version from analyzer/pom.xml');
const conf = join(desktop, 'src-tauri/tauri.conf.json');
const c = JSON.parse(readFileSync(conf, 'utf8'));
if (c.version !== version) { c.version = version; writeFileSync(conf, JSON.stringify(c, null, 2) + '\n'); }
const pkgPath = join(desktop, 'package.json');
const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'));
if (pkg.version !== version) { pkg.version = version; writeFileSync(pkgPath, JSON.stringify(pkg, null, 2) + '\n'); }
const cargo = join(desktop, 'src-tauri/Cargo.toml');
const toml = readFileSync(cargo, 'utf8');
const updated = toml.replace(/^version = "[^"]+"/m, `version = "${version}"`);
if (updated !== toml) writeFileSync(cargo, updated);
console.log('• version', version);

// 2. viewer
const dist = join(desktop, 'dist');
rmSync(dist, { recursive: true, force: true });
const skip = new Set(['tests', 'node_modules', 'package.json', 'package-lock.json', 'graph.json']);
cpSync(join(root, 'viewer'), dist, { recursive: true, filter: src => !skip.has(basename(src)) });
console.log('• viewer copied to', dist);

// 3. engine
const engine = join(desktop, 'src-tauri/engine/flowlens-analyzer.jar');
if (!existsSync(engine)) {
  console.log('• engine missing: building it');
  execFileSync(process.execPath, [join(here, 'build-engine.mjs')], { stdio: 'inherit' });
}
