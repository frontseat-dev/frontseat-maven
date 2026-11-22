package dev.frontseat.mojoio.extension.descriptor;

import dev.frontseat.mojoio.spec.IoDeclaration;
import dev.frontseat.mojoio.spec.MojoIo;
import dev.frontseat.mojoio.spec.PluginIoException;

/**
 * Turns declarative {@link MojoIo} entries into executable descriptors. An input or output kind this
 * version does not know makes the mojo non-cacheable, as the format requires.
 */
final class PluginIoMapper {

    private PluginIoMapper() {}

    static MojoCacheDescriptor toDescriptor(String pluginKey, MojoIo io, String source) {
        String[] ga = pluginKey.split(":", 2);
        MojoCacheDescriptor.Builder b = MojoCacheDescriptor.forGoal(ga[0], ga[1], io.goal()).source(source)
                .stampingCarry(io.stampingCarry())
                .ignoreWrites(io.ignoredWrites());
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

    private static java.util.List<String> split(String patterns) {
        return java.util.Arrays.stream(patterns.split("\\s*,\\s*")).filter(p -> !p.isBlank()).toList();
    }

    private static InputSpec input(IoDeclaration d) {
        return switch (d.kind()) {
            case "files" -> d.attribute("expression").isPresent()
                    ? Inputs.filesAt(d.required("expression"))
                    : Inputs.files(d.required("parameter"));
            case "classpath" -> Inputs.classpath(d.required("parameter"), d.attribute("excludeParameter").orElse(null));
            case "testClasspath" -> Inputs.testClasspath();
            case "resources" -> d.attribute("expression").isPresent()
                    ? Inputs.resourcesAt(d.required("expression"))
                    : Inputs.resources(d.required("parameter"));
            case "projectProperty" -> Inputs.projectPropertyNamedBy(d.attribute("nameParameter").orElse(null),
                    d.required("defaultName"));
            default -> throw new PluginIoException("unknown input kind <" + d.kind() + ">");
        };
    }

    private static OutputSpec output(IoDeclaration d) {
        return switch (d.kind()) {
            case "directory" -> Outputs.directory(d.required("parameter"),
                    d.attribute("includes").orElse("**").split("\\s*,\\s*"));
            case "file" -> Outputs.file(d.required("parameter"), d.required("name"));
            case "copiedResources" -> Outputs.copiedResources(d.required("resourcesParameter"),
                    d.required("outputDirectoryParameter"));
            case "observed" -> Outputs.observed(split(d.attribute("includes").orElse("**")),
                    split(d.attribute("excludes").orElse("")));
            case "javaagentDestFiles" -> Outputs.javaagentDestFiles(d.required("parameter"));
            default -> throw new PluginIoException("unknown output kind <" + d.kind() + ">");
        };
    }
}
