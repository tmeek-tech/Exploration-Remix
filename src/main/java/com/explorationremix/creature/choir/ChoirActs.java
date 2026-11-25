package com.explorationremix.creature.choir;

import com.explorationremix.world.feature.ChoirArena;
import com.explorationremix.world.terrain.ChoirSites;

/**
 * The clock of every blow the Hollow Choir strikes (0.22.3): how long each warns, when it lands, where it reaches and
 * what gets a player out of it. The fight and the drawing both read these and nothing else, so what a player is shown
 * and what hurts them cannot drift apart: the ring on the floor is the ring that strikes, to the tick.
 *
 * <p>Every blow is built the same way, because the mod has no dodge roll, no parry and no stamina: a warning that
 * says <em>where</em> and counts down to <em>when</em>, long enough to act on with nothing but walking, jumping and
 * a sword, and one plain way out of each.
 * <ul>
 * <li><b>Slam.</b> A tendril pulls free of the ceiling and rears over a mark on the floor, the mark fills, and it
 * comes down. Out: leave the circle, or land a strong attack on a tendril while it rears, which spoils it. And a
 * slam leaves an opening: the tendril's head lies on its mark for a second after, and a blow on it there counts for
 * more than one on its root.
 * <li><b>Toll.</b> A ring of light drops from the crown, lands round the dais and runs out over the floor. Out: jump
 * it as it passes, or stand behind anything solid, which it does not pass (the bowl's broken pillars are for this).
 * <li><b>Grab.</b> Ripples run in over the floor to the dais, then everyone is drawn in after them. It does not hurt;
 * it puts you where the next blow wants you. Out: nothing but a pillar between you and the dais.
 * <li><b>Bloom.</b> Every block of sculk in the bowl lights and brightens, then pulses. Out: be on clean stone.
 * </ul>
 *
 * <p>All of it is plain sums, so it can be tested without the game.
 */
public final class ChoirActs {
    private ChoirActs() {}

    // ----------------------------------------------------------------------------------------------------- the slam

    /**
     * The blow lands this many ticks after the wind-up ends, and the tendril's head lies where it struck until the
     * second number, when it lifts away and the tendril is free again. That last second is the opening a slam leaves:
     * the head can be struck while it lies there ({@link #HEAD_W}).
     */
    public static final int SLAM_AT = 6, SLAM_END = 24;
    /**
     * A slam's circle on the floor, how far over and under its mark it reaches, and how far from its root a tendril
     * can bring one down.
     */
    public static final double SLAM_R = 3.6, SLAM_TALL = 2.5, SLAM_REACH = 17;
    /**
     * The head as it lies on its mark after a slam: a box this wide and tall, which a sword reaches from just outside
     * the circle, and a blow on it counts this much over what the same blow does to the tendril's root.
     */
    public static final float HEAD_W = 2.4F, HEAD_H = 1.8F, HEAD_BONUS = 1.5F;
    /** Of the wind-up, the share the tendril takes to rear right over its mark; it hangs there for the rest. */
    public static final double REAR_SHARE = 0.6;

    /**
     * How long a tendril rears before it strikes, by phase: 1.3 seconds, then 1.1, then 0.9, and {@link #SLAM_AT}
     * ticks more for the stroke itself. A player in the middle of the circle walks out of it in 0.85 seconds and
     * sprints out in 0.65, so the last phase has to be run from and the first can be walked.
     */
    public static int windUp(int phase) { return phase >= 3 ? 18 : phase == 2 ? 22 : 26; }

    /**
     * How full a slam's mark is, 0 as the tendril starts to rear and 1 the tick the blow lands: the thing a player
     * reads the "when" from. {@code tick} is the act's own clock, and {@code striking} whether the wind-up is over.
     */
    public static float slamFill(int phase, boolean striking, float tick) {
        float all = windUp(phase) + SLAM_AT;
        float at = striking ? windUp(phase) + tick : tick;
        return Math.max(0, Math.min(1, at / all));
    }

