package sample.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AppTest {

    @Test
    void usesTheLibrary() {
        assertEquals("Hello, world!", App.run("world"));
    }

    @Test
    void testResourcesAreOnTheClasspath() throws Exception {
        Properties p = new Properties();
        try (InputStream in = AppTest.class.getResourceAsStream("/test.properties")) {
            p.load(in);
        }
        assertEquals("yes", p.getProperty("loaded"));
    }
}
