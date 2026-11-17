package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * Moves a Mossback's box through a block world the way the game does (ClimbMoveControl: straight along its way at its
 * speed, climbing straight up a riser in its way and coming down off a step, clinging to walls and ceilings), and eases
 * its way out and its heading the way MossbackEntity's client does, for the body to follow.
 */
final class ClipDriver {
    final BlockWorld world;
    /** The middle of its box (1.4 wide, 0.9 tall). */
    final Vector3d c = new Vector3d();
    final Vector3d heading = new Vector3d(0, 0, 1), up = new Vector3d(0, 1, 0);
    final Vector3d clientUp = new Vector3d(0, 1, 0), clientFwd = new Vector3d(0, 0, 1);
    double vy;
    boolean hurry;
    /** Off its floor at a step: climbing a riser, or coming down off one (MossbackBody.Drive#step). */
    boolean step;
    double rear;
    MossbackBody.Mode mode = MossbackBody.Mode.GROUND;
    static final double HW = 0.7, HH = 0.45;

    ClipDriver(BlockWorld world, double x, double y, double z, double hx, double hz) {
        this.world = world;
        c.set(x, y + HH, z);
        heading.set(hx, 0, hz).normalize();
        clientFwd.set(heading);
    }

    boolean onFloor() { return up.y > 0.9; }

    /**
     * Moves along its heading by {@code step}. On a floor it goes over a step the way the game moves it
     * (ClimbMoveControl): blocked by rock, it climbs straight up at its climbing pace until it clears it; past an edge
     * with a floor no more than a step under it, it comes down to it as steadily, still steering; off a higher drop it
     * falls. At a step its body stays on its feet, as MossbackEntity's client has it (Steps).
     */
    void move(double step) {
        this.step = false;
        if (!onFloor()) { c.fma(step, heading); return; }
        double under = c.y - HH - floorTop();
        boolean gliding = under > 1.0E-6 && under <= Steps.DEEP + 1.0E-6 && vy == 0;
        double across = gliding ? step * GLIDE : step;
        double hx = heading.x * across, hz = heading.z * across;
        if (blocked(hx, hz)) {
            // up to the riser, then straight up it
            double lo = 0, hi = 1;
            for (int i = 0; i < 12; i++) {
                double m = (lo + hi) / 2;
                if (blocked(hx * m, hz * m)) hi = m; else lo = m;
            }
            c.x += hx * lo; c.z += hz * lo;
            c.y += step * CLIMB;
            vy = 0;
            mode = MossbackBody.Mode.GROUND;
            this.step = true;
            return;
        }
        c.x += hx; c.z += hz;
        under = c.y - HH - floorTop();
        if (under <= 1.0E-6) { c.y -= under; vy = 0; mode = MossbackBody.Mode.GROUND; }
        else if (under <= Steps.DEEP + 1.0E-6 && vy == 0) {
            c.y -= Math.min(under, step * GLIDE);
            mode = MossbackBody.Mode.GROUND;
            this.step = c.y - HH - floorTop() > 1.0E-6;
        } else settle();
    }

    /** The game climbs at this share of its pace, and comes down a step about this much across and down a tick. */
    static final double CLIMB = 0.85, GLIDE = 0.6;

    /** The highest top of rock under its box (at or below its bottom). */
    double floorTop() {
        return world.top(c.x - HW, c.z - HW, c.x + HW, c.z + HW, c.y - HH + 1.0E-6);
    }

    private boolean blocked(double dx, double dz) {
        return Steps.any(world, c.x + dx - HW + 1.0E-6, c.y - HH + 1.0E-6, c.z + dz - HW + 1.0E-6,
                c.x + dx + HW - 1.0E-6, c.y + HH, c.z + dz + HW - 1.0E-6);
    }

    /** Off a floor: falls onto the highest rock under its box, touching nothing on the way. */
    void settle() {
        double want = floorTop() + HH;
        if (want >= c.y - 1.0E-6) { c.y = want; vy = 0; }
        else {
            vy = Math.min(vy + 0.08, 1.0);
            c.y = Math.max(want, c.y - vy);
            if (c.y <= want + 1.0E-9) vy = 0;
        }
        mode = c.y > want + 0.08 ? MossbackBody.Mode.AIR : MossbackBody.Mode.GROUND;
    }

    /** Turns its heading about its rock's way out (degrees, right-handed: positive turns it left on a floor). */
    void turn(double deg) {
        double a = Math.toRadians(deg);
        Vector3d k = up;
        Vector3d v = heading;
        double cs = Math.cos(a), sn = Math.sin(a), d = v.dot(k);
        Vector3d cr = new Vector3d(k).cross(v);
        v.mul(cs).fma(sn, cr).fma(d * (1 - cs), k).normalize();
    }

    /** The drive for this tick, eased as the entity's client eases it. */
    void drive(MossbackBody.Drive d) {
        if (clientUp.dot(up) < -0.99) clientUp.add(new Vector3d(clientFwd).cross(clientUp).mul(0.3));
        clientUp.lerp(up, 0.35).normalize();
        Vector3d want = new Vector3d(heading);
        if (clientFwd.dot(want) < -0.99) clientFwd.add(new Vector3d(clientUp).cross(clientFwd).mul(0.3));
        clientFwd.lerp(want, 0.3);
        clientFwd.fma(-clientFwd.dot(clientUp), clientUp);
        if (clientFwd.lengthSquared() < 1.0E-6) clientFwd.set(want);
        clientFwd.normalize();
        d.x = c.x; d.y = c.y; d.z = c.z;
        d.up.set(clientUp);
        d.forward.set(clientFwd);
        d.lead.set(heading);
        d.mode = mode;
        d.rear = rear;
        d.hurry = hurry;
        d.step = step;
    }
}
