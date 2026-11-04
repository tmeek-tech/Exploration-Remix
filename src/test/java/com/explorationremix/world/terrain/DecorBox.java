package com.explorationremix.world.terrain;

import com.explorationremix.world.feature.ColumnFit;
import com.explorationremix.world.feature.Detour;
import com.explorationremix.world.feature.TerracePoolsFeature;
import com.explorationremix.world.feature.WalkWindow;
import com.explorationremix.world.feature.Walkway;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The Wasteland's decorations laid on blocks made as the game makes them ({@link NoiseVox}): the features its two
 * biomes place in steps 2 and 8 (wasteland/lava_pools, hanging_columns, great_hanging_columns and lava_falls,
 * ash_hollows/lava_pool), with their numbers read from the data the game reads. Their placement modifiers (count,
 * in_square, layer_range over the lava table with inset 2, environment_scan of 32, random_offset, the air filter, biome)
 * and the features' own rules are followed line by line (TerracePoolsFeature, ColumnClusterFeature with its
 * clearance, FluidFallFeature in wall mode for lava), and they ask the real walk guard ({@link Walkway} for this
 * terrain) and {@link ColumnFit}. Every other feature of the two biomes is one that sets no block in a player's way
 * (lichen, shards, plants, the climbing vines and the open way pass, which do nothing in a layer with no plan).
 *
 * <p>Around each placement a window's walk graph (NoiseVox's moves) is compared before and after it. Its ways out are
 * the window's rim places that belonged to the featureless box's main component; a cut is two of them that were joined
 * both ways in the window before and are joined in neither direction after (one way: in one direction only). After the
 * run the whole box's graph is compared with the featureless one ({@link #finish}): the places of its main component that
 * are still places but left it, and the pits among them (walked into from the main component, no walk back).
 *
 * <p>Each chunk draws from its own random, not from the game's decoration seeds, and the blocks' own random picks (the
 * mixed block providers) are not drawn: the placements are the game's in kind and number, not block for block.
 */
final class DecorBox {
    static final int POOLS = 0, HANGING = 1, GREAT = 2, FALLS = 3, ASH_POOLS = 4, FEATURES = 5;
    static final String[] NAMES = {"lava pools", "hanging columns", "great hanging columns", "lava falls", "ash pools"};
    /** The decoration blocks' kinds. */
    static final int K_COLUMN = 1, K_POOL = 2, K_BED = 3, K_RIM = 4, K_SOURCE = 5, K_FALL = 6, K_SUMP = 7;
    /** The window round a placement: R columns each way, RY rows each way. */
    static final int R = 12, RY = 22;
    /** The features this emulates, and the others the two biomes place (none sets a block in a player's way). */
    static final Set<String> EMULATED = Set.of("explorationremix:wasteland/lava_pools", "explorationremix:wasteland/hanging_columns",
            "explorationremix:wasteland/great_hanging_columns", "explorationremix:wasteland/lava_falls", "explorationremix:ash_hollows/lava_pool");
    static final Set<String> HARMLESS = Set.of("explorationremix:wasteland/smoulder_lichen", "explorationremix:wasteland/amber_shards",
            "explorationremix:wasteland/cinder_blooms", "explorationremix:wasteland/ash_ferns", "explorationremix:ash_hollows/amber_shards",
            "explorationremix:climb_vines", "explorationremix:open_way");
    static final int[] HX = {0, 1, 0, -1}, HZ = {-1, 0, 1, 0};   // north, east, south, west (Direction.Plane.HORIZONTAL)
    private static final int NONE = Integer.MIN_VALUE;
    private static final Path DATA = Path.of("src/main/resources/data/explorationremix/worldgen");

    /** The features' numbers, as the data gives them. */
    static final class Config {
        final int[] count = new int[FEATURES], radius = new int[FEATURES], n = new int[FEATURES], hLo = new int[FEATURES], hHi = new int[FEATURES],
                clearance = new int[FEATURES];
        int greatAir, fallOffLo, fallOffHi, minDrop, search, widthLo, widthHi;
        /** Every feature id the two biomes place, by step. */
        final List<String> biomeFeatures = new ArrayList<>();