    /** How far a tendril has reared over its mark at a tick of the wind-up, 0 standing to 1 right over. */
    public static float rear(int phase, float tick) {
        return (float) Math.max(0, Math.min(1, tick / (windUp(phase) * REAR_SHARE)));
    }

    /**
     * How far the head has come down on its mark at a tick of the stroke, 0 hanging over it to 1 on it: slow off the
     * top and fastest as it lands, and on the mark exactly at {@link #SLAM_AT}.
     */
    public static float plunge(float tick) {
        float u = Math.max(0, Math.min(1, tick / SLAM_AT));
        return u * u;
    }

    /** Whether a slam strikes a player {@code dist} from its mark across the floor and {@code up} over it. */
    public static boolean slamStrikes(double dist, double up) {
        return dist <= SLAM_R && Math.abs(up) <= SLAM_TALL;
    }

    // ------------------------------------------------------------------------------------------------ being thrown

    /**
     * How a slam throws whoever it strikes, blocks a tick along the floor and up: three and a half blocks in a hop a
     * block high, which from the middle of the circle is out to its edge, where the head it leaves lying can be
     * struck. {@link #TOLL_THROW} is a toll's, which is two blocks, and {@link #GRAB_LIFT} is how the grab lifts
     * whoever it draws in, enough to carry them over a brazier. None of them lifts anyone high enough for the landing
     * to hurt: a blow's hurt is the blow's and nothing else.
     *
     * <p>These are what a player is thrown at, to the number, and the fight sees to that itself (see
     * {@code ChoirSummon.fling}). 0.22.2 left its pushes for the game to pass on, and the game passes a push on a tick
     * late and half worn away, so its grab drew a player two blocks where five were meant; and its slam threw a
     * player who had not moved straight up, on top of the blow's own knock, and the fall hurt them again.
     */
    public static final double SLAM_THROW = 0.7, SLAM_LIFT = 0.36, TOLL_THROW = 0.45, TOLL_LIFT = 0.3, GRAB_LIFT = 0.3;
    /**
     * A slam throws a player out of its circle the way they stand from its middle. In the middle itself, where the
     * mark was laid under them and they never moved, there is no such way, so they go on along the way the tendril
     * came down: each is thrown as if they stood this far beyond the middle on that line.
     */
    public static final double SLAM_ALONG = 0.5;
    /**
     * Of a throw along the floor, the share a player already in the air is given. The floor takes nearly half of a
     * throw's speed in its first tick and the air a tenth, so the same throw would carry a jumping player half as far
     * again as a standing one.
     */
    public static final double AIR_SHARE = 0.65;

    // ----------------------------------------------------------------------------------------------------- the toll

    /** How long the ring takes to drop from the crown to the floor, and how long after one ring the next leaves. */
    public static final int TOLL_TELL = 20, TOLL_GAP = 14;
    /**
     * Where a ring lands and where it is spent: the outer edge of the dais's ring of chiselled stone, and the foot of
     * the seats. Inside it is the eye: a player there is out of every toll and in the middle of every slam. It lands
     * outside the three braziers that stand on the dais ring on purpose: a ring stops at anything solid, and three
     * things standing right where it lands would each have cut a clear lane out of it all the way to the seats.
     */
    public static final double TOLL_R0 = ChoirArena.DAIS_RING, TOLL_R1 = ChoirSites.SEAT_R0 + 0.5;
    /** The ring leaves the crown this wide, and widens to {@link #TOLL_R0} as it drops. */
    public static final double TOLL_CROWN_R = 2.25;
    /** A ring passes under feet this far off the floor: a jump clears it, a step up clears it, a hop does not. */
    public static final double TOLL_OVER = 0.3;
    /** The floor round the dais is cut into this many slices to find what stands in a ring's way. */
    public static final int TOLL_SLICES = 128;

