package org.bxteam.divinemc.moonrise;

import ca.spottedleaf.moonrise.common.list.ReferenceList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Normal
@NullMarked
class ReferenceListTest {
    // callers (player-ticking chunks, nearby players) rely on set semantics; a list that keeps duplicates made spawning run twice per chunk
    @Test
    void behavesAsAnIdentitySet() {
        final ReferenceList<Object> list = new ReferenceList<>(new Object[0]);
        final Object a = new Object();
        final Object b = new Object();
        final Object c = new Object();

        assertTrue(list.add(a));
        assertFalse(list.add(a));
        assertTrue(list.add(b));
        assertTrue(list.add(c));
        assertFalse(list.add(b));
        assertEquals(3, list.size());

        assertTrue(list.remove(a));
        assertFalse(list.contains(a));
        assertFalse(list.remove(a));
        assertEquals(2, list.size());

        final Object[] raw = list.getRawDataUnchecked();
        assertEquals(Set.of(b, c), new HashSet<>(Arrays.asList(raw).subList(0, list.size())));

        assertTrue(list.add(a));
        assertTrue(list.remove(b));
        assertTrue(list.contains(a));
        assertTrue(list.contains(c));
        assertFalse(list.contains(b));
        assertEquals(2, list.size());
    }
}
