package com.explorationremix.world.lava;

import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The lava plane (0.21.6): where its surface stands at a game time between two settings, and how deep it stands over
 * an entity's box (the fluid height the entity mixin gives it).
 */
class LavaPlaneTest {
    private static final LavaCycle STD = LavaCycle.standard(40);
    private static final int TABLE = -1890, TOP = -1850, BOTTOM = -1920;
    private static final int R0 = 20100, F0 = 22500;
    private final LavaPlane plane = LavaPlane.SERVER;

    @AfterEach
    void off() { plane.off(); }

    @Test
    void theSurfaceCountsOnWithTheGameTime() {
        assertFalse(plane.on());
        assertTrue(Double.isNaN(plane.surface(0, 0)), "no plane before it is set");
        // set at game time 1000 with the cycle at the start of the rise
        plane.set(STD, TABLE, TOP, BOTTOM, R0, 1000, false);
        assertTrue(plane.on());
        assertTrue(Double.isNaN(plane.surface(1000, 0)), "down at its table: the table's own lava blocks are the surface");
        assertEquals(STD.height(R0 + 150), plane.height(1150, 0), 1e-12);
        assertEquals(TABLE - LavaCycle.SURFACE_DROP + 5, plane.surface(1150, 0), 1e-9, "halfway up the first climb");
        assertEquals(STD.height(R0 + 150.5), plane.height(1150, 0.5F), 1e-12, "between ticks for drawing");
        assertEquals(TABLE - LavaCycle.SURFACE_DROP + 40, plane.surface(1000 + F0 - R0, 0), 1e-9, "full: where the old block sea's top was");
        assertEquals(TABLE - LavaCycle.SURFACE_DROP, plane.floor(), 1e-12);
        assertSame(STD, plane.cycle());
        assertEquals(TABLE, plane.tableY());
        // a held clock stays where it was set
        plane.set(STD, TABLE, TOP, BOTTOM, R0 + 300, 5000, true);
        assertEquals(10, plane.height(5000, 0), 1e-12);
        assertEquals(10, plane.height(90_000, 0.7F), 1e-12);
        plane.off();
        assertEquals(0, plane.height(5000, 0), 0);
        assertNull(plane.cycle());
    }

    @Test
    void anEntityUnderTheSurfaceIsInItsLavaAsDeepAsItStands() {
        plane.set(STD, TABLE, TOP, BOTTOM, R0 + 300, 0, true);        // held in the first break: 10 blocks up
        double s = TABLE - LavaCycle.SURFACE_DROP + 10;
        // a player standing on the floor of the band, 1.8 tall: in lava up to the surface
        AABB feet = new AABB(0.2, TABLE, 0.2, 0.8, TABLE + 1.8, 0.8);
        assertEquals(s - TABLE, plane.depth(feet, 0), 1e-9);
        // wading at the edge: the feet just under the surface
        AABB wading = feet.move(0, s - TABLE - 0.3, 0);
        assertEquals(0.3, plane.depth(wading, 0), 1e-9);
        // standing on a ledge over it: dry
        assertEquals(0, plane.depth(feet.move(0, 10.5, 0), 0), 0);
        // wholly under the plane's floor (in the table's own lava, or a hollow under it): the blocks there answer
        assertEquals(0, plane.depth(feet.move(0, -3, 0), 0), 0);
        // the plane down at its table: nothing
        plane.set(STD, TABLE, TOP, BOTTOM, 0, 0, true);
        assertEquals(0, plane.depth(feet, 0), 0);
        assertFalse(plane.under(TABLE + 0.5, 0, 0));
    }

    @Test
    void underTellsAPointInTheLavaFromOneOverOrUnderIt() {
        plane.set(STD, TABLE, TOP, BOTTOM, F0, 0, true);               // full
        double s = TABLE - LavaCycle.SURFACE_DROP + 40;
        assertTrue(plane.under(s - 0.01, 0, 0));
        assertFalse(plane.under(s + 0.01, 0, 0));
        assertTrue(plane.under(TABLE - LavaCycle.SURFACE_DROP, 0, 0), "its floor is in it");
        assertFalse(plane.under(TABLE - 1, 0, 0), "the table's own lava is blocks");
    }

    @Test
    void settingTheSameClockAgainKeepsItsPlace() {
        // the server sets it every tick with the clock one tick on: nothing changes, the height cache stays good
        plane.set(STD, TABLE, TOP, BOTTOM, R0 + 100, 500, false);
        double h = plane.height(600, 0);
        for (int k = 1; k <= 50; k++) plane.set(STD, TABLE, TOP, BOTTOM, R0 + 100 + k, 500 + k, false);
        assertEquals(h, plane.height(600, 0), 0);
        assertEquals(STD.height(R0 + 200), h, 1e-12);
        // a clock set elsewhere moves it at once
        plane.set(STD, TABLE, TOP, BOTTOM, F0, 600, false);
        assertEquals(40, plane.height(600, 0), 1e-12);
    }
}
