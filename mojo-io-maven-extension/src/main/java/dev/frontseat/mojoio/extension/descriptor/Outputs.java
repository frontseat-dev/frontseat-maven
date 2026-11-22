package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.cache.FileTrees;
import dev.frontseat.mojoio.extension.ExecutionContext;
import dev.frontseat.mojoio.extension.NotCacheableException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Resource;

/** Built-in output kinds (see docs/plugin-io.md). */
public final class Outputs {

    private static final Pattern JAVAAGENT_DESTFILE = Pattern.compile("-javaagent:[^\\s]*?[=,]destfile=([^,\\s\"]+)");

    private Outputs() {}

    /**
     * Files under the directory named by {@code parameter} matching any of {@code includes}. Outputs are
     * declared rather than diffed, because a mojo that finds its outputs up to date (e.g. the compiler on
     * an unchanged tree) writes nothing, yet those files are still its outputs.
     */
    public static OutputSpec directory(String parameter, String... includes) {
        return ctx -> {
            List<ResolvedOutput> result = new ArrayList<>();
            for (Path dir : ctx.parameterPaths(parameter)) {
                result.add(new ResolvedOutput(parameter, dir, List.of(includes), List.of()));
            }
            return result;
        };
    }

    /** A directory a Maven expression evaluates to, e.g. {@code ${project.build.outputDirectory}}. */
    public static OutputSpec directoryAt(String expression, String... includes) {
        return ctx -> {
            List<ResolvedOutput> result = new ArrayList<>();
            for (Path dir : ctx.paths(ctx.evaluateExpression(expression))) {
                result.add(new ResolvedOutput(expression, dir, List.of(includes), List.of()));
            }
            return result;
        };
    }

    /**
     * One file in the directory named by {@code parameter}. {@code nameTemplate} uses {@code {param}} for a
     * parameter's value and {@code {-param}} for {@code "-" + value} when the value is not empty, e.g.
     * {@code {finalName}{-classifier}.jar}.
     */
    public static OutputSpec file(String parameter, String nameTemplate) {
        return ctx -> {
            List<Path> dirs = ctx.parameterPaths(parameter);
            if (dirs.size() != 1) {
                throw new NotCacheableException("expected one directory in '" + parameter + "', got " + dirs);
            }
            Matcher m = Pattern.compile("\\{(-?)([A-Za-z0-9_.]+)}").matcher(nameTemplate);
            StringBuilder name = new StringBuilder();
            while (m.find()) {
                Object v = ctx.parameter(m.group(2));
                String s = v == null ? "" : v.toString();
                m.appendReplacement(name, Matcher.quoteReplacement(
                        m.group(1).isEmpty() ? s : s.isEmpty() ? "" : "-" + s));
            }
            m.appendTail(name);
            return List.of(new ResolvedOutput(parameter, dirs.get(0).resolve(name.toString()), List.of(), List.of()));
        };
    }

    /** What a resources mojo copies: each selected file, mapped to the output directory/targetPath. */
    public static OutputSpec copiedResources(String resourcesParameter, String outputDirectoryParameter) {
        return ctx -> {
            List<Path> outDirs = ctx.parameterPaths(outputDirectoryParameter);
            if (outDirs.size() != 1) {
                throw new NotCacheableException("expected one output directory, got " + outDirs);
            }
            Object addDefaultExcludes = ctx.parameter("addDefaultExcludes");
            boolean defaultExcludes = addDefaultExcludes == null || Boolean.parseBoolean(addDefaultExcludes.toString());
            List<ResolvedOutput> result = new ArrayList<>();
            for (Resource r : Inputs.resourceList(ctx, resourcesParameter)) {
                Path target = r.getTargetPath() == null ? outDirs.get(0) : outDirs.get(0).resolve(r.getTargetPath());
                Path dir = Path.of(r.getDirectory());
                List<String> copied = new ArrayList<>();
                for (Path f : Inputs.selectedFiles(r, defaultExcludes)) {
                    copied.add(FileTrees.relative(dir, f));
                }
                if (!copied.isEmpty()) {
                    result.add(new ResolvedOutput(resourcesParameter, target.normalize(), copied, List.of()));
                }
            }
            return result;
        };
    }

    /**
     * Whatever the mojo writes in the build directory while it runs (patterns relative to it). For mojos
     * that run code with hooks of its own: tests, agents such as JaCoCo writing coverage inside the test
     * JVM, forked tools. Measured from a before/after snapshot of the module.
     */
    public static OutputSpec observed(List<String> includes, List<String> excludes) {
        return ctx -> List.of(new ResolvedOutput("observed",
                Path.of(ctx.project().getBuild().getDirectory()).toAbsolutePath().normalize(), includes, excludes, true));
    }

    /**
     * Coverage files written by a {@code -javaagent:...=destfile=...} in the mojo's {@code argLine}
     * (JaCoCo). The test run writes them, so they are outputs of the test execution.
     */
    public static OutputSpec javaagentDestFiles(String argLineParameter) {
        return ctx -> {
            Object argLine = ctx.parameter(argLineParameter);
            List<ResolvedOutput> result = new ArrayList<>();
            if (argLine != null) {
                Matcher m = JAVAAGENT_DESTFILE.matcher(argLine.toString());
                while (m.find()) {
                    result.add(new ResolvedOutput(argLineParameter, Path.of(m.group(1)), List.of(), List.of()));
                }
            }
            return result;
        };
    }
}
