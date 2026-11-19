package sample.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreeterTest {

    @Test
    void greets() {
        assertEquals("Hello, Ada!", new Greeter().greet("Ada"));
    }

    @Test
    void resourceIsFiltered() {
        assertEquals("1.0.0-SNAPSHOT", new Greeter().version());
    }
}