    /** How fast a ring runs out over the floor, blocks a tick: 11 blocks a second, then 13, then 15. */
    public static double tollSpeed(int phase) { return phase >= 3 ? 0.75 : phase == 2 ? 0.65 : 0.55; }

    /** One ring in the first phase, two after it. */
    public static int tollRings(int phase) { return phase >= 2 ? 2 : 1; }

    /**
     * Half the width of the band that strikes. It is wide enough that a ring cannot step over a player between two
     * ticks, even one running in through it: half a tick of the ring's own run and half a tick of a sprint, and a
     * little over.
     */
    public static double tollHalf(int phase) { return 0.3 + tollSpeed(phase) / 2; }

    /**
     * How far out ring {@code ring} of a toll is at a tick of the act (fractions of a tick for the drawing), or NaN
     * while it has not landed yet or once it is spent.
     */
    public static double tollRadius(int phase, int ring, double tick) {
        double since = tick - TOLL_TELL - ring * (double) TOLL_GAP;
        if (since < 0) return Double.NaN;
        double r = TOLL_R0 + since * tollSpeed(phase);
        return r > TOLL_R1 ? Double.NaN : r;
    }

    /**
     * How far ring {@code ring} of a toll has dropped from the crown at a tick of the act, 0 as it leaves and 1 as it
     * lands, or NaN before it leaves and once it is down.
     */
    public static double tollDrop(int ring, double tick) {
        double u = (tick - ring * (double) TOLL_GAP) / TOLL_TELL;
        return u < 0 || u >= 1 ? Double.NaN : u;
    }

    /** The tick of the act by which every ring of a toll is spent. */
    public static int tollEnd(int phase) {
        return TOLL_TELL + (tollRings(phase) - 1) * TOLL_GAP + (int) Math.ceil((TOLL_R1 - TOLL_R0) / tollSpeed(phase)) + 1;
    }

    /** The slice of the floor a point lies in, counted round from east. */
    public static int tollSlice(double dx, double dz) {
        double a = Math.atan2(dz, dx);
        if (a < 0) a += Math.PI * 2;
        return Math.min(TOLL_SLICES - 1, (int) (a / (Math.PI * 2) * TOLL_SLICES));
    }

    /** The way the middle of a slice lies. */
    public static double sliceAngle(int slice) { return (slice + 0.5) * Math.PI * 2 / TOLL_SLICES; }

    /**
     * Whether a ring at {@code radius} strikes a player {@code dist} from the dais's middle whose feet are
     * {@code up} over the floor, with the nearest solid thing in their slice {@code wall} out (anything past
     * {@link #TOLL_R1} for nothing).
     */
    public static boolean tollStrikes(int phase, double radius, double dist, double up, double wall) {
        if (Double.isNaN(radius) || Math.abs(dist - radius) > tollHalf(phase)) return false;
        if (up > TOLL_OVER) return false;
        return dist <= wall;
    }

    // ----------------------------------------------------------------------------------------------------- the grab

    /** The ripples run in for this long, then the pull; the act is over at the second number. */
    public static final int GRAB_TELL = 20, GRAB_END = 36;
    /** Three ripples, this many ticks apart, each this long on its way in. */
    public static final int GRAB_RIPPLES = 3, GRAB_RIPPLE_GAP = 4, GRAB_RIPPLE_RUN = 12;

    /** How far out ripple {@code k} is at a tick of the act, or NaN while it is not running: from the rim in. */
    public static double grabRipple(int k, double tick) {
        double u = (tick - k * (double) GRAB_RIPPLE_GAP) / GRAB_RIPPLE_RUN;
        if (u < 0 || u > 1) return Double.NaN;
        return ChoirSites.RIM_R - (ChoirSites.RIM_R - GRAB_TO) * u * u;
    }

    /** Nobody is drawn nearer the dais's middle than this: it is where the ripples end. */
    public static final double GRAB_TO = 2.5;

