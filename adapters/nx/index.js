// @ts-check
/**
 * Nx plugin for Maven, built on mojo-io's tool-agnostic task graph (docs/task-graph.md).
 *
 * - Projects and targets come from `mvn -Dmojoio.graph.output=…` (a dry run): one Nx target per graph
 *   task (by default a stage: compile, test, package, verify), with the graph's dependsOn (Maven 4
 *   phase links).
 * - A target runs its executions in one Maven invocation, through mvnd when available:
 *   `-pl <id> -am -Dmojoio.only=<id> <invocations>`. The mojo-io extension replays the recorded state of
 *   everything that does not run, so restored outputs are enough to continue the build.
 * - Inputs: the task's source files and poms, the fingerprint of its evaluated configuration (in the
 *   command, which Nx hashes), and the outputs of the tasks it depends on (the classpath).
 * - Outputs: the declared outputs and the state files; "observed" outputs (tests, agents) as the build
 *   directory minus the outputs of the project's other tasks.
 *
 * Requires the mojo-io extension in the workspace (.mvn/extensions.xml).
 *
 * Options (nx.json plugins[].options):
 *   maven:        Maven executable (default: $MOJOIO_MAVEN, else mvnd if on PATH, else mvn)
 *   goal:         lifecycle goal the graph is computed for (default: "verify")
 *   granularity:  target size: "phase", "stage" (default: compile, test, package, verify) or "module".
 *                 Every target is one Maven invocation (about 0.1s overhead through mvnd), so fewer,
 *                 bigger targets make cold builds faster; smaller ones cache more finely.
 *   args:         extra Maven arguments for every target (default: [])
 */
