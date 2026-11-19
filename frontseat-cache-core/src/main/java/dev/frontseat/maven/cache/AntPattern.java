package dev.frontseat.maven.cache;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Ant-style path patterns as used by Maven resources and file sets: {@code **} matches any number of
 * directories, {@code *} any characters within one path segment, {@code ?} one character. A pattern
 * ending in '/' is treated as ending in '/**'. Paths use '/' separators.
 */
public final class AntPattern {

    /** The subset of Maven/plexus default excludes that matters for source and resource trees. */
    public static final List<String> DEFAULT_EXCLUDES = List.of(
            "**/*~", "**/#*#", "**/.#*", "**/%*%", "**/._*",
            "**/CVS", "**/CVS/**", "**/.cvsignore",
            "**/.svn", "**/.svn/**",
            "**/.git", "**/.git/**", "**/.gitignore", "**/.gitattributes",
            "**/.hg", "**/.hg/**", "**/.bzr", "**/.bzr/**",
            "**/.DS_Store");

    private final Pattern regex;

    private AntPattern(Pattern regex) {
        this.regex = regex;
    }

    public static AntPattern compile(String pattern) {
        String p = pattern.trim().replace('\\', '/');
        if (p.endsWith("/")) {
            p = p + "**";
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < p.length()) {
            char c = p.charAt(i);
            if (c == '*' && i + 1 < p.length() && p.charAt(i + 1) == '*') {
                boolean followedBySlash = i + 2 < p.length() && p.charAt(i + 2) == '/';
                if (followedBySlash) {
                    sb.append("(?:.*/)?");
                    i += 3;
                } else {
                    sb.append(".*");
                    i += 2;
                }
            } else if (c == '*') {
                sb.append("[^/]*");
                i++;
            } else if (c == '?') {
                sb.append("[^/]");
                i++;
            } else {
                sb.append(Pattern.quote(String.valueOf(c)));
                i++;
            }
        }
        return new AntPattern(Pattern.compile(sb.toString()));
    }

    public boolean matches(String relativePath) {
        return regex.matcher(relativePath.replace('\\', '/')).matches();
    }

    /**
     * Whether {@code path} is selected by the include/exclude lists. An empty include list means
     * "everything".
     */
    public static boolean selected(String path, List<String> includes, List<String> excludes) {
        boolean included = includes == null || includes.isEmpty()
                || includes.stream().anyMatch(p -> compile(p).matches(path));
        if (!included) {
            return false;
        }
        return excludes == null || excludes.stream().noneMatch(p -> compile(p).matches(path));
    }
}
