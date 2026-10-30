package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The blind-end finder on hand-made grids of 24 x 40 x 24 cells (a window of 3 x 3 chunks). */
class DeadEndsTest {
    private static DeadEnds.Grid grid() { return new DeadEnds.Grid(0, 0, 0, 24, 40, 24); }

    /** A box of open cells, inclusive. */
    private static void open(DeadEnds.Grid g, int i0, int j0, int k0, int i1, int j1, int k1) {
        for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) for (int k = k0; k <= k1; k++) g.set(i, j, k, DeadEnds.AIR);
    }

    private static List<DeadEnds.Tip> tips(DeadEnds.Grid g) { return DeadEnds.tips(g, 8, 15, 8, 15); }

    @Test
    void aTunnelThatStopsIsABlindEnd() {
        DeadEnds.Grid g = grid();
        // Along x from the window's west edge, two cells wide, ending at cell 12.
        open(g, 0, 20, 11, 12, 21, 12);
        List<DeadEnds.Tip> tips = tips(g);
        assertEquals(1, tips.size(), "tips " + tips);
        DeadEnds.Tip t = tips.get(0);
        assertTrue(t.i() >= 10, "the tip lies at the end: " + t);
        assertTrue(t.fx() > 0.7, "it points on along the tunnel: " + t);
    }

    @Test
    void aTunnelRightThroughIsNot() {
        DeadEnds.Grid g = grid();
        open(g, 0, 20, 11, 23, 21, 12);
        assertTrue(tips(g).isEmpty());
    }

    @Test
    void aLoopHasNoBlindEnd() {
        DeadEnds.Grid g = grid();
        open(g, 6, 20, 6, 17, 21, 7);
        open(g, 6, 20, 16, 17, 21, 17);
        open(g, 6, 20, 6, 7, 21, 17);
        open(g, 16, 20, 6, 17, 21, 17);
        assertTrue(tips(g).isEmpty());
    }

    @Test
    void aRoomIsNotATunnelEnd() {
        DeadEnds.Grid g = grid();
        for (int i = 0; i < 24; i++) for (int j = 0; j < 40; j++) for (int k = 0; k < 24; k++) {
            if ((i - 12) * (i - 12) + (j - 20) * (j - 20) + (k - 12) * (k - 12) <= 49) g.set(i, j, k, DeadEnds.AIR);
        }
        open(g, 0, 20, 11, 12, 21, 12);
        assertTrue(tips(g).isEmpty());
    }

    @Test
    void aMouthToTheSurfaceIsAWayOut() {
        DeadEnds.Grid g = grid();
        // A tunnel up from the window's floor that ends at the ground: its top cells are outside.
        open(g, 11, 0, 11, 12, 30, 12);
        for (int j = 27; j <= 30; j++) for (int i = 11; i <= 12; i++) for (int k = 11; k <= 12; k++) g.set(i, j, k, DeadEnds.OUTSIDE);
        assertTrue(tips(g).isEmpty());
    }

    @Test
    void theLinkGoesOnToTheNextCave() {
        DeadEnds.Grid g = grid();
        open(g, 0, 20, 11, 12, 21, 12);
        // Another cave ahead, along z, 7 cells past the end.
        open(g, 19, 19, 0, 20, 22, 23);
        List<DeadEnds.Tip> tips = tips(g);
        assertEquals(1, tips.size());
        DeadEnds.Link l = DeadEnds.link(g, tips.get(0));
        assertNotNull(l);
        assertTrue(l.i() >= 19 && l.i() <= 20, "joins the cave ahead: " + l);
        assertTrue(l.length() >= 6 && l.length() <= 24, "length " + l.length());
        DeadEnds.open(g, l);
        assertTrue(tips(g).isEmpty(), "no blind end once joined");
    }

    @Test
    void noLinkBackIntoItsOwnTunnel() {
        DeadEnds.Grid g = grid();
        open(g, 0, 20, 11, 12, 21, 12);
        assertNull(DeadEnds.link(g, tips(g).get(0)));
    }
}
