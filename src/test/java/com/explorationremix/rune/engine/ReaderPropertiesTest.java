package com.explorationremix.rune.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rules that must hold for every row: checked on every row of up to four slots over a test alphabet, and on random
 * longer rows, for a wand, a bow and a sword.
 */
class ReaderPropertiesTest {
    static final RuneId[] ALPHABET = {null, RuneId.SPARK, RuneId.ORB, RuneId.BURST, RuneId.CLEAVE, RuneId.DASH, RuneId.WIDE, RuneId.SWIFT,
            RuneId.BOUNCE, RuneId.EMBER, RuneId.FROST, RuneId.ON_HIT, RuneId.AFTER, RuneId.FAN};
    static final HostSpec[] GEAR = {HostSpec.of(Host.WAND).withBonus(3), HostSpec.of(Host.BOW).withBonus(5), HostSpec.of(Host.SWORD).withBonus(5)};
    static final Limits LIM = Limits.DEFAULT;

    /** Every row of up to {@code n} slots, then {@code random} random rows of 5 to 9 slots with random breaks. */
    static void everyRow(int n, int random, Consumer<Row> check) {
        for (int len = 1; len <= n; len++) {
            int[] idx = new int[len];
            while (true) {
                RuneId[] s = new RuneId[len];
                for (int i = 0; i < len; i++) s[i] = ALPHABET[idx[i]];
                check.accept(new Row(s, 0));
                int k = len - 1;
                while (k >= 0 && ++idx[k] == ALPHABET.length) idx[k--] = 0;
                if (k < 0) break;
            }
        }
        Random rng = new Random(7);
        for (int t = 0; t < random; t++) {
            int len = 5 + rng.nextInt(5);
            RuneId[] s = new RuneId[len];
            for (int i = 0; i < len; i++) s[i] = ALPHABET[rng.nextInt(ALPHABET.length)];
            check.accept(new Row(s, rng.nextInt(1 << (len - 1)) & rng.nextInt(1 << (len - 1))));
        }
    }

    static String signature(Reading r) {
        StringBuilder b = new StringBuilder();
        for (CastReading c : r.casts()) {
            b.append('{');
            for (Part p : c.parts()) b.append(p.relay() == null ? "" : p.relay().id).append(p.acts());
            b.append(' ').append(c.cost()).append('}');
        }
        return b.toString();
    }

    @Test
    void everySlotIsAccountedFor() {
        everyRow(4, 4000, row -> {
            for (HostSpec spec : GEAR) {
                Reading r = Reader.read(row, spec, 2, LIM);
                for (int i = 0; i < row.size(); i++) {
                    Note n = r.note(i);
                    assertNotNull(n, row + " slot " + i);
                    assertEquals(row.get(i) == null, n.state() == Note.State.EMPTY, row + " slot " + i);
                    if (n.live()) {
                        assertTrue(n.cast() >= 0 && n.cast() < r.casts().size(), row + " slot " + i);
                    } else {
                        assertEquals(-1, n.cast(), row + " slot " + i);
                    }
                }
            }
        });
    }

    @Test
    void costsCountEveryPlannedCopyAndNothingElse() {
        everyRow(4, 4000, row -> {
            for (HostSpec spec : GEAR) {
                Reading r = Reader.read(row, spec, 2, LIM);
                for (CastReading c : r.casts()) {
                    Plan p = Plan.expand(c, LIM);
                    int nodes = 0, cost = 0;
                    List<Plan.Node> todo = new ArrayList<>(p.roots);
                    while (!todo.isEmpty()) {
                        Plan.Node node = todo.remove(todo.size() - 1);
                        nodes++;
                        cost += node.act.unitCost;
                        todo.addAll(node.children);
                    }
                    assertEquals(c.planned(), nodes, row.toString());
                    assertEquals(c.cost(), cost, row.toString());
                    assertTrue(c.planned() <= LIM.maxActions(), row.toString());
                    assertTrue(c.parts().size() <= LIM.maxParts(), row.toString());
                    assertTrue(c.planned() <= c.wanted(), row.toString());
                    assertTrue(c.delay() >= (spec.host().arrowCarrier ? 0 : 2), row.toString());
                    // every action's slot is live, in this cast and part
                    for (Part part : c.parts()) {
                        for (Act a : part.acts()) {
                            if (a.slot < 0) continue;
                            assertTrue(r.note(a.slot).live(), row.toString());
                            assertEquals(c.index(), r.note(a.slot).cast(), row.toString());
                            assertEquals(a.part, r.note(a.slot).part(), row.toString());
                        }
                    }
                }
                assertTrue(r.recovery() >= 6 && r.recovery() <= 80);
            }
        });
    }

    @Test
    void greyRunesDoNothing() {
        // emptying a grey slot never changes what the row casts (a relay that is too deep still marks where the
        // cast stops, so it is the one exception)
        everyRow(4, 4000, row -> {
            for (HostSpec spec : GEAR) {
                Reading r = Reader.read(row, spec, 2, LIM);
                String sig = signature(r);
                for (int i = 0; i < row.size(); i++) {
                    Note n = r.note(i);
                    if (n.state() != Note.State.GREY || n.reason() == Note.Reason.TOO_DEEP) continue;
                    assertEquals(sig, signature(Reader.read(row.with(i, null), spec, 2, LIM)), row + " without slot " + i);
                }
            }
        });
    }

    @Test
    void aRowWithNoBreaksIsOneCast() {
        everyRow(4, 0, row -> {
            for (HostSpec spec : GEAR) assertTrue(Reader.read(row, spec, 2, LIM).casts().size() <= 1, row.toString());
        });
    }

    @Test
    void aBreakNeverChangesTheCastsOnEitherSide() {
        everyRow(4, 2000, row -> {
            if (row.breaks() != 0) return;
            for (HostSpec spec : GEAR) {
                for (int b = 0; b < row.size() - 1; b++) {
                    Row left = row, right = row;
                    for (int i = 0; i < row.size(); i++) {
                        if (i > b) left = left.with(i, null);
                        else right = right.with(i, null);
                    }
                    String split = signature(Reader.read(row.withBreak(b, true), spec, 2, LIM));
                    assertEquals(signature(Reader.read(left, spec, 2, LIM)) + signature(Reader.read(right, spec, 2, LIM)), split,
                            row + " broken after " + b);
                }
            }
        });
    }

    @Test
    void theWordsComeOutForEveryRow() {
        everyRow(4, 4000, row -> {
            for (HostSpec spec : GEAR) {
                Reading r = Reader.read(row, spec, 2, LIM);
                List<String> lines = Explain.lines(r);
                assertFalse(lines.isEmpty());
                for (String l : lines) assertFalse(l.contains("null"), row + ": " + l);
                for (int i = 0; i < row.size(); i++) {
                    String why = Explain.note(r, i);
                    assertEquals(r.note(i).state() == Note.State.GREY || r.note(i).state() == Note.State.ASLEEP, !why.isEmpty(), row + " slot " + i);
                }
            }
        });
    }

    @Test
    void actionsAreReadInRowOrder() {
        // within a cast, the actions appear part by part in the order of their slots
        everyRow(4, 4000, row -> {
            for (HostSpec spec : GEAR) {
                for (CastReading c : Reader.read(row, spec, 2, LIM).casts()) {
                    int last = -2;
                    for (Part p : c.parts()) {
                        for (Act a : p.acts()) {
                            assertTrue(a.slot > last, row.toString());
                            last = a.slot;
                        }
                    }
                }
            }
        });
    }
}
