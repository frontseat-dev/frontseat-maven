package sample.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppIT {

    @Test
    void greetsThroughThePackagedLibrary() {
        assertEquals("Hello, it!", App.run("it"));
    }
}
