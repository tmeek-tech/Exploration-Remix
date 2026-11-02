package com.explorationremix.preview;

import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Net;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Rock;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline preview of the deep world's shape: renders the terrain the way the game makes it (the density at its 4 x 8 x
 * 4 corners, interpolated, then the network's per-block pass), without starting Minecraft. Per layer of cell (0, 0):
 * the longest tunnel unrolled with its walking floor, sections across the six longest tunnels, a map and the same map
 * with the plan drawn over it, x and z sections through each set piece, and straight sections through the cell; then
 * the stack of layers through a gate. The layers made by the noise fields have no plan: a map and two sections each, and
 * in {@code mat} mode their cavern views (spec-looks 7.1: material slices and biome maps at four heights, material
 * sections through the largest open regions) and the join's views ({@code join_*}: a map of the seal, the openings and
 * any lid, sections through the openings over the join's rows). Usage: TerrainPreview outDir [seed] [all|0|1|2|3|mat].
 */
public final class TerrainPreview {
    private final Terrain t;
    private final Shape shape;
    private final Map<Long, Double> corners = new HashMap<>();

    TerrainPreview(Terrain t) { this.t = t; this.shape = t.shape; }

    private double corner(int cx, int cy, int cz) {
        long k = ((long) (cx & 0xFFFFF) << 40) | ((long) (cy & 0xFFFFF) << 20) | (cz & 0xFFFFF);
        Double v = corners.get(k);
        if (v == null) {
            v = t.density(cx * 4, cy * 8, cz * 4);
            corners.put(k, v);
        }
        return v;
    }

    /** Interpolated density, as NoiseChunk computes it. */
    double interp(int x, int y, int z) {
        int cx = Math.floorDiv(x, 4), cy = Math.floorDiv(y, 8), cz = Math.floorDiv(z, 4);
        double fx = (x - cx * 4) / 4.0, fy = (y - cy * 8) / 8.0, fz = (z - cz * 4) / 4.0;
        double c000 = corner(cx, cy, cz), c100 = corner(cx + 1, cy, cz), c010 = corner(cx, cy + 1, cz), c110 = corner(cx + 1, cy + 1, cz);
        double c001 = corner(cx, cy, cz + 1), c101 = corner(cx + 1, cy, cz + 1), c011 = corner(cx, cy + 1, cz + 1), c111 = corner(cx + 1, cy + 1, cz + 1);
        double x00 = c000 + (c100 - c000) * fx, x10 = c010 + (c110 - c010) * fx, x01 = c001 + (c101 - c001) * fx, x11 = c011 + (c111 - c011) * fx;
        double y0 = x00 + (x10 - x00) * fy, y1 = x01 + (x11 - x01) * fy;
        return y0 + (y1 - y0) * fz;
    }

    static int biomeColor(DeepBiome b) {
        if (b == null) return 0x666666;
        return switch (b) {
            case IVORY_ROOT_CAVERN -> 0xF2E6C8;
            case LANTERN_LAKE -> 0xE8B04A;
            case ASURINE_GEODES -> 0x9FD2FF;
            case DEEP_BLUE_RIFT -> 0x4A7BD8;
            case SILVER_FERN_RIFT -> 0x9DC48A;
            case DEEP_DARK -> 0x2F5560;
            case RUST_SPRINGS -> 0xD8603A;
            case EMBER_GARDENS -> 0xF09A50;
            case BASALT_GALLERIES -> 0xC0A090;
            case OBSIDIAN_TUBES -> 0x7A3FB0;
            case WASTELAND -> 0x8C6F5A;
            case ASH_HOLLOWS -> 0xD8D2C8;
            case CALDERA -> 0xF0562A;
            case CAUSEWAY -> 0x8E93A8;
            case SCORIA_CANYONS -> 0xB8603E;
        };
    }

    /** The density as the game ends up with it: interpolated, then the network's per-block pass. */
    double blockDensity(int x, int y, int z) { return t.fine(x, y, z, interp(x, y, z)); }

    /** 0 rock, 1 air, 2 water, 3 lava, 4 band rock. */
    int kind(int x, int y, int z) {
        if (y < shape.floorY) return 0;
        double d = blockDensity(x, y, z);
        if (d > 0) return shape.bandAt(y) > 0 ? 4 : 0;
        int f = t.fluidAt(x, y, z);
        return f == Terrain.WATER ? 2 : (f == Terrain.LAVA ? 3 : 1);
    }

