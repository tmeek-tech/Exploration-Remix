package com.explorationremix.creature.body;

import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;

/**
 * Where the ember urchin goes (0.23.7, Taran: "It should also walk around and be more of an obstacle to avoid than
 * something to fight"): its own way round the Wasteland's tunnels, never after anyone and never away (the cards' "Goes
 * its own way"), at a quarter of a player's walk.
 *
 * <ul>
 *     <li>Every {@link #PLAN} ticks it looks along {@link #HEADINGS} ways round it: how far it could go each way, its ball
 *     kept clear of the rock, its middle over a floor at its own height, at slopes it can walk and in its own ground
 *     ({@link Ground#allowed}: the Wasteland, not the Ash Hollows nor anywhere under). It keeps on the way it goes where
 *     that runs on, takes another where it does not (round corners, a branch at a junction now and then), and turns
 *     away from another urchin's tunnel.</li>
 *     <li>In a tunnel it keeps to the middle, between both walls and halfway up (so its spines brace on all four
 *     sides); in a cave it walks the floor on its lower spines, its middle {@link #STAND} over it ({@link #height}),
 *     and keeps off the walls.</li>
 *     <li>Something in its spines' way ahead ({@code blocked}) holds it where it is (it never walks into anyone); held
 *     long, it turns back.</li>
 *     <li>Now and then it rests a while, smouldering.</li>
 * </ul>
 *
 * <p>Plain numbers over a {@link Ground}, so the offline tests walk it through the Wasteland's own blocks.
 */
public final class EmberWalk {
    /** The rock and its ground. */
    public interface Ground {
        /** Whether the block at (x, y, z) stops it (any block with a collision box). */
        boolean solid(int x, int y, int z);

        /** Whether its middle may be here: in its own layer, out of the refuge. */
        boolean allowed(double x, double y, double z);
    }

    public enum State { WALK, REST, HOLD }

    // ------------------------------------------------------------------------------------------------ tuning
    /** Its going (blocks a tick): a quarter of a player's walk (the cards' "A quarter of walking pace"). */
    public static final double SPEED = 0.054;
    /** Its middle keeps at least this far from any block (its ball's radius and room for its spines' roots). */
    public static final double CLEAR = 1.05;
    /**
     * Its middle keeps halfway up a tunnel up to {@code 2 * TUNNEL_MID} tall; in anything taller it comes down to
     * stand on its lower spines, {@code STAND} over the floor of a cave ({@link #height}).
     */
    public static final double TUNNEL_MID = 3.0, STAND = 2.6;
    /** A floor further under its middle than this is a drop: it never goes out over one. */
    static final double DROP = 4.2;
    /** It looks along this many ways, this far each, a step at a time; again every PLAN ticks. */
    static final int HEADINGS = 16, PLAN = 12;
    public static final double PROBE = 6.0, PROBE_STEP = 0.75;
    /**
     * Past its first step a probe follows the way round a bend: blocked straight on, it tries these turns (radians),
     * keeping the one that goes, up to MOST_TURN in all (so a winding tunnel reads as a way on, not a wall).
     */
    static final double[] BENDS = {0.0, 0.4, -0.4, 0.8, -0.8};
    static final double MOST_TURN = 1.4;
    /** A way's run counts this much less for each radian its probe turned (so that the straightest way on is taken). */
    static final double TURN_COST = 1.5;
    /**
     * Shut in ahead, it looks further for a way on before it turns ({@link #wayOn}): over as many as WAY_CELLS places
     * round it, a block at a time, for ground at least WAY_FAR off ahead of it.
     */
    static final int WAY_CELLS = 320;
    static final double WAY_FAR = 6.0;
    /** Shut in ahead: no look within 70 degrees of the way it goes runs on this far. */
    static final double SHUT = 3.0;
    /** The most its middle may climb or fall at a probe's step (blocks). */
    static final double SLOPE = 1.05;
    /** In a tunnel it keeps to the middle when both walls lie within this; in a cave this far off a wall. */
    static final double SIDE_REACH = 4.6, WALL_KEEP = 2.4;
    /**
     * Its pull to the middle (a share of the gap, blocks a tick at most): across, and up or down (quick enough to keep its
     * height up and down the steepest climb it takes at its pace).
     */
    static final double CENTRE = 0.05, CENTRE_MOST = 0.024, RISE = 0.1, RISE_MOST = 0.06;
    /** Held this long (ticks) with something in its way, it turns back. */
    static final int HOLD_MOST = 70;
    /** Gone less than STUCK_MOVE in STUCK_TICKS while walking, it is stuck: it looks round afresh, the way it was going barred. */
    static final int STUCK_TICKS = 60;
    static final double STUCK_MOVE = 0.4;
    /** Another urchin nearer than this along a way turns it from that way. */
    static final double OTHERS = 12.0;
    /** One tick in this, walking, it stops to rest for REST_MIN to REST_MAX ticks. */
    static final int REST_ODDS = 1100, REST_MIN = 60, REST_MAX = 180;

