package com.explorationremix.world.feature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.GalleriesField;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.WastelandField;

/**
 * Keeps the deep layers' ways walkable. The open way pass clears every way's walking corridor, but only inside its own
 * chunk: a feature decorating a neighbouring chunk later can still reach across into it. So every feature that builds
 * something solid asks here first and leaves the corridors alone: no column, spike, root, pool rim, machine part or
 * waterfall stands on a way or hangs into its headroom. The ways are the network's (section 12.2): every tunnel, inner
 * walk, ledge passage and stub of every cell, the rift's ledge path, the Causeway's column path (a walking line for this
 * guard only: nothing carves it), the gates' passages and landings, and the den gates' ramps up into the pack's caves.
 *
 * <p>The Galleries' ways down from the Wasteland are a noise field's too: the ramps of the holes it opens, with their
 * feet and the holes' throats ({@link GalleriesField#wayNear}). No feature lays lava on them, takes their floor or hangs
 * into their head room (the 0.8.0 pack run found a lava pool and a fall cutting one), and lava falls keep further from
 * the heads and the throats ({@link #nearWayDown}).
 *
 * <p>The Wasteland has no planned ways: its tunnels are a noise field's ({@link WastelandField}), and nothing carves a
 * way through them. There the guard keeps to the field's walking channels instead ({@link #nearChannel}): the middle
 * line of every tunnel and cave, from a little under its walking floor to over a standing player's head. That keeps
 * columns, pool rims and falls from walling a tunnel off, without emptying the caves of decoration.
 */
public final class Walkway {
    /** How far from a walking line its corridor reaches sideways, at least. */
    public static final double HALF_WIDTH = 2.6;

    private Walkway() {}

    /** True when (x, y, z) is inside a way's walking corridor: on the way's line and up to four blocks above its floor. */
    public static boolean blocks(int x, int y, int z) {
        return near(x, z, y, y, 0);
    }

    /**
     * True when a structure in column (x, z) spanning y0 to y1 would come within {@code margin} blocks of a way's
     * walking corridor (sideways), at the corridor's height: within 0.6 of the way's half-width (at least 2.6) plus the
     * margin of its line, between its feet level and three blocks over it ({@link #nearWays}); or, in the Wasteland,
     * within the margin of a tunnel's walking channel ({@link #nearChannel}).
     */
    public static boolean near(int x, int z, int y0, int y1, double margin) {
        Terrain t = terrain();
        return t != null && near(t, x, z, y0, y1, margin);
    }

    /** {@link #near} for a given terrain (the game's, or a test's). */
    public static boolean near(Terrain t, int x, int z, int y0, int y1, double margin) {
        return nearWays(t, x, z, y0, y1, margin) || nearChannel(t, x, z, y0, y1, margin);
    }

    /**
     * The planned layers' ways, the gates and the Galleries' ways down from the Wasteland (their ramps' decks, feet and
     * heads and the holes' throats, {@link GalleriesField#wayNear}): {@link #near} without the Wasteland's walking
     * channels.
     */
    public static boolean nearWays(int x, int z, int y0, int y1, double margin) {
        Terrain t = terrain();
        return t != null && nearWays(t, x, z, y0, y1, margin);
    }

    /** {@link #nearWays} for a given terrain. */
    public static boolean nearWays(Terrain t, int x, int z, int y0, int y1, double margin) {
        int lo = Math.min(y0, y1), hi = Math.max(y0, y1);
        if (nearGate(t, x, z, lo, hi, margin)) return true;
        if (lo >= t.shape.packFloor) return false;
        if (t.galleries != null && t.galleries.wayNear(x, z, lo, hi, margin, margin)) return true;
        int li0 = t.shape.layerAt(Math.min(hi, t.shape.packFloor - 1)), li1 = t.shape.layerAt(lo);
        for (int li = 0; li < t.shape.layerCount; li++) {
            if (li != li0 && li != li1 || !Terrain.planned(li)) continue;
            Network.WayLines w = t.wayLinesAt(li, x, z, 8 + margin);
            for (int k = 0; k < w.n; k++) {
                if (w.d[k] > Math.max(0.6 * w.half[k], HALF_WIDTH) + margin) continue;
                int feet = (int) Math.ceil(w.floor[k] - 1e-9);
                if (hi >= feet && lo <= feet + 3) return true;
            }
        }
        return false;
    }

    /**
     * The ways down from the Wasteland into the Galleries alone, with their heads and the holes' throats kept
     * {@code headMargin} away at any row ({@link GalleriesField#wayNear}): a lava fall keeps far enough off that it hides
     * no way from the cavern.
     */
    public static boolean nearWayDown(int x, int z, int y0, int y1, double margin, double headMargin) {
        Terrain t = terrain();
        return t != null && nearWayDown(t, x, z, y0, y1, margin, headMargin);
    }

