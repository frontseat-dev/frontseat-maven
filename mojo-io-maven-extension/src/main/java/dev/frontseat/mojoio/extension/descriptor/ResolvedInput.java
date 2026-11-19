package dev.frontseat.mojoio.extension.descriptor;

import java.nio.file.Path;
import java.util.List;

/**
 * A declared input, resolved for one execution but not yet hashed. The same value feeds the built-in
 * cache key and the exported task graph, so every consumer sees identical inputs.
 *
 * <ul>
 *   <li>{@link Kind#FILES}: files or directory trees; under a directory, only files selected by
 *       {@code includes}/{@code excludes} (Ant patterns; empty includes = everything).</li>
 *   <li>{@link Kind#CLASSPATH}: an ordered classpath, compared by content. When dependencies are not
 *       resolved (graph export) {@code paths} is empty and {@code scope} says which classpath.</li>
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

    public static ResolvedInput classpath(String name, List<Path> paths, String scope) {
        return new ResolvedInput(name, Kind.CLASSPATH, paths, List.of(), List.of(), null, scope);
    }

    public static ResolvedInput value(String name, String value) {
        return new ResolvedInput(name, Kind.VALUE, List.of(), List.of(), List.of(), value, null);
    }
}