    // ------------------------------------------------------------------------------------------------ state
    /** The way it goes (level, unit). */
    public final Vector3d heading = new Vector3d(1, 0, 0);
    /** Its going this tick (blocks), eased. */
    public final Vector3d velocity = new Vector3d();
    public State state = State.WALK;
    private int planIn, holdTicks, restTicks, stuckTicks;
    private final Vector3d stuckFrom = new Vector3d();
    private boolean stuckSet;
    private long seed = 0x5EED_E3BL, ticks;
    /** For checks: how far it could go its way at its last look, the gaps round it this tick. */
    public double run, floorGap, roofGap, leftGap, rightGap;
    /** How far (radians, either way) the last probe turned to follow its way. */
    private double probeTurn;
    /** For checks: how many times it was stuck, held long and turned back. */
    public int stuckCount, turnedBack;

    private final Vector3d q = new Vector3d(), side = new Vector3d(), want = new Vector3d(), h = new Vector3d();

    public void setSeed(long s) { this.seed = s * 0x9E3779B97F4A7C15L + 0x5EED_E3BL; }

    /** Sets the way it goes (as it is set down, or loaded). */
    public void face(double x, double z) {
        double l = Math.sqrt(x * x + z * z);
        if (l > 1.0E-6) heading.set(x / l, 0, z / l);
        planIn = 0;
    }

    /**
     * Its going this tick (blocks), its middle at {@code pos}: {@code others} the middles of other urchins near,
     * {@code blocked} whether something stands in its spines' way ahead.
     */
    public Vector3d tick(Ground g, Vector3d pos, List<Vector3d> others, boolean blocked) {
        ticks++;
        measure(g, pos);
        want.zero();
        switch (state) {
            case HOLD -> {
                if (!blocked) { state = State.WALK; holdTicks = 0; break; }
                if (++holdTicks > HOLD_MOST) {
                    // held long: back the other way
                    plan(g, pos, others, true);
                    state = State.WALK;
                    holdTicks = 0;
                    turnedBack++;
                }
            }
            case REST -> {
                if (blocked) { state = State.HOLD; holdTicks = 0; restTicks = 0; break; }
                if (--restTicks <= 0) { state = State.WALK; planIn = 0; }
            }
            default -> {
                if (blocked) { state = State.HOLD; holdTicks = 0; break; }
                if (rand(3) * REST_ODDS < 1.0) {
                    state = State.REST;
                    restTicks = REST_MIN + (int) (rand(4) * (REST_MAX - REST_MIN));
                    break;
                }
                if (--planIn <= 0) plan(g, pos, others, false);
                stuck(g, pos, others);
            }
        }
        if (state == State.WALK && run >= 0.5) want.set(heading).mul(SPEED * Math.min(1.0, run / 1.5));
        centre(want);
        velocity.lerp(want, state == State.WALK ? 0.12 : 0.2);
        return velocity;
    }

    /** Its pull to the middle: between the walls of a tunnel (off the wall of a cave), and up or down to its height over the floor. */
    private void centre(Vector3d out) {
        side.set(-heading.z, 0, heading.x);
        double off = 0;
        if (leftGap < SIDE_REACH && rightGap < SIDE_REACH) off = (leftGap - rightGap) * 0.5;
        else if (leftGap < WALL_KEEP) off = -(WALL_KEEP - leftGap);
        else if (rightGap < WALL_KEEP) off = WALL_KEEP - rightGap;
        out.fma(clamp(off * CENTRE, -CENTRE_MOST, CENTRE_MOST), side);
        if (floorGap < DROP + 1.0) {
            double room = floorGap + roofGap;
            double at = Math.max(Math.min(CLEAR, room * 0.5), height(room));
            out.y += clamp((at - floorGap) * RISE, -RISE_MOST, RISE_MOST);
        }
    }