    /** {@link #nearWayDown(int, int, int, int, double, double)} for a given terrain. */
    public static boolean nearWayDown(Terrain t, int x, int z, int y0, int y1, double margin, double headMargin) {
        return t.galleries != null && t.galleries.wayNear(x, z, Math.min(y0, y1), Math.max(y0, y1), margin, headMargin);
    }

    /** The Wasteland's walking channels alone (see {@link #nearChannel(Terrain, int, int, int, int, double)}). */
    public static boolean nearChannel(int x, int z, int y0, int y1, double margin) {
        Terrain t = terrain();
        return t != null && nearChannel(t, x, z, y0, y1, margin);
    }

    /**
     * True when a structure in column (x, z) spanning y0 to y1 would stand in one of the Wasteland's walking channels:
     * within 1 + {@code margin} blocks of the middle line of a tunnel or cave of its field, from 3 rows under the walking
     * floor to 6 over it ({@link WastelandField#walkNear}). False outside the Wasteland's rows.
     */
    public static boolean nearChannel(Terrain t, int x, int z, int y0, int y1, double margin) {
        int lo = Math.min(y0, y1), hi = Math.max(y0, y1);
        if (t.wasteland == null || hi < t.shape.bottom(Terrain.WASTELAND) - 16 || lo > t.shape.top(Terrain.WASTELAND)) return false;
        return t.wasteland.walkNear(x, z, lo, hi, margin);
    }

    /**
     * Whether row y is the Wasteland's (or the rows its openings fall through), where no plan keeps the ways and the
     * guard keeps the field's walking channels: the decorations there also keep the mouths of passages ({@link Detour}).
     */
    public static boolean channelled(int y) {
        Terrain t = terrain();
        return t != null && channelled(t, y);
    }

    /** {@link #channelled(int)} for a given terrain. */
    public static boolean channelled(Terrain t, int y) {
        return t.wasteland != null && y >= t.shape.bottom(Terrain.WASTELAND) - 16 && y <= t.shape.top(Terrain.WASTELAND);
    }

    /**
     * True when an opening from the Wasteland into the Galleries reaches a column within {@code r} blocks (each way) of
     * (x, z), for a feature at row y: lava features keep that far from the holes ({@link WastelandField#openingNear}).
     */
    public static boolean nearOpening(int x, int z, int y, int r) {
        Terrain t = terrain();
        return t != null && nearOpening(t, x, z, y, r);
    }

    /** {@link #nearOpening(int, int, int, int)} for a given terrain. */
    public static boolean nearOpening(Terrain t, int x, int z, int y, int r) {
        return channelled(t, y) && t.wasteland.openingNear(x, z, r);
    }

    private static Terrain terrain() {
        Terrain t = DeepWorld.terrain();
        return t != null && DeepWorld.installed() ? t : null;
    }

    /**
     * The gate passages through the bands and their landings: in band 1 every den gate's ({@link Plans#denGates}), each
     * with its ramp up into the pack's caves.
     */
    private static boolean nearGate(Terrain t, int x, int z, int lo, int hi, double margin) {
        if (lo > t.shape.packFloor + 48) return false;
        Plans plans = t.plans;
        int cx = plans.cellOf(x), cz = plans.cellOf(z);
        for (Plans.Gate g : plans.denGates(cx, cz)) {
            if (nearPassage(g, x, z, lo, hi, margin)) return true;
            // a ramp runs far past the box round its gate's passage and landings, so it has its own
            Route.Link ramp = plans.ramp(g);
            if (!Terrain.rampNear(ramp, x, z, margin + 6)) continue;
            for (int y = lo; y <= hi; y += 2) if (ramp.dist(x + 0.5, y + 0.5, z + 0.5) < margin + 1.5) return true;
        }
        for (int band = 2; band <= t.shape.layerCount; band++) {
            if (t.shape.bandThickness(band) == 0) continue;   // no rows, no gate (the Wasteland opens into the Galleries)
            if (nearPassage(plans.gate(band, cx, cz), x, z, lo, hi, margin)) return true;
        }
        return false;
    }

    /** A gate's two landings (their floors and six blocks over them) and its passage. */
    private static boolean nearPassage(Plans.Gate g, int x, int z, int lo, int hi, double margin) {
        if (!g.overlaps(x, z, x, z, margin + 6)) return false;
        double r = g.landR + margin;
        if (Math.hypot(x - g.ux, z - g.uz) < r && hi >= g.uFloor && lo <= g.uFloor + 6) return true;
        if (Math.hypot(x - g.lx, z - g.lz) < r && hi >= g.lFloor && lo <= g.lFloor + 6) return true;
        for (int y = lo; y <= hi; y += 2) {
            if (g.passageXZ(x + 0.5, y, z + 0.5) < g.radius * 1.4 + margin) return true;
        }
        return false;
    }
}
