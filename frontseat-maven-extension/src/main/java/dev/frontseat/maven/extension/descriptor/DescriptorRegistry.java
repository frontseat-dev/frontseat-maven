package dev.frontseat.maven.extension.descriptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.plugin.MojoExecution;

/**
 * Known descriptors, by {@code groupId:artifactId:goal}. Mojos without a descriptor always run: the
 * cache only takes what it has been told how to handle.
 *
 * <p>Descriptors are version-independent for now; the plugin version is part of every cache key.
 */
@Named
@Singleton
public class DescriptorRegistry {

    private static final String MAVEN_PLUGINS = "org.apache.maven.plugins";

    private final Map<String, MojoCacheDescriptor> descriptors = new HashMap<>();

    public DescriptorRegistry() {
        register(resources("resources"));
        register(resources("testResources"));

        register(MojoCacheDescriptor.forGoal(MAVEN_PLUGINS, "maven-compiler-plugin", "compile")
                .input(Inputs.files("compileSourceRoots"))
                .input(Inputs.classpath("compilePath", "outputDirectory"))
                .ignore("compileSourceRoots", "compilePath",
                        // diagnostics / process management only
                        "showCompilationChanges", "skipMultiThreadWarning", "compilerReuseStrategy", "verbose",
                        "staleMillis")
                .output(Outputs.directory("outputDirectory", "**/*.class"))
                .output(Outputs.directory("generatedSourcesDirectory", "**"))
                .prerequisitePhase("process-resources")
                .build());

        register(MojoCacheDescriptor.forGoal(MAVEN_PLUGINS, "maven-compiler-plugin", "testCompile")
                .input(Inputs.files("compileSourceRoots"))
                .input(Inputs.classpath("testPath", "outputDirectory"))
                .ignore("compileSourceRoots", "testPath",
                        "showCompilationChanges", "skipMultiThreadWarning", "compilerReuseStrategy", "verbose",
                        "staleMillis")
                .output(Outputs.directory("outputDirectory", "**/*.class"))
                .output(Outputs.directory("generatedTestSourcesDirectory", "**"))
                .prerequisitePhase("process-test-resources")
                .build());

        register(MojoCacheDescriptor.forGoal(MAVEN_PLUGINS, "maven-surefire-plugin", "test")
                .input(Inputs.testClasspath())
                .input(Inputs.files("classesDirectory"))
                .input(Inputs.files("testClassesDirectory"))
                .input(Inputs.files("suiteXmlFiles"))
                .input(Inputs.files("includesFile"))
                .input(Inputs.files("excludesFile"))
                .input(Inputs.files("systemPropertiesFile"))
                .ignore("classesDirectory", "testClassesDirectory",
                        // reporting/console and process plumbing; does not change test outcomes
                        "consoleOutputReporter", "statelessTestsetInfoReporter", "statelessTestsetReporter",
                        "forkNode", "printSummary", "trimStackTrace", "tempDir", "debugForkedProcess",
                        "parallelMavenExecution", "enableProcessChecker", "forkedProcessExitTimeoutInSeconds",
                        "shutdown")
                .output(Outputs.directory("reportsDirectory", "**"))
                .output(Outputs.javaagentDestFiles("argLine"))
                .prerequisitePhase("process-test-classes")
                .build());

        register(MojoCacheDescriptor.forGoal("org.jacoco", "jacoco-maven-plugin", "prepare-agent")
                // prepare-agent prepends to the existing property, so its current value is an input.
                .input(Inputs.projectPropertyNamedBy("propertyName", "argLine"))
                .build());
    }

    private static MojoCacheDescriptor resources(String goal) {
        return MojoCacheDescriptor.forGoal(MAVEN_PLUGINS, "maven-resources-plugin", goal)
                .input(Inputs.resources("resources"))
                .input(Inputs.files("filters"))
                .input(Inputs.files("buildFilters"))
                .ignore("resources")
                .output(Outputs.copiedResources("resources", "outputDirectory"))
                .build();
    }

    public void register(MojoCacheDescriptor descriptor) {
        descriptors.put(descriptor.pluginKey() + ":" + descriptor.goal(), descriptor);
    }

    public Optional<MojoCacheDescriptor> find(MojoExecution execution) {
        return Optional.ofNullable(descriptors.get(
                execution.getGroupId() + ":" + execution.getArtifactId() + ":" + execution.getGoal()));
    }
}
