package com.explorationremix.creature.body;

import org.joml.Vector3d;

/**
 * How the chimney lurker climbs its vent (0.21.0): the game moves its body's centre over the rock itself, held its
 * ride out from the rock, as a spider walks a wall. It keeps to the upright plane through its vent's axis on one side
 * (its azimuth), and in that plane follows the rock's outline with the rock always on one hand, so whatever the vent's
 * shape it goes over the rim, down the outer wall, out over a flange and back in under it, round the foot's flare and
 * out along the floor (going {@link Sense#OUT}), or the same way back from the floor up to the rim and over it, down
 * into the chimney's mouth ({@link Sense#IN}). Never by a path worked out ahead: each tick it reads the rock round it
 * ({@link TerrainPatch}), so a vent a player has dug into is climbed as it now is.
 *
 * <p>In the plane, let s run out from the axis and y up. The rock's way out there, n = (ns, ny), turned a quarter
 * round gives the way along the rock: going out, (ny, -ns) (down an outer wall, out across a top, up an inner wall);
 * going in, (-ny, ns). Over a convex edge the way out swings round the edge and the body rolls round it at its ride; in
 * a hollow the blended way out (TerrainPatch's fillets) carries it round the corner.
 *
 * <p>Plain numbers and JOML, no game classes: the entity drives it on the server with the level's rock, the tests with
 * a world of their own.
 */
public final class LurkerCrawl {
    public enum Sense { OUT, IN }

    /** Rock further than this is not felt; surfaces meeting in a hollow are blended over SOFT (blocks). */
    static final double REACH = 2.4, SOFT = 0.28;
    /** Further than this from the rock (beyond its ride), it has lost its hold. */
    public static final double LOST = 1.5;
    /** How far round the rock it reads each tick (blocks). */
    static final int AROUND = 3;
    /**
     * It holds only the rock on its own side: rock whose way out turns further than this (a dot) from its own way out
     * lies across the gap from it (the far wall of a narrow flue, a ceiling low over its rim, a wall beyond a narrow
     * moat), and is not felt. Round a convex edge and into a hollow's corner the rock it comes to turns at most a
     * quarter round (a dot of 0), so it is felt.
     */
    static final double HOLD = -0.35;

    /** The body's centre, held {@link #ride} out from the rock. */
    public final Vector3d pos = new Vector3d();
    /** The rock's way out under it (eased) and the way it went this tick (unit). */
    public final Vector3d up = new Vector3d(0, 1, 0), heading = new Vector3d(0, -1, 0);
    public final double ride;
    private double ax, az, ox = 1, oz = 0;
    private Sense sense = Sense.OUT;
    /** The highest it has been since it set off, and the ticks since. */
    public double highest;
    public int ticks;
    private double lastNs = 1, lastNy = 0;
    /** Just set off: its way out is read afresh from all the rock round it before it holds to its own side. */
    private boolean fresh = true;
    private final TerrainPatch rock = new TerrainPatch();
    private final double[] f = new double[4];

    public LurkerCrawl(double ride) {
        this.ride = ride;
    }

    public Sense sense() { return sense; }
    /** The plane's azimuth (radians, from +x toward +z). */
    public double azimuth() { return Math.atan2(oz, ox); }
    public double axisX() { return ax; }
    public double axisZ() { return az; }

