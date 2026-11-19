package dev.frontseat.maven.pluginio;

/** A plugin-io document is malformed. */
public class PluginIoException extends RuntimeException {

    public PluginIoException(String message) {
        super(message);
    }

    public PluginIoException(String message, Throwable cause) {
        super(message, cause);
    }
}
