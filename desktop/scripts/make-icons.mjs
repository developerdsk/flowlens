#!/usr/bin/env node
// Renders viewer/icon.svg to a 1024px PNG and lets the Tauri CLI produce every platform icon.
// Needs Playwright's Chromium (set CHROMIUM_PATH) and the Tauri CLI (npm ci).
import { chromium } from 'playwright-core';
import { readFileSync, mkdirSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const svg = readFileSync(join(root, 'viewer/icon.svg'), 'utf8');
const out = join(here, '../src-tauri/icons');
mkdirSync(out, { recursive: true });
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH || undefined });
const page = await browser.newPage({ viewport: { width: 1024, height: 1024 } });
await page.setContent(`<html><body style="margin:0;background:transparent">${svg.replace('<svg ', '<svg width="1024" height="1024" ')}</body></html>`);
const png = join(out, 'icon-source.png');
await page.screenshot({ path: png, omitBackground: true, clip: { x: 0, y: 0, width: 1024, height: 1024 } });
await browser.close();
execFileSync('npx', ['tauri', 'icon', png, '-o', out], { stdio: 'inherit', cwd: resolve(here, '..'), shell: process.platform === 'win32' });
