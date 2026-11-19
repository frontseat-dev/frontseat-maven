package dev.frontseat.maven.extension;

/** Thrown when an execution cannot be cached safely. The execution then runs normally. */
public class NotCacheableException extends Exception {

    public NotCacheableException(String reason) {
        super(reason);
    }

    public NotCacheableException(String reason, Throwable cause) {
        super(reason, cause);
    }
}
