package dev.frontseat.maven.extension;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** Everything a descriptor needs to compute inputs and collect outputs for one mojo execution. */
public final class ExecutionContext {

    private final MavenSession session;
    private final MavenProject project;
    private final MojoExecution execution;
    private final PathNormalizer normalizer;
    private final PluginParameterExpressionEvaluator evaluator;

    public ExecutionContext(MavenSession session, MavenProject project, MojoExecution execution,
                            PathNormalizer normalizer) {
        this.session = session;
        this.project = project;
        this.execution = execution;
        this.normalizer = normalizer;
        this.evaluator = new PluginParameterExpressionEvaluator(session, execution);
    }

    public MavenSession session() {
        return session;
    }

    public MavenProject project() {
        return project;
    }

    public MojoExecution execution() {
        return execution;
    }

    public PathNormalizer normalizer() {
        return normalizer;
    }

    public Path basedir() {
        return project.getBasedir().toPath().toAbsolutePath().normalize();
    }

    /** Raw configuration node for a parameter, or {@code null}. */
    public Xpp3Dom parameterNode(String name) {
        Xpp3Dom config = execution.getConfiguration();
        return config == null ? null : config.getChild(name);
    }

    /**
     * Evaluates a parameter the way Maven would: its configured value, falling back to its default
     * expression. A node with children evaluates to a list of its children's values.
     */
    public Object parameter(String name) throws NotCacheableException {
        Xpp3Dom node = parameterNode(name);
        return node == null ? null : evaluate(node);
    }

    public Object evaluate(Xpp3Dom node) throws NotCacheableException {
        if (node.getChildCount() > 0) {
            List<Object> values = new ArrayList<>();
            for (Xpp3Dom child : node.getChildren()) {
                values.add(evaluate(child));
            }
            return values;
        }
        return evaluateLeaf(node);
    }

    /**
     * A leaf's value as Maven's configurator would compute it: the configured expression, or the
     * {@code default-value} when that is absent or evaluates to {@code null}.
     */
    public Object evaluateLeaf(Xpp3Dom node) throws NotCacheableException {
        Object value = evaluateExpression(node.getValue());
        if (value == null) {
            value = evaluateExpression(node.getAttribute("default-value"));
        }
        return value;
    }

    public Object evaluateExpression(String expression) throws NotCacheableException {
        if (expression == null) {
            return null;
        }
        try {
            return evaluator.evaluate(expression);
        } catch (ExpressionEvaluationException e) {
            throw new NotCacheableException("cannot evaluate '" + expression + "': " + e.getMessage(), e);
        }
    }

    /** A parameter that names files or directories, flattened to absolute paths. */
    public List<Path> parameterPaths(String name) throws NotCacheableException {
        List<Path> paths = new ArrayList<>();
        collectPaths(parameter(name), paths);
        return paths;
    }

    private void collectPaths(Object value, List<Path> out) throws NotCacheableException {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> c) {
            for (Object o : c) {
                collectPaths(o, out);
            }
        } else if (value instanceof Object[] a) {
            for (Object o : a) {
                collectPaths(o, out);
            }
        } else if (value instanceof File f) {
            out.add(resolve(f.getPath()));
        } else if (value instanceof Path p) {
            out.add(resolve(p.toString()));
        } else if (value instanceof String s) {
            if (!s.isBlank()) {
                out.add(resolve(s));
            }
        } else {
            throw new NotCacheableException("expected a path but got " + value.getClass().getName());
        }
    }

    private Path resolve(String path) {
        Path p = Path.of(path);
        return (p.isAbsolute() ? p : basedir().resolve(p)).normalize();
    }
}
