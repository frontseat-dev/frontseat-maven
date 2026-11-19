package dev.frontseat.mojoio.extension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes absolute paths portable by replacing well-known roots with tokens, so cache keys and stored
 * project state do not depend on where a checkout or the local repository lives:
 *
 * <ul>
 *   <li>{@value #BASEDIR}: the current project's base directory,</li>
 *   <li>{@value #ROOT}: the multi-module root (where {@code .mvn/} lives),</li>
 *   <li>{@value #M2REPO}: the local Maven repository.</li>
 * </ul>
 *
 * The longest matching root wins, so a module path becomes {@code @@BASEDIR@@/...} rather than
 * {@code @@ROOT@@/module/...}. A root only matches on a path boundary.
 */
public final class PathNormalizer {

    public static final String BASEDIR = "@@BASEDIR@@";
    public static final String ROOT = "@@ROOT@@";
    public static final String M2REPO = "@@M2REPO@@";

    private static final Pattern REPO_REFERENCE = Pattern.compile(Pattern.quote(M2REPO) + "([^\\s=,;:\"'" + "]+)");

    private final List<String[]> roots = new ArrayList<>();

    public PathNormalizer(Path basedir, Path root, Path localRepo) {
        add(BASEDIR, basedir);
        add(ROOT, root);
        add(M2REPO, localRepo);
        roots.sort(Comparator.comparingInt((String[] r) -> r[1].length()).reversed());
    }

    private void add(String token, Path path) {
        if (path != null) {
            roots.add(new String[] {token, path.toAbsolutePath().normalize().toString()});
        }
    }

    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (String[] root : roots) {
            String regex = Pattern.quote(root[1]) + "(?=[/\\\\]|$|[^A-Za-z0-9._\\-])";
            result = result.replaceAll(regex, Matcher.quoteReplacement(root[0]));
        }
        return result.replace('\\', '/');
    }

    public String denormalize(String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (String[] root : roots) {
            result = result.replace(root[0], root[1].replace('\\', '/'));
        }
        return result;
    }

    /**
     * Local-repository files a normalized value refers to (e.g. a {@code -javaagent:} jar). A cache hit
     * whose state needs such a file is only usable if the file exists on this machine.
     */
    public Set<Path> missingRepositoryFiles(String normalized) {
        Set<Path> missing = new LinkedHashSet<>();
        if (normalized == null) {
            return missing;
        }
        Matcher m = REPO_REFERENCE.matcher(normalized);
        while (m.find()) {
            Path file = Path.of(denormalize(M2REPO + m.group(1)));
            if (!Files.exists(file)) {
                missing.add(file);
            }
        }
        return missing;
    }
}
