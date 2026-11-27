package dev.frontseat.mojoio.extension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.maven.execution.MavenSession;

/**
 * Maven 4's default lifecycle as a phase tree with links: which phases of the same project, of its
 * dependencies (by scope) or of its children a phase waits for. Read from Maven 4's
 * {@code LifecycleRegistry} at run time; on Maven 3 a built-in copy of the Maven 4 default (4.0.0-rc-7)
 * is used, so both produce the same task graph.
 */
final class LifecycleModel {

    record Link(String kind, String type, String scope, String phase) {}

    record Phase(String name, String parent, List<Link> links) {}

    /** Maven 3 phase -> Maven 4 phase or hook, exactly as Maven 4 defines the aliases. */
    static final Map<String, String> BUILTIN_ALIASES = Map.ofEntries(
            Map.entry("generate-sources", "sources"), Map.entry("process-sources", "after:sources"),
            Map.entry("generate-resources", "resources"), Map.entry("process-resources", "after:resources"),
            Map.entry("process-classes", "after:compile"),
            Map.entry("generate-test-sources", "test-sources"), Map.entry("process-test-sources", "after:test-sources"),
            Map.entry("generate-test-resources", "test-resources"),
            Map.entry("process-test-resources", "after:test-resources"),
            Map.entry("process-test-classes", "after:test-compile"),
            Map.entry("prepare-package", "before:package"),
            Map.entry("pre-integration-test", "before:integration-test"),
            Map.entry("post-integration-test", "after:integration-test"));

    private final String source;
    /** In tree pre-order. */
    private final Map<String, Phase> phases;
    private final Map<String, String> aliases;

    private LifecycleModel(String source, Map<String, Phase> phases, Map<String, String> aliases) {
        this.source = source;
        this.phases = phases;
        this.aliases = aliases;
    }

    String source() {
        return source;
    }

    Collection<Phase> phases() {
        return phases.values();
    }

    Map<String, String> aliases() {
        return aliases;
    }

    /** The tree phase an execution bound to {@code phase} belongs to (v3 name, v4 phase or hook). */
    String treePhase(String phase) {
        String p = aliases.getOrDefault(phase, phase);
        for (String prefix : List.of("before:", "after:", "at:")) {
            if (p.startsWith(prefix)) {
                p = p.substring(prefix.length());
            }
        }
        return p;
    }

    /**
     * The stage a tree phase belongs to: {@code compile} (everything up to "ready"), {@code test} (unit
     * tests), {@code package}, {@code verify} (integration tests and verification), {@code install},
     * {@code deploy}. Null for phases outside the default lifecycle.
     */
    String stage(String treePhase) {
        for (Phase p = phases.get(treePhase); p != null; p = p.parent() == null ? null : phases.get(p.parent())) {
            switch (p.name()) {
                case "package", "install", "deploy" -> {
                    return p.name();
                }
                case "unit-test" -> {
                    return "test";
                }
                case "integration-test", "verify" -> {
                    return "verify";
                }
                case "build", "validate" -> {
                    return "compile";
                }
                default -> { }
            }
        }
        return null;
    }

    List<Link> links(String treePhase) {
        Phase p = phases.get(treePhase);
        return p == null ? List.of() : p.links();
    }

    /**
     * Phases that are done once {@code phase} is: everything before it in pre-order (earlier siblings and
     * ancestors' earlier siblings run first) and its own subtree.
     */
    Set<String> doneBy(String phase) {
        Set<String> done = new LinkedHashSet<>();
        if (!phases.containsKey(phase)) {
            return done;
        }
        List<String> order = new ArrayList<>(phases.keySet());
        int end = order.indexOf(phase);
        for (int i = end + 1; i < order.size() && isDescendant(order.get(i), phase); i++) {
            end = i;
        }
        done.addAll(order.subList(0, end + 1));
        return done;
    }

