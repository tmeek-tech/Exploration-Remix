package com.explorationremix.world.feature;

import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.terrain.ChoirSites;
import com.explorationremix.world.terrain.Hashing;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The shape of the Hollow Choir's arena, as a pure function of its site ({@link ChoirSites.Site}): a round bowl of
 * deepslate sunk {@link ChoirSites#DEPTH} blocks into a deep dark cavern's floor, with three unclimbable tiers of
 * seats round it, two ramps cut through them to walk in and out, a rim walk level with the cavern floor carrying three
 * key sockets, braziers burning pale blue inside the bowl, broken pillars standing on the fighting floor and the
 * Choir's own dais in the middle. Nothing is built over the rim, so the cavern's ceiling is the arena's roof and the
 * view across the bowl stays clear.
 *
 * <p>Every question here is answered from the site and a column, so each chunk builds its own part and they all agree
 * ({@link ChoirArenaFeature}). The sculk that creeps over the floor as the fight goes on is the same kind of function
 * of the phase ({@link #phaseSculk}), so the fight can lay it down and take it away again without remembering where
 * it put it.
 */
public final class ChoirArena {
    /** Columns this far past the rim are dressed too, so the rim walk meets the cavern's floor without a step. */
    public static final double APRON = 2.5;
    /** The seats: three treads, each this deep and this high ({@link ChoirSites#STEP_RISE}). */
    public static final double TREAD = (ChoirSites.RIM_R - ChoirSites.SEAT_R0) / ChoirSites.STEPS;
    /** The ramps: two, opposite, this wide (half-width, blocks), cut from the apron down onto the fighting floor. */
    public static final double RAMP_HALF = 2.5;
    /** The way ramp 0 or 1 runs: the quarter turns off the first socket, the spokes nothing else stands on. */
    public static double rampAngle(ChoirSites.Site s, int i) { return s.turn() + Math.PI / 2 + i * Math.PI; }
    /** The ramp's foot: it meets the fighting floor this far in. */
    public static final double RAMP_FOOT = ChoirSites.FLOOR_R - 2;
    /** The dais in the middle, which the crown hangs over, and the ring of worked stone round it. */
    public static final double DAIS_R = 4.5, DAIS_RING = 6;
    /**
     * The sockets: three, a third of a turn apart, on the rim walk. That is the top tread, which the third riser
     * brings up level with the cavern's floor: the band inside {@link ChoirSites#RIM_R} that the bowl always lays
     * itself, rather than the apron outside it, which is only dressed where the cavern's own floor happens to come up
     * level. A socket out there would stand on whatever the cavern left.
     */
    public static final double SOCKET_R = ChoirSites.RIM_R - 1.5;
    /**
     * The braziers, in three rings: six round the middle tread, three in on the fighting floor, and three on the dais
     * ring itself. Three rings because a brazier lights fifteen blocks and the bowl is forty-eight across, so a ring
     * round the rim alone leaves the dais in the dark — which is where the whole fight happens. The rings also give
     * the fight its three steps of light: the seats go out, then the floor, and the dais burns to the end.
     */
    public static final double BRAZIER_TREAD_R = ChoirSites.SEAT_R0 + TREAD + TREAD / 2, BRAZIER_FLOOR_R = 13,
            BRAZIER_DAIS_R = DAIS_RING - 1;
    /** The broken pillars on the fighting floor: how many, how far out, and how tall (they never block the view). */
    public static final int PILLARS = 5;
    public static final double PILLAR_R0 = 9, PILLAR_R1 = ChoirSites.FLOOR_R - 2;
    public static final int PILLAR_LO = 2, PILLAR_HI = 4;
    /** Air is cleared to this far over the cavern's floor, so nothing of the old floor is left inside the rim. */
    public static final int CLEAR_UP = 3;
    /** The rock laid under a surface block, and how far below that a void is plugged. */
    public static final int SUB = 2, PLUG = 10;
    private static final long SALT_SCULK = 6101, SALT_PILLAR = 6102, SALT_WEAR = 6103;

    private ChoirArena() {}

    // ------------------------------------------------------------------------------------------------- the surface

    /** No column: outside the arena altogether. */
    public static final int NONE = Integer.MIN_VALUE;

    /** True when the column lies in the arena's footprint, apron and all. */
    public static boolean inside(ChoirSites.Site s, double x, double z) {
        return Math.hypot(x - s.x(), z - s.z()) <= ChoirSites.RIM_R + APRON;
    }

    /**
     * The y of the arena's top block in a column, or {@link #NONE} outside it: the fighting floor, a tread, a ramp
     * on its way down, or the rim walk (which the apron carries out level to the cavern's floor).
     */
    public static int topY(ChoirSites.Site s, double x, double z) {
        double dx = x - s.x(), dz = z - s.z();
        double r = Math.hypot(dx, dz);
        if (r > ChoirSites.RIM_R + APRON) return NONE;
        double ramp = rampDrop(s, dx, dz, r);
        if (!Double.isNaN(ramp)) return (int) Math.round(s.rimY() - ramp);
        if (r < ChoirSites.SEAT_R0) return (int) s.floorY();
        for (int k = 0; k < ChoirSites.STEPS; k++)
            if (r < ChoirSites.SEAT_R0 + (k + 1) * TREAD)
                return (int) Math.round(s.floorY() + (k + 1) * ChoirSites.STEP_RISE);
        return (int) s.rimY();
    }

    /**
     * How far below the rim a ramp's floor lies in this column, or NaN where no ramp runs.
     *
     * <p>Everything the arena stands on its floor is on a spoke off the first socket, and they are chosen so that no
     * two share one: the three sockets at 0, 120 and 240 degrees, a brazier on the seats under each of those and on
     * the three spokes between them, a brazier in on the floor on those three between, and the two ramps on the
     * quarter turns, 90 and 270, which the rest leaves empty. So a ramp is always a clean cut through the seats with
     * nothing standing in it, and you can see both of them from the middle. The dais ring's three braziers are the one
     * thing that shares a spoke, the sockets' own: they stand five blocks out and a socket twenty-two, so the two can
     * never meet, and standing in line is the point of them.
     */
    private static double rampDrop(ChoirSites.Site s, double dx, double dz, double r) {
        for (int i = 0; i < 2; i++) {
            double a = rampAngle(s, i);
            double cos = Math.cos(a), sin = Math.sin(a);
            double u = dx * cos + dz * sin, d = Math.abs(-dx * sin + dz * cos);
            if (u <= 0 || d > RAMP_HALF) continue;
            if (u > ChoirSites.RIM_R + APRON) continue;
            double t = (ChoirSites.RIM_R - u) / (ChoirSites.RIM_R - RAMP_FOOT);
            if (r < RAMP_FOOT) return ChoirSites.DEPTH;
            return ChoirSites.DEPTH * Math.max(0, Math.min(1, t));
        }
        return Double.NaN;
    }

    /** True when the column stands on the fighting floor (inside the seats, ramps' feet included). */
    public static boolean onFloor(ChoirSites.Site s, double x, double z) {
        return Math.hypot(x - s.x(), z - s.z()) < ChoirSites.SEAT_R0;
    }

    /** True when the column is a ramp's floor. */
    public static boolean onRamp(ChoirSites.Site s, double x, double z) {
        double dx = x - s.x(), dz = z - s.z();
        return !Double.isNaN(rampDrop(s, dx, dz, Math.hypot(dx, dz)));
    }

    // ------------------------------------------------------------------------------------------------- the stone

    /** The block that lies at the top of a column: worked deepslate, worn by a value field, sculk over the dais. */
    public static BlockState surface(ChoirSites.Site s, int x, int z) {
        double dx = x + 0.5 - s.x(), dz = z + 0.5 - s.z();
        double r = Math.hypot(dx, dz);
        if (r <= DAIS_R) return Blocks.SCULK.defaultBlockState();
        if (r <= DAIS_RING) return Blocks.CHISELED_DEEPSLATE.defaultBlockState();
        if (sculkAt(s, x, z, 0)) return Blocks.SCULK.defaultBlockState();
        double w = wear(s, x, z);
        if (r > ChoirSites.RIM_R) return w < 0.45 ? Blocks.DEEPSLATE_TILES.defaultBlockState()
                : Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState();
        if (onRamp(s, x + 0.5, z + 0.5)) return w < 0.5 ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                : Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState();
        if (r >= ChoirSites.SEAT_R0) return w < 0.3 ? Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState()
                : Blocks.DEEPSLATE_TILES.defaultBlockState();
        return w < 0.25 ? Blocks.CRACKED_DEEPSLATE_TILES.defaultBlockState()
                : w < 0.8 ? Blocks.DEEPSLATE_TILES.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    }

    /** The rock just under the surface: the risers of the seats are read as faces of it, so it is worked stone. */
    public static BlockState sub(ChoirSites.Site s, int x, int z) {
        return wear(s, x, z) < 0.5 ? Blocks.POLISHED_DEEPSLATE.defaultBlockState()
                : Blocks.DEEPSLATE_BRICKS.defaultBlockState();
    }

    /** What plugs a void found under the bowl. */
    public static BlockState plug() { return Blocks.DEEPSLATE.defaultBlockState(); }

    /** A smooth 0..1 field over the stone, so the worn and whole blocks lie in patches instead of a speckle. */
    private static double wear(ChoirSites.Site s, int x, int z) {
        return value(s.hash() ^ SALT_WEAR, x / 7.0, z / 7.0);
    }

    // ------------------------------------------------------------------------------------------------- the sculk

    /**
     * Whether sculk has taken a column of the arena at a phase of the fight (0 before it starts): from the middle
     * out, and further out each phase, so the bowl closes in as the Choir is driven down. Only the bowl itself is
     * taken; the rim walk and the apron stay clean stone.
     */
    public static boolean sculkAt(ChoirSites.Site s, int x, int z, int phase) {
        double dx = x + 0.5 - s.x(), dz = z + 0.5 - s.z();
        double r = Math.hypot(dx, dz);
        if (r > ChoirSites.RIM_R) return false;
        // how far out the sculk has come, and how hard it pushes there
        double reach = switch (phase) {
            case 0 -> ChoirSites.FLOOR_R * 0.55;
            case 1 -> ChoirSites.FLOOR_R * 0.55;
            case 2 -> ChoirSites.SEAT_R0 + TREAD;
            default -> ChoirSites.RIM_R;
        };
        if (r > reach) return false;
        double t = 1 - r / Math.max(1, reach);
        double v = value(s.hash() ^ SALT_SCULK, x / 5.5, z / 5.5);
        return v < 0.18 + 0.62 * t * t;
    }

    /** The highest phase that changes the ground, so a reset need only lay phase 0 again. */
    public static final int LAST_PHASE = 3;

    // ------------------------------------------------------------------------------------------------- the fittings

    /** Where the three key sockets stand on the rim walk: {x, z} block columns, in the order they are filled. */
    public static int[][] sockets(ChoirSites.Site s) {
        int[][] out = new int[3][];
        for (int i = 0; i < 3; i++) {
            double a = s.turn() + i * 2 * Math.PI / 3;
            out[i] = new int[]{(int) Math.floor(s.x() + SOCKET_R * Math.cos(a)),
                    (int) Math.floor(s.z() + SOCKET_R * Math.sin(a))};
        }
        return out;
    }

    /** How many braziers stand in each ring, outwards in: the dais, the fighting floor, the seats. */
    public static final int BRAZIER_TREAD = 6, BRAZIER_FLOOR = 3, BRAZIER_DAIS = 3;

    /**
     * Where the braziers stand: {x, z, y} with y the block the brazier itself sits on. The first {@link #BRAZIER_TREAD}
     * are the seats' ring, the next {@link #BRAZIER_FLOOR} the floor's, the last {@link #BRAZIER_DAIS} the dais's.
     */
    public static int[][] braziers(ChoirSites.Site s) {
        int[][] out = new int[BRAZIER_TREAD + BRAZIER_FLOOR + BRAZIER_DAIS][];
        // six round the seats: one under each socket, one on each spoke between them
        for (int i = 0; i < 6; i++) {
            double a = s.turn() + i * Math.PI / 3;
            int x = (int) Math.floor(s.x() + BRAZIER_TREAD_R * Math.cos(a));
            int z = (int) Math.floor(s.z() + BRAZIER_TREAD_R * Math.sin(a));
            out[i] = new int[]{x, z, topY(s, x + 0.5, z + 0.5) + 1};
        }
        // three in on the fighting floor, on the spokes between the sockets, so the lights alternate round the bowl
        for (int i = 0; i < 3; i++) {
            double a = s.turn() + Math.PI / 3 + i * 2 * Math.PI / 3;
            int x = (int) Math.floor(s.x() + BRAZIER_FLOOR_R * Math.cos(a));
            int z = (int) Math.floor(s.z() + BRAZIER_FLOOR_R * Math.sin(a));
            out[BRAZIER_TREAD + i] = new int[]{x, z, topY(s, x + 0.5, z + 0.5) + 1};
        }
        // three on the dais ring, on the sockets' own spokes: from each socket you look down an aisle of flame to the
        // dais. They stand on the ring of worked stone, never on the sculk the crown hangs over.
        for (int i = 0; i < BRAZIER_DAIS; i++) {
            double a = s.turn() + i * 2 * Math.PI / 3;
            int x = (int) Math.floor(s.x() + BRAZIER_DAIS_R * Math.cos(a));
            int z = (int) Math.floor(s.z() + BRAZIER_DAIS_R * Math.sin(a));
            out[BRAZIER_TREAD + BRAZIER_FLOOR + i] = new int[]{x, z, topY(s, x + 0.5, z + 0.5) + 1};
        }
        return out;
    }

    /**
     * Whether the brazier at this index of {@link #braziers} burns at a phase of the fight (0 before it starts): the
     * seats' ring goes out as phase 2 begins, the floor's as phase 3 does, and the three on the dais never do. The
     * fight and the tests both read it here, so the two cannot drift apart.
     */
    public static boolean brazierLit(int i, int phase) {
        if (phase <= 0) return true;
        if (i < BRAZIER_TREAD) return phase < 2;
        if (i < BRAZIER_TREAD + BRAZIER_FLOOR) return phase < 3;
        return true;
    }

    /** A broken pillar on the fighting floor: the low corner of its 2 x 2 and how many blocks tall it stands. */
    public record Stump(int x, int z, int height) {}

    /** The broken pillars, hashed round the floor and kept out of the dais and the ramps' feet. */
    public static Stump[] stumps(ChoirSites.Site s) {
        Stump[] out = new Stump[PILLARS];
        int n = 0;
        for (int i = 0; n < PILLARS && i < PILLARS * 8; i++) {
            long h = Hashing.mix(s.hash(), SALT_PILLAR, i, 0);
            double a = Hashing.range(h, 0, Math.PI * 2);
            double r = Hashing.range(Hashing.mix(h, 1, 0, 0), PILLAR_R0, PILLAR_R1);
            int x = (int) Math.floor(s.x() + r * Math.cos(a)), z = (int) Math.floor(s.z() + r * Math.sin(a));
            // the whole 2 x 2 has to be fighting floor and none of it a ramp, or the stump would stand in a step
            boolean off = false;
            for (int cx = 0; cx < 2 && !off; cx++)
                for (int cz = 0; cz < 2 && !off; cz++)
                    if (!onFloor(s, x + cx + 0.5, z + cz + 0.5) || onRamp(s, x + cx + 0.5, z + cz + 0.5)) off = true;
            if (off) continue;
            boolean near = false;
            for (int k = 0; k < n; k++) if (Math.hypot(out[k].x() - x, out[k].z() - z) < 7) near = true;
            for (int[] b : braziers(s)) if (Math.hypot(b[0] - x, b[1] - z) < 4) near = true;
            if (near) continue;
            int hgt = (int) Math.round(Hashing.range(Hashing.mix(h, 2, 0, 0), PILLAR_LO, PILLAR_HI));
            out[n++] = new Stump(x, z, hgt);
        }
        return n == PILLARS ? out : java.util.Arrays.copyOf(out, n);
    }

    /** The block a stump is built of at a height above the floor (its cap is chiselled, its shaft polished). */
    public static BlockState stumpBlock(boolean cap) {
        return cap ? Blocks.CHISELED_DEEPSLATE.defaultBlockState() : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
    }

    /** The lit brazier, which the fight puts out between phases. */
    public static BlockState brazier(boolean lit) {
        return ERBlocks.CHOIR_BRAZIER.get().defaultBlockState()
                .setValue(com.explorationremix.block.ChoirBrazierBlock.LIT, lit);
    }

    // ------------------------------------------------------------------------------------------------- value noise

    /** Smooth hashed value noise, 0..1: bilinear between the lattice points of a 1-unit grid. */
    static double value(long seed, double x, double z) {
        int i = (int) Math.floor(x), k = (int) Math.floor(z);
        double tx = x - i, tz = z - k;
        tx = tx * tx * (3 - 2 * tx);
        tz = tz * tz * (3 - 2 * tz);
        double a = Hashing.unit(Hashing.mix(seed, 0, i, k)), b = Hashing.unit(Hashing.mix(seed, 0, i + 1, k));
        double c = Hashing.unit(Hashing.mix(seed, 0, i, k + 1)), d = Hashing.unit(Hashing.mix(seed, 0, i + 1, k + 1));
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }
}
