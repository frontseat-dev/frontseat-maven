package dev.frontseat.mojoio.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class CacheKeyTest {

    @Test
    void sameComponentsGiveSameKey() {
        assertEquals(new CacheKey().add("a", "1").add("b", "2").hash(),
                new CacheKey().add("a", "1").add("b", "2").hash());
    }

    @Test
    void componentBoundariesAreUnambiguous() {
        assertNotEquals(new CacheKey().add("a", "bc").hash(), new CacheKey().add("ab", "c").hash());
    }

    @Test
    void orderMatters() {
        assertNotEquals(new CacheKey().add("a", "1").add("b", "2").hash(),
                new CacheKey().add("b", "2").add("a", "1").hash());
    }
}