    private boolean isDescendant(String name, String ancestor) {
        for (Phase p = phases.get(name); p != null && p.parent() != null; p = phases.get(p.parent())) {
            if (p.parent().equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    /** Maven scopes a dependencies-link scope covers. */
    static Set<String> scopes(String linkScope) {
        return switch (linkScope == null ? "" : linkScope) {
            case "compile" -> Set.of("compile", "provided", "system");
            case "runtime" -> Set.of("compile", "runtime");
            case "test-only" -> Set.of("test");
            case "test" -> Set.of("compile", "provided", "system", "runtime", "test");
            default -> Set.of("compile", "provided", "system", "runtime", "test");
        };
    }

    /** Dependency scopes Maven resolves for a mojo's {@code requiresDependencyResolution}. */
    static Set<String> resolutionScopes(String resolution) {
        return switch (resolution) {
            case "compile" -> Set.of("compile", "provided", "system");
            case "runtime" -> Set.of("compile", "runtime");
            case "compile+runtime" -> Set.of("compile", "provided", "system", "runtime");
            case "runtime+system" -> Set.of("compile", "runtime", "system");
            default -> Set.of("compile", "provided", "system", "runtime", "test");
        };
    }

    static LifecycleModel load(MavenSession session) {
        return fromMaven4(session).orElseGet(LifecycleModel::builtin);
    }

    /** Maven 4: the "default" lifecycle from the LifecycleRegistry, via reflection (we compile against 3.9). */
    private static Optional<LifecycleModel> fromMaven4(MavenSession session) {
        try {
            Object apiSession = MavenSession.class.getMethod("getSession").invoke(session);
            ClassLoader cl = apiSession.getClass().getClassLoader();
            Class<?> registryType = Class.forName("org.apache.maven.api.services.LifecycleRegistry", false, cl);
            Object registry = Class.forName("org.apache.maven.api.Session", false, cl)
                    .getMethod("getService", Class.class).invoke(apiSession, registryType);
            Optional<?> lifecycle = (Optional<?>) registryType.getMethod("lookup", String.class).invoke(registry, "default");
            if (lifecycle.isEmpty()) {
                return Optional.empty();
            }
            Class<?> lifecycleType = Class.forName("org.apache.maven.api.Lifecycle", false, cl);
            Class<?> phaseType = Class.forName("org.apache.maven.api.Lifecycle$Phase", false, cl);
            Class<?> linkType = Class.forName("org.apache.maven.api.Lifecycle$Link", false, cl);
            Class<?> pointerType = Class.forName("org.apache.maven.api.Lifecycle$Pointer", false, cl);
            Class<?> depsType = Class.forName("org.apache.maven.api.Lifecycle$DependenciesPointer", false, cl);
            Class<?> aliasType = Class.forName("org.apache.maven.api.Lifecycle$Alias", false, cl);
            Map<String, Phase> phases = new LinkedHashMap<>();
            for (Object p : (Collection<?>) lifecycleType.getMethod("phases").invoke(lifecycle.get())) {
                walk(p, null, phases, phaseType, linkType, pointerType, depsType);
            }
            Map<String, String> aliases = new LinkedHashMap<>();
            for (Object a : (Collection<?>) lifecycleType.getMethod("aliases").invoke(lifecycle.get())) {
                aliases.put((String) aliasType.getMethod("v3Phase").invoke(a), (String) aliasType.getMethod("v4Phase").invoke(a));
            }
            return Optional.of(new LifecycleModel("maven", phases, aliases));
        } catch (NoSuchMethodException | ClassNotFoundException e) {
            return Optional.empty(); // Maven 3
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static void walk(Object phase, String parent, Map<String, Phase> out, Class<?> phaseType,
                             Class<?> linkType, Class<?> pointerType, Class<?> depsType) throws ReflectiveOperationException {
        String name = (String) phaseType.getMethod("name").invoke(phase);
        List<Link> links = new ArrayList<>();
        for (Object l : (Collection<?>) phaseType.getMethod("links").invoke(phase)) {
            Object pointer = linkType.getMethod("pointer").invoke(l);
            Method scope = depsType.isInstance(pointer) ? depsType.getMethod("scope") : null;
            links.add(new Link(String.valueOf(linkType.getMethod("kind").invoke(l)).toLowerCase(java.util.Locale.ROOT),
                    String.valueOf(pointerType.getMethod("type").invoke(pointer)).toLowerCase(java.util.Locale.ROOT),
                    scope == null ? null : (String) scope.invoke(pointer),
                    (String) pointerType.getMethod("phase").invoke(pointer)));
        }
        out.put(name, new Phase(name, parent, List.copyOf(links)));
        for (Object child : (Collection<?>) phaseType.getMethod("phases").invoke(phase)) {
            walk(child, name, out, phaseType, linkType, pointerType, depsType);
        }
    }

    /** Maven 4.0.0-rc-7's default lifecycle, for Maven 3 runs. */
    static LifecycleModel builtin() {
        Map<String, Phase> p = new LinkedHashMap<>();
        add(p, "all", null, new Link("after", "children", null, "all"));
        add(p, "each", "all");
        add(p, "validate", "each");
        add(p, "initialize", "validate");
        add(p, "build", "each", project("validate"));
        add(p, "sources", "build");
        add(p, "resources", "build");
        add(p, "compile", "build", project("sources"), dependencies("compile", "ready"));
        add(p, "ready", "build", project("compile"), project("resources"));
        add(p, "package", "build", project("ready"), dependencies("runtime", "package"));
        add(p, "verify", "each", project("validate"));
        add(p, "unit-test", "verify");
        add(p, "test-sources", "unit-test");
        add(p, "test-resources", "unit-test");
        add(p, "test-compile", "unit-test", project("test-sources"), project("ready"), dependencies("test-only", "ready"));
        add(p, "test", "unit-test", project("test-compile"), project("test-resources"), dependencies("test", "ready"));
        add(p, "integration-test", "verify");
        add(p, "install", "each", project("package"));
        add(p, "deploy", "each", project("package"));
        return new LifecycleModel("built-in (Maven 4.0.0-rc-7 default)", p, BUILTIN_ALIASES);
    }

    private static void add(Map<String, Phase> p, String name, String parent, Link... links) {
        p.put(name, new Phase(name, parent, List.of(links)));
    }

    private static Link project(String phase) {
        return new Link("after", "project", null, phase);
    }

    private static Link dependencies(String scope, String phase) {
        return new Link("after", "dependencies", scope, phase);
    }
}
