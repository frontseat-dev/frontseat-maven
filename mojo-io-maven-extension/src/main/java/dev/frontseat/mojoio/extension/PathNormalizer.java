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
    private final dev.frontseat.mojoio.cache.Stamps stamps;

    public PathNormalizer(Path basedir, Path root, Path localRepo) {
        this(basedir, root, localRepo, dev.frontseat.mojoio.cache.Stamps.NONE);
    }

    /**
     * With {@code stamps}, stamp values (e.g. the project version) are tokenized too, as
     * {@code @@STAMP:name@@}, except inside local-repository paths, whose versions are dependency
     * versions rather than stamps.
     */
    public PathNormalizer(Path basedir, Path root, Path localRepo, dev.frontseat.mojoio.cache.Stamps stamps) {
        this.stamps = stamps;
        add(BASEDIR, basedir);
        add(ROOT, root);
        add(M2REPO, localRepo);
        roots.sort(Comparator.comparingInt((String[] r) -> r[1].length()).reversed());
    }

    private PathNormalizer(List<String[]> roots, dev.frontseat.mojoio.cache.Stamps stamps) {
        this.roots.addAll(roots);
        this.stamps = stamps;
    }

    public PathNormalizer withStamps(dev.frontseat.mojoio.cache.Stamps stamps) {
        return new PathNormalizer(roots, stamps);
    }

    public dev.frontseat.mojoio.cache.Stamps stamps() {
        return stamps;
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
        result = result.replace('\\', '/');
        if (stamps.isEmpty()) {
            return result;
        }
        StringBuilder sb = new StringBuilder();
        Matcher m = REPO_REFERENCE.matcher(result);
        int last = 0;
        while (m.find()) {
            sb.append(stamps.tokenize(result.substring(last, m.start()))).append(m.group());
            last = m.end();
        }
        return sb.append(stamps.tokenize(result.substring(last))).toString();
    }

    public String denormalize(String value) {
        if (value == null) {
            return null;
        }
        String result = stamps.detokenize(value);
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
