package com.explorationremix.preview;

import com.explorationremix.world.terrain.DeepBiome;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Hashing;
import com.explorationremix.world.terrain.Rock;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Shape;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline preview of the deep world's shape: renders the terrain the way the game would after its 4x8x4 noise
 * interpolation, without starting Minecraft. Usage: TerrainPreview outDir [seed].
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
        };
    }

    /** 0 rock, 1 air, 2 water, 3 lava, 4 band rock. */
    int kind(int x, int y, int z) {
        if (y < shape.floorY) return 0;
        double d = interp(x, y, z);
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

    /** The route unrolled: x is the arc length, y is height; the walking line is drawn in red. */
    BufferedImage routeSection(Route r, int li, boolean onPath) {
        int len = (int) r.length;
        int y0 = shape.bottom(li) - 6, y1 = shape.top(li) + 20;
        BufferedImage img = new BufferedImage(len, y1 - y0 + 1, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < len; i++) {
            double[] p = r.pointAt(i);
            double[] p2 = r.pointAt(i + 1);
            double dx = p2[0] - p[0], dz = p2[1] - p[1], l = Math.max(1e-6, Math.hypot(dx, dz));
            double off = onPath ? r.offsetAt(i) : 0;
            int x = (int) Math.floor(p[0] - dz / l * off), z = (int) Math.floor(p[1] + dx / l * off);
            for (int y = y0; y <= y1; y++) img.setRGB(i, y1 - y, pixel(x, y, z));
            int fy = (int) Math.floor(r.floorAt(i));
            if (fy >= y0 && fy <= y1) img.setRGB(i, y1 - fy, 0xFF0000);
        }
        return img;
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
            double d = interp(x, y, z);
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
            if (li == shape.layerCount - 1 && y < shape.waterY(li) + 4 && t.bedrockLanding(x, y, z)) { m[i] = Rock.BEDROCK.ordinal(); continue; }
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

    void overlay(BufferedImage img, Route r, int x0, int z0, int step) {
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        for (int i = 0; i + 2 < (int) r.length; i += 2) {
            double[] a = r.pointAt(i), b = r.pointAt(i + 2);
            g.drawLine((int) ((a[0] - x0) / step), (int) ((a[1] - z0) / step), (int) ((b[0] - x0) / step), (int) ((b[1] - z0) / step));
        }
        g.setColor(Color.GREEN);
        Plans.Gate e = r.entry;
        g.drawOval((int) ((e.lx - x0) / step) - 4, (int) ((e.lz - z0) / step) - 4, 8, 8);
        g.setColor(Color.MAGENTA);
        g.drawOval((int) ((r.endX - x0) / step) - 4, (int) ((r.endZ - z0) / step) - 4, 8, 8);
        g.dispose();
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
        for (int li = 0; li < 4; li++) {
            System.out.printf("layer %d %s: y %d..%d water %d%n", li, s.id(li), s.bottom(li), s.top(li), s.waterY(li));
        }
        long t0 = System.nanoTime();
        if (what.equals("mat")) {
            // Rock along each route and across it, as the game would place it.
            for (int li = 0; li < 4; li++) {
                Route r = t.plans.route(li, 0, 0);
                int ns = 4;
                BufferedImage fan = new BufferedImage(ns * 130, s.top(li) - s.bottom(li) + 12, BufferedImage.TYPE_INT_RGB);
                for (int k = 0; k < ns; k++) {
                    double sk = r.length * (k + 0.5) / ns;
                    double[] a = r.pointAt(sk), b = r.pointAt(sk + 1);
                    double dx = b[0] - a[0], dz = b[1] - a[1], l = Math.max(1e-6, Math.hypot(dx, dz));
                    double nx = -dz / l, nz = dx / l;
                    BufferedImage sec = p.materialSection(a[0] - nx * 62, a[1] - nz * 62, a[0] + nx * 62, a[1] + nz * 62, s.bottom(li) - 6, s.top(li) + 6);
                    fan.createGraphics().drawImage(sec, k * 130, 0, null);
                }
                save(scale(fan, 2, 2), new File(out, "mat_fan_" + li + ".png"));
                p.corners.clear();
            }
            Plans.Gate g = t.plans.gate(2, 0, 0);
            save(p.materialSection(g.ux - 100, g.uz, g.ux + 100, g.uz, s.floorY - 2, s.packFloor + 2), new File(out, "mat_stack.png"));
            System.out.printf("done in %.1f s%n", (System.nanoTime() - t0) / 1e9);
            return;
        }
        int cell = t.plans.cellOf(0);
        for (int li = 0; li < 4; li++) {
            if (!what.equals("all") && !what.equals(String.valueOf(li))) continue;
            Route r = t.plans.route(li, 0, 0);
            System.out.printf("layer %d route length %.0f, floor %.0f -> %.0f, halls %d, geodes %d, bridges %d%n", li, r.length, r.floorAt(0), r.floorAt(r.length), r.halls.size(), r.geodes.size(), r.bridges.size());
            save(p.routeSection(r, li, false), new File(out, "route_" + li + ".png"));
            if (li == Terrain.BLUE) save(p.routeSection(r, li, true), new File(out, "route_" + li + "_path.png"));
            // cross-sections perpendicular to the route
            int ns = 6;
            BufferedImage fan = new BufferedImage(ns * 170, s.top(li) - s.bottom(li) + 30, BufferedImage.TYPE_INT_RGB);
            for (int k = 0; k < ns; k++) {
                double sk = r.length * (k + 0.5) / ns;
                double[] a = r.pointAt(sk), b = r.pointAt(sk + 1);
                double dx = b[0] - a[0], dz = b[1] - a[1], l = Math.max(1e-6, Math.hypot(dx, dz));
                double nx = -dz / l, nz = dx / l;
                BufferedImage sec = p.section(a[0] - nx * 80, a[1] - nz * 80, a[0] + nx * 80, a[1] + nz * 80, s.bottom(li) - 8, s.top(li) + 20);
                fan.createGraphics().drawImage(sec, k * 170, 0, null);
            }
            save(fan, new File(out, "fan_" + li + ".png"));
            BufferedImage map = p.topDown(li, -300, -300, 1200, 2);
            p.overlay(map, r, -300, -300, 2);
            save(map, new File(out, "map_" + li + ".png"));
            // straight sections through the cell
            save(p.section(0, 300, 600, 300, s.bottom(li) - 8, s.top(li) + 20), new File(out, "secx_" + li + ".png"));
            save(p.section(300, 0, 300, 600, s.bottom(li) - 8, s.top(li) + 20), new File(out, "secz_" + li + ".png"));
            p.corners.clear();
        }
        // the full stack through a gate
        Plans.Gate g = t.plans.gate(2, 0, 0);
        save(p.section(g.ux - 150, g.uz, g.ux + 150, g.uz, s.floorY - 4, s.packFloor + 30), new File(out, "stack.png"));
        System.out.printf("done in %.1f s%n", (System.nanoTime() - t0) / 1e9);
    }
}
