package dev.frontseat.maven.extension.descriptor;

import dev.frontseat.maven.pluginio.IoDeclaration;
import dev.frontseat.maven.pluginio.MojoIo;
import dev.frontseat.maven.pluginio.PluginIoException;

/**
 * Turns declarative {@link MojoIo} entries into executable descriptors. An input or output kind this
 * version does not know makes the mojo non-cacheable, as the format requires.
 */
final class PluginIoMapper {

    private PluginIoMapper() {}

    static MojoCacheDescriptor toDescriptor(String pluginKey, MojoIo io, String source) {
        String[] ga = pluginKey.split(":", 2);
        MojoCacheDescriptor.Builder b = MojoCacheDescriptor.forGoal(ga[0], ga[1], io.goal()).source(source);
        if (!io.cacheable()) {
            return b.notCacheable(io.reason()).build();
        }
        try {
            for (IoDeclaration d : io.inputs()) {
                b.input(input(d));
            }
            for (IoDeclaration d : io.outputs()) {
                b.output(output(d));
            }
        } catch (PluginIoException e) {
            return b.notCacheable(e.getMessage() + " (in " + source + ")").build();
        }
        io.ignoredParameters().forEach(b::ignore);
        if (io.prerequisitePhase() != null) {
            b.prerequisitePhase(io.prerequisitePhase());
        }
        return b.build();
    }

    private static InputSpec input(IoDeclaration d) {
        return switch (d.kind()) {
            case "files" -> Inputs.files(d.required("parameter"));
            case "classpath" -> Inputs.classpath(d.required("parameter"), d.attribute("excludeParameter").orElse(null));
            case "testClasspath" -> Inputs.testClasspath();
            case "resources" -> Inputs.resources(d.required("parameter"));
            case "projectProperty" -> Inputs.projectPropertyNamedBy(d.attribute("nameParameter").orElse(null),
                    d.required("defaultName"));
            default -> throw new PluginIoException("unknown input kind <" + d.kind() + ">");
        };
    }

    private static OutputSpec output(IoDeclaration d) {
        return switch (d.kind()) {
            case "directory" -> Outputs.directory(d.required("parameter"),
                    d.attribute("includes").orElse("**").split("\\s*,\\s*"));
            case "copiedResources" -> Outputs.copiedResources(d.required("resourcesParameter"),
                    d.required("outputDirectoryParameter"));
            case "javaagentDestFiles" -> Outputs.javaagentDestFiles(d.required("parameter"));
            default -> throw new PluginIoException("unknown output kind <" + d.kind() + ">");
        };
    }
}
