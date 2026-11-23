package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Frost Fern Grotto's ice columns grown from noise (0.20.7, Taran: "Make ice columns procedurally generated instead
 * of structures"): over a cave of known shape, grown chunk by chunk as a world decorates them, writing each block as it
 * goes so later chunks read what earlier ones grew, the ice never floats, comes out block for block the same whichever
 * order the chunks come in, keeps out of a narrow tunnel, never stands in water, and grows both kinds of formation.
 */
class IceColumnsTest {
    private static final byte ROCK = 0, AIR = 1, PACKED = 2, BLUE = 3, TIP = 4, WATER = 5;
    /** The test cave: 8 by 8 chunks, 64 high. */
    private static final int N = 128, H = 64;
    /** The narrow tunnel: three wide, ten high, east from the room along z = 64. */
    private static final int TUNNEL_Z = 64, TUNNEL_FLOOR = 12, TUNNEL_TOP = 21;
    private static final long SEED = 8675309L;
    /** Where the test cave lies in the world: over the thickest grove, where the most formation middles fall in its room. */
    private static final int OX, OZ;

    static {
        IceColumns ic = new IceColumns(SEED);
        int best = -1, ox = 0, oz = 0;
        for (int i = 0; i < 48; i++) for (int j = 0; j < 48; j++) {
            int x = i * 64, z = j * 64, n = ic.seeds(x + 24, z + 28, x + 96, z + 100).size();
            if (n > best) {
                best = n;
                ox = x;
                oz = z;
            }
        }
        OX = ox;
        OZ = oz;
    }

    private static int idx(int x, int y, int z) { return (x * H + y) * N + z; }

    private static boolean in(int x, int y, int z) { return x >= 0 && x < N && y >= 0 && y < H && z >= 0 && z < N; }

    private static boolean ice(byte b) { return b == PACKED || b == BLUE || b == TIP; }

