package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Inputs that are not parameters: the project's own files ({@code projectFiles}), its effective model
 * ({@code projectModel}), the Maven version; and resources written in the plugin configuration.
 */
class DeclaredInputsIT {

    private static final String PROJECT = "declared-inputs";
    private static final String ROOT = "declared-inputs";

    @Test
    void eachInputKindInvalidatesExactlyWhatReadsIt() throws Exception {
        Path cache = MavenBuild.cacheDir("declared-inputs");
        MavenBuild build = MavenBuild.checkout(PROJECT, "declared-inputs", "checkout", cache);

        build.run("verify");
        MavenBuild.Result warm = build.run("clean", "verify");
        assertEquals("HIT", warm.outcome(ROOT, "enforcer:enforce"), warm.log());
        assertEquals("HIT", warm.outcome(ROOT, "antrun:run"), warm.log());
        assertEquals("HIT", warm.outcome(ROOT, "resources:copy-resources"), warm.log());
        assertTrue(Files.readString(build.dir().resolve("target/all.txt")).contains("root notes"), "observed output restored");
        assertEquals("extra", Files.readString(build.dir().resolve("target/extra/a.txt")).trim(), "inline resource restored");

        // A file of the project: the mojo that reads the project's files misses, nothing else.
        Files.writeString(build.dir().resolve("README.txt"), "root notes, edited\n");
        MavenBuild.Result readme = build.run("verify");
        assertEquals("MISS", readme.outcome(ROOT, "antrun:run"), readme.log());
        assertEquals("HIT", readme.outcome(ROOT, "enforcer:enforce"), readme.log());
        assertEquals("HIT", readme.outcome(ROOT, "resources:copy-resources"), readme.log());

        // A file of a nested module is not one of the parent's files.
        Files.writeString(build.dir().resolve("sub/notes.txt"), "sub notes, edited\n");
        MavenBuild.Result nested = build.run("verify");
        assertEquals("HIT", nested.outcome(ROOT, "antrun:run"), nested.log());
        assertEquals("MISS", nested.outcome("declared-inputs-sub", "antrun:run"), nested.log());

        // The model: a property nobody's configuration uses still changes what a rule check may see.
        Path pom = build.dir().resolve("pom.xml");
        Files.writeString(pom, Files.readString(pom).replace("<properties>", "<properties>\n    <added>1</added>"));
        MavenBuild.Result model = build.run("verify");
        assertEquals("MISS", model.outcome(ROOT, "enforcer:enforce"), model.log());
        assertEquals("HIT", model.outcome(ROOT, "resources:copy-resources"), model.log());

        // A resource written in the plugin configuration.
        Files.writeString(build.dir().resolve("src/extra/a.txt"), "extra, edited\n");
        MavenBuild.Result resource = build.run("verify");
        assertEquals("MISS", resource.outcome(ROOT, "resources:copy-resources"), resource.log());
        assertEquals("extra, edited", Files.readString(build.dir().resolve("target/extra/a.txt")).trim());

        // The classpath, for a mojo that asks Maven for no dependency resolution: the input resolves it
        // itself. (Left to whatever the previous mojo resolved, it would be empty here: the mojo before
        // it ran without resolving anything.)
        MavenBuild.Result before = build.run("verify");
        assertEquals("HIT", before.outcome(ROOT, "build-helper:regex-property"), before.log());
        Files.writeString(pom, Files.readString(pom).replace("<lang.version>3.17.0", "<lang.version>3.18.0"));
        MavenBuild.Result dependency = build.run("verify");
        assertEquals("MISS", dependency.outcome(ROOT, "build-helper:regex-property"), dependency.log());
    }
}
