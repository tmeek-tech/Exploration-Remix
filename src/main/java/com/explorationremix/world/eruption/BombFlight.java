package com.explorationremix.world.eruption;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Where a lava bomb goes and how it is thrown (0.9.0, spec 5.4.2). Pure (no game types), so it is tested offline with
 * the projectile's own order of a tick ({@code ThrowableProjectile.tick}): the hit test on the segment from the
 * position to the position plus the motion, then {@code next = pos + v}, {@code v *= 0.99}, {@code v.y -= 0.05},
 * {@code pos = next}. Heights here are counted from the launch point, which is half a block over the vent's mouth.
 *
 * <ul>
 *     <li><b>Ring bombs</b> land at a uniform angle round the vent, {@code Dmin + (40 - Dmin) u^0.8} blocks from its
 *     axis, {@code Dmin} its stack's foot radius and 6: never back on their own stack or in its crater.</li>
 *     <li><b>Near bombs</b> ({@code bombsNear} of each eruption) come down {@code nearMinBlocks} to
 *     {@code nearMaxBlocks} from a player in the Caldera who is on the ground within 48 blocks of the vent and not on a
 *     refuge ({@link #refuge}: a stack top, a ledge, a strip of shore by the lava), drawn evenly over that ring, in the
 *     same band from the vent; with no such player, or no draw that flies, they are ring bombs.</li>
 *     <li>The bomb rises to an apex of 16 to 30 blocks (by the square root of the eruption's strength), no higher than
 *     the room over the mouth less 4; with less than 12 the bomb is not thrown. Its upward speed is found so the
 *     flight's highest point is the apex, its time to the ground's drop from the simulated fall, and its speed across
 *     from the drag's sum over that time ({@link #solve}). Coming back down past the rim it must be clear of the stack
 *     by 1.5 blocks, or its target moves a block out and it is solved again, at most 10 times ({@link #aim}).</li>
 *     <li>An eruption's bombs go 40 % in the first second of its burst and the rest through the rest of it, at most two
 *     a tick ({@link #launches}).</li>
 * </ul>
 */
public final class BombFlight {
    private BombFlight() {}

    /**
     * The bomb's own gravity ({@code LavaBomb.getGravity}, on both sides, so the client's flight matches) and the
     * projectile's drag, as the game's floats widen to doubles.
     */
    public static final double GRAVITY = 0.05F, DRAG = 0.99F;
    /** The ring's outer edge, blocks from the axis, and how far past the stack's foot its inner edge lies. */
    public static final double RING_MAX = 40, RING_IN = 6;
    /** The apex's draw (times the square root of the strength), its least, and the room kept over it. */
    public static final double APEX_LO = 16, APEX_HI = 30, APEX_MIN = 12, ROOM_OVER = 4;
    /** Passing back down through the mouth plus this many rows, the bomb is this far clear of the stack's foot radius. */
    public static final double CLEAR_ROWS = 3, CLEAR = 1.5;
    /** Times the target moves out for the clearance before the bomb is given up. */
    public static final int CLEAR_TRIES = 10;
    /**
     * How far across from a vent's axis an eruption's bombs come down at most: the ring's edge, the clearance's moves
     * outward, and a block for the blast's centre (the square of this reach round the vent is where its blasts ask for a
     * way down).
     */
    public static final double REACH = RING_MAX + CLEAR_TRIES + 2;
    /** Draws of a near bomb's target before it is a ring bomb. */
    public static final int NEAR_DRAWS = 6;
    /** A player farther than this across from the vent takes no near bomb. */
    public static final double NEAR_REACH = 48;
    /** The refuge test looks this many blocks round the feet; a drop of more than this many rows is a ledge, and lava up to this many under the feet is a shore. */
    public static final int REFUGE_REACH = 3, REFUGE_DROP = 4;
    /** The longest flight simulated, ticks: a bomb lives this long, then fizzles. */
    public static final int MAX_TICKS = 400;
    /** Of an eruption's bombs, this share goes in the first {@link #HEAD_TICKS} ticks of its burst. */
    public static final double HEAD_SHARE = 0.4;
    public static final int HEAD_TICKS = 20;
    /** At most this many bombs are thrown in one tick. */
    public static final int PER_TICK = 2;
    /**
     * Draws of a ring bomb's target (the first and up to 7 more) before it is not thrown: more than the 5 of spec 5.4.2,
     * since a draw is now also refused when its flight would hit the rock over the vent or come down by a refuge.
     */
    public static final int RING_DRAWS = 8;
    /**
     * Apexes tried for one target ({@link #aimUnder}): the drawn one and, when its flight hits what hangs over the vent,
     * lower ones evenly down to {@link #APEX_MIN}.
     */
    public static final int UNDER_TRIES = 3;

    /** A solved flight: the launch velocity, the ticks to the ground (with the fraction of the last), and where it lands, from the axis. */
    public record Flight(double vx, double vy, double vz, double ticks, double tx, double tz) {
        public double speed() { return Math.sqrt(vx * vx + vy * vy + vz * vz); }
    }

    /** How far the ground lies under the launch point at a target (x, z) from the axis, in blocks (more is lower). */
    @FunctionalInterface
    public interface Drop {
        double at(double tx, double tz);
    }

    // ================================================================================================ the flight

    /** The highest the bomb rises over the launch point when thrown up at {@code vy}, in the projectile's steps. */
    public static double apexOf(double vy) {
        double y = 0, best = 0;
        for (int k = 0; k < MAX_TICKS && vy > 0; k++) {
            y += vy;
            vy = vy * DRAG - GRAVITY;
            best = Math.max(best, y);
        }
        return best;
    }

    /** The upward speed whose flight's apex is {@code a} blocks (by bisection). */
    public static double vyForApex(double a) {
        double lo = 0, hi = 6;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (apexOf(mid) < a) lo = mid;
            else hi = mid;
        }
        return (lo + hi) / 2;
    }

    /**
     * Ticks until a bomb thrown up at {@code vy} comes down through {@code -h} (h blocks under the launch point), with
     * the fraction of the last tick; NaN when it does not within {@link #MAX_TICKS}.
     */
    public static double ticksToDrop(double vy, double h) {
        double y = 0;
        for (int k = 0; k < MAX_TICKS; k++) {
            double next = y + vy;
            if (vy < 0 && next <= -h) return k + (y + h) / (y - next);
            y = next;
            vy = vy * DRAG - GRAVITY;
        }
        return Double.NaN;
    }

    /** How far a bomb moving across at 1 block a tick has gone after {@code t} ticks (the drag's sum, the last tick in part). */
    public static double across(double t) {
        int k = (int) Math.floor(t);
        double f = t - k, p = Math.pow(DRAG, k);
        return (1 - p) / (1 - DRAG) + f * p;
    }

    /**
     * The flight from the launch point, at (ox, oz) from the axis, to the target (tx, tz) from the axis, its ground
     * {@code h} blocks under the launch point, rising {@code a} blocks: null when it does not come down there.
     */
    @Nullable
    public static Flight solve(double ox, double oz, double tx, double tz, double h, double a) {
        double vy = vyForApex(a);
        double t = ticksToDrop(vy, h);
        if (Double.isNaN(t) || t <= 0) return null;
        double dx = tx - ox, dz = tz - oz, d = Math.sqrt(dx * dx + dz * dz);
        double vh = d / across(t);
        return d < 1e-9 ? new Flight(0, vy, 0, t, tx, tz) : new Flight(dx / d * vh, vy, dz / d * vh, t, tx, tz);
    }

    /**
     * The tick (with its fraction) a flight thrown up at {@code vy} comes back down through {@code y} blocks over the
     * launch point, or NaN when it never rises that high.
     */
    public static double ticksDownThrough(double vy, double y) {
        double py = 0;
        for (int k = 0; k < MAX_TICKS; k++) {
            double next = py + vy;
            if (vy < 0 && py >= y && next < y) return k + (py - y) / (py - next);
            py = next;
            vy = vy * DRAG - GRAVITY;
        }
        return Double.NaN;
    }

    /**
     * {@link #solve} with the stack's clearance: where the bomb comes back down through {@link #CLEAR_ROWS} over the mouth
     * ({@code clearY} blocks over the launch point) it must be at least {@code stackR + 1.5} from the axis, else the
     * target moves a block outward from the axis and the flight is solved again, at most {@link #CLEAR_TRIES} times.
     * Null when no flight clears.
     */
    @Nullable
    public static Flight aim(double ox, double oz, double tx, double tz, Drop drop, double a, double stackR, double clearY) {
        double d = Math.sqrt(tx * tx + tz * tz);
        double ux = d < 1e-9 ? 1 : tx / d, uz = d < 1e-9 ? 0 : tz / d;
        for (int i = 0; i <= CLEAR_TRIES; i++) {
            double x = ux * (d + i), z = uz * (d + i);
            Flight f = solve(ox, oz, x, z, drop.at(x, z), a);
            if (f == null) continue;
            double tc = ticksDownThrough(f.vy, clearY);
            if (Double.isNaN(tc)) return f;
            double s = across(tc), cx = ox + f.vx * s, cz = oz + f.vz * s;
            if (Math.sqrt(cx * cx + cz * cz) >= stackR + CLEAR) return f;
        }
        return null;
    }

    /** Whether a solved flight may be thrown: it clears what hangs over the vent, and comes down where it may. */
    @FunctionalInterface
    public interface Accept {
        boolean test(Flight f);
    }

    /**
     * {@link #aim} for the target (tx, tz) at apex {@code a}, kept when {@code accept} takes it; else at lower apexes,
     * evenly down to {@link #APEX_MIN} ({@link #UNDER_TRIES} in all), so a bomb thrown under rock hanging over its vent
     * flies out under it rather than bursting in it. Null when no apex gives a flight {@code accept} takes.
     */
    @Nullable
    public static Flight aimUnder(double ox, double oz, double tx, double tz, Drop drop, double a, double stackR, double clearY, Accept accept) {
        for (int i = 0; i < UNDER_TRIES; i++) {
            double ai = i == 0 ? a : a - (a - APEX_MIN) * i / (UNDER_TRIES - 1);
            if (i > 0 && ai >= a - 1e-9) break;
            Flight f = aim(ox, oz, tx, tz, drop, ai, stackR, clearY);
            if (f != null && accept.test(f)) return f;
        }
        return null;
    }

    /**
     * Whether a flight thrown from (x, y, z) clears what hangs over its vent (spec 5.4.3's overhangs, which the room
     * straight up the axis does not see): flown through {@code g} until it comes back down through {@code over} blocks
     * over the launch point, it neither bursts on a block nor fizzles in a fluid above row {@code row} (the mouth's).
     * What it meets lower, past the stack, is its landing.
     */
    public static boolean clearsOver(Grid g, double x, double y, double z, Flight f, double over, int row) {
        double t = ticksDownThrough(f.vy, over);
        int n = (int) Math.ceil(Double.isNaN(t) ? f.ticks : t);
        int[] cell = new int[3];
        int out = fly(g, new double[]{x, y, z}, new double[]{f.vx, f.vy, f.vz}, Math.min(MAX_TICKS, Math.max(1, n)), cell);
        return out == FLYING || cell[1] <= row;
    }

    /** The ring's inner edge round a stack of foot radius {@code stackR}. */
    public static double ringMin(double stackR) { return stackR + RING_IN; }

    /** A ring bomb's distance from the axis, from a uniform {@code u}. */
    public static double ringDistance(double stackR, double u) {
        double dmin = ringMin(stackR);
        return dmin + (RING_MAX - dmin) * Math.pow(u, 0.8);
    }

    /** The apex for an eruption of strength {@code s} with {@code room} open rows over the mouth, from a uniform {@code u}; NaN when too low to throw. */
    public static double apex(double s, int room, double u) {
        double a = Math.min((APEX_LO + (APEX_HI - APEX_LO) * u) * Math.sqrt(Math.max(0, s)), room - ROOM_OVER);
        return a < APEX_MIN ? Double.NaN : a;
    }

    /** A point drawn evenly over the ring {@code min} to {@code max} round the origin: {dx, dz}. */
    public static double[] nearDraw(double min, double max, Random r) {
        double lo = Math.min(min, Math.max(min, max)), hi = Math.max(min, max);
        double d = Math.sqrt(lo * lo + r.nextDouble() * (hi * hi - lo * lo)), ang = r.nextDouble() * Math.PI * 2;
        return new double[]{d * Math.cos(ang), d * Math.sin(ang)};
    }

    /**
     * When an eruption's {@code n} bombs go, in ticks from the start of its burst of {@code burst} ticks, sorted:
     * {@link #HEAD_SHARE} of them (rounded) at random in the first {@link #HEAD_TICKS} (the burst itself), the rest at
     * random through the rest of it, at most {@link #PER_TICK} in a tick: a bomb whose tick is full goes in the next
     * tick that is not (round to the burst's start), and one that finds none is not thrown.
     */
    public static int[] launches(int n, int burst, Random r) {
        if (n <= 0 || burst <= 0) return new int[0];
        int head = Math.min(HEAD_TICKS, burst), first = (int) Math.round(n * HEAD_SHARE);
        int[] count = new int[burst];
        int thrown = 0;
        for (int i = 0; i < n; i++) {
            int t = i < first || head >= burst ? r.nextInt(head) : head + r.nextInt(burst - head);
            for (int k = 0; k < burst; k++) {
                int u = (t + k) % burst;
                if (count[u] < PER_TICK) {
                    count[u]++;
                    thrown++;
                    break;
                }
            }
        }
        int[] out = new int[thrown];
        int j = 0;
        for (int t = 0; t < burst; t++) for (int k = 0; k < count[t]; k++) out[j++] = t;
        return out;
    }

    // ================================================================================================ near bombs

    /** Whether a column (x, z) from the axis may take a bomb now (in the game: entity-ticking). */
    @FunctionalInterface
    public interface Ticking {
        boolean at(double tx, double tz);
    }

    /**
     * A near bomb for a player whose feet stand at (px, pz) from the axis: up to {@link #NEAR_DRAWS} targets drawn
     * {@code min} to {@code max} from them, each kept when it lies in the ring's band from the axis, its column may take a
     * bomb, its flight clears the stack and the target it then lands on is still {@code min} to {@code max} from the
     * player. Null when none is: the bomb is a ring bomb.
     */
    @Nullable
    public static Flight near(double ox, double oz, double px, double pz, double min, double max, double stackR, Drop drop, Ticking ticking,
                              double a, double clearY, Random r) {
        return near(ox, oz, px, pz, min, max, stackR, drop, ticking, a, clearY, r, f -> true);
    }

    /**
     * {@link #near} with {@code accept} also asked of each flight ({@link #aimUnder}: at the drawn apex, then lower ones),
     * so a near bomb comes down by a player on the far side of rock hanging over the vent.
     */
    @Nullable
    public static Flight near(double ox, double oz, double px, double pz, double min, double max, double stackR, Drop drop, Ticking ticking,
                              double a, double clearY, Random r, Accept accept) {
        double lo = Math.min(min, max), hi = Math.max(min, max);
        for (int i = 0; i < NEAR_DRAWS; i++) {
            double[] o = nearDraw(lo, hi, r);
            double tx = px + o[0], tz = pz + o[1], d = Math.sqrt(tx * tx + tz * tz);
            if (d < ringMin(stackR) || d > RING_MAX || !ticking.at(tx, tz)) continue;
            Flight f = aimUnder(ox, oz, tx, tz, drop, a, stackR, clearY, g -> {
                double fx = g.tx - px, fz = g.tz - pz, fd = Math.sqrt(fx * fx + fz * fz);
                return fd >= lo - 1e-9 && fd <= hi + 1e-9 && ticking.at(g.tx, g.tz) && accept.test(g);
            });
            if (f != null) return f;
        }
        return null;
    }

    // ================================================================================================ the refuge test

    /** What a cell round a player's feet holds, for the refuge test. */
    public static final int OPEN = 0, LAVA = 1, SOLID = 2;

    /** The cells round a player's feet: what (dx, dy, dz) from the feet cell holds ({@link #OPEN}, {@link #LAVA} or {@link #SOLID}). */
    @FunctionalInterface
    public interface Cells {
        int at(int dx, int dy, int dz);
    }

    /**
     * Whether the player whose feet cell {@code c} reads from stands on a refuge, where a near bomb must not blow the
     * ground away: within {@link #REFUGE_REACH} blocks of the feet a column drops more than {@link #REFUGE_DROP} rows (a
     * stack top, a ledge), or lava lies at the feet's row or up to {@link #REFUGE_DROP} rows under it (a strip of shore
     * by the lake or the risen sea; a player who climbed out of the lava's way).
     */
    public static boolean refuge(Cells c) {
        for (int dx = -REFUGE_REACH; dx <= REFUGE_REACH; dx++) {
            for (int dz = -REFUGE_REACH; dz <= REFUGE_REACH; dz++) {
                for (int dy = 0; dy >= -REFUGE_DROP; dy--) if (c.at(dx, dy, dz) == LAVA) return true;
                if (c.at(dx, 0, dz) == SOLID) continue;
                boolean drop = true;
                for (int dy = -1; dy >= -(REFUGE_DROP + 1); dy--) {
                    if (c.at(dx, dy, dz) == SOLID) {
                        drop = false;
                        break;
                    }
                }
                if (drop) return true;
            }
        }
        return false;
    }

    // ================================================================================================ through the cells

    /** What a cell of the world holds, for a bomb's own two hit tests (open, a fluid, or a block it hits). */
    @FunctionalInterface
    public interface Grid {
        /** {@link #OPEN}, {@link #LAVA} (any fluid with no collision) or {@link #SOLID}. */
        int at(int x, int y, int z);
    }

    /** How a flight through a grid ends: still flying, in a fluid (it fizzles, no blast), or on a block (it bursts). */
    public static final int FLYING = 0, FIZZLE = 1, BURST = 2;

    /**
     * Flies a bomb from {@code pos} at {@code v} through {@code g} for at most {@code ticks} ticks, as the entity does
     * each tick: first its own fluid test on the tick's segment (the first cell that is not open, fluids included; a fluid
     * there makes it fizzle and the tick ends at once), then the game's hit test, which passes through fluids, on the same
     * segment (the first solid cell bursts it), then its move. Returns {@link #FIZZLE}, {@link #BURST} or {@link #FLYING},
     * with the cell it ended in written to {@code cell}.
     */
    public static int fly(Grid g, double[] pos, double[] v, int ticks, int[] cell) {
        double x = pos[0], y = pos[1], z = pos[2], vx = v[0], vy = v[1], vz = v[2];
        for (int k = 0; k < ticks; k++) {
            int hit = first(g, x, y, z, x + vx, y + vy, z + vz, true, cell);
            if (hit == LAVA) return FIZZLE;
            hit = first(g, x, y, z, x + vx, y + vy, z + vz, false, cell);
            if (hit == SOLID) return BURST;
            x += vx;
            y += vy;
            z += vz;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
            vz *= DRAG;
        }
        return FLYING;
    }

    /**
     * The first cell on the segment from (x0, y0, z0) to (x1, y1, z1) that is solid, or with {@code fluids} also one
     * that holds a fluid: its kind, with the cell in {@code cell}; {@link #OPEN} when there is none. The cells are
     * walked in the order the segment enters them.
     */
    static int first(Grid g, double x0, double y0, double z0, double x1, double y1, double z1, boolean fluids, int[] cell) {
        int x = (int) Math.floor(x0), y = (int) Math.floor(y0), z = (int) Math.floor(z0);
        int ex = (int) Math.floor(x1), ey = (int) Math.floor(y1), ez = (int) Math.floor(z1);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0, sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dx), tdy = sy == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dy),
                tdz = sz == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / dz);
        double tx = sx == 0 ? Double.POSITIVE_INFINITY : (sx > 0 ? (x + 1 - x0) : (x0 - x)) * tdx;
        double ty = sy == 0 ? Double.POSITIVE_INFINITY : (sy > 0 ? (y + 1 - y0) : (y0 - y)) * tdy;
        double tz = sz == 0 ? Double.POSITIVE_INFINITY : (sz > 0 ? (z + 1 - z0) : (z0 - z)) * tdz;
        for (int n = 0; n < 64; n++) {
            int k = g.at(x, y, z);
            if (k == SOLID || fluids && k == LAVA) {
                cell[0] = x;
                cell[1] = y;
                cell[2] = z;
                return k;
            }
            if (x == ex && y == ey && z == ez) break;
            if (tx <= ty && tx <= tz) {
                if (tx > 1) break;
                x += sx;
                tx += tdx;
            } else if (ty <= tz) {
                if (ty > 1) break;
                y += sy;
                ty += tdy;
            } else {
                if (tz > 1) break;
                z += sz;
                tz += tdz;
            }
        }
        return OPEN;
    }
}