    /**
     * A wide room with an uneven floor and a ceiling 13 to 25 over it (so joined columns and broken spikes both have
     * rooms to grow in), a pool in one part of it, and a narrow tunnel out of its east side.
     */
    private static byte[] cave() {
        byte[] v = new byte[N * H * N];
        Perlin p1 = new Perlin(SEED * 3 + 1), p2 = new Perlin(SEED * 5 + 2), p3 = new Perlin(SEED * 7 + 3);
        for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) {
            double floor = 9 + 2.5 * p1.noise2(x / 14.0, z / 14.0);
            double ceil = floor + 19 + 6 * p2.noise2(x / 22.0, z / 22.0) + 1.5 * p3.noise2(x / 6.0, z / 6.0);
            boolean room = Math.hypot(x - 60, z - 64) < 44 + 4 * p3.noise2(x / 15.0, z / 15.0);
            boolean tunnel = x >= 96 && Math.abs(z - TUNNEL_Z) <= 1;
            for (int y = 0; y < H; y++) {
                byte b = ROCK;
                if (room && y > floor && y < ceil) b = AIR;
                if (tunnel && y >= TUNNEL_FLOOR && y <= TUNNEL_TOP) b = AIR;
                // the pool: the room's lowest three blocks in its south-west part
                if (b == AIR && room && x < 50 && z < 50 && y < floor + 4 && !tunnel) b = WATER;
                v[idx(x, y, z)] = b;
            }
        }
        return v;
    }

    /** Grows the ice over the cave chunk by chunk in a shuffled order; the count of icicles is returned in {@code tips[0]}. */
    private static byte[] grow(long order, int[] tips) {
        byte[] v = cave();
        IceColumns ic = new IceColumns(SEED);
        IceColumns.World w = new IceColumns.World() {
            byte at(int x, int y, int z) { return in(x - OX, y, z - OZ) ? v[idx(x - OX, y, z - OZ)] : ROCK; }

            @Override
            public boolean open(int x, int y, int z) { byte b = at(x, y, z); return b == AIR || ice(b); }

            @Override
            public boolean standable(int x, int y, int z) { return at(x, y, z) == ROCK; }

            @Override
            public boolean grotto(int x, int y, int z) { return true; }

            /** Icier downwards: 0.4 at the top of the cave to 1 at its bottom. */
            @Override
            public double iciness(int x, int y, int z) { return Math.max(0, Math.min(1, 1 - 0.6 * y / H)); }

            @Override
            public boolean way(int x, int z, int y0, int y1, double margin) { return false; }

            @Override
            public int low(int x, int z) { return 1; }

            @Override
            public int high(int x, int z) { return H - 2; }
        };
        IceColumns.Sink sink = new IceColumns.Sink() {
            @Override
            public void ice(int x, int y, int z, boolean blue) {
                // each column is grown once, by its own chunk: what it fills is still the cave's air
                assertEquals(AIR, v[idx(x - OX, y, z - OZ)], "ice only into open space");
                v[idx(x - OX, y, z - OZ)] = blue ? BLUE : PACKED;
            }

            @Override
            public void icicle(int x, int y, int z, boolean down, int index, int length) {
                assertEquals(AIR, v[idx(x - OX, y, z - OZ)], "an icicle only into air");
                v[idx(x - OX, y, z - OZ)] = TIP;
                tips[0]++;
            }
        };
        List<int[]> chunks = new ArrayList<>();
        for (int cx = 0; cx < N / 16; cx++) for (int cz = 0; cz < N / 16; cz++) chunks.add(new int[]{cx, cz});
        Collections.shuffle(chunks, new Random(order));
        for (int[] c : chunks) ic.chunk(OX + c[0] * 16, OZ + c[1] * 16, w, sink);
        return v;
    }

    @Test
    void iceGrowsTheSameInAnyChunkOrderAndNeverFloats() {
        int[] tips = new int[1];
        byte[] a = grow(1, tips);
        byte[] b = grow(2, new int[1]);
        assertArrayEquals(a, b, "the ice is the same whichever chunk is decorated first");

        // every block of ice hangs together, through ice, with rock or water
        boolean[] held = new boolean[a.length];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        int[][] d6 = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int total = 0;
        for (int x = 0; x < N; x++) for (int y = 0; y < H; y++) for (int z = 0; z < N; z++) {
            if (!ice(a[idx(x, y, z)])) continue;
            total++;
            for (int[] d : d6) {
                int nx = x + d[0], ny = y + d[1], nz = z + d[2];
                byte n = in(nx, ny, nz) ? a[idx(nx, ny, nz)] : ROCK;
                if (n == ROCK || n == WATER) {
                    held[idx(x, y, z)] = true;
                    q.add(new int[]{x, y, z});
                    break;
                }
            }
        }
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] d : d6) {
                int x = c[0] + d[0], y = c[1] + d[1], z = c[2] + d[2];
                if (in(x, y, z) && ice(a[idx(x, y, z)]) && !held[idx(x, y, z)]) {
                    held[idx(x, y, z)] = true;
                    q.add(new int[]{x, y, z});
                }
            }
        }
        int floating = 0;
        for (int i = 0; i < a.length; i++) if (ice(a[i]) && !held[i]) floating++;
        assertTrue(total > 2000, "only " + total + " blocks of ice");
        assertEquals(0, floating, "blocks of ice afloat, of " + total);

        // both kinds: columns filled floor to ceiling (joined), and icicles at the points of broken ones
        int filled = 0;
        for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) {
            int run = 0;
            boolean full = false;
            for (int y = 1; y < H - 1; y++) {
                byte c = a[idx(x, y, z)];
                if (ice(c)) run++;
                else run = 0;
                if (run >= 12 && a[idx(x, y + 1, z)] == ROCK && a[idx(x, y - run, z)] == ROCK) full = true;
            }
            if (full) filled++;
        }
        assertTrue(filled > 20, "only " + filled + " columns of ice floor to ceiling");
        assertTrue(tips[0] > 5, "only " + tips[0] + " icicles at broken formations' points");
    }

    @Test
    void noIceInTheNarrowTunnelOrStandingInWater() {
        byte[] v = grow(3, new int[1]);
        // the tunnel's middle, past the reach of any formation whose middle is in the room
        for (int x = 112; x < N; x++) for (int z = TUNNEL_Z - 1; z <= TUNNEL_Z + 1; z++)
            for (int y = TUNNEL_FLOOR; y <= TUNNEL_TOP; y++)
                assertEquals(AIR, v[idx(x, y, z)], "ice in the narrow tunnel at " + x + " " + y + " " + z);
        // standing ice grows from rock alone: none rests on water unless it is a whole column, held by the ceiling
        for (int x = 0; x < N; x++) for (int z = 0; z < N; z++) for (int y = 1; y < H - 1; y++) {
            if (!ice(v[idx(x, y, z)]) || v[idx(x, y - 1, z)] != WATER) continue;
            int top = y;
            while (top < H - 1 && ice(v[idx(x, top + 1, z)])) top++;
            assertEquals(ROCK, v[idx(x, top + 1, z)], "ice standing on water at " + x + " " + y + " " + z);
        }
    }

    @Test
    void theFieldsAreThoseOfTheSeed() {
        IceColumns a = new IceColumns(SEED), b = new IceColumns(SEED), c = new IceColumns(SEED + 1);
        List<IceColumns.Seed> sa = a.seeds(0, 0, 255, 255), sb = b.seeds(0, 0, 255, 255), sc = c.seeds(0, 0, 255, 255);
        assertEquals(sa.size(), sb.size());
        for (int i = 0; i < sa.size(); i++) {
            assertEquals(sa.get(i).ax, sb.get(i).ax);
            assertEquals(sa.get(i).az, sb.get(i).az);
        }
        int[] axA = sa.stream().mapToInt(s -> s.ax * 4096 + s.az).toArray(), axC = sc.stream().mapToInt(s -> s.ax * 4096 + s.az).toArray();
        assertTrue(!Arrays.equals(axA, axC), "another seed, other middles");
    }
}