        static Config read() {
            Config c = new Config();
            String[] ids = {"wasteland/lava_pools", "wasteland/hanging_columns", "wasteland/great_hanging_columns", "wasteland/lava_falls", "ash_hollows/lava_pool"};
            for (int f = 0; f < FEATURES; f++) {
                JsonArray placement = json("placed_feature/" + ids[f]).getAsJsonArray("placement");
                for (JsonElement m : placement) {
                    JsonObject o = m.getAsJsonObject();
                    String type = o.get("type").getAsString();
                    if (type.equals("minecraft:count")) c.count[f] = o.get("count").getAsInt();
                    if (type.equals("minecraft:block_predicate_filter")) c.greatAir = -o.getAsJsonObject("predicate").getAsJsonArray("offset").get(1).getAsInt();
                    if (type.equals("minecraft:random_offset") && f == FALLS) {
                        JsonObject u = o.getAsJsonObject("y_spread").getAsJsonObject("value");
                        c.fallOffLo = u.get("min_inclusive").getAsInt();
                        c.fallOffHi = u.get("max_inclusive").getAsInt();
                    }
                }
                JsonObject cf = json("configured_feature/" + ids[f]).getAsJsonObject("config");
                if (f == FALLS) {
                    c.minDrop = cf.get("min_drop").getAsInt();
                    c.search = cf.get("search").getAsInt();
                    JsonObject w = cf.getAsJsonObject("width").getAsJsonObject("value");
                    c.widthLo = w.get("min_inclusive").getAsInt();
                    c.widthHi = w.get("max_inclusive").getAsInt();
                    continue;
                }
                c.radius[f] = cf.get("radius").getAsInt();
                if (f == HANGING || f == GREAT) {
                    c.n[f] = cf.get("count").getAsInt();
                    JsonObject h = cf.getAsJsonObject("height").getAsJsonObject("value");
                    c.hLo[f] = h.get("min_inclusive").getAsInt();
                    c.hHi[f] = h.get("max_inclusive").getAsInt();
                    c.clearance[f] = cf.has("clearance") ? cf.get("clearance").getAsInt() : 0;
                    if ((cf.has("hang_chance") ? cf.get("hang_chance").getAsFloat() : 0.25F) < 1)
                        throw new IllegalStateException(ids[f] + " no longer hangs every column: the emulation needs its standing ones");
                }
            }
            for (String b : new String[]{"wasteland", "ash_hollows"}) {
                for (JsonElement step : json("biome/" + b).getAsJsonArray("features"))
                    for (JsonElement id : step.getAsJsonArray()) c.biomeFeatures.add(id.getAsString());
            }
            return c;
        }

        static JsonObject json(String path) {
            try (Reader r = Files.newBufferedReader(DATA.resolve(path + ".json"))) {
                return JsonParser.parseReader(r).getAsJsonObject();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    final Terrain t;
    final NoiseVox v;
    final Config cfg;
    final int top, bot, W;
    /** The featureless box's main component, by block. */
    private final BitSet main;
    /** Opening columns of the featureless box (WastelandFieldTest.holeColumn), by column. */
    private final boolean[] hole;
    /** The decoration blocks: packed position to kind. */
    final Map<Long, Integer> kinds = new HashMap<>();
    /**
     * Whether the Wasteland's guards act (the walking channels, the openings' rule, the columns' clearance); off, only
     * the gates are kept, as before fix round 1 (for measuring what the guards do).
     */
    boolean guards = true;

    // what the run counted, per feature
    final int[] tries = new int[FEATURES], placed = new int[FEATURES], blocks = new int[FEATURES], cuts = new int[FEATURES], oneWay = new int[FEATURES];
    /** Refused by the walk guard, by the openings' rule; hanging columns cut short by their clearance. */
    final int[] guarded = new int[FEATURES], nearOpening = new int[FEATURES], shortened = new int[FEATURES];
    /** Pool floor cells a channel kept from a pool; lava falls refused for closing a passage's mouth (Detour). */
    int poolCellsOnChannels, detoured;
    /** Refused for a walk round them they would cut, leave one way or turn into a pit ({@link WalkWindow}). */
    final int[] walks = new int[FEATURES];
    /** The area decorated (m^2), the placements compared and where the first cuts are. */
    long area;
    int compared;
    final List<String> cutAt = new ArrayList<>();
    // measured after the run (finish())
    int lavaBlocks, lavaNearOpening, channelPlaces, channelLost;
    /**
     * The featureless main component's places that are still places but are no longer in the box's main component, and
     * of them the pits: walked into from the main component with no walk back (the first few listed).
     */
    int leftMain, pits;
    final List<String> pitAt = new ArrayList<>();

    // the blocks the current placement set: position and material before and after
    private int jn;
    private int[] jx = new int[256], jy = new int[256], jz = new int[256], jOld = new int[256], jNew = new int[256];

    /** Decorations for the box's blocks; its walk graph must be the featureless one (graph() run, nothing set). */
    DecorBox(Terrain t, NoiseVox v) {
        this.t = t;
        this.v = v;
        this.cfg = Config.read();
        int li = Terrain.WASTELAND;
        top = t.shape.top(li);
        bot = t.shape.bottom(li);
        W = t.shape.waterY(li);
        int mainC = 0;
        for (int c = 0; c < v.compSize.length; c++) if (v.compSize[c] > v.compSize[mainC]) mainC = c;
        main = new BitSet(v.nx * v.ny * v.nz);
        for (int p = 0; p < v.places; p++) if (v.comp[p] == mainC) main.set(bit(v.px(p), v.py(p), v.pz(p)));
        hole = new boolean[v.nx * v.nz];
        for (int i = 0; i < v.nx; i++) for (int k = 0; k < v.nz; k++) hole[i * v.nz + k] = WastelandFieldTest.holeColumn(v, bot, v.x0 + i, v.z0 + k);
        // the walking channels' places in the main component, for what the decorations take of them
        for (int p = 0; p < v.places; p++) {
            int x = v.px(p), y = v.py(p), z = v.pz(p);
            if (v.comp[p] == mainC && y >= bot && y <= top && Walkway.nearChannel(t, x, z, y, y + 1, 0)) channelPlaces++;
        }
    }

    private int bit(int x, int y, int z) { return ((x - v.x0) * v.nz + (z - v.z0)) * v.ny + (y - v.y0); }

    private static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF); }

