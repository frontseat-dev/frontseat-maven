package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * An annotation processor built in the same reactor, named in configuration with a release version (as
 * in mapstruct-examples): its version says nothing about its content. The graph makes the compile wait
 * for the processor's tasks, so a build system's hash of the compile covers the processor's content.
 */
class ReactorProcessorIT {

    @Test
    void aChangedReactorProcessorReachesTheTask() throws Exception {
        MavenBuild build = MavenBuild.checkout("reactor-processor", "reactor-processor", "checkout");
        JsonNode app = TaskGraph.project(TaskGraph.export(build, "package"), "app");
        assertTrue(TaskGraph.execution(app, "compile").get("cacheable").asBoolean(), app.toString());
        assertTrue(TaskGraph.texts(TaskGraph.task(app, "compile").get("dependsOn")).stream()
                .anyMatch(d -> d.startsWith("com.example.samples:processor:")), app.toString());

        Path cache = MavenBuild.emptyDir("reactor-processor", "bs");
        ReferenceBuildSystem bs = new ReferenceBuildSystem(build, cache, "stage");
        bs.build("package");
        Path info = build.dir().resolve("app/target/generated-sources/annotations/sample/app/Info.java");
        assertTrue(Files.readString(info).contains("processor v1"));

        Path processor = build.dir().resolve("processor/src/main/java/sample/processor/InfoProcessor.java");
        Files.writeString(processor, Files.readString(processor).replace("processor v1", "processor v2"));
        List<String> missed = bs.build("package").stream().filter(o -> !o.hit())
                .map(ReferenceBuildSystem.Outcome::task).collect(Collectors.toList());
        assertTrue(missed.contains("com.example.samples:app:compile"), missed.toString());
        assertTrue(Files.readString(info).contains("processor v2"), Files.readString(info));
    }
}