    /** Sets off from {@code from} along the plane through the axis (ax, az) on its side, going {@code sense}. */
    public void start(Vector3d from, double axisX, double axisZ, Sense s) {
        pos.set(from);
        ax = axisX;
        az = axisZ;
        double dx = from.x - ax, dz = from.z - az, d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0E-3) { ox = 1; oz = 0; } else { ox = dx / d; oz = dz / d; }
        sense = s;
        highest = from.y;
        ticks = 0;
        lastNs = sense == Sense.OUT ? -1 : 1;
        lastNy = 0;
        fresh = true;
    }

    /**
     * Turns round on the rock it holds, to go {@code s} from here (its plane and its hold kept; its highest and its
     * ticks counted afresh).
     */
    public void turn(Sense s) {
        sense = s;
        highest = pos.y;
        ticks = 0;
    }

    /** Turns the plane to {@code azimuth} (radians), as the body walks round the rim's top toward it. */
    public void turnTo(double azimuth, double maxStep) {
        double cur = Math.atan2(oz, ox);
        double d = wrap(azimuth - cur);
        d = Math.max(-maxStep, Math.min(maxStep, d));
        double a = cur + d;
        ox = Math.cos(a);
        oz = Math.sin(a);
    }

    /** Reads the rock round where it is (the tick's patch); false when there is none to hold. */
    public boolean read(Terrain t) {
        int x = (int) Math.floor(pos.x), y = (int) Math.floor(pos.y), z = (int) Math.floor(pos.z);
        rock.build(t, x - AROUND, y - AROUND, z - AROUND, x + AROUND, y + AROUND, z + AROUND);
        if (fresh && rock.field(pos.x, pos.y, pos.z, REACH, SOFT, f)) {
            up.set(f[1], f[2], f[3]);
            fresh = false;
        }
        return field() && f[0] < ride + LOST;
    }

    /** The rock it holds, where it is now (its own side only: see {@link #HOLD}). */
    private boolean field() {
        return rock.field(pos.x, pos.y, pos.z, REACH, SOFT, f, up.x, up.y, up.z, HOLD);
    }

    /** The patch read last ({@link #read}). */
    public TerrainPatch rock() { return rock; }

    /**
     * One tick: {@code speed} blocks along the rock the way it goes, then back onto the plane and onto its ride out from
     * the rock. Reads the rock first; false (and it stays put) when there is none to hold.
     */
    public boolean step(Terrain t, double speed) {
        if (!read(t)) return false;
        ticks++;
        double ns = f[1] * ox + f[3] * oz, ny = f[2];
        double nl = Math.sqrt(ns * ns + ny * ny);
        if (nl < 0.3) { ns = lastNs; ny = lastNy; }
        else { ns /= nl; ny /= nl; lastNs = ns; lastNy = ny; }
        double ts, ty;
        if (sense == Sense.OUT) { ts = ny; ty = -ns; } else { ts = -ny; ty = ns; }
        double mx = ox * ts * speed, my = ty * speed, mz = oz * ts * speed;
        double bx = pos.x, by = pos.y, bz = pos.z;
        pos.add(mx, my, mz);
        // back toward the plane through the axis (what drifted across it)
        double ex = -oz, ez = ox;
        double off = (pos.x - ax) * ex + (pos.z - az) * ez;
        pos.x -= ex * off * 0.5;
        pos.z -= ez * off * 0.5;
        settle();
        heading.set(pos.x - bx, pos.y - by, pos.z - bz);
        if (heading.lengthSquared() < 1.0E-10) heading.set(ox * ts, ty, oz * ts);
        heading.normalize();
        highest = Math.max(highest, pos.y);
        return true;
    }

    /** Onto its ride out from the rock of the last {@link #read} (a few steps along the way out). */
    public void settle() {
        for (int it = 0; it < 3; it++) {
            if (!field()) break;
            double m = ride - f[0];
            if (Math.abs(m) < 1.0E-3) break;
            pos.add(f[1] * m * 0.85, f[2] * m * 0.85, f[3] * m * 0.85);
        }
        if (field()) {
            up.lerp(new Vector3d(f[1], f[2], f[3]), 0.5);
            if (up.lengthSquared() < 1.0E-8) up.set(f[1], f[2], f[3]);
            up.normalize();
        }
    }

    /** The rock's way out where it is now (from the last {@link #read}), or false with no rock near. */
    public boolean wayOut(Vector3d out) {
        if (!field()) return false;
        out.set(f[1], f[2], f[3]);
        return true;
    }

    /** How far it is from the rock now (from the last {@link #read}), or +inf. */
    public double gap() {
        return field() ? f[0] : Double.POSITIVE_INFINITY;
    }

    /** Its way out along the plane: + out from the axis, - in toward it (the way the rock it is on faces). */
    public double facingOut() { return lastNs; }

    /** Its way out's upward share (1 on a floor or a top, 0 on a wall). */
    public double facingUp() { return lastNy; }

    static double wrap(double a) {
        a %= 2.0 * Math.PI;
        if (a > Math.PI) a -= 2.0 * Math.PI;
        if (a < -Math.PI) a += 2.0 * Math.PI;
        return a;
    }
}
