package sample.b;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import sample.a.Fixtures;
import sample.c.MainC;

class MainBTest {

    @Test
    void usesTheTestJarOfAAndTheClassesOfC() throws Exception {
        assertEquals("d", new MainC().runtimeName());
        assertEquals("fixture", Fixtures.fixture());
        assertEquals("c", new MainC().name());
        assertEquals("b", new MainB().name());
    }
}
