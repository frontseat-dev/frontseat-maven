package dev.frontseat.mojoio.spec;

/**
 * One change a mojo execution made to the in-memory Maven project. Kinds (state format 1):
 *
 * <ul>
 *   <li>{@code compileSourceRoot} / {@code testCompileSourceRoot}: value = added root;</li>
 *   <li>{@code property}: name = property, value = new value;</li>
 *   <li>{@code propertyRemoved}: name = property;</li>
 *   <li>{@code artifactFile}: value = the project's main artifact file.</li>
 * </ul>
 *
 * Paths inside values use the tokens {@code @@BASEDIR@@} (the module), {@code @@ROOT@@} (the
 * multi-module root) and {@code @@M2REPO@@} (the local repository), so a state file stays valid when
 * restored into another checkout or machine.
 */
public record StateChange(String kind, String name, String value) {}