    private boolean air(int x, int y, int z) { return v.mat(x, y, z) == NoiseVox.AIR; }

    private boolean rock(int x, int y, int z) { return v.mat(x, y, z) == NoiseVox.ROCK; }

    private void put(int x, int y, int z, int m, int kind) {
        if (!v.in(x, y, z)) return;
        if (jn == jx.length) {
            jx = Arrays.copyOf(jx, jn * 2);
            jy = Arrays.copyOf(jy, jn * 2);
            jz = Arrays.copyOf(jz, jn * 2);
            jOld = Arrays.copyOf(jOld, jn * 2);
            jNew = Arrays.copyOf(jNew, jn * 2);
        }
        jx[jn] = x;
        jy[jn] = y;
        jz[jn] = z;
        jOld[jn] = v.mat(x, y, z);
        jNew[jn] = m;
        jn++;
        v.set(x, y, z, m);
        kinds.put(key(x, y, z), kind);
    }

    // ================================================================================================ the run
    /** Decorates every chunk lying wholly inside [xa, xb) x [za, zb), chunk by chunk, each in the game's feature order. */
    DecorBox run(long seed, int xa, int za, int xb, int zb) {
        int lo = W + 2, hi = top - 2;   // layer_range wasteland, inset 2, above the lava table
        for (int cx = Math.floorDiv(xa + 15, 16); cx * 16 + 16 <= xb; cx++) for (int cz = Math.floorDiv(za + 15, 16); cz * 16 + 16 <= zb; cz++) {
            Random r = new Random(Hashing.mix(seed, cx, cz, 4242));
            int bx = cx * 16, bz = cz * 16;
            area += 256;
            // step 2 (local modifications), then step 8 (fluid springs)
            for (int i = 0; i < cfg.count[POOLS]; i++) onFloor(r, bx, bz, lo, hi, POOLS);
            for (int i = 0; i < cfg.count[HANGING]; i++) underCeiling(r, bx, bz, lo, hi, HANGING);
            for (int i = 0; i < cfg.count[GREAT]; i++) underCeiling(r, bx, bz, lo, hi, GREAT);
            for (int i = 0; i < cfg.count[ASH_POOLS]; i++) onFloor(r, bx, bz, lo, hi, ASH_POOLS);
            for (int i = 0; i < cfg.count[FALLS]; i++) underCeiling(r, bx, bz, lo, hi, FALLS);
        }
        return this;
    }

    /** environment_scan from (x, y, z): from air, through air for up to 32 steps, to rock. The rock's row, or NONE. */
    int scan(int x, int y, int z, int dir) {
        if (!air(x, y, z)) return NONE;
        for (int i = 0; i < 32; i++) {
            y += dir;
            if (!air(x, y, z)) break;
        }
        return rock(x, y, z) ? y : NONE;
    }

    private void onFloor(Random r, int bx, int bz, int lo, int hi, int f) {
        int x = bx + r.nextInt(16), z = bz + r.nextInt(16), y = lo + r.nextInt(hi - lo + 1);
        int fy = scan(x, y, z, -1);
        if (fy == NONE) return;
        int oy = fy + 1;
        if (t.biomeAt(x, oy, z) != (f == ASH_POOLS ? DeepBiome.ASH_HOLLOWS : DeepBiome.WASTELAND)) return;
        tries[f]++;
        jn = 0;
        if (!pool(x, oy, z, cfg.radius[f], f)) return;
        placed[f]++;
        check(f, x, oy, z);
    }

