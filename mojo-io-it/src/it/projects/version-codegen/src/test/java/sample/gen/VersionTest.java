package sample.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void compiledConstantMatchesTheBuildVersion() {
        assertEquals(System.getProperty("expectedVersion"), Version.VALUE);
    }
}
