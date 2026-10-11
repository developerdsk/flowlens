#!/usr/bin/env node
// Builds the engine that ships inside the desktop app:
//   src-tauri/engine/flowlens-analyzer.jar   (the analyzer, from analyzer/)
//   src-tauri/engine/runtime/                 (a minimal Java runtime made with jlink)
//
// Options (env):
//   JAVA_HOME          JDK 17+ used for jdeps/jlink (default: the `java` on PATH)
//   FLOWLENS_JMODS     jmods folder of a JDK for ANOTHER platform/arch (cross-build, e.g. x64 on Apple Silicon).
//                      Must be the same JDK version as JAVA_HOME.
//   SKIP_JAR=1         reuse analyzer/target/flowlens-analyzer.jar
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, rmSync, mkdirSync, copyFileSync, readdirSync, lstatSync, readlinkSync, unlinkSync, cpSync, statSync } from 'node:fs';
import { join, resolve, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../..');
const analyzer = join(root, 'analyzer');
const jar = join(analyzer, 'target', 'flowlens-analyzer.jar');
const out = resolve(here, '../src-tauri/engine');
const win = process.platform === 'win32';
const run = (cmd, args, opts = {}) => execFileSync(cmd, args, { stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8', shell: win && cmd.endsWith('.cmd'), ...opts });

/** `java -XshowSettings:properties -version` writes to stderr. */
function javaProps(java) {
  const r = spawnSync(java, ['-XshowSettings:properties', '-version'], { encoding: 'utf8', env: { ...process.env, JAVA_TOOL_OPTIONS: '' } });
  return (r.stderr || '') + (r.stdout || '');
}

function javaHome() {
  if (process.env.JAVA_HOME && existsSync(join(process.env.JAVA_HOME, 'bin'))) return process.env.JAVA_HOME;
  const m = /java\.home = (.+)/.exec(javaProps('java'));
  if (!m) throw new Error('Set JAVA_HOME to a JDK 17+');
  return m[1].trim();
}

// 1. the jar
if (!process.env.SKIP_JAR || !existsSync(jar)) {
  console.log('• building analyzer jar');
  run(join(analyzer, win ? 'mvnw.cmd' : 'mvnw'), ['-B', '-q', '-DskipTests', 'package'], { cwd: analyzer, stdio: 'inherit' });
}
if (!existsSync(jar)) throw new Error('analyzer jar missing: ' + jar);

// 2. modules the engine needs (java.desktop is left out on purpose: the app uses native dialogs)
const home = javaHome();
const bin = n => join(home, 'bin', n + (win ? '.exe' : ''));
let modules;
try {
  modules = run(bin('jdeps'), ['--print-module-deps', '--ignore-missing-deps', '--multi-release', '17', jar]).trim().split(',');
} catch {
  modules = ['java.base', 'java.logging', 'java.sql', 'java.xml', 'jdk.httpserver'];
}
const wanted = new Set(modules.filter(m => m && m !== 'java.desktop'));
['java.base', 'java.xml', 'jdk.httpserver', 'jdk.charsets', 'jdk.zipfs', 'java.logging'].forEach(m => wanted.add(m));
console.log('• runtime modules:', [...wanted].sort().join(', '));

// 3. jlink
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
const major = parseInt((/java\.specification\.version = (\d+)/.exec(javaProps(bin('java'))) || [0, '17'])[1], 10);
const args = [
  '--add-modules', [...wanted].join(','),
  '--strip-debug', '--no-man-pages', '--no-header-files',
  major >= 21 ? '--compress=zip-6' : '--compress=2',
  '--output', join(out, 'runtime'),
];
if (process.env.FLOWLENS_JMODS) args.unshift('--module-path', process.env.FLOWLENS_JMODS);
console.log('• jlink' + (process.env.FLOWLENS_JMODS ? ' (cross-platform jmods: ' + process.env.FLOWLENS_JMODS + ')' : ''));
run(bin('jlink'), args, { stdio: 'inherit' });

// 4. bundlers dislike symlinks (jlink puts some in legal/): replace them with copies
(function deref(dir) {
  for (const name of readdirSync(dir)) {
    const p = join(dir, name);
    const st = lstatSync(p);
    if (st.isSymbolicLink()) {
      const target = resolve(dirname(p), readlinkSync(p));
      unlinkSync(p);
      if (existsSync(target) && statSync(target).isDirectory()) cpSync(target, p, { recursive: true });
      else if (existsSync(target)) copyFileSync(target, p);
    } else if (st.isDirectory()) deref(p);
  }
})(join(out, 'runtime'));

copyFileSync(jar, join(out, 'flowlens-analyzer.jar'));

// 5. smoke test the runtime (skipped when cross-building for another arch)
if (!process.env.FLOWLENS_JMODS) {
  const v = run(join(out, 'runtime', 'bin', 'java' + (win ? '.exe' : '')), ['-jar', join(out, 'flowlens-analyzer.jar'), '--version'], { env: { ...process.env, JAVA_TOOL_OPTIONS: '' } }).trim();
  console.log('• engine OK, version', v.split('\n').pop());
}
const size = dir => readdirSync(dir).reduce((s, n) => { const p = join(dir, n); const st = lstatSync(p); return s + (st.isDirectory() ? size(p) : st.size); }, 0);
console.log(`• engine ready in ${out} (${(size(out) / 1048576).toFixed(1)} MB)`);
