package dev.frontseat.mojoio.spec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class StateFileTest {

    @Test
    void roundTripsChangesWithSpacesEmptyAndNullValues() {
        StateFile f = new StateFile("g:a:1:goal@id", List.of(
                new StateChange("property", "argLine", "-javaagent:@@M2REPO@@/a b.jar=destfile=@@BASEDIR@@/x"),
                new StateChange("property", "empty", ""),
                new StateChange("propertyRemoved", "gone", null)), null);
        assertEquals(f, StateFile.decode(f.encode()));
    }

    @Test
    void incompleteStateCarriesItsReason() {
        StateFile f = new StateFile("g:a:1:goal@id", List.of(), "execution attached artifacts");
        StateFile decoded = StateFile.decode(f.encode());
        assertFalse(decoded.complete());
        assertEquals("execution attached artifacts", decoded.incompleteReason());
    }

    @Test
    void rejectsOtherFiles() {
        assertThrows(IllegalArgumentException.class, () -> StateFile.decode("hello\n"));
    }
}