    /** The gaps round its middle: down to the floor, up to the roof, out to each side of its way (each the free run to a block). */
    private void measure(Ground g, Vector3d p) {
        floorGap = gap(g, p, 0, -1, 0, DROP + 2.0);
        roofGap = gap(g, p, 0, 1, 0, 9.0);
        leftGap = gap(g, p, -heading.z, 0, heading.x, SIDE_REACH + 1.0);
        rightGap = gap(g, p, heading.z, 0, -heading.x, SIDE_REACH + 1.0);
    }

    /** The free run from p along (dx, dy, dz) (unit) to the first block, up to {@code most}, a quarter block at a time. */
    static double gap(Ground g, Vector3d p, double dx, double dy, double dz, double most) {
        for (double d = 0.25; d <= most; d += 0.25) {
            if (g.solid(floor(p.x + dx * d), floor(p.y + dy * d), floor(p.z + dz * d))) return d - 0.125;
        }
        return most;
    }

    // ------------------------------------------------------------------------------------------------ choosing its way

    /**
     * Looks round: each way's free run ({@link #probe}), kept to the way it goes where that runs on, away from other
     * urchins; {@code back}, the way it goes is barred (and those near it), so it turns back.
     */
    private void plan(Ground g, Vector3d pos, List<Vector3d> others, boolean back) {
        planIn = PLAN;
        double best = -Double.MAX_VALUE, bestRun = 0;
        double bx = heading.x, bz = heading.z;
        double turn = rand(5) * 2.0 * Math.PI / HEADINGS;
        double longest = 0, ahead = 0;
        double lx = bx, lz = bz;
        for (int k = 0; k < HEADINGS; k++) {
            double a = turn + 2.0 * Math.PI * k / HEADINGS;
            h.set(Math.cos(a), 0, Math.sin(a));
            double r = probe(g, pos, h);
            if (r > longest) { longest = r; lx = h.x; lz = h.z; }
            double dot = h.x * heading.x + h.z * heading.z;
            if (dot > 0.3) ahead = Math.max(ahead, r);
            if (back && dot > -0.2) continue;
            double score = r - TURN_COST * probeTurn + (back ? 0.0 : 1.6 * Math.max(0.0, dot) + 0.4 * dot - (dot < -0.7 ? 2.5 : 0.0)) + 0.8 * rand(10 + k);
            if (others != null) {
                for (Vector3d o : others) {
                    double ox = o.x - pos.x, oz = o.z - pos.z, d = Math.sqrt(ox * ox + oz * oz);
                    if (d < 1.0E-3 || d > OTHERS || Math.abs(o.y - pos.y) > 6.0) continue;
                    score -= 3.0 * (1.0 - d / OTHERS) * Math.max(0.0, (h.x * ox + h.z * oz) / d);
                }
            }
            if (score > best) { best = score; bestRun = r; bx = h.x; bz = h.z; }
        }
        // shut in short every way it would take: the longest run there is
        if (bestRun < 1.2 && longest > bestRun) { bx = lx; bz = lz; bestRun = longest; }
        // shut in ahead (no straight look runs on that way): a winding way on, if its steps find one
        if (!back && ahead < SHUT && wayOn(g, pos)) return;
        heading.set(bx, 0, bz).normalize();
        run = bestRun;
    }

    /** A place its steps reach, and the one it was reached from. */
    private record Cell(double x, double y, double z, Cell from) {}

    private final ArrayDeque<Cell> wayOpen = new ArrayDeque<>();
    private final HashSet<Long> waySeen = new HashSet<>();
    private static final int[] WX = {1, -1, 0, 0, 1, 1, -1, -1}, WZ = {0, 0, 1, -1, 1, -1, 1, -1};
    /** For checks: how many times it found a winding way on where its probes saw none. */
    public int wayOns;

