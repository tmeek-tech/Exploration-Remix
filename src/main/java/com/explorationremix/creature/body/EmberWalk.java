package com.explorationremix.creature.body;

import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Where the ember urchin goes (0.23.7, Taran: "It should also walk around and be more of an obstacle to avoid than
 * something to fight"): its own way round the Wasteland's tunnels at a quarter of a player's walk; and after someone at
 * a player's walk (0.24.3, Taran: "Can you make it chase the player at the player's walking speed and keep this as its
 * meandering speed?"; when it chases is {@link EmberChase}'s).
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
 *     <li>Chasing ({@link #chase}), it finds its way to them a block at a time as it steps ({@link #route}: round
 *     bends, through junctions, up and down between storeys, never over a drop nor into the Ash Hollows), keeping to
 *     the middle of the way where there is room, and goes along it at {@link #CHASE_SPEED}, pressing in on them when it
 *     gets there; nothing in its way holds it then. Before it sets off it stops a moment ({@link #rouse}).</li>
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

    public enum State { WALK, REST, HOLD, ROUSE, CHASE }

    // ------------------------------------------------------------------------------------------------ tuning
    /** Its going (blocks a tick): a quarter of a player's walk (the cards' "A quarter of walking pace"). */
    public static final double SPEED = 0.054;
    /** Its middle keeps at least this far from any block (its ball's radius and room for its spines' roots). */
    public static final double CLEAR = 1.05;
    /**
     * Chasing, it squeezes through where its middle has only this much room (round the tight corners and bumps of a
     * tunnel that its walk keeps out of; never through a way less than three blocks wide or tall), at SQUEEZE_COST more
     * a place, so that it does only to keep after someone. Shut in so tight that its walk finds no way on (where a chase
     * left it), it walks with this much room until it is out.
     */
    public static final double SQUEEZE = 0.85, SQUEEZE_COST = 2.0;
    /** Squeezing, its box (its ball's, half a block and a little over each way) is kept out of the rock too. */
    static final double BOX = 0.66;
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

    /** Chasing, it goes at a player's walking pace (4.3 blocks a second, so that a sprint gets away). */
    public static final double CHASE_SPEED = 0.21585;
    /**
     * Its way to whom it chases is found afresh every ROUTE_EVERY ticks, sooner (but at most every ROUTE_SOONEST) when they
     * have gone on GOAL_MOVED from where it was found to, looking through at most ROUTE_CELLS places.
     */
    static final int ROUTE_EVERY = 10, ROUTE_SOONEST = 4, ROUTE_CELLS = 700;
    static final double GOAL_MOVED = 1.5;
    /**
     * A place on its way with no room of ROOMY round its middle costs TIGHT_COST more to go through (so that it keeps to
     * the middle of a tunnel, its spines braced all round, rather than cutting its corners); each block climbed or come
     * down, CLIMB_COST more.
     */
    static final double ROOMY = 1.6, TIGHT_COST = 1.2, CLIMB_COST = 0.3;
    /** Its way's end: within REACHED across of them, its middle within REACHED_UP of STAND over their feet. */
    static final double REACHED = 1.5, REACHED_UP = 3.0;
    /** It presses in on them to this near across (its spines well into them, its ball short of them). */
    public static final double CATCH = 1.8;
    /** It counts a place on its way passed within ARRIVE of it, and makes for the one LOOK on from that. */
    static final double ARRIVE = 0.8;
    static final int LOOK = 1;
    /** Another urchin this near ahead on its way: it waits behind it. */
    static final double QUEUE = 3.5;
    /** Chasing but hardly going (less than CHASE_STUCK_MOVE in CHASE_STUCK ticks, short of them): it finds its way afresh. */
    static final int CHASE_STUCK = 20;
    static final double CHASE_STUCK_MOVE = 0.5;

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
    /** The room its looks keep its middle in: {@link #CLEAR}, or {@link #SQUEEZE} when that finds it no way on. */
    private double room = CLEAR;
    /** For checks: how many times it was stuck, held long and turned back. */
    public int stuckCount, turnedBack;

    /** Chasing: its way (its middle at each place), how far along it it is, and whether it reaches them. */
    private double[] routeX = new double[64], routeY = new double[64], routeZ = new double[64];
    private int routeLen, routeAt, routeIn, routeAge, chaseStuck;
    private boolean routeReaches, caught;
    private final Vector3d routeGoal = new Vector3d(), chaseFrom = new Vector3d();
    /** For checks: ways found, places looked through for them (in all, and the most for one). */
    public int routes, routeCells, routeMost;

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
        room = CLEAR;
        plan(g, pos, others, back, true);
        // shut in so tight that it finds no way on (a chase squeezed it in here): it looks again with the room it
        // squeezes through with, until it is out
        if (run < 0.5 && !back) {
            room = SQUEEZE;
            plan(g, pos, others, false, false);
            squeezes++;
            room = CLEAR;
        }
    }

    /** For checks: how many times it looked for a way out of a squeeze (shut in tight). */
    public int squeezes;

    private void plan(Ground g, Vector3d pos, List<Vector3d> others, boolean back, boolean first) {
        planIn = first ? PLAN : PLAN / 2;
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
                double y = step(g, c.y, x, z, PROBE_STEP, room);
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
                y = step(g, q.y, x, z, PROBE_STEP, room);
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
        if (!clear(g, x, ny, z, clearance) || clearance < CLEAR && !boxClear(g, x, ny, z, BOX) || !g.allowed(x, ny, z)) return Double.NaN;
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

    /** Whether a box reaching {@code r} each way from (x, y, z) touches no block. */
    public static boolean boxClear(Ground g, double x, double y, double z, double r) {
        for (int bx = floor(x - r), x1 = floor(x + r); bx <= x1; bx++)
            for (int by = floor(y - r), y1 = floor(y + r); by <= y1; by++)
                for (int bz = floor(z - r), z1 = floor(z + r); bz <= z1; bz++)
                    if (g.solid(bx, by, bz)) return false;
        return true;
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

    // ------------------------------------------------------------------------------------------------ chasing

    /** Its warning before it sets off after someone: it stops where it is. */
    public Vector3d rouse(Ground g, Vector3d pos) {
        ticks++;
        if (state != State.ROUSE) routeLen = 0;
        state = State.ROUSE;
        measure(g, pos);
        want.zero();
        velocity.lerp(want, 0.3);
        return velocity;
    }

    /**
     * Its going this tick chasing someone whose feet are at {@code goal} (or where it last saw them), its middle at
     * {@code pos}, {@code others} the middles of other urchins near: along its way to them ({@link #route}, found afresh
     * now and then and as they go on), at {@link #CHASE_SPEED}, easing to a stop as it presses in on them ({@link #CATCH})
     * or comes to the end of a way that does not reach them; behind another urchin on its way, it waits.
     */
    public Vector3d chase(Ground g, Vector3d pos, Vector3d goal, List<Vector3d> others) {
        ticks++;
        if (state != State.CHASE) {
            routeLen = 0;
            chaseStuck = 0;
            chaseFrom.set(pos);
        }
        state = State.CHASE;
        measure(g, pos);
        double gx = goal.x - pos.x, gz = goal.z - pos.z, across = Math.sqrt(gx * gx + gz * gz);
        caught = across < CATCH && Math.abs(goal.y + 1.0 - pos.y) < REACHED_UP;
        routeAge++;
        boolean gone = routeGoal.distanceSquared(goal) > GOAL_MOVED * GOAL_MOVED;
        boolean ended = routeLen > 1 && routeAt >= routeLen - 1 && !routeReaches;
        if (!caught && (routeLen == 0 || --routeIn <= 0 || (routeAge >= ROUTE_SOONEST && (gone || ended)))) {
            route(g, pos, goal);
            routeIn = ROUTE_EVERY;
            routeAge = 0;
            routeGoal.set(goal);
        }
        want.zero();
        run = 0;
        if (!caught && routeLen > 1) {
            // on past each place it has come to (or is nearer the next than), making for the one after
            while (routeAt < routeLen - 1 && (horizontal(pos, routeAt) < ARRIVE || horizontal(pos, routeAt + 1) < horizontal(pos, routeAt))) routeAt++;
            int aim = Math.min(routeAt + LOOK, routeLen - 1);
            double ax = routeX[aim] - pos.x, ay = routeY[aim] - pos.y, az = routeZ[aim] - pos.z;
            double ad = Math.sqrt(ax * ax + ay * ay + az * az);
            // how far it has still to go along its way (its end is beside them if it reaches them: it never quite stops
            // short of them, and comes to a stop at the end of one that does not)
            double left = horizontal(pos, aim) + (routeLen - 1 - aim);
            if (ad > 1.0E-3) {
                double speed = CHASE_SPEED * clamp(left / 1.2 + (routeReaches ? 0.25 : 0.0), 0.0, 1.0);
                want.set(ax, ay, az).mul(speed / ad);
                double hl = Math.sqrt(ax * ax + az * az);
                if (hl > 1.0E-3) heading.set(ax / hl, 0, az / hl);
            }
            run = left;
            // another urchin close ahead on its way: it waits behind it
            if (others != null) {
                for (Vector3d o : others) {
                    double ox = o.x - pos.x, oy = o.y - pos.y, oz = o.z - pos.z, od = Math.sqrt(ox * ox + oz * oz);
                    if (od < 1.0E-3 || od > QUEUE || Math.abs(oy) > 3.0) continue;
                    if ((ox * heading.x + oz * heading.z) / od > 0.5) { want.mul(0.15); break; }
                }
            }
        } else if (!caught && across > 1.0E-3) heading.set(gx / across, 0, gz / across);
        velocity.lerp(want, 0.3);
        // hardly going though it would: its way is found afresh
        if (caught || want.lengthSquared() < 1.0E-6) {
            chaseStuck = 0;
            chaseFrom.set(pos);
        } else if (++chaseStuck >= CHASE_STUCK) {
            if (chaseFrom.distance(pos) < CHASE_STUCK_MOVE) {
                stuckCount++;
                routeLen = 0;
            }
            chaseStuck = 0;
            chaseFrom.set(pos);
        }
        return velocity;
    }

    /** Given up the chase: it walks on its own way from where it is, the way it was going. */
    public void endChase() {
        state = State.WALK;
        planIn = 0;
        holdTicks = 0;
        stuckSet = false;
        routeLen = 0;
        caught = false;
    }

    /** Whether, chasing, it can still get nearer them: pressing in on them, its way reaches them, or at least takes it on. */
    public boolean gettingThere() {
        return caught || routeReaches || routeLen - routeAt > 2 || state != State.CHASE;
    }

    /** Chasing: whether it is pressing in on them; how many places its way has, and whether it reaches them. */
    public boolean caught() { return caught; }

    public int routeLength() { return routeLen; }

    public boolean routeReaches() { return routeReaches; }

    /** Its way's place {@code i} (its middle there), for checks. */
    public Vector3d routePlace(int i, Vector3d out) { return out.set(routeX[i], routeY[i], routeZ[i]); }

    private double horizontal(Vector3d pos, int i) {
        double dx = routeX[i] - pos.x, dz = routeZ[i] - pos.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** A place on its way, how it was come to and what it costs to get there; {@code f} its cost and what is left. */
    private static final class Node {
        final double x, y, z;
        double cost;
        Node from;
        boolean done;

        Node(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private record Open(Node node, double f) {}

    /** Where its middle would be in a column, stepping from a row (y NaN: nowhere), and what more it costs to go through for want of room. */
    private record Stand(double x, double y, double z, double cramped) {}

    private static final Stand NONE = new Stand(0.0, Double.NaN, 0.0, 0.0);
    /** Where in a column its middle may be, if not at the column's middle: nudged this far across it (blocks). */
    private static final double[][] NUDGE = {{0.25, 0}, {-0.25, 0}, {0, 0.25}, {0, -0.25}, {0.45, 0}, {-0.45, 0}, {0, 0.45}, {0, -0.45}};
    private final PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
    private final HashMap<Long, Node> nodes = new HashMap<>();
    private final HashMap<Long, Stand> stands = new HashMap<>();

    /**
     * Its way from {@code from} (its middle) to someone whose feet are at {@code goal}: a block at a time as it steps
     * ({@link #step}: never over a drop, up or down no steeper than it climbs, out of its ground; squeezing through
     * where it must, {@link #SQUEEZE}), the least costly (its length, its climbs, and more through cramped places:
     * {@link #ROOMY}), found by looking through at most {@link
     * #ROUTE_CELLS} places, nearest first toward them. If no way it finds reaches them, the way to the place it reached
     * nearest them (so that it still goes on toward them, or waits where it is nearest).
     */
    void route(Ground g, Vector3d from, Vector3d goal) {
        open.clear();
        nodes.clear();
        stands.clear();
        routes++;
        double gy = goal.y + STAND;
        Node start = new Node(from.x, from.y, from.z);
        nodes.put(cellKey(from.x, from.y, from.z), start);
        open.add(new Open(start, guess(start, goal, gy)));
        Node end = null, nearest = start;
        double nearestLeft = guess(start, goal, gy);
        int looked = 0;
        while (!open.isEmpty() && looked < ROUTE_CELLS) {
            Open o = open.poll();
            Node c = o.node;
            if (c.done) continue;
            c.done = true;
            looked++;
            double dx = goal.x - c.x, dz = goal.z - c.z;
            if (dx * dx + dz * dz <= REACHED * REACHED && Math.abs(c.y - gy) <= REACHED_UP) { end = c; break; }
            double left = guess(c, goal, gy);
            if (left < nearestLeft) { nearestLeft = left; nearest = c; }
            // its places are one in each column of blocks (its middle, or as near it as there is room for: see
            // standFrom), so that a way is found the same wherever it starts from: from where it is, the nearest nine
            boolean first = c == start;
            int cx = (int) Math.floor(c.x), cz = (int) Math.floor(c.z);
            for (int k = 0; k < (first ? 9 : 8); k++) {
                int bx = first ? cx + k % 3 - 1 : cx + WX[k], bz = first ? cz + k / 3 - 1 : cz + WZ[k];
                Stand st = standFrom(g, c.y, bx, bz);
                if (Double.isNaN(st.y) || Math.abs(st.y - c.y) > SLOPE) continue;
                long key = cellKey(bx, st.y, bz);
                Node n = nodes.get(key);
                if (n != null && n.done) continue;
                double length = Math.sqrt((st.x - c.x) * (st.x - c.x) + (st.z - c.z) * (st.z - c.z));
                double cost = c.cost + length + CLIMB_COST * Math.abs(st.y - c.y) + st.cramped;
                if (n != null && cost >= n.cost) continue;
                if (n == null) {
                    n = new Node(st.x, st.y, st.z);
                    nodes.put(key, n);
                }
                n.cost = cost;
                n.from = c;
                open.add(new Open(n, cost + guess(n, goal, gy)));
            }
        }
        routeCells += looked;
        routeMost = Math.max(routeMost, looked);
        routeReaches = end != null;
        Node last = end != null ? end : nearest;
        int n = 0;
        for (Node k = last; k != null; k = k.from) n++;
        if (routeX.length < n) {
            routeX = new double[n * 2];
            routeY = new double[n * 2];
            routeZ = new double[n * 2];
        }
        int i = n - 1;
        for (Node k = last; k != null; k = k.from, i--) {
            routeX[i] = k.x;
            routeY[i] = k.y;
            routeZ[i] = k.z;
        }
        routeLen = n;
        routeAt = 0;
    }

    /** How far a place is from them, as its way will likely go: across, and up or down past what its way's end allows. */
    private static double guess(Node c, Vector3d goal, double gy) {
        double dx = goal.x - c.x, dz = goal.z - c.z;
        return Math.sqrt(dx * dx + dz * dz) + 0.5 * Math.max(0.0, Math.abs(c.y - gy) - REACHED_UP);
    }

    /**
     * Where its middle would be in column (bx, bz), stepping there from row {@code y} ({@link #step}): at the column's
     * middle if there is room ({@link #CLEAR}), else nudged across it to where there is ({@link #NUDGE}: past a bump
     * in a wall, off a tight corner), else where it could squeeze through ({@link #SQUEEZE}, its box clear too); and
     * what more that costs for want of room. Each worked out once a way.
     */
    private Stand standFrom(Ground g, double y, int bx, int bz) {
        long key = cellKey(bx, y, bz);
        Stand st = stands.get(key);
        if (st != null) return st;
        st = NONE;
        double mx = bx + 0.5, mz = bz + 0.5;
        // its height over the floor is the column's own wherever in it it is
        double ny = standAt(g, mx, y, mz);
        if (!Double.isNaN(ny) && g.allowed(mx, ny, mz)) {
            Stand squeeze = null;
            for (int k = -1; k < NUDGE.length; k++) {
                double x = k < 0 ? mx : mx + NUDGE[k][0], z = k < 0 ? mz : mz + NUDGE[k][1];
                if (clear(g, x, ny, z, CLEAR)) {
                    st = new Stand(x, ny, z, clear(g, x, ny, z, ROOMY) ? 0.0 : TIGHT_COST);
                    break;
                }
                if (squeeze == null && clear(g, x, ny, z, SQUEEZE) && boxClear(g, x, ny, z, BOX)) squeeze = new Stand(x, ny, z, SQUEEZE_COST + TIGHT_COST);
            }
            if (st == NONE && squeeze != null) st = squeeze;
        }
        stands.put(key, st);
        return st;
    }

    /** For /remix ember: what it is doing, in words. */
    public String describe() {
        return switch (state) {
            case WALK -> String.format(java.util.Locale.ROOT, "walking (%.1f ahead), floor %.1f under, roof %.1f over, walls %.1f and %.1f",
                    run, floorGap, roofGap, leftGap, rightGap);
            case REST -> "resting (" + restTicks + " ticks)";
            case HOLD -> "held (something in its way, " + holdTicks + " ticks)";
            case ROUSE -> "rousing";
            case CHASE -> caught ? "chasing: pressing in" : String.format(java.util.Locale.ROOT, "chasing: %.1f to go, its way %d places%s (%d ways found, at most %d places looked through)",
                    run, routeLen, routeReaches ? "" : ", not reaching them", routes, routeMost);
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
