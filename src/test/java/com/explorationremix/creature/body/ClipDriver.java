package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * Moves a Mossback's box through a block world the way the game does (ClimbMoveControl: straight along its way at its
 * speed, stepping up a block at a time on a floor and falling off one, clinging to walls and ceilings), and eases its
 * way out and its heading the way MossbackEntity's client does, for the body to follow.
 */
final class ClipDriver {
    final BlockWorld world;
    /** The middle of its box (1.4 wide, 0.9 tall). */
    final Vector3d c = new Vector3d();
    final Vector3d heading = new Vector3d(0, 0, 1), up = new Vector3d(0, 1, 0);
    final Vector3d clientUp = new Vector3d(0, 1, 0), clientFwd = new Vector3d(0, 0, 1);
    double vy;
    boolean hurry;
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

    /** Moves along its heading by {@code step}; on a floor it steps up onto rock in its way (a block at most) or falls. */
    void move(double step) {
        c.fma(step, heading);
        if (onFloor()) settle();
    }

    /** On a floor: stands on the highest rock under its box (a step up at once, a step down falling, touching nothing). */
    void settle() {
        double top = world.top(c.x - HW, c.z - HW, c.x + HW, c.z + HW, c.y - HH + 1.0 + 1.0E-6);
        double want = top + HH;
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
    }
}
