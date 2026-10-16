#!/usr/bin/env node
// Runs the Tauri CLI, turning each non-empty SECRET_<NAME> into <NAME>. The bundler treats a signing
// variable that exists, even empty, as "sign", so unset secrets must not reach it at all.
import { spawnSync } from 'node:child_process';

const env = {};
for (const [k, v] of Object.entries(process.env)) {
  if (!k.startsWith('SECRET_')) env[k] = v;
  else if (v) env[k.slice('SECRET_'.length)] = v;
}
const r = spawnSync('npx', ['tauri', ...process.argv.slice(2)], { stdio: 'inherit', env, shell: process.platform === 'win32' });
process.exit(r.status ?? 1);
