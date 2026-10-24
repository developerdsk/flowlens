#!/usr/bin/env node
// Runs before `tauri dev` / `tauri build`:
//  1. syncs the app version (scripts/version.mjs)
//  2. copies the viewer into desktop/dist (without tests / node_modules)
//  3. builds the engine if it is missing
import { rmSync, cpSync, existsSync } from 'node:fs';
import { join, resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { sync } from './version.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const desktop = resolve(here, '..');
const root = resolve(desktop, '..');

// 1. version
console.log('• version', sync());

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
