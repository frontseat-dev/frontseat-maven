package dev.frontseat.mojoio.extension.descriptor;

import java.nio.file.Path;
import java.util.List;

/**
 * A declared input, resolved for one execution, as the task graph lists it.
 *
 * <ul>
 *   <li>{@link Kind#FILES}: files or directory trees; under a directory, only files selected by
 *       {@code includes}/{@code excludes} (Ant patterns; empty includes = everything).</li>
 *   <li>{@link Kind#CLASSPATH}: a classpath, named by the dependency scope it is resolved for
 *       ({@code scope}); {@code paths} is empty, as dependencies are not resolved.</li>
 *   <li>{@link Kind#VALUE}: a value that is not a file, e.g. a property the mojo reads.</li>
 * </ul>
 */
public record ResolvedInput(String name, Kind kind, List<Path> paths, List<String> includes,
                            List<String> excludes, String value, String scope) {

    public enum Kind { FILES, CLASSPATH, VALUE }

    public ResolvedInput {
        paths = List.copyOf(paths);
        includes = List.copyOf(includes);
        excludes = List.copyOf(excludes);
    }

    public static ResolvedInput files(String name, List<Path> paths) {
        return new ResolvedInput(name, Kind.FILES, paths, List.of(), List.of(), null, null);
    }

    public static ResolvedInput files(String name, Path dir, List<String> includes, List<String> excludes) {
        return new ResolvedInput(name, Kind.FILES, List.of(dir), includes, excludes, null, null);
    }

    public static ResolvedInput classpath(String name, String scope) {
        return new ResolvedInput(name, Kind.CLASSPATH, List.of(), List.of(), List.of(), null, scope);
    }

    public static ResolvedInput value(String name, String value) {
        return new ResolvedInput(name, Kind.VALUE, List.of(), List.of(), List.of(), value, null);
    }
}
