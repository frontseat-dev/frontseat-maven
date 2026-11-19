package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.cache.AntPattern;
import dev.frontseat.mojoio.cache.FileTrees;
import dev.frontseat.mojoio.extension.ExecutionContext;
import dev.frontseat.mojoio.extension.NotCacheableException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.model.Resource;

/** Built-in output kinds. */
public final class Outputs {

    private static final Pattern JAVAAGENT_DESTFILE = Pattern.compile("-javaagent:[^\\s]*?[=,]destfile=([^,\\s\"]+)");

    private Outputs() {}

    /**
     * Files under the directory named by {@code parameter} matching any of {@code includes}. Outputs
     * are declared by pattern rather than diffed, because a mojo that finds its outputs up to date
     * (e.g. the compiler on an unchanged tree) writes nothing, yet those files are still its outputs.
     */
    public static OutputSpec directory(String parameter, String... includes) {
        return (ctx, out) -> {
            for (Path dir : ctx.parameterPaths(parameter)) {
                for (Path f : FileTrees.listFiles(dir)) {
                    if (AntPattern.selected(FileTrees.relative(dir, f), List.of(includes), List.of())) {
                        put(ctx, f, out);
                    }
                }
            }
        };
    }

    /** What a resources mojo copies: each selected file, mapped to the output directory/targetPath. */
    public static OutputSpec copiedResources(String resourcesParameter, String outputDirectoryParameter) {
        return (ctx, out) -> {
            List<Path> outDirs = ctx.parameterPaths(outputDirectoryParameter);
            if (outDirs.size() != 1) {
                throw new NotCacheableException("expected one output directory, got " + outDirs);
            }
            Object addDefaultExcludes = ctx.parameter("addDefaultExcludes");
            boolean defaultExcludes = addDefaultExcludes == null || Boolean.parseBoolean(addDefaultExcludes.toString());
            for (Resource r : Inputs.resourceList(ctx, resourcesParameter)) {
                Path target = outDirs.get(0);
                if (r.getTargetPath() != null) {
                    target = target.resolve(r.getTargetPath());
                }
                Path dir = Path.of(r.getDirectory());
                for (Path f : Inputs.selectedFiles(r, defaultExcludes)) {
                    Path copied = target.resolve(FileTrees.relative(dir, f)).normalize();
                    if (Files.isRegularFile(copied)) {
                        put(ctx, copied, out);
                    }
                }
            }
        };
    }

    /**
     * Coverage files written by a {@code -javaagent:...=destfile=...} in the mojo's {@code argLine}
     * (JaCoCo). The test run writes them, so they are outputs of the test execution.
     */
    public static OutputSpec javaagentDestFiles(String argLineParameter) {
        return (ctx, out) -> {
            Object argLine = ctx.parameter(argLineParameter);
            if (argLine == null) {
                return;
            }
            Matcher m = JAVAAGENT_DESTFILE.matcher(argLine.toString());
            while (m.find()) {
                Path f = Path.of(m.group(1));
                if (Files.isRegularFile(f)) {
                    put(ctx, f, out);
                }
            }
        };
    }

    private static void put(ExecutionContext ctx, Path file, Map<String, Path> out) throws NotCacheableException {
        Path abs = file.toAbsolutePath().normalize();
        if (!abs.startsWith(ctx.basedir())) {
            throw new NotCacheableException("output " + abs + " is outside the project directory");
        }
        out.put(FileTrees.relative(ctx.basedir(), abs), abs);
    }
}
