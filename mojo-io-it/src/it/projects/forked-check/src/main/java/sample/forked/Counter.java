package sample.forked;

/** Counts. */
public final class Counter {

    private int count;

    /** Adds one and returns the new count. */
    public int increment() {
        count++;
        return count;
    }
}