    /**
     * Its probes see no way on ahead: it looks further, stepping a block at a time round it as it would walk
     * ({@link #step}) through as many as {@link #WAY_CELLS} places, for the nearest ground at least {@link #WAY_FAR} off
     * and ahead of the way it goes (a way on through a narrow, winding stretch that no straight look follows), and turns
     * toward the second place of its steps there. False if there is none: it turns back.
     */
    private boolean wayOn(Ground g, Vector3d pos) {
        wayOpen.clear();
        waySeen.clear();
        wayOpen.add(new Cell(pos.x, pos.y, pos.z, null));
        waySeen.add(cellKey(pos.x, pos.y, pos.z));
        int cells = 0;
        while (!wayOpen.isEmpty() && cells++ < WAY_CELLS) {
            Cell c = wayOpen.poll();
            for (int k = 0; k < 8; k++) {
                double x = c.x + WX[k], z = c.z + WZ[k];
                double y = step(g, c.y, x, z, PROBE_STEP);
                if (Double.isNaN(y) || !waySeen.add(cellKey(x, y, z))) continue;
                Cell n = new Cell(x, y, z, c);
                double dx = x - pos.x, dz = z - pos.z, d = Math.sqrt(dx * dx + dz * dz);
                if (d >= WAY_FAR && dx * heading.x + dz * heading.z > 0.3 * d) {
                    // its steps there, from the first: it makes for the second
                    Cell aim = n;
                    while (aim.from != null && aim.from.from != null && aim.from.from.from != null) aim = aim.from;
                    double ax = aim.x - pos.x, az = aim.z - pos.z, ad = Math.sqrt(ax * ax + az * az);
                    if (ad < 1.0E-3) return false;
                    heading.set(ax / ad, 0, az / ad);
                    run = Math.max(1.5, ad);
                    wayOns++;
                    return true;
                }
                wayOpen.add(n);
            }
        }
        return false;
    }

    private static long cellKey(double x, double y, double z) {
        return ((long) floor(x) & 0x3FFFFFFL) << 38 | ((long) floor(y) & 0xFFFL) << 26 | ((long) floor(z) & 0x3FFFFFFL);
    }

    /**
     * How far it could go setting out along the level way {@code way} from {@code from} (blocks, up to {@link #PROBE}):
     * a step at a time ({@link #step}: its middle at its height over the floor there, clear of the rock, at a slope it
     * can climb, in its own ground), its first step straight on and the rest following the way round bends
     * ({@link #BENDS}); it stops at a wall, a drop, a squeeze or the edge of its ground.
     */
    public double probe(Ground g, Vector3d from, Vector3d way) {
        q.set(from);
        double wx = way.x, wz = way.z, turned = 0, d = 0;
        for (int i = 1; i * PROBE_STEP <= PROBE + 1.0E-9; i++) {
            double y = Double.NaN, x = 0, z = 0;
            for (int t = 0; t < (i == 1 ? 1 : BENDS.length) && Double.isNaN(y); t++) {
                double a = BENDS[t];
                if (Math.abs(turned + a) > MOST_TURN) continue;
                double c = Math.cos(a), sn = Math.sin(a), tx = wx * c - wz * sn, tz = wx * sn + wz * c;
                x = q.x + tx * PROBE_STEP;
                z = q.z + tz * PROBE_STEP;
                y = step(g, q.y, x, z, PROBE_STEP);
                if (!Double.isNaN(y) && a != 0.0) { wx = tx; wz = tz; turned += a; }
            }
            if (Double.isNaN(y)) break;
            q.set(x, y, z);
            d += PROBE_STEP;
        }
        probeTurn = Math.abs(turned);
        return d;
    }

    /**
     * A step of its way {@code length} long, its middle at height {@code y}, over to (x, z): where its middle would be
     * there ({@link #standAt}), or NaN if it cannot go there: no floor (a drop), a squeeze, rock in its ball's way, a
     * slope steeper than it climbs, or out of its ground.
     */
    public static double step(Ground g, double y, double x, double z, double length) { return step(g, y, x, z, length, CLEAR); }

    /** {@link #step}, its ball kept {@code clearance} from the rock. */
    public static double step(Ground g, double y, double x, double z, double length, double clearance) {
        double ny = standAt(g, x, y, z);
        if (Double.isNaN(ny) || Math.abs(ny - y) > SLOPE * length / PROBE_STEP) return Double.NaN;
        if (!clear(g, x, ny, z, clearance) || !g.allowed(x, ny, z)) return Double.NaN;
        return ny;
    }