    int pixel(int x, int y, int z) {
        int k = kind(x, y, z);
        return switch (k) {
            case 0 -> shape.layerAt(y) >= 0 ? 0x3A3A3A : 0x2A2A2A;
            case 4 -> 0x24182E;
            case 2 -> 0x2E6FD0;
            case 3 -> 0xFF7A1A;
            default -> biomeColor(t.biomeAt(x, y, z));
        };
    }

    /** Vertical section along a line from (x0, z0) to (x1, z1), one pixel per block, from y0 up to y1. */
    BufferedImage section(double x0, double z0, double x1, double z1, int y0, int y1) {
        int len = (int) Math.round(Math.hypot(x1 - x0, z1 - z0));
        BufferedImage img = new BufferedImage(len, y1 - y0 + 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < len; i++) {
            double f = i / (double) Math.max(1, len - 1);
            int x = (int) Math.floor(x0 + (x1 - x0) * f), z = (int) Math.floor(z0 + (z1 - z0) * f);
            for (int y = y0; y <= y1; y++) img.setRGB(i, y1 - y, pixel(x, y, z));
        }
        return img;
    }

    /** A point of a way at arc length s: {x, z, floor, dx, dz} (the unit direction there). */
    static double[] wayAt(Net.Way w, double s) {
        double acc = 0;
        for (int i = 1; i < w.n; i++) {
            double l = Math.hypot(w.x[i] - w.x[i - 1], w.z[i] - w.z[i - 1]);
            if (acc + l >= s || i == w.n - 1) {
                double f = l < 1e-9 ? 0 : Math.max(0, Math.min(1, (s - acc) / l));
                return new double[]{w.x[i - 1] + (w.x[i] - w.x[i - 1]) * f, w.z[i - 1] + (w.z[i] - w.z[i - 1]) * f,
                        w.f[i - 1] + (w.f[i] - w.f[i - 1]) * f, (w.x[i] - w.x[i - 1]) / Math.max(1e-9, l), (w.z[i] - w.z[i - 1]) / Math.max(1e-9, l)};
            }
            acc += l;
        }
        return new double[]{w.x[0], w.z[0], w.f[0], 1, 0};
    }

    /** The tunnels of a plan (no inner walks, ledges or stubs), longest first. */
    static List<Net.Way> tunnels(Net n) {
        List<Net.Way> out = new ArrayList<>();
        for (Net.Way w : n.ways) if (!w.inner && !w.ledge && !w.taper && w.a >= 0 && w.b >= 0 && w.n > 1) out.add(w);
        out.sort(Comparator.comparingDouble((Net.Way w) -> -w.length));
        return out;
    }

    /** A way unrolled: x is the arc length, y is height; its walking floor is drawn in red. */
    BufferedImage waySection(Net.Way w, int li) {
        int len = Math.max(1, (int) w.length);
        int y0 = shape.bottom(li) - 6, y1 = shape.top(li) + 20;
        BufferedImage img = new BufferedImage(len, y1 - y0 + 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < len; i++) {
            double[] p = wayAt(w, i);
            int x = (int) Math.floor(p[0]), z = (int) Math.floor(p[1]);
            for (int y = y0; y <= y1; y++) img.setRGB(i, y1 - y, pixel(x, y, z));
            int fy = (int) Math.floor(p[2]) - 1;
            if (fy >= y0 && fy <= y1) img.setRGB(i, y1 - fy, 0xFF0000);
        }
        return img;
    }