    /**
     * How hard the grab draws a player who stands {@code dist} from the dais's middle, blocks a tick. Harder the
     * further out, so the seats and the rim are no place to wait it out, and eased off close in, so nobody is thrown
     * through the dais and out of the far side of it.
     */
    public static double grabPull(double dist) {
        double full = 1.15 + 0.04 * Math.max(0, dist - ChoirSites.SEAT_R0);
        return Math.max(0, Math.min(full, (dist - GRAB_TO) / 5.5));
    }

    // ---------------------------------------------------------------------------------------------------- the bloom

    /** The sculk brightens for this long, then pulses; the act is over at the second number. */
    public static final int BLOOM_TELL = 24, BLOOM_END = 44;

    /** How brightly the sculk is lit at a tick of a bloom: a slow swell, the pulse itself at full, then dying off. */
    public static float bloomLight(float tick) {
        if (tick < 0) return 0;
        if (tick < BLOOM_TELL) {
            float u = tick / BLOOM_TELL;
            // it throbs as it swells, and the throb quickens: the last beats before the pulse are the countdown
            float throb = 0.5F + 0.5F * (float) Math.sin(u * u * 28.0);
            return 0.10F + 0.45F * u * u + 0.18F * u * throb;
        }
        float after = (tick - BLOOM_TELL) / (BLOOM_END - BLOOM_TELL);
        return Math.max(0, 1.0F - after * 1.6F);
    }

    // ------------------------------------------------------------------------------------------------- the entrance

    /**
     * How long the Choir takes to come down out of the dark when it is called, which is also how long it holds its
     * first blow. Its tendrils grow down to their roots one after another while it does: the first starts at
     * {@link #GROW_FIRST}, each next {@link #GROW_GAP} later, and each takes {@link #GROW_TICKS} to reach the floor.
     */
    public static final int ENTER_TICKS = 80, GROW_FIRST = 8, GROW_GAP = 8, GROW_TICKS = 24;
    /** A cut tendril whips back up into the ceiling in this long. */
    public static final int PART_TICKS = 14;

    /** The tick of the entrance at which a place's tendril starts down. */
    public static int growStart(int place) { return GROW_FIRST + place * GROW_GAP; }

    /**
     * How far the arena's own ground stands over the bowl's floor at a point measured from the dais's middle: nothing
     * on the floor, a step of the seats, a ramp on its way up to the rim (they run on the quarter turns off the first
     * socket, which is what {@code turn} is for), or the rim itself. It is {@link ChoirArena#topY} without a site, so
     * the drawing can lay a ripple on the ground from what little the player's game is told.
     */
    public static double groundUp(double turn, double dx, double dz) {
        double r = Math.hypot(dx, dz);
        for (int i = 0; i < 2; i++) {
            double a = turn + Math.PI / 2 + i * Math.PI, cos = Math.cos(a), sin = Math.sin(a);
            double u = dx * cos + dz * sin, d = Math.abs(-dx * sin + dz * cos);
            if (u <= 0 || d > ChoirArena.RAMP_HALF || u > ChoirSites.RIM_R + ChoirArena.APRON) continue;
            if (r < ChoirArena.RAMP_FOOT) return 0;
            double t = (ChoirSites.RIM_R - u) / (ChoirSites.RIM_R - ChoirArena.RAMP_FOOT);
            return ChoirSites.DEPTH + Math.round(-ChoirSites.DEPTH * Math.max(0, Math.min(1, t)));
        }
        return stepUp(r);
    }

    /** How far the seats' steps stand over the bowl's floor at a distance from its middle (0 on the floor itself). */
    public static double stepUp(double r) {
        if (r < ChoirSites.SEAT_R0) return 0;
        for (int k = 0; k < ChoirSites.STEPS; k++)
            if (r < ChoirSites.SEAT_R0 + (k + 1) * ChoirArena.TREAD) return (k + 1) * ChoirSites.STEP_RISE;
        return ChoirSites.DEPTH;
    }
}
