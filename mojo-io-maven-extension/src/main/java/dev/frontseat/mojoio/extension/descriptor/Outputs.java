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