    /** Sections across a plan's longest tunnels at their middles, side by side, each {@code half} to either side. */
    BufferedImage fan(Net n, int li, int count, int half, boolean materials) {
        List<Net.Way> ws = tunnels(n);
        int k = Math.min(count, ws.size());
        int y0 = shape.bottom(li) - 8, y1 = shape.top(li) + 20;
        BufferedImage fan = new BufferedImage(Math.max(1, k) * (2 * half + 10), y1 - y0 + 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = fan.createGraphics();
        for (int i = 0; i < k; i++) {
            Net.Way w = ws.get(i);
            double[] a = wayAt(w, w.length * 0.5);
            double nx = -a[4], nz = a[3];
            BufferedImage sec = materials ? materialSection(a[0] - nx * half, a[1] - nz * half, a[0] + nx * half, a[1] + nz * half, y0, y1)
                    : section(a[0] - nx * half, a[1] - nz * half, a[0] + nx * half, a[1] + nz * half, y0, y1);
            g.drawImage(sec, i * (2 * half + 10), 0, null);
        }
        g.dispose();
        return fan;
    }

    /** Top-down map of a layer: brightness is how much of the column is open, colour is the biome at the opening. */
    BufferedImage topDown(int li, int x0, int z0, int size, int step) {
        int n = size / step;
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        int top = shape.top(li), bot = shape.bottom(li);
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            int x = x0 + i * step, z = z0 + j * step;
            int open = 0, water = 0, lava = 0;
            DeepBiome biome = null;
            int firstOpen = Integer.MIN_VALUE;
            for (int y = bot; y <= top; y += 2) {
                int k = kind(x, y, z);
                if (k == 1) { open++; if (firstOpen == Integer.MIN_VALUE) { firstOpen = y; biome = t.biomeAt(x, y, z); } }
                else if (k == 2) water++;
                else if (k == 3) lava++;
            }
            int total = (top - bot) / 2 + 1;
            double frac = Math.min(1, (open + water + lava) / (double) total * 2.2);
            int base = (water > 0 && open < 6) ? 0x2E6FD0 : (lava > 0 && open < 6 ? 0xFF7A1A : biomeColor(biome));
            if (open + water + lava == 0) { img.setRGB(i, j, 0x202020); continue; }
            Color c = new Color(base);
            int rr = (int) (c.getRed() * (0.25 + 0.75 * frac)), gg = (int) (c.getGreen() * (0.25 + 0.75 * frac)), bb = (int) (c.getBlue() * (0.25 + 0.75 * frac));
            if (water > 0 && open >= 6) { rr = rr * 2 / 3; gg = gg * 2 / 3 + 20; bb = Math.min(255, bb * 2 / 3 + 70); }
            img.setRGB(i, j, (rr << 16) | (gg << 8) | bb);
        }
        return img;
    }

    // ------------------------------------------------------------------------------------------ materials

    private static final int M_AIR = -1, M_WATER = -2, M_LAVA = -3, M_STONE = -4;

    /**
     * One column of blocks from the deep floor up to the pack's floor, as the game would place them: the aquifer's
     * choices first (fluids, band stone, bedrock, deep rock), then the surface pass over the remaining stone.
     */
    int[] column(int x, int z) {
        int n = shape.packFloor - shape.floorY;
        int[] m = new int[n];
        double[] dens = new double[n];
        for (int i = 0; i < n; i++) {
            int y = shape.floorY + i;
            double d = blockDensity(x, y, z);
            dens[i] = d;
            if (d <= 0) {
                int f = t.fluidAt(x, y, z);
                m[i] = f == Terrain.WATER ? M_WATER : f == Terrain.LAVA ? M_LAVA : M_AIR;
                continue;
            }
            m[i] = M_STONE;
            if (y <= shape.floorY + 4) {
                double chance = (shape.floorY + 5 - y) / 5.0;
                if (y == shape.floorY || Hashing.unit(Hashing.mix(t.seed ^ 0x51ED270B27F1A3C9L, x, y, z)) < chance) { m[i] = Rock.BEDROCK.ordinal(); continue; }
            }
            int li = shape.layerAt(y);
            if (li < 0) { m[i] = Rock.BAND_STONE.ordinal(); continue; }
            if (d > 0.75) m[i] = t.palette.interior(li, x, y, z).ordinal();
        }
        // surface pass, top down, as SurfaceSystem.buildSurface does it
        int above = 0, waterHeight = Integer.MIN_VALUE, runBottom = Integer.MAX_VALUE;
        for (int i = n - 1; i >= 0; i--) {
            int y = shape.floorY + i;
            if (m[i] == M_AIR) { above = 0; waterHeight = Integer.MIN_VALUE; continue; }
            if (m[i] == M_WATER || m[i] == M_LAVA) { if (waterHeight == Integer.MIN_VALUE) waterHeight = y + 1; continue; }
            if (runBottom >= y) {
                runBottom = Integer.MIN_VALUE;
                for (int j = i - 1; j >= -1; j--) {
                    if (j < 0 || m[j] == M_AIR || m[j] == M_WATER || m[j] == M_LAVA) { runBottom = shape.floorY + j + 1; break; }
                }
            }
            above++;
            int below = y - runBottom + 1;
            if (m[i] == M_STONE) m[i] = t.palette.shell(x, y, z, above, below, waterHeight != Integer.MIN_VALUE).ordinal();
        }
        return m;
    }

