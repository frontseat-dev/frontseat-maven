package dev.frontseat.mojoio.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Audit mode observes what every mojo reads, writes and starts, and reports what its declaration does
 * not cover.
 */
class AuditIT {

    private static JsonNode execution(MavenBuild build, String prefix) throws Exception {
        JsonNode report = new ObjectMapper().readTree(
                build.dir().resolve("target/mojo-io-audit/mojo-io-audit.json").toFile());
        for (JsonNode e : report.get("executions")) {
            if (e.get("execution").asText().startsWith(prefix)) {
                return e;
            }
        }
        throw new AssertionError("no execution " + prefix + " in " + report);
    }

    /** The built-in declarations cover what the default lifecycle's mojos do, forked test JVMs included. */
    @Test
    void builtInDeclarationsPassAnAudit() throws Exception {
        MavenBuild build = MavenBuild.checkout("basic-multimodule", "audit-clean", "checkout",
                MavenBuild.cacheDir("audit-clean"));
        MavenBuild.Result result = build.run("verify", "-Dmojoio.audit=fail");
        assertTrue(result.log().contains("[mojo-io] AUDIT "), result.log());

        JsonNode surefire = execution(build, "surefire:test (default-test) @ lib-core");
        assertTrue(surefire.get("reads").toString().contains("target/test-classes/sample/core/GreeterTest.class"),
                "the forked test JVM's reads are observed: " + surefire);
        JsonNode compile = execution(build, "compiler:compile (default-compile) @ app");
        assertTrue(compile.get("reads").toString().contains("lib-core-1.0.0-SNAPSHOT.jar"), compile.toString());
        assertEquals(0, compile.get("undeclaredReads").size(), compile.toString());
    }

    /** A declaration that leaves out the classpath: the compiler reads the upstream jar anyway. */
    @Test
    void aMissingInputFailsTheAudit() throws Exception {
        MavenBuild build = MavenBuild.checkout("basic-multimodule", "audit-classpath", "checkout",
                MavenBuild.cacheDir("audit-classpath"));
        Path dir = Files.createDirectories(build.dir().resolve(".mvn/plugin-io"));
        Files.writeString(dir.resolve("compiler.xml"), """
                <plugin-io xmlns="urn:mojo-io:plugin-io:1.0">
                  <plugin groupId="org.apache.maven.plugins" artifactId="maven-compiler-plugin">
                    <mojo goal="compile">
                      <inputs><files parameter="compileSourceRoots"/></inputs>
                      <outputs><directory parameter="outputDirectory"/></outputs>
                      <ignoredWrites>maven-status/**</ignoredWrites>
                    </mojo>
                  </plugin>
                </plugin-io>
                """);
        MavenBuild.Result result = build.runAllowingFailure("verify", "-Dmojoio.audit=fail");
        assertNotEquals(0, result.exitCode(), result.log());
        assertTrue(result.log().contains("[mojo-io] AUDIT compiler:compile (default-compile) @ app"), result.log());
        JsonNode compile = execution(build, "compiler:compile (default-compile) @ app");
        assertTrue(compile.get("undeclaredReads").toString().contains("lib-core-1.0.0-SNAPSHOT.jar"), compile.toString());
    }

    /** A file read and a process started that the declaration doesn't mention. */
    @Test
    void undeclaredReadsAndProcessesAreReported() throws Exception {
        MavenBuild build = MavenBuild.checkout("audit", "audit-antrun", "checkout", MavenBuild.cacheDir("audit-antrun"));
        MavenBuild.Result warn = build.run("package", "-Dmojoio.audit=warn");
        assertTrue(warn.log().contains("[mojo-io] AUDIT antrun:run (notes) @ audit"), warn.log());
        JsonNode antrun = execution(build, "antrun:run (notes) @ audit");
        assertEquals("[\"notes.txt\"]", antrun.get("undeclaredReads").toString(), antrun.toString());
        assertTrue(antrun.get("unobservedProcesses").toString().contains("true"), antrun.toString());
        assertFalse(execution(build, "compiler:compile (default-compile) @ audit").get("undeclaredReads").size() > 0);

        MavenBuild.Result fail = build.runAllowingFailure("package", "-Dmojoio.audit=fail");
        assertNotEquals(0, fail.exitCode(), fail.log());
        assertTrue(fail.log().contains("[mojo-io] AUDIT 1 cacheable execution(s)"), fail.log());
    }
}
