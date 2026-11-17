package com.explorationremix.creature.body;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The climbing AI the Mossback and the Skein share, as the game's server runs it ({@link GameDriver}'s copy of the path
 * finder over the climbing nodes, ClimbNavigation and ClimbMoveControl, with vanilla collision and step up): it gets
 * up a wall without floating up beside it and falling, whatever lies at the wall's foot (a step, a slab, both, as
 * Terrain Slabs leaves them), and it never wedges between two slab corners where it cuts across them.
 */
class ClimbAiTest {
    /** A floor; a wall three blocks high rises from x 4 (or 5, past what lies at its foot) with its top at y 4. */
    private static BlockWorld floor() { return new BlockWorld().fill(-8, -1, -8, 14, -1, 8); }

    private static final List<Scene> WALLS = List.of(
            new Scene("a wall straight off the floor", () -> floor().fill(4, 0, -8, 14, 3, 8)),
            new Scene("a step at its foot", () -> floor().fill(4, 0, -8, 4, 0, 8).fill(5, 0, -8, 14, 3, 8)),
            new Scene("a step two deep at its foot", () -> floor().fill(3, 0, -8, 4, 0, 8).fill(5, 0, -8, 14, 3, 8)),
            new Scene("a step three deep at its foot", () -> floor().fill(2, 0, -8, 4, 0, 8).fill(5, 0, -8, 14, 3, 8)),
            new Scene("a slab line before a step at its foot", () -> slabs(floor().fill(4, 0, -8, 4, 0, 8).fill(5, 0, -8, 14, 3, 8), 3)),
            new Scene("a slab line at its foot", () -> slabs(floor().fill(4, 0, -8, 14, 3, 8), 3)),
            new Scene("a slab line on a step at its foot", () -> slabs(floor().fill(3, 0, -8, 4, 0, 8).fill(5, 0, -8, 14, 3, 8), 4, 1)));

    private static BlockWorld slabs(BlockWorld w, int x) { return slabs(w, x, 0); }

    private static BlockWorld slabs(BlockWorld w, int x, int y) {
        for (int z = -8; z <= 8; z++) w.slab(x, y, z);
        return w;
    }

    private record Scene(String name, Supplier<BlockWorld> world) {}

    private static GameDriver run(BlockWorld w, double sx, double sz, double hx, double hz, double gx, double gy, double gz, double modifier, int limit) {
        GameDriver d = new GameDriver(w, sx, 0, sz, hx, hz, modifier).pathTo(gx, gy, gz);
        while (!d.arrived() && d.ticks < limit) d.tick();
        return d;
    }

    @Test
    void itWalksToAWallsFootAndClimbsItWithoutFloatingUpBesideIt() {
        List<String> bad = new ArrayList<>();
        for (Scene s : WALLS)
            for (double modifier : new double[]{GameDriver.ROAM, GameDriver.CHASE})
                for (double sz : new double[]{-1.5, -0.6, 0.0, 0.7, 1.3}) {
                    GameDriver d = run(s.world.get(), -3.0, sz, 1, 0, 11, 4, sz, modifier, 600);
                    // cresting a step it may let go for a tick or two and drop a little onto it; floating up beside the
                    // wall it fell back a block or more
                    if (!d.arrived() || d.maxFall > 0.25)
                        bad.add(String.format("%s, %s from z %.1f: %s, fell %.2f", s.name, modifier > 1 ? "chasing" : "roaming", sz,
                                d.arrived() ? "arrived" : "never arrived", d.maxFall));
                }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    /** Rises it walks up from the floor: it gets over each, at any start against the blocks' grid. */
    private static final List<Scene> RISES = List.of(
            new Scene("a slab line", () -> slabs(floor(), 3)),
            new Scene("a block step", () -> floor().fill(3, 0, -8, 14, 0, 8)),
            new Scene("a block step with a slab line on its edge", () -> slabs(floor().fill(3, 0, -8, 14, 0, 8), 3, 1)),
            new Scene("a stair of half steps", () -> slabs(slabs(floor(), 3).fill(4, 0, -8, 14, 0, 8), 5, 1).fill(6, 1, -8, 14, 1, 8)));

    @Test
    void itGetsUpSlabsStepsAndHalfStepStairs() {
        List<String> bad = new ArrayList<>();
        for (int i = 0; i < RISES.size(); i++) {
            Scene s = RISES.get(i);
            int top = new int[]{0, 1, 1, 2}[i];
            for (double modifier : new double[]{GameDriver.ROAM, GameDriver.CHASE})
                for (int phase = 0; phase < 20; phase++) {
                    double sx = -3.0 + phase * 0.05, sz = 0.3 + phase * 0.037;
                    GameDriver d = run(s.world.get(), sx, sz, 1, 0, 9, top, sz, modifier, 400);
                    if (!d.arrived() || d.maxFall > 0.25)
                        bad.add(String.format("%s, %s from (%.2f, %.2f): %s, fell %.2f", s.name, modifier > 1 ? "chasing" : "roaming", sx, sz,
                                d.arrived() ? "arrived" : "never arrived", d.maxFall));
                }
        }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }

    @Test
    void itNeverWedgesBetweenTwoCornersItCutsAcross() {
        List<String> bad = new ArrayList<>();
        for (boolean block : new boolean[]{false, true})
            for (double modifier : new double[]{GameDriver.ROAM, GameDriver.CHASE}) {
                // a diagonal step from the node at (1, 0, 1) to (2, 0, 2) cuts past cells (3, 0, 1) and (1, 0, 3)
                BlockWorld w = floor().slab(3, 0, 1);
                if (block) w.block(1, 0, 3);
                else w.slab(1, 0, 3);
                GameDriver d = run(w, 2.0, 2.0, 1, 1, 5, 0, 5, modifier, 300);
                if (!d.arrived())
                    bad.add(String.format("a slab and %s at the corners, %s: stuck at (%.2f %.2f %.2f)", block ? "a block" : "a slab",
                            modifier > 1 ? "chasing" : "roaming", d.x, d.y, d.z));
            }
        assertTrue(bad.isEmpty(), String.join("\n", bad));
    }
}
