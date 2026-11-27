package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * An annotation processor built in the same reactor, named in configuration with a release version:
 * it counts by content, not by its version (as in mapstruct-examples).
 */
class ReactorProcessorIT {

    @Test
    void aChangedReactorProcessorReachesTheKey() throws Exception {
        Path cache = MavenBuild.cacheDir("reactor-processor");
        MavenBuild build = MavenBuild.checkout("reactor-processor", "reactor-processor", "checkout", cache);
        build.run("package");
        MavenBuild.Result warm = build.run("clean", "package");
        assertEquals("HIT", warm.outcome("app", "compiler:compile"), warm.log());

        Path processor = build.dir().resolve("processor/src/main/java/sample/processor/InfoProcessor.java");
        Files.writeString(processor, Files.readString(processor).replace("processor v1", "processor v2"));
        MavenBuild.Result changed = build.run("clean", "package");
        assertEquals("MISS", changed.outcome("app", "compiler:compile"), changed.log());
        assertTrue(Files.readString(build.dir().resolve("app/target/generated-sources/annotations/sample/app/Info.java"))
                .contains("processor v2"));
    }
}
