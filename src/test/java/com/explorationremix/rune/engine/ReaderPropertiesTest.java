package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every loadout there is, on every kind of weapon, below and at its second phase: what must always hold. */
class ReaderPropertiesTest {
    static final HostSpec[] HOSTS = {HostSpec.of(Host.SWORD), HostSpec.of(Host.BOW), HostSpec.of(Host.CROSSBOW)};

    static List<RuneId> withNone(RuneKind k) {
        List<RuneId> out = new ArrayList<>();
        out.add(null);
        out.addAll(RuneId.ofKind(k));
        return out;
    }

    @Test
    void everyLoadoutReads() {
        int n = 0;
        for (HostSpec spec : HOSTS) {
            for (RuneId a : withNone(RuneKind.ACTION)) {
                for (RuneId m : withNone(RuneKind.MODIFIER)) {
                    for (RuneId p : withNone(RuneKind.PASSIVE)) {
                        Loadout l = new Loadout(a, m, p);
                        for (int level : new int[]{0, 30}) {
                            check(Reader.read(l, spec, level), spec, level);
                            n++;
                        }
                    }
                }
            }
        }
        assertTrue(n > 1000);
    }

    private static void check(Reading r, HostSpec spec, int level) {
        String what = r.loadout() + " on a " + spec.host().word + " at level " + level;
        assertTrue(r.casts().size() <= 1, what + ": one cast at most");
        for (Socket s : Socket.values()) {
            Note n = r.note(s);
            assertNotNull(n, what);
            assertEquals(r.loadout().get(s) == null, n.state() == Note.State.EMPTY, what + ": a socket is empty only when it holds nothing");
        }
        // a socket that does nothing costs nothing: the cast is the same without it
        for (Socket s : Socket.values()) {
            if (!r.note(s).grey()) continue;
            Reading without = Reader.read(r.loadout().with(s, null), spec, level);
            assertEquals(cost(without), cost(r), what + ": the grey " + s + " changes the cost");
        }
        CastReading c = r.cast();
        if (c != null) {
            assertTrue(c.cost() >= 0, what);
            assertTrue(c.delay() >= 0, what);
            assertEquals(c.cost() > spec.maxEnergy(), c.red(), what);
            assertEquals(spec.host().arrowCarrier ? (r.active() == null ? 1 : 2) : 1, c.parts().size(), what);
        } else {
            assertTrue(r.active() == null, what + ": an active rune always casts");
        }
        assertTrue(r.recovery() >= 6 && r.recovery() <= 80, what);
        assertEquals(r.active() != null && level >= r.active().phaseLevel, r.empowered(), what);
        // the words never say "null" and every line ends a sentence
        List<String> lines = Explain.lines(r);
        assertFalse(lines.isEmpty(), what);
        for (String line : lines) {
            assertFalse(line.contains("null"), what + ": " + line);
            assertTrue(line.endsWith(".") || line.endsWith(")") || line.endsWith("!"), what + ": " + line);
        }
        for (Socket s : Socket.values()) assertFalse(Explain.note(r, s).contains("null"), what);
    }

    private static int cost(Reading r) { return r.cast() == null ? 0 : r.cast().cost(); }
}