const { createHash } = require('crypto');
const { execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');
const os = require('os');

const CACHE_VERSION = 5;

/** Maven 3 default lifecycle (task names are canonical Maven 3 phases on Maven 3 and 4 alike). */
const DEFAULT_LIFECYCLE = ['validate', 'initialize', 'generate-sources', 'process-sources', 'generate-resources',
  'process-resources', 'compile', 'process-classes', 'generate-test-sources', 'process-test-sources',
  'generate-test-resources', 'process-test-resources', 'test-compile', 'process-test-classes', 'test',
  'prepare-package', 'package', 'pre-integration-test', 'integration-test', 'post-integration-test', 'verify',
  'install', 'deploy'];

function mavenExecutable(options) {
  if (options && options.maven) return options.maven;
  if (process.env.MOJOIO_MAVEN) return process.env.MOJOIO_MAVEN;
  for (const dir of (process.env.PATH || '').split(path.delimiter)) {
    if (dir && fs.existsSync(path.join(dir, 'mvnd'))) return 'mvnd';
  }
  return 'mvn';
}

/**
 * Arguments for every Maven call. With mvnd, parallel targets start extra daemons, and mvnd stops such
 * duplicates after 10 idle seconds by default: a build would keep starting cold JVMs. Keep them warm.
 */
function baseArgs(maven) {
  return /mvnd(\.cmd|\.exe|\.sh)?$/.test(maven) ? ['-Dmvnd.duplicateDaemonGracePeriod=3h'] : [];
}

function granularity(options) {
  const g = (options && options.granularity) || 'stage';
  if (!['phase', 'stage', 'module'].includes(g)) throw new Error(`@mojo-io/nx: unknown granularity "${g}"`);
  return g;
}

/** Files whose content determines the graph: every pom, the plugin-io overrides, the extension config. */
function graphInputs(workspaceRoot, pomFiles) {
  const files = [...pomFiles];
  const walk = (dir) => {
    const abs = path.join(workspaceRoot, dir);
    if (!fs.existsSync(abs)) return;
    for (const e of fs.readdirSync(abs, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      e.isDirectory() ? walk(path.join(dir, e.name)) : files.push(path.join(dir, e.name));
    }
  };
  walk('.mvn/plugin-io');
  for (const f of ['.mvn/extensions.xml', '.mvn/maven.config', '.mvn/jvm.config']) {
    if (fs.existsSync(path.join(workspaceRoot, f))) files.push(f);
  }
  return files;
}

/** The user's Maven settings (profiles, properties, mirrors) also shape the evaluated configuration. */
function userSettings() {
  const f = path.join(os.homedir(), '.m2', 'settings.xml');
  return fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : '';
}

function hash(...parts) {
  const h = createHash('sha256');
  for (const p of parts) h.update(String(p)).update('\0');
  return h.digest('hex');
}

function javaVersion() {
  try {
    return execFileSync('java', ['-version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
  } catch (e) {
    return String((e && e.stderr) || 'unknown');
  }
}

/** Runs the dry-run graph export, cached on the inputs that determine it. */
function readGraph(workspaceRoot, pomFiles, options) {
  const maven = mavenExecutable(options);
  const goal = (options && options.goal) || 'verify';
  const inputs = graphInputs(workspaceRoot, pomFiles);
  const key = hash(CACHE_VERSION, maven, goal, granularity(options), JSON.stringify((options && options.args) || []), javaVersion(),
    process.env.JAVA_HOME || '', userSettings(), ...inputs.map((f) => f + '=' + fs.readFileSync(path.join(workspaceRoot, f), 'utf8')));
  const cacheDir = path.join(workspaceRoot, '.nx', 'workspace-data', 'mojo-io');
  const cached = path.join(cacheDir, `graph-${key}.json`);
  if (fs.existsSync(cached)) {
    // createDependencies reads the most recently used graph.
    const now = new Date();
    fs.utimesSync(cached, now, now);
    return JSON.parse(fs.readFileSync(cached, 'utf8'));
  }

  fs.mkdirSync(cacheDir, { recursive: true });
  const out = path.join(os.tmpdir(), `mojo-io-graph-${process.pid}-${Date.now()}.json`);
  execFileSync(maven, [...baseArgs(maven), '-B', '-q', goal, `-Dmojoio.graph.output=${out}`,
    `-Dmojoio.graph.granularity=${granularity(options)}`, ...(((options && options.args) || []))],
    { cwd: workspaceRoot, stdio: ['ignore', 'pipe', 'inherit'] });
  const graph = JSON.parse(fs.readFileSync(out, 'utf8'));
  fs.rmSync(out, { force: true });
  if (graph.format !== 'mojo-io-task-graph' || graph.version !== 1) {
    throw new Error(`@mojo-io/nx: unsupported graph ${graph.format} ${graph.version}`);
  }
  fs.writeFileSync(cached, JSON.stringify(graph));
  return graph;
}

/** Nx project name for a Maven project: artifactId, or groupId.artifactId when artifactIds clash. */
function projectNames(graph) {
  const counts = {};
  for (const p of graph.projects) {
    const a = p.id.split(':')[1];
    counts[a] = (counts[a] || 0) + 1;
  }
  const names = {};
  for (const p of graph.projects) {
    const [g, a] = p.id.split(':');
    names[p.id] = counts[a] > 1 ? `${g}.${a}` : a;
  }
  return names;
}

/** mojo-io path placeholders -> Nx tokens. */
function nxPath(p) {
  return p.replace('{projectRoot}', '{projectRoot}').replace('{workspaceRoot}', '{workspaceRoot}');
}

function globs(output) {
  const base = nxPath(output.path);
  const includes = output.includes && output.includes.length ? output.includes : null;
  if (!includes) return [base];
  return includes.map((i) => `${base}/${i}`);
}

function targetsFor(project, graph, names, options) {
  const maven = mavenExecutable(options);
  const executions = Object.fromEntries(project.executions.map((e) => [e.id, e]));
  const extDeps = hash(...(project.externalDependencies || []));
  const tasks = project.tasks || [];

  // Declared (non-observed) outputs of every task in the project: an observed output excludes them.
  const declared = (task) => task.executions.flatMap((id) => executions[id].outputs.filter((o) => !o.observed)
    .flatMap(globs).concat([nxPath(executions[id].stateFile)]));
  const allDeclared = Object.fromEntries(tasks.map((t) => [t.name, declared(t)]));

  const targets = {};
  for (const task of tasks) {
    const members = task.executions.map((id) => executions[id]);
    const cacheable = members.every((e) => e.cacheable);
    const fingerprint = hash(...members.map((e) => e.fingerprint || e.id), extDeps);

    const inputs = [];
    for (const pom of project.poms) inputs.push(nxPath(pom));
    const fileInputs = members.flatMap((e) => e.inputs.filter((i) => i.kind === 'files'));
    // Generated paths (under the build directory) are outputs of other tasks: hashed below.
    const inBuildDirectory = (glob) => glob.includes('/target/') || glob.endsWith('/target');
    for (const input of fileInputs) {
      for (const p of input.paths) {
        const glob = nxPath(p);
        if (inBuildDirectory(glob)) continue;
        if (input.includes && input.includes.length) {
          // Only what the mojo selects (its excludes are not applied: hashing a little more is safe).
          inputs.push(glob, ...input.includes.map((i) => `${glob}/${i}`));
          continue;
        }
        inputs.push(glob, `${glob}/**/*`);
        // Nx applies a "!" to the whole target, so an exclusion may only be passed on where no other
        // input of the task could want those files. That is the case for the subtrees "the project's
        // own files" leaves out (the build directory, nested modules), unless another input lies in
        // one or is a whole directory around it.
        if (input.name !== 'projectFiles') continue;
        for (const x of input.excludes || []) {
          if (!x.endsWith('/**')) continue;
          const subtree = `${glob}/${x.slice(0, -3)}`;
          const wanted = fileInputs.some((other) => other !== input && other.paths.map(nxPath).some((o) =>
            !inBuildDirectory(o) && (o === subtree || o.startsWith(`${subtree}/`)
              || (!(other.includes && other.includes.length) && (subtree === o || subtree.startsWith(`${o}/`))))));
          if (!wanted) inputs.push(`!${subtree}/**`, `!${subtree}/**/*`);
        }
      }
    }
    // What build extensions read while Maven loads this project and the other reactor projects the
    // command selects (Tycho: manifests, feature.xml, the target definition).
    for (const f of project.loadFiles || []) inputs.push(nxPath(f));
    for (const ref of project.reactorReferences || []) {
      const other = graph.projects.find((p) => p.id === ref);
      if (!other) continue;
      const otherRoot = other.path === '' ? '{workspaceRoot}' : `{workspaceRoot}/${other.path}`;
      for (const f of other.loadFiles || []) inputs.push(f.replace('{projectRoot}', otherRoot));
    }
    // transitive: Maven classpaths are transitive, so a change two modules up must change this hash too.
    inputs.push({ dependentTasksOutputFiles: '**/*', transitive: true });

    const outputs = [];
    for (const e of members) {
      for (const o of e.outputs) {
        if (!o.observed) {
          outputs.push(...globs(o));
          continue;
        }
        // Known only after the run: everything written in the build directory, minus what other tasks
        // of the project declare.
        outputs.push(`${nxPath(o.path)}/**/*`);
        for (const [name, globsOfOther] of Object.entries(allDeclared)) {
          if (name !== task.name) outputs.push(...globsOfOther.map((g) => `!${g}`));
        }
        // Bookkeeping of any execution in the project (maven-status, maven-archiver, consumer poms) and
        // mojo-io's own directory are not outputs; their churn would destabilise hashes downstream.
        for (const other of project.executions) {
          for (const w of other.ignoredWrites || []) outputs.push(`!${nxPath(o.path)}/${w}`);
        }
        outputs.push(`!${nxPath(o.path)}/mojo-io/**`);
      }
    }
    // State files last: they live under the build directory's mojo-io/ that observed outputs exclude.
    for (const e of members) outputs.push(nxPath(e.stateFile));

    const dependsOn = task.dependsOn.map((id) => {
      const [g, a, ...rest] = id.split(':');
      const owner = `${g}:${a}`;
      const name = rest.join(':');
      return owner === project.id ? name : { projects: [names[owner]], target: name };
    });

    // The reactor projects the task needs that Maven's "-am" can miss (named in configuration, or
    // added as dependencies while loading, as Eclipse Tycho does).
    const projectList = [project.id, ...(project.reactorReferences || [])].join(',');
    const invocation = [maven, ...baseArgs(maven), '-B', '--no-transfer-progress', '-pl', projectList, '-am',
      `-Dmojoio.only=${project.id}`,
      // Not read by Maven: makes Nx's hash cover what it cannot hash itself (evaluated configuration,
      // plugin versions, JDK, external dependencies).
      `-Dmojoio.fingerprint=${fingerprint}`,
      ...(((options && options.args) || [])), ...members.map((e) => e.invocation)].join(' ');
    // The extension fails a build that ended without building the selected project and leaves a marker
    // (a rare mvnd race: see docs/performance.md). That failure, and only that one, is retried once.
    // The marker sits next to the state directory. A leftover one is removed first, so that only a
    // failure of this run is retried.
    const stateDir = path.posix.dirname(members[0].stateFile.replace('{projectRoot}', project.path === '' ? '.' : project.path));
    const marker = `${path.posix.dirname(stateDir)}/not-built`;
    const command = process.platform === 'win32' ? invocation
      : `rm -f ${marker}; ${invocation} || { [ -f ${marker} ] && rm -f ${marker} && ${invocation}; }`;

    targets[task.name] = {
      executor: 'nx:run-commands',
      options: { command, cwd: '{workspaceRoot}' },
      cache: cacheable,
      inputs,
      outputs: [...new Set(outputs)],
      dependsOn,
      metadata: { technologies: ['maven'], description: `${task.name}: ${members.map((e) => e.plugin.split(':')[1] + ':' + e.goal).join(', ')}` },
    };
  }

  // Every lifecycle phase up to the goal is a target, like `mvn <phase>`: phases with no mojos bound are
  // no-ops on the latest task before them, so `nx run-many -t verify` works whatever is bound.
  const goal = (options && options.goal) || 'verify';
  const goalIndex = DEFAULT_LIFECYCLE.indexOf(goal);
  let latest = null;
  for (const phase of DEFAULT_LIFECYCLE.slice(0, goalIndex < 0 ? DEFAULT_LIFECYCLE.length : goalIndex + 1)) {
    if (targets[phase]) {
      latest = phase;
    } else {
      // A runTogether group is named after its base phase; its hook phases resolve to it.
      const group = tasks.find((t) => t.executions.some((id) => executions[id].canonicalPhase === phase));
      targets[phase] = {
        executor: 'nx:noop',
        dependsOn: group ? [group.name] : latest ? [latest] : [],
        cache: true,
        metadata: { technologies: ['maven'], description: `${phase}: no mojos bound (lifecycle phase)` },
      };
      if (group) latest = group.name;
    }
  }
  return targets;
}

/** @type {import('nx/src/project-graph/plugins').CreateNodes} */
const createNodes = [
  '**/pom.xml',
  async (pomFiles, options, context) => {
    if (!pomFiles.includes('pom.xml')) return []; // the reactor root drives everything
    const graph = readGraph(context.workspaceRoot, pomFiles, options);
    const names = projectNames(graph);
    const results = [];
    for (const p of graph.projects) {
      const root = p.path === '' ? '.' : p.path;
      results.push([path.join(p.path, 'pom.xml'), {
        projects: {
          [root]: {
            name: names[p.id],
            root,
            projectType: p.packaging === 'pom' ? 'library' : 'library',
            targets: targetsFor(p, graph, names, options),
            metadata: { maven: { id: p.id, version: p.version, packaging: p.packaging } },
          },
        },
      }]);
    }
    return results;
  },
];

/** @type {import('nx/src/project-graph/plugins').CreateDependencies} */
const createDependencies = async (options, context) => {
  const cacheDir = path.join(context.workspaceRoot, '.nx', 'workspace-data', 'mojo-io');
  if (!fs.existsSync(cacheDir)) return [];
  const latest = fs.readdirSync(cacheDir).filter((f) => f.startsWith('graph-'))
    .map((f) => path.join(cacheDir, f)).sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs)[0];
  if (!latest) return [];
  const graph = JSON.parse(fs.readFileSync(latest, 'utf8'));
  const names = projectNames(graph);
  const deps = [];
  for (const p of graph.projects) {
    for (const up of p.dependsOn) {
      if (names[up] && context.projects[names[p.id]] && context.projects[names[up]]) {
        deps.push({ source: names[p.id], target: names[up], type: 'static', sourceFile: path.join(p.path, 'pom.xml') });
      }
    }
  }
  return deps;
};

module.exports = { name: '@mojo-io/nx', createNodes, createDependencies };
