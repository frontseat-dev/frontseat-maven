package dev.frontseat.maven.extension.descriptor;

import dev.frontseat.maven.pluginio.MojoIo;
import dev.frontseat.maven.pluginio.PluginIo;
import dev.frontseat.maven.pluginio.PluginIoException;
import dev.frontseat.maven.pluginio.PluginIoReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import javax.inject.Named;
import javax.inject.Singleton;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the I/O declarations for a mojo execution. Sources, highest precedence first:
 *
 * <ol>
 *   <li><b>project</b>: {@code .mvn/plugin-io/*.xml} under the multi-module root, so a build can fix
 *       or veto a declaration without waiting for a release;</li>
 *   <li><b>plugin</b>: {@value PluginIoReader#PLUGIN_JAR_ENTRY} inside the plugin's own jar, so the
 *       plugin author can describe their mojos;</li>
 *   <li><b>built-in</b>: declarations shipped with this extension for common plugins.</li>
 * </ol>
 *
 * The first source that mentions the goal wins, entirely. Mojos nobody declares always run.
 */
@Named
@Singleton
public class DescriptorRegistry {

    public static final String PROJECT_DIRECTORY = ".mvn/plugin-io";
    static final String BUILTIN_RESOURCE = "/META-INF/frontseat/builtin-plugin-io.xml";

    private static final Logger LOG = LoggerFactory.getLogger(DescriptorRegistry.class);

    private final Map<String, MojoCacheDescriptor> builtin;
    private final Map<Path, Map<String, MojoCacheDescriptor>> projectDeclarations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, MojoCacheDescriptor>> pluginDeclarations = new ConcurrentHashMap<>();

    public DescriptorRegistry() {
        URL url = DescriptorRegistry.class.getResource(BUILTIN_RESOURCE);
        if (url == null) {
            throw new IllegalStateException("Missing " + BUILTIN_RESOURCE);
        }
        builtin = index(PluginIoReader.read(url), "built-in");
    }

    public Optional<MojoCacheDescriptor> find(MojoExecution execution, MavenSession session) {
        String goalKey = execution.getGroupId() + ":" + execution.getArtifactId() + ":" + execution.getGoal();
        MojoCacheDescriptor d = forProject(root(session)).get(goalKey);
        if (d == null) {
            d = forPlugin(execution).get(goalKey);
        }
        if (d == null) {
            d = builtin.get(goalKey);
        }
        return Optional.ofNullable(d);
    }

    Map<String, MojoCacheDescriptor> forProject(Path root) {
        if (root == null) {
            return Map.of();
        }
        return projectDeclarations.computeIfAbsent(root, r -> {
            Path dir = r.resolve(PROJECT_DIRECTORY);
            if (!Files.isDirectory(dir)) {
                return Map.of();
            }
            try (Stream<Path> files = Files.list(dir)) {
                Map<String, MojoCacheDescriptor> result = new ConcurrentHashMap<>();
                for (Path f : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList()) {
                    List<PluginIo> plugins = PluginIoReader.read(f);
                    for (PluginIo p : plugins) {
                        if (p.key() == null) {
                            throw new PluginIoException(f + ": <plugin> needs groupId and artifactId");
                        }
                    }
                    result.putAll(index(plugins, "project " + r.relativize(f)));
                }
                return result;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private Map<String, MojoCacheDescriptor> forPlugin(MojoExecution execution) {
        PluginDescriptor pd = execution.getMojoDescriptor() == null ? null
                : execution.getMojoDescriptor().getPluginDescriptor();
        if (pd == null || pd.getPluginArtifact() == null || pd.getPluginArtifact().getFile() == null) {
            return Map.of();
        }
        String key = pd.getId();
        return pluginDeclarations.computeIfAbsent(key, k -> {
            try {
                return index(PluginIoReader.readFromPluginJar(pd.getPluginArtifact().getFile().toPath(),
                        pd.getGroupId(), pd.getArtifactId()), "plugin " + pd.getId());
            } catch (PluginIoException e) {
                LOG.warn("[frontseat] ignoring invalid plugin-io.xml in {}: {}", pd.getId(), e.getMessage());
                return Map.of();
            }
        });
    }

    static Map<String, MojoCacheDescriptor> index(List<PluginIo> plugins, String source) {
        Map<String, MojoCacheDescriptor> result = new ConcurrentHashMap<>();
        for (PluginIo plugin : plugins) {
            for (MojoIo mojo : plugin.mojos()) {
                result.put(plugin.key() + ":" + mojo.goal(), PluginIoMapper.toDescriptor(plugin.key(), mojo, source));
            }
        }
        return result;
    }

    private static Path root(MavenSession session) {
        if (session == null || session.getRequest() == null) {
            return null;
        }
        if (session.getRequest().getMultiModuleProjectDirectory() != null) {
            return session.getRequest().getMultiModuleProjectDirectory().toPath().toAbsolutePath().normalize();
        }
        return session.getTopLevelProject() == null ? null
                : session.getTopLevelProject().getBasedir().toPath().toAbsolutePath().normalize();
    }
}
