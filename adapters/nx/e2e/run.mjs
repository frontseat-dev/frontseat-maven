// End-to-end proof for @mojo-io/nx: correctness against raw Maven, and speed.
//
//   node adapters/nx/e2e/run.mjs            (from the repository root, after `mise run install`)
//
// Env: MOJOIO_MAVEN (mvn or mvnd executable used by the Nx targets; default mvnd if on PATH),
//      E2E_SKIP_PERF=1 to skip the 20-module benchmark.
import { execSync, spawn, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const repo = process.cwd();
const plugin = path.join(repo, 'adapters/nx');
const maven = process.env.MOJOIO_MAVEN || 'mvnd';
const work = fs.mkdtempSync(path.join(os.tmpdir(), 'mojoio-nx-e2e-'));
// A shared remote cache, as a team or CI would use: Nx's self-hosted HTTP cache protocol.
const cachePort = 20000 + Math.floor(Math.random() * 20000);
const cacheServer = spawn(process.execPath, [path.join(plugin, 'e2e/cache-server.mjs'), path.join(work, 'remote-cache'), String(cachePort)],
  { stdio: 'ignore' });
process.on('exit', () => cacheServer.kill());
const env = { ...process.env, MOJOIO_MAVEN: maven, NX_DAEMON: 'false', NX_NO_CLOUD: 'true', NX_TUI: 'false',
  NX_SELF_HOSTED_REMOTE_CACHE_SERVER: `http://127.0.0.1:${cachePort}`, NX_SELF_HOSTED_REMOTE_CACHE_ACCESS_TOKEN: 'e2e' };
const report = [];
let failures = 0;

function check(ok, what) {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`);
  report.push(`- ${ok ? '✅' : '❌'} ${what}`);
  if (!ok) failures++;
}

function sh(cmd, cwd) {
  const t = process.hrtime.bigint();
  const r = spawnSync('bash', ['-c', cmd], { cwd, env, encoding: 'utf8', maxBuffer: 1 << 28 });
  const seconds = Number(process.hrtime.bigint() - t) / 1e9;
  if (r.status !== 0) {
    console.error(r.stdout, r.stderr);
    throw new Error(`failed (${r.status}): ${cmd} in ${cwd}`);
  }
  return { out: r.stdout + r.stderr, seconds };
}

function copyDir(src, dst, skip = ['target', 'node_modules', '.nx', '.git']) {
  fs.mkdirSync(dst, { recursive: true });
  for (const e of fs.readdirSync(src, { withFileTypes: true })) {
    if (skip.includes(e.name)) continue;
    const s = path.join(src, e.name), d = path.join(dst, e.name);
    e.isDirectory() ? copyDir(s, d, skip) : fs.copyFileSync(s, d);
  }
}

/** Turn a Maven project into an Nx workspace using @mojo-io/nx and the mojo-io extension. */
function nxWorkspace(src, dir, options = {}) {
  copyDir(src, dir);
  fs.mkdirSync(path.join(dir, '.mvn'), { recursive: true });
  fs.writeFileSync(path.join(dir, '.mvn/extensions.xml'), `<extensions><extension>
  <groupId>dev.frontseat.mojoio</groupId><artifactId>mojo-io-maven-extension</artifactId><version>0.1.0-SNAPSHOT</version>
</extension></extensions>\n`);
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name: 'e2e', private: true,
    devDependencies: { nx: '24.0.6', '@mojo-io/nx': `file:${plugin}` } }, null, 2));
  fs.writeFileSync(path.join(dir, 'nx.json'), JSON.stringify({ plugins: [{ plugin: '@mojo-io/nx', options }], parallel: 4 }, null, 2));
  fs.writeFileSync(path.join(dir, '.gitignore'), 'target/\nnode_modules/\n.nx/\n');
  sh('git init -q . && git add -A && git -c user.email=e2e@example.com -c user.name=e2e commit -qm init && npm install --no-fund --no-audit --silent', dir);
  return dir;
}

/** Runs Nx; returns seconds and the tasks that actually ran (not restored from cache). */
function nx(dir, args) {
  const { out, seconds } = sh(`npx nx ${args} --outputStyle=static`, dir);
  const ran = [...out.matchAll(/^> nx run (\S+)(.*)$/gm)].filter((m) => !/cache\]/.test(m[2])).map((m) => m[1]);
  const total = [...out.matchAll(/^> nx run (\S+)/gm)].length;
  return { seconds, ran, total };
}

const sha = (f) => createHash('sha256').update(fs.readFileSync(f)).digest('hex');

function walk(dir, base = dir, out = {}) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    e.isDirectory() ? walk(p, base, out) : (out[path.relative(base, p)] = sha(p));
  }
  return out;
}

function jarEntries(jar) {
  const list = execSync(`unzip -Z1 "${jar}"`, { encoding: 'utf8' }).split('\n').filter((n) => n && !n.endsWith('/')).sort();
  return Object.fromEntries(list.map((n) => [n, createHash('sha256').update(execSync(`unzip -p "${jar}" "${n}"`)).digest('hex')]));
}

/** What must be identical to raw Maven: classes, resources, jar contents, test results. */
function fingerprint(dir, modules) {
  const r = {};
  for (const m of modules) {
    const t = path.join(dir, m, 'target');
    r[m] = { classes: walk(path.join(t, 'classes')), testClasses: walk(path.join(t, 'test-classes')), jars: {} };
    for (const j of fs.existsSync(t) ? fs.readdirSync(t).filter((f) => f.endsWith('.jar')) : []) r[m].jars[j] = jarEntries(path.join(t, j));
    const reports = path.join(t, 'surefire-reports');
    r[m].tests = fs.existsSync(reports) ? fs.readdirSync(reports).filter((f) => f.endsWith('.xml')).map((f) =>
      fs.readFileSync(path.join(reports, f), 'utf8').match(/tests="(\d+)" errors="(\d+)" skipped="(\d+)" failures="(\d+)"/)[0]).sort() : [];
  }
  return JSON.stringify(r);
}

// ---------------------------------------------------------------- correctness (sample project)
const sample = path.join(repo, 'mojo-io-it/src/it/projects/basic-multimodule');
const modules = ['lib-core', 'app'];
await new Promise((r) => setTimeout(r, 500)); // cache server start
console.log(`work dir: ${work}\nmaven for Nx targets: ${maven}\nremote cache: ${env.NX_SELF_HOSTED_REMOTE_CACHE_SERVER}\n`);

const raw = path.join(work, 'raw');
copyDir(sample, raw);
sh('mvn -B -q verify', raw);
const reference = fingerprint(raw, modules);

const a = nxWorkspace(sample, path.join(work, 'a'));
const cold = nx(a, 'run-many -t verify');
check(cold.ran.length === cold.total, `cold: Nx ran all ${cold.total} tasks (${cold.seconds.toFixed(1)}s)`);
check(fingerprint(a, modules) === reference, 'cold: classes, resources, jar contents and test results identical to raw `mvn verify`');

// A fresh checkout elsewhere, sharing only the remote cache.
const b = nxWorkspace(sample, path.join(work, 'b'));
const restored = nx(b, 'run-many -t verify');
check(restored.ran.length === 0, `fresh checkout + shared remote cache: all ${restored.total} tasks restored, none ran (${restored.seconds.toFixed(1)}s)`);
check(fingerprint(b, modules) === reference, 'fresh checkout: restored outputs identical to raw `mvn verify`');

// Continue the build on restored outputs: Maven needs the replayed state (artifact files, argLine).
const cont = sh(`${maven} -B -q -pl com.example.samples:app -am -Dmojoio.only=com.example.samples:app -Dmojoio.cache.enabled=false -Dmojoio.prerequisites=false org.apache.maven.plugins:maven-surefire-plugin:3.5.2:test@default-test`, b);
check(fs.statSync(path.join(b, 'app/target/jacoco.exec')).size > 0, 'restored checkout continues with plain Maven: app tests run with the JaCoCo agent (replayed argLine)');

// Change a lib-core test: Maven 4 phase links keep app's compile and test cached.
const t = path.join(b, 'lib-core/src/test/java/sample/core/GreeterTest.java');
fs.writeFileSync(t, fs.readFileSync(t, 'utf8').replace('class GreeterTest {',
  'class GreeterTest {\n\n    @Test\n    void greetsTheWorld() {\n        assertEquals("Hello, world!", new Greeter().greet("world"));\n    }\n'));
const libTest = nx(b, 'run-many -t verify');
check(libTest.ran.includes('lib-core:test') && !libTest.ran.includes('lib-core:compile') && !libTest.ran.some((x) => x === 'app:compile' || x === 'app:test'),
  `lib-core test changed: ran [${libTest.ran.join(', ')}]; app compile/test stayed cached`);
const raw2 = path.join(work, 'raw2');
copyDir(b, raw2);
sh('rm -rf .mvn node_modules package.json nx.json && mvn -B -q verify', raw2);
check(fingerprint(b, modules) === fingerprint(raw2, modules), 'after the change: outputs identical to raw `mvn verify` of the changed sources');

// ---------------------------------------------------------------- speed (20-module reactor)
if (!process.env.E2E_SKIP_PERF) {
  const gen = path.join(work, 'bench-src');
  sh(`java ${path.join(repo, 'perf/Bench.java')} --generate ${gen}`, repo);
  const benchModules = fs.readdirSync(gen).filter((d) => /^m\d+$/.test(d));
  const rawBench = path.join(work, 'bench-raw');
  copyDir(gen, rawBench);
  sh('mvn -B -q verify', rawBench); // warm-up: downloads
  const rows = [];
  const time = (label, fn) => { const r = fn(); rows.push([label, r.seconds, r.ran ?? '', r.total ?? '']); console.log(`${label.padEnd(58)} ${r.seconds.toFixed(1)}s`); return r; };
  time('raw mvn clean verify', () => sh('mvn -B -q -o clean verify', rawBench));
  if (maven.endsWith('mvnd')) {
    sh(`${maven} -B -q -o verify`, rawBench);
    time('raw mvnd clean verify', () => sh(`${maven} -B -q -o clean verify`, rawBench));
  }
  // Cold builds by target size: every target is one Maven invocation.
  let c;
  for (const g of ['phase', 'stage', 'module']) {
    // The default is left unset, as in the fresh checkout below: Nx hashes the plugin options.
    const ws = nxWorkspace(gen, path.join(work, `bench-nx-${g}`), g === 'stage' ? {} : { granularity: g });
    nx(ws, 'show projects'); // graph export
    const r = time(`Nx cold, granularity ${g}${g === 'stage' ? ' (default)' : ''}`, () => nx(ws, 'run-many -t verify'));
    check(fingerprint(ws, benchModules) === fingerprint(rawBench, benchModules), `20 modules, ${g}: Nx outputs identical to raw Maven (${r.ran.length} Maven invocations)`);
    if (g === 'stage') c = r;
  }
  const n2 = nxWorkspace(gen, path.join(work, 'bench-nx-2'));
  nx(n2, 'show projects');
  const w = time('Nx fresh checkout, shared cache (all restored)', () => nx(n2, 'run-many -t verify'));
  check(w.ran.length === 0, `20 modules, fresh checkout: all ${w.total} tasks restored`);
  const src = path.join(n2, 'm00/src/main/java/bench/m00/C5.java');
  fs.appendFileSync(src, '\n// touched\n');
  const ch = time('Nx after a comment change in m00', () => nx(n2, 'run-many -t verify'));
  fs.appendFileSync(src, '\nclass Extra { int x = 1; }\n');
  const ch2 = time('Nx after a real change in m00 (new class)', () => nx(n2, 'run-many -t verify'));
  // Every module depends on m00, directly or transitively, so every compile and test must re-run.
  const rawChanged = path.join(work, 'bench-raw-changed');
  copyDir(n2, rawChanged);
  sh('rm -rf .mvn node_modules package.json nx.json && mvn -B -q -o verify', rawChanged);
  check(fingerprint(n2, benchModules) === fingerprint(rawChanged, benchModules), `20 modules, after the change: outputs identical to raw Maven (${ch2.ran.length} of ${ch2.total} tasks re-ran)`);

  // One target per module, with mojo-io's own cache on inside it: when a module's target re-runs,
  // its mojos with unchanged inputs are restored instead of run.
  const inner = { granularity: 'module', innerCache: true, args: [`-Dmojoio.cache.dir=${path.join(work, 'inner-cache')}`] };
  const n3 = nxWorkspace(gen, path.join(work, 'bench-nx-inner'), inner);
  nx(n3, 'show projects');
  time('Nx cold, module + inner cache', () => nx(n3, 'run-many -t verify'));
  const t3 = path.join(n3, 'm00/src/test/java/bench/m00');
  const testFile = path.join(t3, fs.readdirSync(t3)[0]);
  fs.appendFileSync(testFile, '\n// touched\n');
  const in1 = time('Nx module + inner cache, after a test comment change in m00', () => nx(n3, 'run-many -t verify'));
  const rawInner = path.join(work, 'bench-raw-inner');
  copyDir(n3, rawInner);
  sh('rm -rf .mvn node_modules package.json nx.json && mvn -B -q -o verify', rawInner);
  check(fingerprint(n3, benchModules) === fingerprint(rawInner, benchModules), `module + inner cache: outputs identical to raw Maven after the change (${in1.ran.length} targets re-ran)`);
  report.push('', '| Scenario | Seconds | Tasks run / total |', '|---|---|---|',
    ...rows.map(([l, s, ran, tot]) => `| ${l} | ${s.toFixed(1)} | ${Array.isArray(ran) ? `${ran.length} / ${tot}` : ''} |`));
  console.log(`comment change ran: ${ch.ran.length}, new class ran: ${ch2.ran.length} tasks`);
}

fs.writeFileSync(path.join(work, 'report.md'), report.join('\n') + '\n');
console.log(`\n${report.join('\n')}\n\nreport: ${path.join(work, 'report.md')}`);
process.exit(failures ? 1 : 0);