    private void underCeiling(Random r, int bx, int bz, int lo, int hi, int f) {
        int x = bx + r.nextInt(16), z = bz + r.nextInt(16), y = lo + r.nextInt(hi - lo + 1);
        int cy = scan(x, y, z, +1);
        if (cy == NONE) return;
        int oy = f == FALLS ? cy + cfg.fallOffLo + r.nextInt(cfg.fallOffHi - cfg.fallOffLo + 1) : cy - 1;
        if (f == GREAT && !air(x, oy - cfg.greatAir, z)) return;
        if (t.biomeAt(x, oy, z) != DeepBiome.WASTELAND) return;
        tries[f]++;
        jn = 0;
        if (f == FALLS) {
            int[] foot = fall(r, x, oy, z);
            if (foot == null) return;
            placed[f]++;
            check(f, foot[0], foot[1], foot[2]);
            return;
        }
        int n = hanging(r, x, oy, z, f);
        if (n == 0) return;
        placed[f]++;
        blocks[f] += n;
        // the window stands on the floor under the cluster
        int fl = oy;
        while (fl > oy - 40 && air(x, fl - 1, z)) fl--;
        check(f, x, fl, z);
    }

    // ------------------------------------------------------------------------------------------ TerracePoolsFeature
    private boolean isFloor(int x, int y, int z) { return rock(x, y, z) && air(x, y + 1, z) && air(x, y + 2, z); }

    private boolean poolFloor(int x, int y, int z) {
        if (!isFloor(x, y, z)) return false;
        if (!guards || !Walkway.nearChannel(t, x, z, y, y + 1, TerracePoolsFeature.CHANNEL_MARGIN)) return true;
        poolCellsOnChannels++;
        return false;
    }