    static int matColor(int v) {
        if (v == M_AIR) return 0x0b0b10;
        if (v == M_WATER) return 0x2f6fbf;
        if (v == M_LAVA) return 0xff7a10;
        if (v == M_STONE) return 0xff00ff;
        return Rock.values()[v].color;
    }

    BufferedImage materialSection(double x0, double z0, double x1, double z1, int y0, int y1) {
        double len = Math.hypot(x1 - x0, z1 - z0);
        int w = (int) Math.ceil(len), h = y1 - y0 + 1;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w; i++) {
            int x = (int) Math.floor(x0 + (x1 - x0) * i / len), z = (int) Math.floor(z0 + (z1 - z0) * i / len);
            int[] col = column(x, z);
            for (int y = y0; y <= y1; y++) {
                int c = y < shape.floorY || y >= shape.packFloor ? 0x303030 : matColor(col[y - shape.floorY]);
                img.setRGB(i, y1 - y, c);
            }
        }
        return img;
    }

    /**
     * The plan over a map: the ways' walking lines (red; inner walks orange), the climbs (cyan), the chambers' centres
     * (yellow), the way in (green) and the way on (magenta).
     */
    void overlay(BufferedImage img, Net n, int x0, int z0, int step) {
        Graphics2D g = img.createGraphics();
        for (Net.Way w : n.ways) {
            g.setColor(w.inner ? Color.ORANGE : Color.RED);
            for (int i = 1; i < w.n; i++)
                g.drawLine((int) ((w.x[i - 1] - x0) / step), (int) ((w.z[i - 1] - z0) / step), (int) ((w.x[i] - x0) / step), (int) ((w.z[i] - z0) / step));
        }
        g.setColor(Color.CYAN);
        for (Net.Shaft sh : n.shafts) g.drawOval((int) ((sh.x - x0) / step) - 2, (int) ((sh.z - z0) / step) - 2, 4, 4);
        g.setColor(Color.YELLOW);
        for (Net.Chamber c : n.ch) g.fillRect((int) ((c.x - x0) / step) - 1, (int) ((c.z - z0) / step) - 1, 3, 3);
        if (n.entry >= 0) {
            Net.Chamber e = n.ch.get(n.entry);
            g.setColor(Color.GREEN);
            g.drawOval((int) ((e.x - x0) / step) - 4, (int) ((e.z - z0) / step) - 4, 8, 8);
        }
        if (n.exit >= 0) {
            Net.Chamber e = n.ch.get(n.exit);
            g.setColor(Color.MAGENTA);
            g.drawOval((int) ((e.x - x0) / step) - 4, (int) ((e.z - z0) / step) - 4, 8, 8);
        }
        g.dispose();
    }

    // ------------------------------------------------------------------------------------------ the noise layers' caverns

    /**
     * The block the game places at (x, y, z), as {@link #column} decides it but from the rows round it only: the palette
     * reads at most 8 rows of rock over or under a block, so the run of rock is followed 12 rows each way.
     */
    int materialAt(int x, int y, int z) {
        double d = blockDensity(x, y, z);
        if (d <= 0) {
            int f = t.fluidAt(x, y, z);
            return f == Terrain.WATER ? M_WATER : f == Terrain.LAVA ? M_LAVA : M_AIR;
        }
        if (y <= shape.floorY + 4) {
            double chance = (shape.floorY + 5 - y) / 5.0;
            if (y == shape.floorY || Hashing.unit(Hashing.mix(t.seed ^ 0x51ED270B27F1A3C9L, x, y, z)) < chance) return Rock.BEDROCK.ordinal();
        }
        int li = shape.layerAt(y);
        if (li < 0) return Rock.BAND_STONE.ordinal();
        if (d > 0.75) return t.palette.interior(li, x, y, z).ordinal();
        int above = 1, below = 1;
        boolean wet = false;
        for (int k = 1; k <= 12 && y + k < shape.packFloor; k++) {
            if (blockDensity(x, y + k, z) <= 0) { wet = t.fluidAt(x, y + k, z) != Terrain.AIR; break; }
            above++;
        }
        for (int k = 1; k <= 12 && y - k >= shape.floorY; k++) {
            if (blockDensity(x, y - k, z) <= 0) break;
            below++;
        }
        return t.palette.shell(x, y, z, above, below, wet).ordinal();
    }

    /** A horizontal slice of the blocks at row y, size x size columns from (x0, z0); x right, z down. */
    BufferedImage materialSlice(int x0, int z0, int size, int y) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) img.setRGB(i, j, matColor(materialAt(x0 + i, y, z0 + j)));
        return img;
    }

    /** The sub-biome at row y over size x size columns from (x0, z0): its colour in the open, a dark shade of it in rock. */
    BufferedImage biomeSlice(int x0, int z0, int size, int y) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) {
            int x = x0 + i, z = z0 + j, c = biomeColor(t.biomeAt(x, y, z));
            if (blockDensity(x, y, z) > 0) c = ((c >> 16 & 0xFF) * 2 / 5) << 16 | ((c >> 8 & 0xFF) * 2 / 5) << 8 | (c & 0xFF) * 2 / 5;
            img.setRGB(i, j, c);
        }
        return img;
    }

    /**
     * The largest open regions of layer li over size x size columns from (x0, z0): a flood fill of the density under 0 on
     * a 4-block grid; {x, y, z, grid points} of each region's middle, largest first.
     */
    List<int[]> largestOpen(int li, int x0, int z0, int size, int count) {
        int g = 4, n = size / g, bot = shape.bottom(li), ny = (shape.top(li) - bot) / g + 1;
        boolean[] open = new boolean[n * n * ny];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) for (int k = 0; k < ny; k++)
            open[(i * n + j) * ny + k] = t.density(x0 + i * g, bot + k * g, z0 + j * g) < 0;
        boolean[] seen = new boolean[open.length];
        List<int[]> out = new ArrayList<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int p0 = 0; p0 < open.length; p0++) {
            if (!open[p0] || seen[p0]) continue;
            seen[p0] = true;
            q.add(p0);
            long sx = 0, sy = 0, sz = 0, cnt = 0;
            while (!q.isEmpty()) {
                int pp = q.poll(), i = pp / (n * ny), j = pp / ny % n, k = pp % ny;
                sx += i; sy += k; sz += j; cnt++;
                int[][] nb = {{i + 1, j, k}, {i - 1, j, k}, {i, j + 1, k}, {i, j - 1, k}, {i, j, k + 1}, {i, j, k - 1}};
                for (int[] c : nb) {
                    if (c[0] < 0 || c[1] < 0 || c[2] < 0 || c[0] >= n || c[1] >= n || c[2] >= ny) continue;
                    int qq = (c[0] * n + c[1]) * ny + c[2];
                    if (open[qq] && !seen[qq]) { seen[qq] = true; q.add(qq); }
                }
            }
            out.add(new int[]{x0 + (int) (sx * g / cnt), bot + (int) (sy * g / cnt), z0 + (int) (sz * g / cnt), (int) cnt});
        }
        out.sort(Comparator.comparingInt((int[] r) -> -r[3]));
        return out.subList(0, Math.min(count, out.size()));
    }

    /** Pictures side by side, 10 pixels apart. */
    static BufferedImage strip(List<BufferedImage> parts) {
        int w = 0, h = 1;
        for (BufferedImage b : parts) { w += b.getWidth() + 10; h = Math.max(h, b.getHeight()); }
        BufferedImage img = new BufferedImage(Math.max(1, w), h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        int at = 0;
        for (BufferedImage b : parts) { g.drawImage(b, at, 0, null); at += b.getWidth() + 10; }
        g.dispose();
        return img;
    }

    /**
     * A noise layer's cavern views (spec-looks 7.1), 512 x 512 columns round (0, 0): material slices and biome maps at
     * W+1 (shores, rims), W+12 (the tide), W+40 (the mark) and W+80 (walls and clusters), and material sections along x
     * and z through the middles of the four largest open regions, from under the layer to over it.
     */
    void caverns(int li, File out) throws Exception {
        int W = shape.waterY(li), size = 512, x0 = -size / 2, z0 = -size / 2;
        for (int h : new int[]{1, 12, 40, 80}) {
            int y = W + h;
            if (y > shape.top(li)) continue;
            save(materialSlice(x0, z0, size, y), new File(out, "mat_slice_" + li + "_" + h + ".png"));
            save(biomeSlice(x0, z0, size, y), new File(out, "biome_" + li + "_" + h + ".png"));
            corners.clear();
        }
        List<int[]> regions = largestOpen(li, x0, z0, size, 4);
        List<BufferedImage> xs = new ArrayList<>(), zs = new ArrayList<>();
        int y0 = shape.bottom(li) - 8, y1 = shape.top(li) + 20;
        for (int[] r : regions) {
            System.out.printf("layer %d: open region of %d grid points round %d %d %d%n", li, r[3], r[0], r[1], r[2]);
            xs.add(materialSection(r[0] - 128, r[2] + 0.5, r[0] + 128, r[2] + 0.5, y0, y1));
            zs.add(materialSection(r[0] + 0.5, r[2] - 128, r[0] + 0.5, r[2] + 128, y0, y1));
            corners.clear();
        }
        save(strip(xs), new File(out, "mat_sec_" + li + "_x.png"));
        save(strip(zs), new File(out, "mat_sec_" + li + "_z.png"));
    }

    /**
     * The join of the Wasteland and the Galleries over 512 x 512 columns round (0, 0). {@code join_map}: per column,
     * dark grey where the Wasteland's bottom row and the Galleries' top row are both rock (the seal), light blue where
     * the column is open from 2 under the Wasteland's bottom to 8 over it (an opening), red where the Galleries' top row
     * is open under rock at the Wasteland's bottom (a lid), mid grey for the rest; yellow where lava in the join's rows
     * (the Wasteland's bottom 32 rows over the Galleries' top) has air beside it or under it (a lake's open top is not
     * counted). {@code join_ceiling}: where the Galleries are open 4 rows under their top row, the row their open space
     * ends under: red 4 rows under the top row (the closed join's seal), orange 1 to 3 under, yellow at the Wasteland's
     * bottom rows (up to 4 over), light blue 5 or more over (into the Wasteland), dark where they are rock there; flat
     * ceilings show as patches of one colour. {@code join_sec_x/z} and
     * {@code join_kind_x/z}: sections 3 times enlarged along x and z through the four largest openings over the join's
     * rows (materials, and kinds: rock, air coloured by biome, water, lava).
     */
    void join(File out) throws Exception {
        int wBot = shape.bottom(Terrain.WASTELAND), gTop = shape.top(Terrain.GALLERIES);
        int size = 512, x0 = -size / 2, z0 = -size / 2, ylo = gTop - 31, yhi = wBot + 28;
        BufferedImage map = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB), ceiling = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        boolean[] hole = new boolean[size * size];
        int sealed = 0, holes = 0, lids = 0, lavaByAir = 0;
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                int x = x0 + i, z = z0 + j;
                int[] k = new int[yhi - ylo + 1];
                for (int y = ylo; y <= yhi; y++) k[y - ylo] = kind(x, y, z);
                boolean open = true;
                for (int y = wBot - 2; y <= wBot + 8 && open; y++) open = k[y - ylo] == 1;
                boolean wRock = k[wBot - ylo] == 0 || k[wBot - ylo] == 4, gRock = k[gTop - ylo] == 0 || k[gTop - ylo] == 4;
                int c;
                if (open) { c = 0x9FD2FF; holes++; hole[i * size + j] = true; }
                else if (wRock && gRock) { c = 0x303030; sealed++; }
                else if (wRock && k[gTop - ylo] == 1) { c = 0xE02020; lids++; }
                else c = 0x707070;
                for (int y = gTop + 1; y <= wBot + 31 && y < yhi; y++) {
                    if (k[y - ylo] != 3) continue;
                    if (k[y - 1 - ylo] == 1 || kind(x + 1, y, z) == 1 || kind(x - 1, y, z) == 1 || kind(x, y, z + 1) == 1 || kind(x, y, z - 1) == 1) {
                        c = 0xFFE000;
                        lavaByAir++;
                        break;
                    }
                }
                map.setRGB(i, j, c);
                int cc = 0x202020;
                if (k[gTop - 4 - ylo] != 0) {
                    int y = gTop - 3;
                    while (y < yhi && k[y - ylo] != 0) y++;
                    int over = y - gTop;   // the first rock row over the Galleries' open space, against their top row
                    cc = over <= -3 ? 0xE02020 : over <= 0 ? 0xF09030 : over <= 5 ? 0xE0E040 : 0x9FD2FF;
                }
                ceiling.setRGB(i, j, cc);
            }
            if (i % 64 == 63) corners.clear();
        }
        save(map, new File(out, "join_map.png"));
        save(ceiling, new File(out, "join_ceiling.png"));
        System.out.printf("join: %d sealed columns, %d open through, %d lid columns, %d columns with air beside or under lava, of %d%n",
                sealed, holes, lids, lavaByAir, size * size);
        // the four largest openings (groups of open columns)
        int[] lab = new int[size * size];
        java.util.Arrays.fill(lab, -1);
        List<int[]> groups = new ArrayList<>();
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int p0 = 0; p0 < hole.length; p0++) {
            if (!hole[p0] || lab[p0] >= 0) continue;
            lab[p0] = p0;
            q.add(p0);
            long si = 0, sj = 0, n = 0;
            while (!q.isEmpty()) {
                int pp = q.poll(), i = pp / size, j = pp % size;
                si += i; sj += j; n++;
                int[][] nb = {{i + 1, j}, {i - 1, j}, {i, j + 1}, {i, j - 1}};
                for (int[] c : nb) {
                    if (c[0] < 0 || c[1] < 0 || c[0] >= size || c[1] >= size) continue;
                    int qq = c[0] * size + c[1];
                    if (hole[qq] && lab[qq] < 0) { lab[qq] = p0; q.add(qq); }
                }
            }
            groups.add(new int[]{x0 + (int) (si / n), z0 + (int) (sj / n), (int) n});
        }
        groups.sort(Comparator.comparingInt((int[] g) -> -g[2]));
        List<BufferedImage> mx = new ArrayList<>(), mz = new ArrayList<>(), kx = new ArrayList<>(), kz = new ArrayList<>();
        for (int[] g : groups.subList(0, Math.min(4, groups.size()))) {
            System.out.printf("join: opening of %d columns round %d %d%n", g[2], g[0], g[1]);
            int x = g[0], z = g[1];
            mx.add(scale(materialSection(x - 64, z + 0.5, x + 64, z + 0.5, ylo, yhi), 3, 3));
            mz.add(scale(materialSection(x + 0.5, z - 64, x + 0.5, z + 64, ylo, yhi), 3, 3));
            kx.add(scale(section(x - 64, z + 0.5, x + 64, z + 0.5, ylo, yhi), 3, 3));
            kz.add(scale(section(x + 0.5, z - 64, x + 0.5, z + 64, ylo, yhi), 3, 3));
            corners.clear();
        }
        save(strip(mx), new File(out, "join_sec_x.png"));
        save(strip(mz), new File(out, "join_sec_z.png"));
        save(strip(kx), new File(out, "join_kind_x.png"));
        save(strip(kz), new File(out, "join_kind_z.png"));
    }

    static void save(BufferedImage img, File f) throws Exception { ImageIO.write(img, "png", f); }

    static BufferedImage scale(BufferedImage src, int sx, int sy) {
        BufferedImage out = new BufferedImage(src.getWidth() * sx, src.getHeight() * sy, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < out.getWidth(); x++) for (int y = 0; y < out.getHeight(); y++) out.setRGB(x, y, src.getRGB(x / sx, y / sy));
        return out;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0]);
        out.mkdirs();
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 12345L;
        String what = args.length > 2 ? args[2] : "all";
        Terrain t = new Terrain(TerrainSettings.defaults(), -64, seed);
        TerrainPreview p = new TerrainPreview(t);
        Shape s = t.shape;
        for (int li = 0; li < s.layerCount; li++) {
            System.out.printf("layer %d %s: y %d..%d water %d%n", li, s.id(li), s.bottom(li), s.top(li), s.waterY(li));
        }
        long t0 = System.nanoTime();
        if (what.equals("mat")) {
            // Rock across each planned layer's four longest tunnels, as the game would place it; the noise layers' caverns.
            for (int li = 0; li < s.layerCount; li++) {
                if (Terrain.planned(li)) save(scale(p.fan(t.network(li, 0, 0), li, 4, 60, true), 2, 2), new File(out, "mat_fan_" + li + ".png"));
                else p.caverns(li, out);
                p.corners.clear();
                System.out.printf("layer %d done at %.1f s%n", li, (System.nanoTime() - t0) / 1e9);
            }
            if (s.layerCount > Terrain.GALLERIES) p.join(out);
            p.corners.clear();
            Plans.Gate g = t.plans.gate(2, 0, 0);
            save(p.materialSection(g.ux - 100, g.uz, g.ux + 100, g.uz, s.floorY - 2, s.packFloor + 2), new File(out, "mat_stack.png"));
            System.out.printf("done in %.1f s%n", (System.nanoTime() - t0) / 1e9);
            return;
        }
        int size = 1;
        while (t.plans.cellOf(size) == 0) size++;   // the cell size
        for (int li = 0; li < s.layerCount; li++) {
            if (!what.equals("all") && !what.equals(String.valueOf(li))) continue;
            if (!Terrain.planned(li)) {
                // a noise field (the Wasteland, the Galleries): no plan to draw, only its blocks
                System.out.printf("layer %d: a noise field%n", li);
                save(p.topDown(li, -size / 4, -size / 4, size * 3 / 2, 2), new File(out, "map_" + li + ".png"));
                save(p.section(0, size / 2.0, size, size / 2.0, s.bottom(li) - 8, s.top(li) + 20), new File(out, "secx_" + li + ".png"));
                save(p.section(size / 2.0, 0, size / 2.0, size, s.bottom(li) - 8, s.top(li) + 20), new File(out, "secz_" + li + ".png"));
                p.corners.clear();
                continue;
            }
            Net n = t.network(li, 0, 0);
            List<Net.Way> ts = tunnels(n);
            System.out.printf("layer %d: %d chambers, %d ways (%d tunnels, longest %.0f), %d climbs%s%n", li, n.ch.size(), n.ways.size(), ts.size(),
                    ts.isEmpty() ? 0 : ts.get(0).length, n.shafts.size(), n.ch.stream().anyMatch(c -> c.kind == Net.Kind.CANYON) ? ", the rift" : "");
            if (!ts.isEmpty()) save(p.waySection(ts.get(0), li), new File(out, "way_" + li + ".png"));
            // cross-sections across the longest tunnels
            save(p.fan(n, li, 6, 80, false), new File(out, "fan_" + li + ".png"));
            BufferedImage map = p.topDown(li, -size / 4, -size / 4, size * 3 / 2, 2);
            save(map, new File(out, "map_" + li + ".png"));
            p.overlay(map, n, -size / 4, -size / 4, 2);
            save(map, new File(out, "plan_" + li + ".png"));
            // the set pieces, cut along x and z through their middles
            for (Net.Chamber c : n.ch) {
                if (!c.set) continue;
                double r = c.R() + 24;
                int y0 = Math.max(s.bottom(li) - 4, (int) Math.floor(c.f0) - 24), y1 = Math.min(s.top(li) + 4, (int) Math.ceil(c.f0 + c.h + c.rise) + 24);
                if (c.helix()) y1 = Math.min(s.top(li) + 4, (int) Math.ceil(c.top) + 12);
                String tag = li + "_" + c.kind.name().toLowerCase(java.util.Locale.ROOT) + "_" + c.id;
                save(scale(p.section(c.x - r, c.z, c.x + r, c.z, y0, y1), 3, 3), new File(out, "set_" + tag + "_x.png"));
                save(scale(p.section(c.x, c.z - r, c.x, c.z + r, y0, y1), 3, 3), new File(out, "set_" + tag + "_z.png"));
            }
            // straight sections through the cell
            save(p.section(0, size / 2.0, size, size / 2.0, s.bottom(li) - 8, s.top(li) + 20), new File(out, "secx_" + li + ".png"));
            save(p.section(size / 2.0, 0, size / 2.0, size, s.bottom(li) - 8, s.top(li) + 20), new File(out, "secz_" + li + ".png"));
            p.corners.clear();
        }
        // the full stack through a gate
        Plans.Gate g = t.plans.gate(2, 0, 0);
        save(p.section(g.ux - 150, g.uz, g.ux + 150, g.uz, s.floorY - 4, s.packFloor + 30), new File(out, "stack.png"));
        System.out.printf("done in %.1f s%n", (System.nanoTime() - t0) / 1e9);
    }
}