    /**
     * Where its middle would stand over (x, z), from about {@code near}: over the floor under it by half the room there
     * ({@link #height}); NaN if there is no floor within {@link #DROP} (a drop), the room
     * is too low, or rock fills the place a step up and over.
     */
    public static double standAt(Ground g, double x, double near, double z) {
        int bx = floor(x), bz = floor(z);
        int y = floor(near);
        if (g.solid(bx, y, bz)) {
            // rock where its middle would be: the roof come down (it goes under) or the floor risen (it goes over)
            boolean open = false;
            for (int k = 1; k <= 2 && !open; k++) {
                if (!g.solid(bx, y - k, bz)) { y -= k; open = true; }
                else if (!g.solid(bx, y + k, bz)) { y += k; open = true; }
            }
            if (!open) return Double.NaN;
        }
        int lowest = floor(near - DROP);
        int f = y;
        while (f > lowest && !g.solid(bx, f - 1, bz)) f--;
        if (f <= lowest) return Double.NaN;
        // the room over the floor (f is the floor's top)
        int roof = y + 1;
        int most = f + 9;
        while (roof < most && !g.solid(bx, roof, bz)) roof++;
        double room = roof - f;
        if (room < 2.0 * CLEAR + 0.2) return Double.NaN;
        return f + Math.max(CLEAR, height(room));
    }

    /**
     * How far over the floor its middle keeps with {@code room} between floor and roof: halfway up a tunnel (up to
     * {@code 2 * TUNNEL_MID} tall), easing down over the next two blocks of room to {@link #STAND} over a cave's floor.
     */
    public static double height(double room) {
        if (room <= 2.0 * TUNNEL_MID) return room * 0.5;
        return Math.max(STAND, TUNNEL_MID - (TUNNEL_MID - STAND) * (room - 2.0 * TUNNEL_MID) / 2.0);
    }

    /** Whether a ball of radius r round (x, y, z) touches no block. */
    public static boolean clear(Ground g, double x, double y, double z, double r) {
        int x0 = floor(x - r), x1 = floor(x + r), y0 = floor(y - r), y1 = floor(y + r), z0 = floor(z - r), z1 = floor(z + r);
        double r2 = r * r;
        for (int bx = x0; bx <= x1; bx++)
            for (int by = y0; by <= y1; by++)
                for (int bz = z0; bz <= z1; bz++) {
                    if (!g.solid(bx, by, bz)) continue;
                    double dx = Math.max(Math.max(bx - x, 0), x - (bx + 1)), dy = Math.max(Math.max(by - y, 0), y - (by + 1)),
                            dz = Math.max(Math.max(bz - z, 0), z - (bz + 1));
                    if (dx * dx + dy * dy + dz * dz < r2) return false;
                }
        return true;
    }

    /** Walking but hardly going: it looks round afresh with the way it was going barred. */
    private void stuck(Ground g, Vector3d pos, List<Vector3d> others) {
        if (!stuckSet) { stuckFrom.set(pos); stuckSet = true; stuckTicks = 0; return; }
        if (++stuckTicks < STUCK_TICKS) return;
        if (stuckFrom.distance(pos) < STUCK_MOVE) {
            stuckCount++;
            plan(g, pos, others, true);
        }
        stuckFrom.set(pos);
        stuckTicks = 0;
    }

    /** For /remix ember: what it is doing, in words. */
    public String describe() {
        return switch (state) {
            case WALK -> String.format(java.util.Locale.ROOT, "walking (%.1f ahead), floor %.1f under, roof %.1f over, walls %.1f and %.1f",
                    run, floorGap, roofGap, leftGap, rightGap);
            case REST -> "resting (" + restTicks + " ticks)";
            case HOLD -> "held (something in its way, " + holdTicks + " ticks)";
        };
    }

    /** A number from 0 to 1 that changes every tick, its own for each walker and each use. */
    private double rand(int use) {
        long x = seed + ticks * 0x9E3779B97F4A7C15L + use * 0xC2B2AE3D27D4EB4FL;
        x ^= x >>> 33; x *= 0xff51afd7ed558ccdL; x ^= x >>> 33; x *= 0xc4ceb9fe1a85ec53L; x ^= x >>> 33;
        return (x >>> 11) * 0x1.0p-53;
    }

    private static int floor(double v) { return (int) Math.floor(v); }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }
}