    /** A lava pool (TerracePoolsFeature.place, lava, no overflow) with its origin at (ox, oy, oz). */
    private boolean pool(int ox, int oy, int oz, int radius, int f) {
        int fy = oy - 1;
        if (!poolFloor(ox, fy, oz)) return false;
        int r = radius;
        while (r >= radius && Walkway.nearWays(t, ox, oz, fy, fy + 1, r + 1)) r--;
        if (r < radius) { guarded[f]++; return false; }
        if (guards && Walkway.nearOpening(t, ox, oz, fy, r + 3)) { nearOpening[f]++; return false; }
        int size = 2 * r + 1;
        byte[] cell = new byte[size * size];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{0, 0});
        cell[r * size + r] = 1;
        int count = 0;
        while (!q.isEmpty()) {
            int[] p = q.poll();
            count++;
            for (int d = 0; d < 4; d++) {
                int nx = p[0] + HX[d], nz = p[1] + HZ[d];
                if (Math.abs(nx) > r || Math.abs(nz) > r || nx * nx + nz * nz > r * r) continue;
                int idx = (nz + r) * size + nx + r;
                if (cell[idx] != 0) continue;
                if (!poolFloor(ox + nx, fy, oz + nz)) { cell[idx] = 2; continue; }
                cell[idx] = 1;
                q.add(new int[]{nx, nz});
            }
        }
        if (count < 10) return false;
        boolean[] lava = new boolean[size * size];
        int interior = 0;
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            int idx = (dz + r) * size + dx + r;
            if (cell[idx] != 1) continue;
            boolean edge = false;
            for (int d = 0; d < 4; d++) {
                int nx = dx + HX[d], nz = dz + HZ[d];
                if (Math.abs(nx) > r || Math.abs(nz) > r || cell[(nz + r) * size + nx + r] != 1) { edge = true; break; }
            }
            if (!edge) { lava[idx] = true; interior++; }
        }
        if (interior < 4) return false;
        if (guards && Walkway.channelled(t, fy)) {
            WalkWindow.Change change = new WalkWindow.Change();
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (!lava[(dz + r) * size + dx + r]) continue;
                change.add(ox + dx, fy, oz + dz, Detour.OTHER).add(ox + dx, fy - 1, oz + dz, Detour.SOLID);
            }
            if (!WalkWindow.keeps(this::walker, change)) { walks[f]++; return false; }
        }
        for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
            int idx = (dz + r) * size + dx + r;
            if (cell[idx] != 1) continue;
            int x = ox + dx, z = oz + dz;
            if (lava[idx]) {
                put(x, fy - 1, z, NoiseVox.ROCK, K_BED);
                put(x, fy, z, NoiseVox.LAVA, K_POOL);
            } else put(x, fy, z, NoiseVox.ROCK, K_RIM);
        }
        blocks[f] += interior;
        return true;
    }

    /** Walkway.near for this terrain; with the guards off, the gates' part alone. */
    private boolean near(int x, int z, int y0, int y1, double margin) {
        return guards ? Walkway.near(t, x, z, y0, y1, margin) : Walkway.nearWays(t, x, z, y0, y1, margin);
    }

    // ------------------------------------------------------------------------------------------ ColumnClusterFeature
    /** A cluster of hanging columns (ColumnClusterFeature.place, every column hanging, with its clearance); blocks set. */
    private int hanging(Random r, int ox, int oy, int oz, int f) {
        int radius = cfg.radius[f], clearance = guards ? cfg.clearance[f] : 0, n = 0;
        for (int i = 0; i < cfg.n[f]; i++) {
            int dx = r.nextInt(2 * radius + 1) - radius, dz = r.nextInt(2 * radius + 1) - radius;
            if (dx * dx + dz * dz > radius * radius) continue;
            double centre = 1 - Math.sqrt(dx * dx + dz * dz) / (radius + 1.0);
            int h = Math.max(1, (int) Math.round((cfg.hLo[f] + r.nextInt(cfg.hHi[f] - cfg.hLo[f] + 1)) * (0.4 + 0.6 * centre)));
            r.nextFloat();   // the hang chance: 1, every column hangs
            int x = ox + dx, z = oz + dz, y = oy, tr = 0;
            if (!air(x, y, z)) continue;
            while (tr < 12 && air(x, y, z)) { y++; tr++; }
            if (tr >= 12 || air(x, y, z) || !rock(x, y, z)) continue;
            if (clearance > 0) {
                int open = 0;
                while (open < h + clearance && air(x, y - 1 - open, z)) open++;
                int fit = ColumnFit.hangingLength(h, open, clearance);
                if (fit < h) shortened[f]++;
                h = fit;
                if (h < 1) continue;
            }
            if (near(x, z, y, y - h, 1)) { guarded[f]++; continue; }
            for (int k = 0; k < h; k++) {
                y--;
                if (!air(x, y, z)) break;
                put(x, y, z, NoiseVox.ROCK, K_COLUMN);
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------------------------------ FluidFallFeature
    /** A lava fall into the wall the origin faces (FluidFallFeature.place, wall mode): its foot, or null. */
    private int[] fall(Random r, int ox, int oy, int oz) {
        if (!air(ox, oy, oz)) return null;
        int dir = r.nextInt(4);
        int k = 0;
        for (int s = 1; s <= cfg.search; s++) {
            int x = ox + HX[dir] * s, z = oz + HZ[dir] * s;
            if (air(x, oy, z)) continue;
            k = rock(x, oy, z) ? s : 0;
            break;
        }
        if (k == 0) return null;
        int wx = ox + HX[dir] * k, wz = oz + HZ[dir] * k, side = (dir + 1) & 3;
        int width = cfg.widthLo + r.nextInt(cfg.widthHi - cfg.widthLo + 1);
        int[] foot = null;
        for (int w = 0; w < width; w++) {
            int[] at = fallOne(wx + HX[side] * w, oy, wz + HZ[side] * w, dir);
            if (at != null) { if (foot == null) foot = at; }
            else if (w == 0) return null;
        }
        return foot;
    }

    /** A block as a walker meets it (Detour's kinds). */
    private int walker(int x, int y, int z) {
        int m = v.mat(x, y, z);
        return m == NoiseVox.ROCK ? Detour.SOLID : m == NoiseVox.AIR ? Detour.OPEN : Detour.OTHER;
    }

    private boolean lavaSource(int x, int y, int z) {
        if (v.mat(x, y, z) != NoiseVox.LAVA) return false;
        Integer k = kinds.get(key(x, y, z));
        return k == null || k == K_POOL || k == K_SOURCE || k == K_SUMP;
    }

    private int[] fallOne(int sx, int sy, int sz, int dir) {
        int fx = sx - HX[dir], fz = sz - HZ[dir];
        if (!rock(sx, sy, sz) || !air(fx, sy, fz)) return null;
        if (!rock(sx, sy + 1, sz) || !rock(sx, sy - 1, sz) || !rock(sx + HX[dir], sy, sz + HZ[dir])) return null;
        for (int d : new int[]{(dir + 1) & 3, (dir + 3) & 3}) {
            int m = v.mat(sx + HX[d], sy, sz + HZ[d]);
            if (m != NoiseVox.ROCK && m != NoiseVox.LAVA) return null;
        }
        int drop = 0;
        while (drop < 300 && air(fx, sy - 1 - drop, fz)) drop++;
        int py = sy - 1 - drop;
        if (drop < cfg.minDrop) return null;
        if (near(fx, fz, sy - drop, sy, 2)) { guarded[FALLS]++; return null; }
        if (guards && Walkway.nearOpening(t, fx, fz, sy, 3)) { nearOpening[FALLS]++; return null; }
        int below = v.mat(fx, py, fz);
        boolean intoPool = below == NoiseVox.LAVA;
        if (!intoPool && below != NoiseVox.ROCK) return null;
        boolean sump = !intoPool;
        if (sump) {
            if (!rock(fx, py - 1, fz)) return null;
            for (int d = 0; d < 4; d++) if (!rock(fx + HX[d], py, fz + HZ[d]) && !lavaSource(fx + HX[d], py, fz + HZ[d])) return null;
            if (guards && Walkway.channelled(t, sy) && !Detour.around(this::walker, fx, py + 1, fz)) { detoured++; return null; }
        }
        if (guards && Walkway.channelled(t, sy)) {
            WalkWindow.Change change = new WalkWindow.Change().add(sx, sy, sz, Detour.OTHER);
            for (int q = 0; q <= drop; q++) change.add(fx, sy - q, fz, Detour.OTHER);
            if (sump) change.add(fx, py, fz, Detour.OTHER);
            if (!WalkWindow.keeps(this::walker, change)) { walks[FALLS]++; return null; }
        }
        put(sx, sy, sz, NoiseVox.LAVA, K_SOURCE);
        for (int q = 0; q <= drop; q++) put(fx, sy - q, fz, NoiseVox.LAVA, K_FALL);
        if (sump) put(fx, py, fz, NoiseVox.LAVA, K_SUMP);
        blocks[FALLS] += drop + 2 + (sump ? 1 : 0);
        return new int[]{fx, py + 1, fz};
    }

    // ================================================================================================ the windows
    /** A walk graph over a window of the box (NoiseVox's places and moves, kept to the window). */
    static final class Win {
        int ax, ay, az, sx, sy, sz, n;
        int[] id, pos, next, comp;

        int at(int x, int y, int z) {
            int i = x - ax, j = y - ay, k = z - az;
            return i < 0 || j < 0 || k < 0 || i >= sx || j >= sy || k >= sz ? -1 : id[(i * sz + k) * sy + j];
        }

        int px(int p) { return ax + pos[p] / (sz * sy); }

        int pz(int p) { return az + pos[p] / sy % sz; }

        int py(int p) { return ay + pos[p] % sy; }
    }

    /** Where a step from (x, y, z) toward d lands, as NoiseVox.move: its feet row, or NONE. */
    private int land(int x, int y, int z, int d) {
        int qx = x + NoiseVox.DX[d], qz = z + NoiseVox.DZ[d];
        if (!v.inColumns(qx, qz)) return NONE;
        if (v.stand(qx, y + 1, qz) && v.open(x, y + 2, z)) return y + 1;
        if (!v.open(qx, y, qz) || !v.open(qx, y + 1, qz)) return NONE;
        for (int yy = y; yy >= y - NoiseVox.MAX_DROP && yy > v.y0; yy--) {
            if (!v.open(qx, yy, qz)) return NONE;
            if (v.mat(qx, yy - 1, qz) == NoiseVox.ROCK) return v.stand(qx, yy, qz) ? yy : NONE;
        }
        return NONE;
    }

    Win window(int cx, int cy, int cz) {
        Win g = new Win();
        g.ax = Math.max(cx - R, v.x0);
        g.az = Math.max(cz - R, v.z0);
        g.ay = Math.max(cy - RY, v.y0 + 1);
        g.sx = Math.min(cx + R, v.x0 + v.nx - 1) - g.ax + 1;
        g.sz = Math.min(cz + R, v.z0 + v.nz - 1) - g.az + 1;
        g.sy = Math.min(cy + RY, v.y0 + v.ny - 2) - g.ay + 1;
        g.id = new int[g.sx * g.sy * g.sz];
        Arrays.fill(g.id, -1);
        int[] pos = new int[1024];
        int n = 0;
        for (int i = 0; i < g.sx; i++) for (int k = 0; k < g.sz; k++) for (int j = 0; j < g.sy; j++) {
            if (!v.stand(g.ax + i, g.ay + j, g.az + k)) continue;
            if (n == pos.length) pos = Arrays.copyOf(pos, n * 2);
            int l = (i * g.sz + k) * g.sy + j;
            g.id[l] = n;
            pos[n++] = l;
        }
        g.n = n;
        g.pos = pos;
        g.next = new int[n * 4];
        for (int p = 0; p < n; p++) {
            int x = g.px(p), y = g.py(p), z = g.pz(p);
            for (int d = 0; d < 4; d++) {
                int yy = land(x, y, z, d);
                g.next[p * 4 + d] = yy == NONE ? -1 : g.at(x + NoiseVox.DX[d], yy, z + NoiseVox.DZ[d]);
            }
        }
        g.comp = scc(n, g.next);
        return g;
    }

    /** Strongly connected components (Kosaraju, iterative) of a graph of 4 moves a node. */
    static int[] scc(int n, int[] next) {
        int[] order = new int[n], stack = new int[n], it = new int[n];
        boolean[] seen = new boolean[n];
        int on = 0;
        for (int s = 0; s < n; s++) {
            if (seen[s]) continue;
            int sp = 0;
            stack[sp] = s;
            it[sp++] = 0;
            seen[s] = true;
            while (sp > 0) {
                int u = stack[sp - 1];
                if (it[sp - 1] < 4) {
                    int w = next[u * 4 + it[sp - 1]++];
                    if (w >= 0 && !seen[w]) { seen[w] = true; stack[sp] = w; it[sp++] = 0; }
                } else { order[on++] = u; sp--; }
            }
        }
        int[] rs = new int[n + 1];
        for (int e : next) if (e >= 0) rs[e + 1]++;
        for (int i = 0; i < n; i++) rs[i + 1] += rs[i];
        int[] radj = new int[rs[n]], fill = Arrays.copyOf(rs, n);
        for (int u = 0; u < n; u++) for (int d = 0; d < 4; d++) { int w = next[u * 4 + d]; if (w >= 0) radj[fill[w]++] = u; }
        int[] comp = new int[n];
        Arrays.fill(comp, -1);
        int cc = 0;
        for (int oi = n - 1; oi >= 0; oi--) {
            int s = order[oi];
            if (comp[s] >= 0) continue;
            int sp = 0;
            stack[sp++] = s;
            comp[s] = cc;
            while (sp > 0) {
                int u = stack[--sp];
                for (int e = rs[u]; e < rs[u + 1]; e++) if (comp[radj[e]] < 0) { comp[radj[e]] = cc; stack[sp++] = radj[e]; }
            }
            cc++;
        }
        return comp;
    }

    static boolean reaches(Win g, int from, int to) {
        if (g.comp[from] == g.comp[to]) return true;
        boolean[] seen = new boolean[g.n];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(from);
        seen[from] = true;
        while (!q.isEmpty()) {
            int u = q.poll();
            if (g.comp[u] == g.comp[to]) return true;
            for (int d = 0; d < 4; d++) {
                int w = g.next[u * 4 + d];
                if (w >= 0 && !seen[w]) { seen[w] = true; q.add(w); }
            }
        }
        return false;
    }

    /** Compares the window round the placement just made (its blocks in the journal) before and after it. */
    private void check(int f, int cx, int cy, int cz) {
        compared++;
        Win after = window(cx, cy, cz);
        for (int i = jn - 1; i >= 0; i--) v.set(jx[i], jy[i], jz[i], jOld[i]);
        Win before = window(cx, cy, cz);
        for (int i = 0; i < jn; i++) v.set(jx[i], jy[i], jz[i], jNew[i]);
        // the ways out: rim places of the featureless main component that are still places, by component before
        Map<Integer, Map<Integer, Integer>> groups = new HashMap<>();
        for (int p = 0; p < before.n; p++) {
            int x = before.px(p), y = before.py(p), z = before.pz(p);
            if (Math.max(Math.abs(x - cx), Math.abs(z - cz)) < R - 1 && Math.abs(y - cy) < RY - 1) continue;
            if (!main.get(bit(x, y, z))) continue;
            int q = after.at(x, y, z);
            if (q >= 0) groups.computeIfAbsent(before.comp[p], c -> new HashMap<>()).putIfAbsent(after.comp[q], q);
        }
        int worst = 0;
        for (Map<Integer, Integer> g : groups.values()) {
            if (g.size() < 2) continue;
            List<Integer> reps = new ArrayList<>(g.values());
            for (int a = 0; a < reps.size() && worst < 2; a++) for (int b = a + 1; b < reps.size() && worst < 2; b++) {
                boolean ab = reaches(after, reps.get(a), reps.get(b)), ba = reaches(after, reps.get(b), reps.get(a));
                if (!ab && !ba) worst = 2;
                else if (!ab || !ba) worst = 1;
            }
        }
        if (worst == 2) cuts[f]++;
        if (worst == 1) oneWay[f]++;
        if (worst > 0 && cutAt.size() < 6) cutAt.add(String.format("%s%s at (%d,%d,%d)", NAMES[f], worst == 1 ? " (one way)" : "", cx, cy, cz));
    }

    // ================================================================================================ after the run
    /** Counts what the decorations did to the lava rule and the channels; the box's graph is rebuilt (graph()). */
    DecorBox finish() {
        for (Map.Entry<Long, Integer> e : kinds.entrySet()) {
            int k = e.getValue();
            if (k != K_POOL && k != K_SOURCE && k != K_FALL && k != K_SUMP) continue;
            long p = e.getKey();
            int x = (int) (p >> 38), y = (int) (p << 26 >> 52), z = (int) (p << 38 >> 38);
            if (v.mat(x, y, z) != NoiseVox.LAVA) continue;
            lavaBlocks++;
            boolean near = false;
            for (int dx = -3; dx <= 3 && !near; dx++) for (int dz = -3; dz <= 3 && !near; dz++) {
                int i = x + dx - v.x0, kk = z + dz - v.z0;
                if (i >= 0 && kk >= 0 && i < v.nx && kk < v.nz && hole[i * v.nz + kk]) near = true;
            }
            if (near) lavaNearOpening++;
        }
        // the channels' places the decorations took: no longer two open blocks over rock
        int lost = 0;
        for (int i = 0; i < v.nx; i++) for (int k = 0; k < v.nz; k++) {
            int x = v.x0 + i, z = v.z0 + k;
            for (int y = Math.max(bot, v.y0 + 1); y <= top && y + 1 < v.y0 + v.ny; y++) {
                if (!main.get(bit(x, y, z)) || v.stand(x, y, z)) continue;
                if (Walkway.nearChannel(t, x, z, y, y + 1, 0)) lost++;
            }
        }
        channelLost = lost;
        v.graph();
        // the whole box: places of the featureless main component that are still places, but no longer in the main one
        boolean[] inMain = new boolean[v.places], entered = new boolean[v.places];
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int p = 0; p < v.places; p++) if (v.inMain(p)) { inMain[p] = entered[p] = true; q.add(p); }
        while (!q.isEmpty()) {
            int u = q.poll();
            for (int d = 0; d < 4; d++) { int w = v.next[u * 4 + d]; if (w >= 0 && !entered[w]) { entered[w] = true; q.add(w); } }
        }
        int[] back = v.walkTo(inMain);
        for (int p = 0; p < v.places; p++) {
            int x = v.px(p), y = v.py(p), z = v.pz(p);
            if (inMain[p] || !main.get(bit(x, y, z))) continue;
            leftMain++;
            if (!entered[p] || back[p] >= 0) continue;
            pits++;
            if (pitAt.size() < 6) pitAt.add(String.format("(%d,%d,%d)", x, y, z));
        }
        return this;
    }

    int placedAll() { int n = 0; for (int p : placed) n += p; return n; }

    int cutsAll() { int n = 0; for (int c : cuts) n += c; return n; }

    int oneWayAll() { int n = 0; for (int c : oneWay) n += c; return n; }

    @Override public String toString() {
        StringBuilder b = new StringBuilder(String.format(java.util.Locale.ROOT, "decorated %.3f km2:", area / 1e6));
        for (int f = 0; f < FEATURES; f++)
            b.append(String.format(java.util.Locale.ROOT, " %s %d of %d tries (%d blocks; guard %d, openings %d, walks %d, shortened %d; cuts %d, one way %d);",
                    NAMES[f], placed[f], tries[f], blocks[f], guarded[f], nearOpening[f], walks[f], shortened[f], cuts[f], oneWay[f]));
        b.append(String.format(java.util.Locale.ROOT, " pool cells kept off channels %d; falls kept from passages' mouths %d; lava blocks %d, within 3 columns of"
                + " an opening %d; channel places %d, taken %d; first cuts %s; main places that left it %d, pits %d %s", poolCellsOnChannels, detoured, lavaBlocks,
                lavaNearOpening, channelPlaces, channelLost, cutAt, leftMain, pits, pitAt));
        return b.toString();
    }

    /** The two biomes' features that are neither emulated here nor harmless (none, or the emulation is incomplete). */
    static Set<String> unknownFeatures(Config c) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : c.biomeFeatures) if (!EMULATED.contains(id) && !HARMLESS.contains(id)) out.add(id);
        return out;
    }
}
