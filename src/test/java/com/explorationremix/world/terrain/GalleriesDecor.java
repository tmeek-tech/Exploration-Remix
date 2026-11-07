package com.explorationremix.world.terrain;

import com.explorationremix.world.feature.Detour;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * The Galleries' decorations laid on blocks made as the game makes them ({@link NoiseVox}): the features the four hall
 * biomes place in steps 2, 8 and 10 (basalt_galleries/lava_pools and hanging_columns; vault_falls and caldera/lava_falls,
 * and the cliff_falls of older data; the floor's lava, basalt_galleries/floor_lava, of fix round 2), with their
 * placements and numbers read from the data the game reads (count, rarity_filter, in_square, open_span, random_offset,
 * fall_sites with its sites' aims, biome) and the features' own rules followed line by line (TerracePoolsFeature,
 * ColumnClusterFeature, FluidFallFeature in its three modes with its tries, spread, sites and min_width, every source of
 * a try planned before any is set and every block of a fall in the chunk being decorated; FloorLavaFeature on every
 * column of the chunk),
 * asking the real walk guard ({@link Walkway} for this terrain). The other features of these biomes set no block in a
 * player's way (the climbing chains, the open way pass, the glow, whose light {@code GlowLight} measures, and the lava
 * pockets' pass, which finds no other mod's slabs in these blocks). Nor do these blocks hold Terrain Slabs' slabs, which
 * the game lays before the falls and which a lava fall reads as the blocks they stand in, a top slab as the vault's rock
 * it took the place of, a bottom slab as the open block at a step of the floor it was laid in (FluidFallFeature, fix
 * round 3): here every fall pours, and is refused, as it is there.
 *
 * <p>Each chunk draws from its own random, not from the game's decoration seeds: the placements are the game's in kind
 * and number, not block for block. Data and code of an older tree run too (no fall_sites, no ways-down guard).
 */
final class GalleriesDecor {
    static final int POOLS = 0, HANGING = 1, VAULT = 2, CLIFF = 3, CALDERA = 4, FLOOR = 5, N = 6;
    static final String[] IDS = {"basalt_galleries/lava_pools", "basalt_galleries/hanging_columns", "basalt_galleries/vault_falls",
            "basalt_galleries/cliff_falls", "caldera/lava_falls", "basalt_galleries/floor_lava"};
    static final String[] NAMES = {"lava pools", "hanging columns", "vault falls", "cliff falls", "caldera falls", "floor lava"};
    static final int K_COLUMN = 1, K_POOL = 2, K_BED = 3, K_RIM = 4, K_SOURCE = 5, K_FALL = 6, K_SUMP = 7, K_FLOOR = 8;
    static final int[] HX = {0, 1, 0, -1}, HZ = {-1, 0, 1, 0};   // north, east, south, west (Direction.Plane.HORIZONTAL)
    private static final int NONE = Integer.MIN_VALUE;
    static final String[] BIOMES = {"basalt_galleries", "caldera", "causeway", "scoria_canyons", "obsidian_tubes"};
    static final DeepBiome[] DEEP = {DeepBiome.BASALT_GALLERIES, DeepBiome.CALDERA, DeepBiome.CAUSEWAY, DeepBiome.SCORIA_CANYONS, DeepBiome.OBSIDIAN_TUBES};

    /** A feature's placement and configuration, from the data. */
    static final class Feat {
        int count = 1, rarity = 1;
        boolean square;
        String anchor;
        int minSpan, inset = 2;
        boolean aboveWater, offset;
        int offLo, offHi;
        /** fall_sites: the set (salt, grid, spacing, chance, keep, ring_min, ring_max, anchor) and the share. */
        double[] sites;
        double shareMin, shareMax = 1;
        final EnumSet<DeepBiome> biomes = EnumSet.noneOf(DeepBiome.class);
        // the configuration
        int radius, n, hLo, hHi, clearance;
        int minDrop, search = 10, wLo = 1, wHi = 1, minWidth = 1, tries = 1, spread;
        String mode = "wall";
        double[] fallSites;
    }

    final Terrain t;
    final NoiseVox v;
    final Feat[] feats = new Feat[N];
    /** Whether the tree has the ways-down guard for lava falls (Walkway.nearWayDown). */
    final boolean modern;
    final double headKeep;
    /** The decoration blocks: packed position to kind. */
    final Map<Long, Integer> kinds = new HashMap<>();
    final int[] tries = new int[N], placed = new int[N], blocks = new int[N], guarded = new int[N], shared = new int[N];
    /** The falls placed: kind, then x, y, z of each source. */
    final List<int[]> falls = new ArrayList<>();
    /** Each placed fall's origin column (its site's, for a set of sites), x and z. */
    final List<int[]> fallOrigins = new ArrayList<>();
    long area;
    /**
     * Whether a fall keeps every block it sets in the chunk being decorated (FluidFallFeature's lava falls, fix round 3);
     * false lets a fall reach over the chunk's edge as before, to measure what the bound changes.
     */
    boolean ownChunk = true;

    /**
     * The views' frame for {@link #fallsSeen}: its height in degrees (16:9). 70, the frame of a player who walks with the
     * game's own field of view; the rig's pictures are 88 high (the shot driver sets 80 and a flying spectator sees 1.1
     * times the setting), and show a few more falls at the frame's edges (fix round 3: the Caldera views of the big box,
     * 6 at most where 70 shows 5).
     */
    double viewFov = 70;
    /** The chunk being decorated. */
    private int ownCx, ownCz;
    /** The blocks the falls set outside the chunk being decorated, per feature. */
    final int[] outside = new int[N];

    GalleriesDecor(Terrain t, NoiseVox v, Path worldgen) {
        this.t = t;
        this.v = v;
        if (!Files.isDirectory(worldgen.resolve("placed_feature"))) throw new IllegalArgumentException("no worldgen data at " + worldgen.toAbsolutePath());
        for (int f = 0; f < N; f++) feats[f] = read(worldgen, f);
        boolean m;
        try {
            Walkway.class.getMethod("nearWayDown", Terrain.class, int.class, int.class, int.class, int.class, double.class, double.class);
            m = true;
        } catch (ReflectiveOperationException e) {
            m = false;
        }
        modern = m;
        // (a constant: reading it loads no Minecraft class)
        headKeep = m ? com.explorationremix.world.feature.FluidFallFeature.HEAD_KEEP : 0;
    }

    private static JsonObject json(Path worldgen, String path) {
        try (Reader r = Files.newBufferedReader(worldgen.resolve(path + ".json"))) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int[] uniform(JsonElement e) {
        if (e.isJsonPrimitive()) return new int[]{e.getAsInt(), e.getAsInt()};
        JsonObject u = e.getAsJsonObject().getAsJsonObject("value");
        return new int[]{u.get("min_inclusive").getAsInt(), u.get("max_inclusive").getAsInt()};
    }

    private static double[] sites(JsonObject o) {
        return new double[]{o.get("salt").getAsInt(), o.has("grid") ? o.get("grid").getAsInt() : 16, o.get("spacing").getAsDouble(),
                o.has("chance") ? o.get("chance").getAsDouble() : 1, o.has("keep") ? o.get("keep").getAsDouble() : 0,
                o.has("ring_min") ? o.get("ring_min").getAsDouble() : 0, o.has("ring_max") ? o.get("ring_max").getAsDouble() : 9,
                o.has("anchor") ? o.get("anchor").getAsDouble() : 0, o.has("aim") ? o.get("aim").getAsInt() : 0,
                o.has("rim_only") && o.get("rim_only").getAsBoolean() ? 1 : 0};
    }

    private static Feat read(Path worldgen, int f) {
        Feat a = new Feat();
        // a feature the data no longer has (0.8.0 fix round: the cliff falls) places nothing: no biome takes it
        if (!Files.exists(worldgen.resolve("placed_feature/" + IDS[f] + ".json"))) return a;
        for (JsonElement m : json(worldgen, "placed_feature/" + IDS[f]).getAsJsonArray("placement")) {
            JsonObject o = m.getAsJsonObject();
            String type = o.get("type").getAsString();
            switch (type) {
                case "minecraft:count" -> a.count = o.get("count").getAsInt();
                case "minecraft:rarity_filter" -> a.rarity = o.get("chance").getAsInt();
                case "minecraft:in_square" -> a.square = true;
                case "minecraft:biome" -> { }
                case "minecraft:random_offset" -> {
                    a.offset = true;
                    int[] u = uniform(o.get("y_spread"));
                    a.offLo = u[0];
                    a.offHi = u[1];
                }
                case "explorationremix:open_span" -> {
                    a.anchor = o.has("anchor") ? o.get("anchor").getAsString() : "any";
                    a.minSpan = o.has("min_span") ? o.get("min_span").getAsInt() : 16;
                    a.inset = o.has("inset") ? o.get("inset").getAsInt() : 2;
                    a.aboveWater = o.has("above_water") && o.get("above_water").getAsBoolean();
                }
                case "explorationremix:fall_sites" -> {
                    a.sites = sites(o.getAsJsonObject("sites"));
                    a.shareMin = o.has("share_min") ? o.get("share_min").getAsDouble() : 0;
                    a.shareMax = o.has("share_max") ? o.get("share_max").getAsDouble() : 1;
                }
                default -> throw new IllegalStateException(IDS[f] + ": a placement this emulation does not know: " + type);
            }
        }
        JsonObject cf = json(worldgen, "configured_feature/" + IDS[f]).getAsJsonObject("config");
        if (f == FLOOR) {
            if (cf.size() > 0) throw new IllegalStateException(IDS[f] + ": a configuration this emulation does not know: " + cf);
        } else if (f == POOLS) a.radius = cf.get("radius").getAsInt();
        else if (f == HANGING) {
            a.radius = cf.get("radius").getAsInt();
            a.n = cf.get("count").getAsInt();
            int[] h = uniform(cf.get("height"));
            a.hLo = h[0];
            a.hHi = h[1];
            a.clearance = cf.has("clearance") ? cf.get("clearance").getAsInt() : 0;
            if ((cf.has("hang_chance") ? cf.get("hang_chance").getAsFloat() : 0.25F) < 1) throw new IllegalStateException(IDS[f] + " no longer hangs every column");
        } else {
            a.minDrop = cf.has("min_drop") ? cf.get("min_drop").getAsInt() : 8;
            a.search = cf.has("search") ? cf.get("search").getAsInt() : 10;
            int[] w = cf.has("width") ? uniform(cf.get("width")) : new int[]{1, 1};
            a.wLo = w[0];
            a.wHi = w[1];
            a.minWidth = cf.has("min_width") ? cf.get("min_width").getAsInt() : 1;
            a.mode = cf.has("mode") ? cf.get("mode").getAsString() : "wall";
            a.tries = cf.has("tries") ? cf.get("tries").getAsInt() : 1;
            a.spread = cf.has("spread") ? cf.get("spread").getAsInt() : 0;
            if (cf.has("sites")) a.fallSites = sites(cf.getAsJsonObject("sites"));
        }
        for (int b = 0; b < BIOMES.length; b++)
            for (JsonElement step : json(worldgen, "biome/" + BIOMES[b]).getAsJsonArray("features"))
                for (JsonElement id : step.getAsJsonArray()) if (id.getAsString().equals("explorationremix:" + IDS[f])) a.biomes.add(DEEP[b]);
        return a;
    }

    static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF); }

    static int kx(long p) { return (int) (p >> 38); }

    static int ky(long p) { return (int) (p << 26 >> 52); }

    static int kz(long p) { return (int) (p << 38 >> 38); }

    private boolean air(int x, int y, int z) { return v.mat(x, y, z) == NoiseVox.AIR; }

    private boolean rock(int x, int y, int z) { return v.mat(x, y, z) == NoiseVox.ROCK; }

    private void put(int x, int y, int z, int m, int kind) {
        if (!v.in(x, y, z)) return;
        v.set(x, y, z, m);
        kinds.put(key(x, y, z), kind);
    }

    private int walker(int x, int y, int z) {
        int m = v.mat(x, y, z);
        return m == NoiseVox.ROCK ? Detour.SOLID : m == NoiseVox.AIR ? Detour.OPEN : Detour.OTHER;
    }

    // ================================================================================================ the run
    /** Decorates every chunk lying wholly inside [xa, xb) x [za, zb), chunk by chunk, each in the game's feature order. */
    GalleriesDecor run(long seed, int xa, int za, int xb, int zb) {
        for (int cx = Math.floorDiv(xa + 15, 16); cx * 16 + 16 <= xb; cx++)
            for (int cz = Math.floorDiv(za + 15, 16); cz * 16 + 16 <= zb; cz++) decorate(seed, cx, cz);
        return this;
    }

    /**
     * As {@link #run(long, int, int, int, int)}, the chunks in the order a random drawn from {@code order} shuffles them
     * into (the game decorates a chunk once its neighbours are generated, in no set order).
     */
    GalleriesDecor run(long seed, int xa, int za, int xb, int zb, long order) {
        List<int[]> chunks = new ArrayList<>();
        for (int cx = Math.floorDiv(xa + 15, 16); cx * 16 + 16 <= xb; cx++)
            for (int cz = Math.floorDiv(za + 15, 16); cz * 16 + 16 <= zb; cz++) chunks.add(new int[]{cx, cz});
        java.util.Collections.shuffle(chunks, new Random(order));
        for (int[] c : chunks) decorate(seed, c[0], c[1]);
        return this;
    }

    private void decorate(long seed, int cx, int cz) {
        Random r = new Random(Hashing.mix(seed, cx, cz, 4243));
        area += 256;
        ownCx = cx;
        ownCz = cz;
        for (int f = 0; f < N; f++) place(r, f, cx * 16, cz * 16);
    }

    /** Whether column (x, z) lies in the chunk being decorated. */
    private boolean own(int x, int z) { return x >> 4 == ownCx && z >> 4 == ownCz; }

    private void place(Random r, int f, int bx, int bz) {
        Feat a = feats[f];
        if (a.biomes.isEmpty()) return;   // in no biome's list: the game never runs it
        if (f == FLOOR) {
            // no placement and no biome filter: it runs in every chunk with a hall biome in its 3 x 3 chunks' sections,
            // which is every chunk of the Galleries (only the tubes' rock lining takes the tubes' biome)
            floor(bx, bz);
            return;
        }
        List<int[]> starts = new ArrayList<>();
        if (a.sites != null) {
            starts.addAll(Sites.aimsInChunk(t, a.sites, bx, bz, a.shareMin, a.shareMax));
        } else {
            for (int i = 0; i < a.count; i++) {
                if (a.rarity > 1 && r.nextFloat() >= 1F / a.rarity) continue;
                starts.add(a.square ? new int[]{bx + r.nextInt(16), bz + r.nextInt(16)} : new int[]{bx, bz});
            }
        }
        for (int[] s : starts) {
            int x = s[0], z = s[1];
            int y = span(r, a, x, z);
            if (y == NONE) continue;
            if (a.offset) y += a.offLo + r.nextInt(a.offHi - a.offLo + 1);
            if (!a.biomes.contains(t.biomeAt(x, y, z))) continue;
            tries[f]++;
            boolean ok = switch (f) {
                case POOLS -> pool(x, y, z, a.radius, f);
                case HANGING -> hanging(r, x, y, z, a, f) > 0;
                default -> fall(r, x, y, z, a, f);
            };
            if (ok) placed[f]++;
        }
    }

    /** OpenSpanPlacement: the row of the try in column (x, z), or NONE. */
    private int span(Random r, Feat a, int x, int z) {
        int li = Terrain.GALLERIES;
        int lo = (a.aboveWater ? t.shape.waterY(li) : t.shape.bottom(li)) + a.inset, hi = t.shape.top(li) - a.inset;
        if (hi - lo + 1 < a.minSpan) return NONE;
        boolean floor = a.anchor.equals("floor"), ceiling = a.anchor.equals("ceiling");
        int seen = 0, pickTop = 0, pickBottom = 0, top = NONE;
        for (int y = hi; y >= lo - 1; y--) {
            boolean isAir = y >= lo && air(x, y, z);
            if (isAir) {
                if (top == NONE) top = y;
                continue;
            }
            if (top != NONE) {
                int bottom = y + 1;
                if (top - bottom + 1 >= a.minSpan && (!floor || rock(x, bottom - 1, z)) && r.nextInt(++seen) == 0) {
                    pickTop = top;
                    pickBottom = bottom;
                }
                top = NONE;
            }
        }
        if (seen == 0) return NONE;
        return ceiling ? pickTop : floor ? pickBottom : pickBottom + r.nextInt(pickTop - pickBottom + 1);
    }

    // ------------------------------------------------------------------------------------------ TerracePoolsFeature
    private boolean isFloor(int x, int y, int z) { return rock(x, y, z) && air(x, y + 1, z) && air(x, y + 2, z); }

    private boolean poolFloor(int x, int y, int z) {
        return isFloor(x, y, z) && !Walkway.nearChannel(t, x, z, y, y + 1, com.explorationremix.world.feature.TerracePoolsFeature.CHANNEL_MARGIN);
    }

    /** A lava pool (TerracePoolsFeature.place, lava, no overflow) with its origin at (ox, oy, oz). */
    private boolean pool(int ox, int oy, int oz, int radius, int f) {
        int fy = oy - 1;
        if (!poolFloor(ox, fy, oz)) return false;
        int r = radius;
        while (r >= radius && Walkway.nearWays(t, ox, oz, fy, fy + 1, r + 1)) r--;
        if (r < radius) { guarded[f]++; return false; }
        if (Walkway.nearOpening(t, ox, oz, fy, r + 3)) { guarded[f]++; return false; }
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
        if (Walkway.channelled(t, fy)) {
            WalkWindow.Change change = new WalkWindow.Change();
            for (int dz = -r; dz <= r; dz++) for (int dx = -r; dx <= r; dx++) {
                if (!lava[(dz + r) * size + dx + r]) continue;
                change.add(ox + dx, fy, oz + dz, Detour.OTHER).add(ox + dx, fy - 1, oz + dz, Detour.SOLID);
            }
            if (!WalkWindow.keeps(this::walker, change)) return false;
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

    // ------------------------------------------------------------------------------------------ ColumnClusterFeature
    /** A cluster of hanging columns (ColumnClusterFeature.place, every column hanging, with its clearance); blocks set. */
    private int hanging(Random r, int ox, int oy, int oz, Feat a, int f) {
        int n = 0;
        for (int i = 0; i < a.n; i++) {
            int dx = r.nextInt(2 * a.radius + 1) - a.radius, dz = r.nextInt(2 * a.radius + 1) - a.radius;
            if (dx * dx + dz * dz > a.radius * a.radius) continue;
            double centre = 1 - Math.sqrt(dx * dx + dz * dz) / (a.radius + 1.0);
            int h = Math.max(1, (int) Math.round((a.hLo + r.nextInt(a.hHi - a.hLo + 1)) * (0.4 + 0.6 * centre)));
            r.nextFloat();   // the hang chance: 1, every column hangs
            int x = ox + dx, z = oz + dz, y = oy, tr = 0;
            if (!air(x, y, z)) continue;
            while (tr < 12 && air(x, y, z)) { y++; tr++; }
            if (tr >= 12 || air(x, y, z) || !rock(x, y, z)) continue;
            if (a.clearance > 0) {
                int open = 0;
                while (open < h + a.clearance && air(x, y - 1 - open, z)) open++;
                h = com.explorationremix.world.feature.ColumnFit.hangingLength(h, open, a.clearance);
                if (h < 1) continue;
            }
            if (Walkway.near(t, x, z, y, y - h, 1)) { guarded[f]++; continue; }
            for (int k = 0; k < h; k++) {
                y--;
                if (!air(x, y, z)) break;
                put(x, y, z, NoiseVox.ROCK, K_COLUMN);
                n++;
            }
        }
        blocks[f] += n;
        return n;
    }

    // ------------------------------------------------------------------------------------------ FluidFallFeature
    /** A lava fall (FluidFallFeature.place) from the origin, with its tries round it and its site's share. */
    private boolean fall(Random r, int ox, int oy, int oz, Feat a, int f) {
        int[] site = a.fallSites != null && modern ? Sites.siteOf(t, a.fallSites, ox, oz) : null;
        List<int[]> src = new ArrayList<>();
        if (fallAt(r, ox, oy, oz, a, f, site, src)) return record(f, src, ox, oz);
        for (int k = 1; k < a.tries; k++) {
            int dx = r.nextInt(2 * a.spread + 1) - a.spread, dz = r.nextInt(2 * a.spread + 1) - a.spread;
            int y = moved(a, ox + dx, oy, oz + dz);
            if (y != NONE && fallAt(r, ox + dx, y, oz + dz, a, f, site, src)) return record(f, src, ox, oz);
        }
        return false;
    }

    private boolean record(int f, List<int[]> src, int ox, int oz) {
        int[] e = new int[1 + 3 * src.size()];
        e[0] = f;
        for (int i = 0; i < src.size(); i++) System.arraycopy(src.get(i), 0, e, 1 + 3 * i, 3);
        falls.add(e);
        fallOrigins.add(new int[]{ox, oz});
        return true;
    }

    /** FluidFallFeature.moved: the row of a later try in the moved column, or NONE. */
    private int moved(Feat a, int x, int y, int z) {
        if (!a.mode.equals("ceiling")) return air(x, y, z) ? y : NONE;
        if (air(x, y, z)) {
            for (int k = 0; k < a.search; k++) if (!air(x, ++y, z)) return y - 1;
            return NONE;
        }
        for (int k = 0; k < a.search; k++) if (air(x, --y, z)) return y;
        return NONE;
    }

    private int wallDistance(Feat a, int ox, int oy, int oz, int dir) {
        for (int k = 1; k <= a.search; k++) {
            int x = ox + HX[dir] * k, z = oz + HZ[dir] * k;
            if (air(x, oy, z)) continue;
            return rock(x, oy, z) ? k : Integer.MAX_VALUE;
        }
        return Integer.MAX_VALUE;
    }

    private boolean fallAt(Random r, int ox, int oy, int oz, Feat a, int f, int[] site, List<int[]> src) {
        if (!air(ox, oy, oz)) return false;
        int dir = r.nextInt(4);
        boolean ceiling = a.mode.equals("ceiling");
        int wx, wy, wz;
        if (ceiling) {
            wx = ox;
            wy = oy + 1;
            wz = oz;
            if (!rock(wx, wy, wz)) return false;
        } else {
            if (a.mode.equals("nearest_wall")) {
                int best = Integer.MAX_VALUE, bestDir = -1;
                for (int k = 0; k < 4; k++, dir = (dir + 1) & 3) {
                    int d = wallDistance(a, ox, oy, oz, dir);
                    if (d < best) { best = d; bestDir = dir; }
                }
                if (bestDir < 0) return false;
                dir = bestDir;
            }
            int k = wallDistance(a, ox, oy, oz, dir);
            if (k == Integer.MAX_VALUE) return false;
            wx = ox + HX[dir] * k;
            wy = oy;
            wz = oz + HZ[dir] * k;
        }
        int side = (dir + 1) & 3;
        int width = a.wLo + r.nextInt(a.wHi - a.wLo + 1);
        // FluidFallFeature: every source planned before any is set; fewer than min_width, none
        List<int[]> plans = new ArrayList<>();
        List<long[]> walks = new ArrayList<>();   // the row's planned blocks in the kept rows (each a walker's OTHER)
        for (int w = 0; w < width; w++) {
            int sx = wx + HX[side] * w, sy = wy, sz = wz + HZ[side] * w;
            if (w > 0 && a.tries > 1) {
                int[] q = face(sx, sy, sz, ceiling ? -1 : dir);
                if (q == null) {
                    if (a.minWidth > 1) break;
                    continue;
                }
                sx = q[0];
                sy = q[1];
                sz = q[2];
            }
            Object[] plan = planOne(a, f, sx, sy, sz, dir, ceiling, site, walks);
            if (plan == null) {
                if (w == 0) return false;
                if (a.minWidth > 1) break;
                continue;
            }
            plans.add((int[]) plan[0]);
            if (plan[1] != null) {
                @SuppressWarnings("unchecked") List<long[]> k = (List<long[]>) plan[1];
                walks = k;
            }
        }
        if (plans.size() < a.minWidth) return false;
        for (int[] p : plans) {
            putOne(f, p);
            src.add(new int[]{p[0], p[1], p[2]});
        }
        return true;
    }

    /** FluidFallFeature.face: the rock face nearest (x, y, z) up the vault (into -1) or into the wall (into: a direction). */
    private int[] face(int x, int y, int z, int into) {
        int ix = into < 0 ? 0 : HX[into], iy = into < 0 ? 1 : 0, iz = into < 0 ? 0 : HZ[into];
        for (int k : new int[]{0, 1, -1, 2, -2}) {
            int qx = x + ix * k, qy = y + iy * k, qz = z + iz * k;
            if (rock(qx, qy, qz) && air(qx - ix, qy - iy, qz - iz)) return new int[]{qx, qy, qz};
        }
        return null;
    }

    private boolean sealed(int x, int y, int z) { return rock(x, y, z); }

    private boolean lavaSource(int x, int y, int z) {
        if (v.mat(x, y, z) != NoiseVox.LAVA) return false;
        Integer k = kinds.get(key(x, y, z));
        return k == null || k == K_POOL || k == K_SOURCE || k == K_SUMP || k == K_FLOOR;
    }

    // ------------------------------------------------------------------------------------------ FloorLavaFeature
    /**
     * FloorLavaFeature.place on the chunk at (bx, bz): on each of its columns, a lava source in the floor's top block
     * where the field's rule puts one ({@link GalleriesField#floorLavaRow}), the blocks still hold it (rock there and
     * under it, rock or a lava source on its four sides) and it lies open to the air above it (fix round 3: these blocks
     * have no slabs another mod lays, which the feature clears).
     */
    private void floor(int bx, int bz) {
        tries[FLOOR]++;
        int n = 0;
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++) {
                int x = bx + dx, z = bz + dz, y = Floor.row(t, x, z);
                if (y == NONE || !holds(x, y, z) || !air(x, y + 1, z)) continue;
                put(x, y, z, NoiseVox.LAVA, K_FLOOR);
                n++;
            }
        if (n > 0) placed[FLOOR]++;
        blocks[FLOOR] += n;
    }

    /** FloorLavaFeature's test for the block at (x, y, z): rock there and under it, rock or a lava source on its four sides. */
    private boolean holds(int x, int y, int z) {
        if (!rock(x, y, z) || !rock(x, y - 1, z)) return false;
        for (int d = 0; d < 4; d++) if (!rock(x + HX[d], y, z + HZ[d]) && !lavaSource(x + HX[d], y, z + HZ[d])) return false;
        return true;
    }

    /**
     * The floor's lava laid on plain blocks, no other decoration with it (what the terrain's tests walk on): FloorLavaFeature
     * on every column of the box, whose order does not change it (it turns rock into lava sources only, and both hold a
     * neighbour), open to the air above every block of it. Returns the blocks laid ({@link #key}).
     */
    static java.util.Set<Long> layFloorLava(Terrain t, NoiseVox v) {
        java.util.Set<Long> laid = new java.util.HashSet<>();
        for (int x = v.x0; x < v.x0 + v.nx; x++)
            for (int z = v.z0; z < v.z0 + v.nz; z++) {
                int y = Floor.row(t, x, z);
                if (y == NONE || !v.in(x, y - 1, z) || !v.in(x, y + 1, z) || v.mat(x, y, z) != NoiseVox.ROCK || v.mat(x, y - 1, z) != NoiseVox.ROCK
                        || v.mat(x, y + 1, z) != NoiseVox.AIR) continue;
                boolean held = true;
                for (int d = 0; d < 4 && held; d++) {
                    int m = v.mat(x + HX[d], y, z + HZ[d]);
                    held = m == NoiseVox.ROCK || m == NoiseVox.LAVA;   // no decoration here: every lava is a source
                }
                if (!held) continue;
                v.set(x, y, z, NoiseVox.LAVA);
                laid.add(key(x, y, z));
            }
        return laid;
    }

    /**
     * FluidFallFeature.plan: {plan, walks} for the source at (sx, sy, sz) on the blocks as they stand, the row's earlier
     * sources not yet set (their change to the kept walks in {@code walks}); plan is {sx, sy, sz, fx, fy, fz, drop, sump};
     * null if it may not pour.
     */
    private Object[] planOne(Feat a, int f, int sx, int sy, int sz, int dir, boolean ceiling, int[] site, List<long[]> walks) {
        int fx = ceiling ? sx : sx - HX[dir], fy = ceiling ? sy - 1 : sy, fz = ceiling ? sz : sz - HZ[dir];
        // a lava fall's source and face, and so its column and sump, in the chunk being decorated
        if (ownChunk && (!own(sx, sz) || !own(fx, fz))) return null;
        if (!rock(sx, sy, sz) || !air(fx, fy, fz)) return null;
        if (site != null && (!Sites.clear(t, a.fallSites, site[0], site[1], sx, sz) || !Sites.clear(t, a.fallSites, site[0], site[1], fx, fz))) { shared[f]++; return null; }
        if (ceiling) {
            if (!sealed(sx, sy + 1, sz)) return null;
            for (int d = 0; d < 4; d++) if (!sealed(sx + HX[d], sy, sz + HZ[d]) && v.mat(sx + HX[d], sy, sz + HZ[d]) != NoiseVox.LAVA) return null;
        } else {
            if (!sealed(sx, sy + 1, sz) || !sealed(sx, sy - 1, sz) || !sealed(sx + HX[dir], sy, sz + HZ[dir])) return null;
            for (int d : new int[]{(dir + 1) & 3, (dir + 3) & 3}) {
                int m = v.mat(sx + HX[d], sy, sz + HZ[d]);
                if (m != NoiseVox.ROCK && m != NoiseVox.LAVA) return null;
            }
        }
        int drop = 0;
        while (drop < 300 && air(fx, fy - 1 - drop, fz)) drop++;
        int py = fy - 1 - drop;
        if (drop < a.minDrop) return null;
        if (Walkway.near(t, fx, fz, fy - drop, fy, 2)) { guarded[f]++; return null; }
        if (Walkway.nearOpening(t, fx, fz, fy, 3)) { guarded[f]++; return null; }
        if (modern && Ways.nearWayDown(t, fx, fz, fy - drop, fy, 2, headKeep)) { guarded[f]++; return null; }
        int below = v.mat(fx, py, fz);
        boolean intoPool = below == NoiseVox.LAVA;
        if (!intoPool && below != NoiseVox.ROCK) return null;
        boolean sump = !intoPool;
        if (sump) {
            if (!sealed(fx, py - 1, fz)) return null;
            for (int d = 0; d < 4; d++) if (!sealed(fx + HX[d], py, fz + HZ[d]) && !lavaSource(fx + HX[d], py, fz + HZ[d])) return null;
            if (Walkway.channelled(t, fy) && !Detour.around(with(walks), fx, py + 1, fz)) return null;
        }
        List<long[]> kept = null;
        if (Walkway.channelled(t, fy)) {
            kept = new ArrayList<>(walks);
            kept.add(new long[]{sx, sy, sz});
            for (int q = 0; q <= drop; q++) kept.add(new long[]{fx, fy - q, fz});
            if (sump) kept.add(new long[]{fx, py, fz});
            WalkWindow.Change change = new WalkWindow.Change();
            for (long[] b : kept) change.add((int) b[0], (int) b[1], (int) b[2], Detour.OTHER);
            if (!WalkWindow.keeps(this::walker, change)) return null;
        }
        return new Object[]{new int[]{sx, sy, sz, fx, fy, fz, drop, sump ? 1 : 0}, kept};
    }

    /** The walker's blocks with the row's planned blocks made (WalkWindow.with, kept here so the 0.8.0 tree runs it too). */
    private Detour.Blocks with(List<long[]> planned) {
        if (planned.isEmpty()) return this::walker;
        java.util.Set<Long> made = new java.util.HashSet<>();
        for (long[] b : planned) made.add(key((int) b[0], (int) b[1], (int) b[2]));
        return (x, y, z) -> made.contains(key(x, y, z)) ? Detour.OTHER : walker(x, y, z);
    }

    private void putOne(int f, int[] p) {
        int sx = p[0], sy = p[1], sz = p[2], fx = p[3], fy = p[4], fz = p[5], drop = p[6];
        boolean sump = p[7] != 0;
        put(sx, sy, sz, NoiseVox.LAVA, K_SOURCE);
        for (int q = 0; q <= drop; q++) put(fx, fy - q, fz, NoiseVox.LAVA, K_FALL);
        if (sump) put(fx, fy - drop - 1, fz, NoiseVox.LAVA, K_SUMP);
        blocks[f] += drop + 2 + (sump ? 1 : 0);
        if (!own(sx, sz)) outside[f]++;
        if (!own(fx, fz)) outside[f] += drop + 1 + (sump ? 1 : 0);
    }

    // ================================================================================================ the measures
    /** Falls as fallcount.py counts them: 8-neighbour groups of columns holding 20 or more falling lava blocks. */
    List<int[]> fallGroups() {
        Map<Long, Integer> cols = new HashMap<>();
        for (Map.Entry<Long, Integer> e : kinds.entrySet()) {
            if (e.getValue() != K_FALL) continue;
            long p = e.getKey();
            if (v.mat(kx(p), ky(p), kz(p)) != NoiseVox.LAVA) continue;
            cols.merge(((long) kx(p) << 32) | (kz(p) & 0xFFFFFFFFL), 1, Integer::sum);
        }
        List<int[]> groups = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Map.Entry<Long, Integer> e : cols.entrySet()) {
            if (e.getValue() < 20 || seen.contains(e.getKey())) continue;
            ArrayDeque<Long> st = new ArrayDeque<>();
            st.add(e.getKey());
            seen.add(e.getKey());
            long sx = 0, sz = 0;
            int n = 0;
            while (!st.isEmpty()) {
                long c = st.pop();
                int x = (int) (c >> 32), z = (int) c;
                sx += x;
                sz += z;
                n++;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    long q = ((long) (x + dx) << 32) | ((z + dz) & 0xFFFFFFFFL);
                    Integer k = cols.get(q);
                    if (k != null && k >= 20 && seen.add(q)) st.add(q);
                }
            }
            groups.add(new int[]{(int) Math.round((double) sx / n), (int) Math.round((double) sz / n), n});
        }
        return groups;
    }

    /**
     * How many falls a view shows (fix round 2 after the 0.8.0 pack runs: the Causeway's falls stood in colonnades, a
     * dozen and more in a view): a fall is seen from an eye when a point of its column (under its first source, down to
     * rock, at five heights) lies in the frame ({@link #viewFov}, 16:9), within 192 blocks (render distance 12), with no
     * rock on the line to it. Yaw and pitch as Minecraft's (yaw 0 south, 90 west; pitch down).
     */
    int fallsSeen(double ex, double ey, double ez, double yaw, double pitch) { return fallsSeen(ex, ey, ez, yaw, pitch, -1); }

    /** As {@link #fallsSeen(double, double, double, double, double)}, the falls of feature {@code kind} only (all for -1). */
    int fallsSeen(double ex, double ey, double ez, double yaw, double pitch, int kind) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double[] fwd = {-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr)};
        double[] right = {-Math.cos(yr), 0, -Math.sin(yr)};
        double[] up = {right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0]};
        double tv = Math.tan(Math.toRadians(viewFov / 2)), th = tv * 16 / 9.0;
        int seen = 0;
        for (int[] f : falls) {
            if (kind >= 0 && f[0] != kind) continue;
            int sx = f[1], sy = f[2], sz = f[3], y = sy - 1;
            while (y > v.y0 && v.mat(sx, y, sz) != NoiseVox.ROCK) y--;
            double cx = sx + 0.5, cz = sz + 0.5, dx = cx - ex, dz = cz - ez;
            if (dx * dx + dz * dz > 192 * 192) continue;
            for (int k = 0; k < 5; k++) {
                double py = y + 1 + (sy - y - 1) * (k + 0.5) / 5.0, dy = py - ey;
                double fw = dx * fwd[0] + dy * fwd[1] + dz * fwd[2];
                if (fw <= 1) continue;
                double rr = dx * right[0] + dy * right[1] + dz * right[2], uu = dx * up[0] + dy * up[1] + dz * up[2];
                if (Math.abs(rr) > th * fw || Math.abs(uu) > tv * fw) continue;
                if (clearTo(ex, ey, ez, cx, py, cz)) { seen++; break; }
            }
        }
        return seen;
    }

    /** No rock on the line from an eye to a point of a fall's column (the column and the blocks round it aside), inside the box. */
    private boolean clearTo(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = bx - ax, dy = by - ay, dz = bz - az;
        int n = (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) / 0.4);
        for (int i = 1; i < n; i++) {
            double q = i / (double) n, x = ax + dx * q, y = ay + dy * q, z = az + dz * q;
            if (Math.abs(x - bx) < 1.6 && Math.abs(z - bz) < 1.6) return true;
            int xi = (int) Math.floor(x), yi = (int) Math.floor(y), zi = (int) Math.floor(z);
            if (!v.in(xi, yi, zi) || v.mat(xi, yi, zi) == NoiseVox.ROCK) return false;
        }
        return true;
    }

    /**
     * The falls {@code n} views show: from standing places drawn at random in the box (a column, then one of its floors
     * over the lava table at random, in the Galleries' halls), looking a random way and 0 to 15 degrees down. Returns the
     * views' counts, highest first.
     */
    int[] fallsPerView(int n, long seed) {
        return fallsPerView(n, seed, EnumSet.of(DeepBiome.BASALT_GALLERIES, DeepBiome.CAUSEWAY, DeepBiome.SCORIA_CANYONS, DeepBiome.CALDERA));
    }

    /** As {@link #fallsPerView(int, long)}, from standing places in the Caldera only. */
    int[] fallsPerCalderaView(int n, long seed) { return fallsPerView(n, seed, EnumSet.of(DeepBiome.CALDERA)); }

    /** As {@link #fallsPerView(int, long)}, from standing places in the biomes given. */
    int[] fallsPerView(int n, long seed, EnumSet<DeepBiome> in) { return fallsPerView(n, seed, in, -1); }

    /** As {@link #fallsPerView(int, long, EnumSet)}, counting the falls of feature {@code kind} only (all for -1). */
    int[] fallsPerView(int n, long seed, EnumSet<DeepBiome> in, int kind) {
        java.util.Random r = new java.util.Random(seed);
        int[] out = new int[n];
        int made = 0, w = t.galleries.W;
        for (int tries = 0; made < n && tries < 200 * n; tries++) {
            int x = v.x0 + r.nextInt(v.nx), z = v.z0 + r.nextInt(v.nz);
            List<Integer> floors = new ArrayList<>();
            for (int y = w + 1; y < v.y0 + v.ny - 3; y++)
                if (v.mat(x, y - 1, z) == NoiseVox.ROCK && v.mat(x, y, z) == NoiseVox.AIR && v.mat(x, y + 1, z) == NoiseVox.AIR && v.mat(x, y + 2, z) == NoiseVox.AIR) floors.add(y);
            if (floors.isEmpty()) continue;
            int y = floors.get(r.nextInt(floors.size()));
            if (!in.contains(t.biomeAt(x, y + 1, z))) continue;
            out[made++] = fallsSeen(x + 0.5, y + 1.62, z + 0.5, r.nextDouble() * 360, r.nextDouble() * 15, kind);
        }
        int[] got = Arrays.copyOf(out, made);
        Arrays.sort(got);
        for (int i = 0; i < got.length / 2; i++) { int q = got[i]; got[i] = got[got.length - 1 - i]; got[got.length - 1 - i] = q; }
        return got;
    }

    /**
     * The falls that pour from the underside of a hanging mass (fix round 3): a source in the rock of a cluster hanging
     * GalleriesField.ANCHOR_HANG rows or more ({@link GalleriesField#hang} over 0 at the source).
     */
    int fromMasses() {
        int n = 0;
        for (int[] f : falls)
            for (int i = 1; i < f.length; i += 3)
                if (t.galleries.hang(f[i], f[i + 1], f[i + 2]) > 0 && t.galleries.column(f[i], f[i + 2]).hang >= GalleriesField.ANCHOR_HANG) {
                    n++;
                    break;
                }
        return n;
    }

    /** Each placed fall's distance to the nearest other fall (the nearest two of their sources' columns). */
    double[] nearestFalls() {
        double[] out = new double[falls.size()];
        for (int a = 0; a < falls.size(); a++) {
            double best = Double.MAX_VALUE;
            int[] fa = falls.get(a);
            for (int b = 0; b < falls.size(); b++) {
                if (a == b) continue;
                int[] fb = falls.get(b);
                if (Math.abs(fa[1] - fb[1]) > 200 || Math.abs(fa[3] - fb[3]) > 200) continue;
                for (int i = 1; i < fa.length; i += 3) for (int j = 1; j < fb.length; j += 3)
                    best = Math.min(best, Math.hypot(fa[i] - fb[j], fa[i + 2] - fb[j + 2]));
            }
            out[a] = best;
        }
        Arrays.sort(out);
        return out;
    }

    /**
     * The decorations' lava on the ways down from the Wasteland of the opened openings given: blocks of a pool, fall,
     * source, sump or the floor's lava in a ramp's deck band (its deck widened by 2 blocks each way, from 2 rows under the deck's row to
     * RAMP_HEAD over it) or in a hole's throat (within 2 blocks of its outline and lip, any row of the Galleries). {ramps
     * with any, blocks}; the first few listed into {@code where}.
     */
    int[] lavaOnWays(List<GalleriesField.Opening> ops, List<String> where) {
        GalleriesField g = t.galleries;
        int rampsHit = 0, n = 0;
        for (GalleriesField.Opening o : ops) {
            GalleriesField.Ramp rp = o.ramp;
            double t0 = o.r + GalleriesField.LIP;
            int hit = 0;
            for (Map.Entry<Long, Integer> e : kinds.entrySet()) {
                int k = e.getValue();
                if (k != K_POOL && k != K_FALL && k != K_SOURCE && k != K_SUMP && k != K_FLOOR) continue;
                long p = e.getKey();
                int x = kx(p), y = ky(p), z = kz(p);
                if (v.mat(x, y, z) != NoiseVox.LAVA) continue;
                double dx = x + 0.5 - o.x, dz = z + 0.5 - o.z;
                if (Math.abs(dx) > o.reach + 10 || Math.abs(dz) > o.reach + 10) continue;
                double d = Math.hypot(dx, dz), tt = dx * rp.ux + dz * rp.uz, l = dx * rp.uz - dz * rp.ux;
                boolean on = d - g.rimR(o, dx, dz, d, tt) - GalleriesField.LIP < 2;
                if (!on && tt >= t0 - 1 && tt <= rp.tEnd) {
                    double b = g.bend(rp, t0, tt);
                    if (Math.abs(l - b) < g.halfWidth(rp, t0, tt, b) + 2) {
                        int row = (int) Math.floor(g.deckPlane(rp, t0, tt) + g.deckTerrace(x + 0.5, z + 0.5, Math.max(0, tt - t0)) + 0.5);
                        on = y >= row - 2 && y <= row + GalleriesField.RAMP_HEAD;
                    }
                }
                if (!on) continue;
                hit++;
                if (where.size() < 8) where.add(String.format(Locale.ROOT, "hole %.1f,%.1f: %s at (%d,%d,%d)", o.x, o.z, k == K_POOL ? "pool" : k == K_SUMP ? "sump" : k == K_FLOOR ? "floor lava" : "fall", x, y, z));
            }
            if (hit > 0) rampsHit++;
            n += hit;
        }
        return new int[]{rampsHit, n};
    }

    /**
     * The floor's lava laid (FloorLavaFeature) as it stands once every chunk is decorated: {blocks, blocks that could
     * flow, blocks under anything but air}, a block that could flow having under it or on one of its four sides anything
     * but rock or a lava source (a fall's flowing lava, air), and a covered one a block over it (one that could rest on
     * the lava alone); the first few of either listed into {@code where}. Blocks on the box's edge are left out.
     */
    int[] floorLavaHeld(List<String> where) {
        int n = 0, loose = 0, covered = 0;
        for (Map.Entry<Long, Integer> e : kinds.entrySet()) {
            if (e.getValue() != K_FLOOR) continue;
            long p = e.getKey();
            int x = kx(p), y = ky(p), z = kz(p);
            if (v.mat(x, y, z) != NoiseVox.LAVA || x == v.x0 || z == v.z0 || x == v.x0 + v.nx - 1 || z == v.z0 + v.nz - 1) continue;
            n++;
            if (!air(x, y + 1, z)) {
                covered++;
                if (where.size() < 8) where.add(String.format(Locale.ROOT, "(%d,%d,%d) under %d", x, y, z, v.mat(x, y + 1, z)));
            }
            String open = !rock(x, y - 1, z) ? "under" : null;
            for (int d = 0; d < 4 && open == null; d++)
                if (!rock(x + HX[d], y, z + HZ[d]) && !lavaSource(x + HX[d], y, z + HZ[d])) open = new String[]{"north", "east", "south", "west"}[d];
            if (open == null) continue;
            loose++;
            if (where.size() < 8) where.add(String.format(Locale.ROOT, "(%d,%d,%d) open %s", x, y, z, open));
        }
        return new int[]{n, loose, covered};
    }

    int placedFalls() { return placed[VAULT] + placed[CLIFF] + placed[CALDERA]; }

    @Override public String toString() {
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "decorated %.3f km2 (%d chunks):", area / 1e6, area / 256));
        for (int f = 0; f < N; f++)
            b.append(String.format(Locale.ROOT, " %s %d of %d tries (%d blocks, %d outside the chunk; guard %d, site share %d);", NAMES[f], placed[f], tries[f], blocks[f], outside[f], guarded[f], shared[f]));
        return b.toString();
    }

    /** Falls per 128-block tile, highest first. */
    static String tiles(List<int[]> groups) {
        Map<String, Integer> m = new TreeMap<>();
        for (int[] gr : groups) m.merge(Math.floorDiv(gr[0], 128) * 128 + "," + Math.floorDiv(gr[1], 128) * 128, 1, Integer::sum);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((p, q) -> q.getValue() - p.getValue());
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(8, l.size()); i++) s.append(i > 0 ? ", " : "").append(l.get(i).getKey()).append(':').append(l.get(i).getValue());
        return s.toString();
    }

    // ------------------------------------------------------------------------- calls only a tree with them makes
    /** FallSites (absent from older trees: loaded only when the data names a set of sites). */
    static final class Sites {
        /** Whether the set has an aim (fix round 3: a site's fall pours from under the hanging mass by it). */
        static boolean aims(double[] s) { return s.length > 8 && s[8] > 0; }

        /** Whether the set has fix round 3's fields (an aim, or only its rim candidates counted). */
        static boolean round3(double[] s) { return aims(s) || (s.length > 9 && s[9] > 0); }

        static com.explorationremix.world.feature.FallSites.Spec spec(double[] s) {
            if (round3(s)) return new com.explorationremix.world.feature.FallSites.Spec((int) s[0], (int) s[1], s[2], s[3], s[4], s[5], s[6], s[7], (int) s[8],
                    s.length > 9 && s[9] > 0);
            return new com.explorationremix.world.feature.FallSites.Spec((int) s[0], (int) s[1], s[2], s[3], s[4], s[5], s[6], s.length > 7 ? s[7] : 0);
        }

        static List<int[]> inChunk(Terrain t, double[] s, int x0, int z0, double lo, double hi) {
            List<int[]> out = new ArrayList<>();
            com.explorationremix.world.feature.FallSites.inChunk(t, spec(s), x0, z0, lo, hi, out);
            return out;
        }

        /** FallSitesPlacement: the columns the falls of the chunk pour from (the sites themselves without an aim). */
        static List<int[]> aimsInChunk(Terrain t, double[] s, int x0, int z0, double lo, double hi) {
            if (!aims(s)) return inChunk(t, s, x0, z0, lo, hi);
            List<int[]> out = new ArrayList<>();
            com.explorationremix.world.feature.FallSites.aimsInChunk(t, spec(s), x0, z0, lo, hi, out);
            return out;
        }

        /** The site a fall pouring from column (x, z) belongs to (the column itself without an aim). */
        static int[] siteOf(Terrain t, double[] s, int x, int z) {
            return aims(s) ? com.explorationremix.world.feature.FallSites.siteOf(t, spec(s), x, z) : new int[]{x, z};
        }

        static boolean clear(Terrain t, double[] s, int siteX, int siteZ, int x, int z) {
            return com.explorationremix.world.feature.FallSites.clear(t, spec(s), siteX, siteZ, x, z);
        }
    }

    /** GalleriesField.floorLavaRow (absent from older trees: called only when the data has the floor's lava). */
    static final class Floor {
        static int row(Terrain t, int x, int z) {
            int y = t.galleries.floorLavaRow(x, z);
            return y == GalleriesField.NO_LAVA ? NONE : y;
        }
    }

    /** Walkway.nearWayDown (absent from older trees). */
    static final class Ways {
        static boolean nearWayDown(Terrain t, int x, int z, int y0, int y1, double margin, double headMargin) {
            return Walkway.nearWayDown(t, x, z, y0, y1, margin, headMargin);
        }
    }
}
