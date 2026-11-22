package dev.frontseat.mojoio.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which upstream phase a reactor dependency needs when the lifecycle asks for "ready". */
class UpstreamPhaseTest {

    private static String phase(String type, String classifier) {
        Map<String, Object> dependency = new HashMap<>();
        dependency.put("type", type);
        dependency.put("classifier", classifier);
        return TaskGraphCollector.upstreamPhase(dependency, "ready");
    }

    @Test
    void plainMainArtifactsAreServedByCompiledClasses() {
        assertEquals("ready", phase("jar", null));
        assertEquals("ready", phase("pom", null));
        assertEquals("ready", phase("bundle", null));
    }

    @Test
    void attachedArtifactsAndArchivesNeedThePackagedProject() {
        assertEquals("package", phase("test-jar", null));
        assertEquals("package", phase("jar", "tests"));
        assertEquals("package", phase("jar", "sources"));
        assertEquals("package", phase("java-source", null));
        assertEquals("package", phase("jar", "any-classifier"));
        assertEquals("package", phase("war", null));
        assertEquals("package", phase("zip", null));
    }

    @Test
    void otherPhasesAreLeftAlone() {
        Map<String, Object> dependency = new HashMap<>();
        dependency.put("type", "test-jar");
        assertEquals("package", TaskGraphCollector.upstreamPhase(dependency, "package"));
    }

    @Test
    void closureFollowsCompileDependenciesAndRuntimeOnesWhereCodeRuns() {
        Map<String, java.util.List<Object>> dependencies = new HashMap<>();
        dependencies.put("g:lib", java.util.List.of(dependency("g:core", "runtime", "jar"), dependency("g:testkit", "test", "jar"),
                dependency("g:model", "compile", "jar")));
        dependencies.put("g:core", java.util.List.of(dependency("g:base", "compile", "jar"), dependency("g:api", "provided", "jar")));
        dependencies.put("g:model", java.util.List.of(dependency("g:fixtures", "compile", "test-jar")));
        assertEquals(java.util.Set.of("g:core", "g:base", "g:model", "g:fixtures"),
                TaskGraphCollector.closure("g:lib", true, dependencies).keySet());
        assertEquals(java.util.Set.of("g:model", "g:fixtures"), TaskGraphCollector.closure("g:lib", false, dependencies).keySet());
        // The member reached as a test-jar needs its project packaged.
        assertEquals("package", TaskGraphCollector.upstreamPhase(
                TaskGraphCollector.closure("g:lib", false, dependencies).get("g:fixtures"), "ready"));
    }

    private static Map<String, Object> dependency(String id, String scope, String type) {
        Map<String, Object> dependency = new HashMap<>();
        dependency.put("id", id);
        dependency.put("scope", scope);
        dependency.put("type", type);
        return dependency;
    }
}
