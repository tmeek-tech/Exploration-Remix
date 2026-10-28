package com.explorationremix.world.plan;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.Layer;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Seeded, lazily built plans for every (layer, cell). Gates are shared between the layer above and the layer below a
 * band, so they are planned once per (band, cell).
 */
public final class LayerPlans {
    private static final double CELL_MARGIN = 56;

    private final long seed;
    private final PlanNoise noise;
    private final int cellSize;
    private final double tubeRadius;
    private final double landingRadius;
    private final double refugeRadius;
    private final double routeHalfWidth;
    private final int huntingRuins;
    private final int routeRuins;
    private final Map<Long, CellPlan> cells = new ConcurrentHashMap<>();
    private final Map<Long, Gate> gates = new ConcurrentHashMap<>();

    public LayerPlans(long seed) {
        ERConfig c = ERConfig.INSTANCE;
        this.seed = seed;
        this.noise = new PlanNoise(seed ^ 0x5DEECE66DL);
        this.cellSize = Math.max(160, c.gateSpacing.get());
        this.tubeRadius = c.gateTubeRadius.get();
        this.landingRadius = c.landingRadius.get();
        this.refugeRadius = c.refugeRadius.get();
        this.routeHalfWidth = c.routeHalfWidth.get();
        this.huntingRuins = c.huntingRuinsPerCell.get();
        this.routeRuins = c.routeRuinsPerCell.get();
    }

    public long seed() { return seed; }
    public int cellSize() { return cellSize; }
    public PlanNoise noise() { return noise; }
    public double tubeRadius() { return tubeRadius; }
    public int cellOf(double coord) { return (int) Math.floor(coord / cellSize); }

    private static long key(int a, int cx, int cz) {
        return ((long) (a & 0xF) << 60) | ((cx & 0x3FFFFFFFL) << 30) | (cz & 0x3FFFFFFFL);
    }

    // ------------------------------------------------------------------ gates

    /** The gate through the given band (1-based) in a cell. */
    public Gate gate(int band, int cellX, int cellZ) {
        return gates.computeIfAbsent(key(band, cellX, cellZ), k -> buildGate(band, cellX, cellZ));
    }

    public Gate gateAt(int band, double x, double z) { return gate(band, cellOf(x), cellOf(z)); }

    private Gate buildGate(int band, int cx, int cz) {
        long h = Hashing.mix(seed, 101 + band, cx, cz);
        double ux = cx * (double) cellSize + Hashing.range(Hashing.mix(h, 1, 0, 0), 0.2, 0.8) * cellSize;
        double uz = cz * (double) cellSize + Hashing.range(Hashing.mix(h, 2, 0, 0), 0.2, 0.8) * cellSize;
        int bandTop = DeepWorld.bandTopY(band), bandBottom = DeepWorld.bandBottomY(band);
        double half = landingRadius / Gate.LANDING_Y_SCALE;
        double uy = bandTop + 1 + half;
        double ly = bandBottom - 1 - half;
        double angle = Hashing.range(Hashing.mix(h, 3, 0, 0), 0, Math.PI * 2);
        double dist = Hashing.range(Hashing.mix(h, 4, 0, 0), 18, 34);
        double lx = ux + Math.cos(angle) * dist, lz = uz + Math.sin(angle) * dist;
        // S-shaped control points so you never see straight through a band
        double perp = Hashing.range(Hashing.mix(h, 5, 0, 0), 12, 20) * (Hashing.unit(Hashing.mix(h, 6, 0, 0)) < 0.5 ? -1 : 1);
        double px = -Math.sin(angle), pz = Math.cos(angle);
        double c1x = ux + Math.cos(angle) * dist * 0.33 + px * perp, c1z = uz + Math.sin(angle) * dist * 0.33 + pz * perp;
        double c2x = ux + Math.cos(angle) * dist * 0.66 - px * perp, c2z = uz + Math.sin(angle) * dist * 0.66 - pz * perp;
        return new Gate(band, cx, cz, ux, uy, uz, lx, ly, lz, c1x, c1z, c2x, c2z, tubeRadius, landingRadius);
    }

    /** Signed distance to the gate tube inside a band (negative inside the tube). */
    public double bandCarveDistance(int band, double x, double y, double z) {
        Gate g = gateAt(band, x, z);
        double d = g.distToTube(x, y, z);
        if (d == Double.MAX_VALUE) return 64;
        double wobble = noise.noise3(x * 0.11, y * 0.09, z * 0.11) * 1.4;
        return d - (g.radius() + wobble);
    }

    /** The first gate's upper landing and ramp carve, which lives in the pack's own caves above band 1. */
    public double gateOneCarveDistance(double x, double y, double z) {
        Gate g = gateAt(1, x, z);
        double d = g.distToUpperLanding(x, y, z);
        Tunnel ramp = rampFor(g);
        d = Math.min(d, ramp.dist(x, y, z));
        double tube = g.distToTube(x, y, z);
        if (tube != Double.MAX_VALUE) d = Math.min(d, tube - g.radius());
        return d;
    }

    private final Map<Long, Tunnel> ramps = new ConcurrentHashMap<>();

    /** A walkable ramp from the first gate's upper landing up into the pack's cave depths. */
    public Tunnel rampFor(Gate g) {
        return ramps.computeIfAbsent(key(0, g.cellX(), g.cellZ()), k -> {
            long h = Hashing.mix(seed, 777, g.cellX(), g.cellZ());
            double angle = Hashing.range(Hashing.mix(h, 1, 0, 0), 0, Math.PI * 2);
            double rise = 40, run = 70;
            double ex = g.upperX() + Math.cos(angle) * run, ez = g.upperZ() + Math.sin(angle) * run;
            Segment s = new Segment(g.upperX(), g.upperY(), g.upperZ(), ex, g.upperY() + rise, ez);
            return new Tunnel(s, 4.5, 3.5, 1.3, Tunnel.Kind.RAMP);
        });
    }

    // ------------------------------------------------------------------ cells

    public CellPlan cell(int layerIndex, int cellX, int cellZ) {
        return cells.computeIfAbsent(key(4 + layerIndex, cellX, cellZ), k -> buildCell(layerIndex, cellX, cellZ));
    }

    public CellPlan cellAt(int layerIndex, double x, double z) { return cell(layerIndex, cellOf(x), cellOf(z)); }

    @Nullable
    public CellPlan cellAt(Layer layer, double x, double z) {
        return layer == null ? null : cellAt(layer.index(), x, z);
    }

    private CellPlan buildCell(int layerIndex, int cx, int cz) {
        Layer layer = DeepWorld.layers().get(layerIndex);
        long h = Hashing.mix(seed, 500 + layerIndex, cx, cz);
        Gate entry = gate(layerIndex + 1, cx, cz);
        boolean last = layerIndex == DeepWorld.layers().size() - 1;
        Gate exit = last ? null : gate(layerIndex + 2, cx, cz);
        Chamber bedrock = null;
        double ex, ey, ez;
        if (exit != null) {
            ex = exit.upperX(); ey = exit.upperY(); ez = exit.upperZ();
        } else {
            // The landing sits on the sealed bottom of the world, so its floor is the bedrock the run is named for.
            double half = (landingRadius + 4) / Gate.LANDING_Y_SCALE;
            ex = cx * (double) cellSize + Hashing.range(Hashing.mix(h, 11, 0, 0), 0.2, 0.8) * cellSize;
            ez = cz * (double) cellSize + Hashing.range(Hashing.mix(h, 12, 0, 0), 0.2, 0.8) * cellSize;
            ey = layer.bottom() + com.explorationremix.world.density.DeepDensityFunction.BOTTOM_SEAL + 1 + half;
            bedrock = new Chamber(Chamber.Kind.BEDROCK_LANDING, ex, ey, ez, landingRadius + 4, 1 / Gate.LANDING_Y_SCALE, 99);
        }
        double sx = entry.lowerX(), sy = entry.lowerY(), sz = entry.lowerZ();

        // Route waypoints: a meandering polyline from the entry landing to the exit landing.
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[]{sx, sy, sz});
        int n = 3 + (int) (Hashing.unit(Hashing.mix(h, 20, 0, 0)) * 2);
        double dx = ex - sx, dz = ez - sz;
        double len = Math.max(1, Math.sqrt(dx * dx + dz * dz));
        double px = -dz / len, pz = dx / len;
        double minY = layer.bottom() + 26, maxY = layer.top() - 26;
        for (int i = 0; i < n; i++) {
            long hi = Hashing.mix(h, 30 + i, 0, 0);
            double t = (i + 1.0) / (n + 1) + Hashing.range(Hashing.mix(hi, 1, 0, 0), -0.08, 0.08);
            double off = Hashing.range(Hashing.mix(hi, 2, 0, 0), -0.28, 0.28) * len;
            double wx = clampCell(sx + dx * t + px * off, cx), wz = clampCell(sz + dz * t + pz * off, cz);
            double wy = Hashing.range(Hashing.mix(hi, 3, 0, 0), minY, maxY);
            pts.add(new double[]{wx, wy, wz});
        }
        pts.add(new double[]{ex, ey, ez});

        List<Tunnel> route = new ArrayList<>();
        double[] radii = styleRadii(layer.style());
        for (int i = 0; i + 1 < pts.size(); i++) {
            double[] a = pts.get(i), b = pts.get(i + 1);
            long hi = Hashing.mix(h, 60 + i, 0, 0);
            double r1 = Hashing.range(Hashing.mix(hi, 1, 0, 0), radii[0], radii[1]) * layer.cavernScale();
            double r2 = Hashing.range(Hashing.mix(hi, 2, 0, 0), radii[0], radii[1]) * layer.cavernScale();
            route.add(new Tunnel(new Segment(a[0], a[1], a[2], b[0], b[1], b[2]), r1, r2, radii[2], Tunnel.Kind.ROUTE));
        }
        CellPlan skeleton = new CellPlan(layer, cx, cz, entry, exit, bedrock, route, List.of(), List.of(), routeHalfWidth, 10);

        List<Chamber> chambers = new ArrayList<>();
        List<Tunnel> links = new ArrayList<>();
        int ruinIndex = 0;

        // Cathedral: the one big set-piece on the route.
        {
            double[] p = skeleton.routePoint(Hashing.range(Hashing.mix(h, 70, 0, 0), 0.42, 0.58));
            double[] cat = cathedral(layer.style());
            double r = cat[0] * layer.cavernScale(), ys = cat[1];
            double cy = clamp(p[1], layer.bottom() + r * ys + 4, layer.top() - r * ys - 4);
            chambers.add(new Chamber(Chamber.Kind.CATHEDRAL, clampCell(p[0], cx), cy, clampCell(p[2], cz), r, ys, -1));
        }

        // Refuges at a third and two thirds of the route, pushed off to one side, each with a ruin.
        double[] refugeFractions = {0.33, 0.66};
        for (int i = 0; i < refugeFractions.length; i++) {
            long hi = Hashing.mix(h, 80 + i, 0, 0);
            double f = refugeFractions[i] + Hashing.range(Hashing.mix(hi, 1, 0, 0), -0.05, 0.05);
            double[] p = skeleton.routePoint(f);
            double side = Hashing.unit(Hashing.mix(hi, 2, 0, 0)) < 0.5 ? -1 : 1;
            double d = refugeRadius + Hashing.range(Hashing.mix(hi, 3, 0, 0), 34, 64);
            double rx = clampCell(p[0] - p[4] * d * side, cx), rz = clampCell(p[2] + p[3] * d * side, cz);
            double ys = 0.55;
            double ry = clamp(p[1] + Hashing.range(Hashing.mix(hi, 4, 0, 0), -8, 8), layer.bottom() + refugeRadius * ys + 3, layer.top() - refugeRadius * ys - 3);
            Chamber refuge = new Chamber(Chamber.Kind.REFUGE, rx, ry, rz, refugeRadius, ys, ruinIndex++);
            chambers.add(refuge);
            links.add(new Tunnel(new Segment(p[0], p[1], p[2], rx, ry - refugeRadius * ys * 0.4, rz), 4.5, 4.0, 1.3, Tunnel.Kind.LINK));
        }

        // Route alcoves: small ruins tucked into the side of the route.
        for (int i = 0; i < routeRuins; i++) {
            long hi = Hashing.mix(h, 100 + i, 0, 0);
            double f = (i + 0.5) / routeRuins + Hashing.range(Hashing.mix(hi, 1, 0, 0), -0.1, 0.1);
            if (Math.abs(f - 0.5) < 0.1) f = f < 0.5 ? 0.38 : 0.62;
            double[] p = skeleton.routePoint(f);
            double side = Hashing.unit(Hashing.mix(hi, 2, 0, 0)) < 0.5 ? -1 : 1;
            double r = Hashing.range(Hashing.mix(hi, 3, 0, 0), 8, 10);
            double d = r + radii[1] * 0.6;
            double ax = clampCell(p[0] - p[4] * d * side, cx), az = clampCell(p[2] + p[3] * d * side, cz);
            double ay = clamp(p[1] - 1, layer.bottom() + r + 3, layer.top() - r - 3);
            chambers.add(new Chamber(Chamber.Kind.ROUTE_ALCOVE, ax, ay, az, r, 0.8, ruinIndex++));
        }

        // Hunting ruins: out in the dark, linked back to the route by a narrow tunnel.
        int placed = 0;
        for (int attempt = 0; attempt < huntingRuins * 6 && placed < huntingRuins; attempt++) {
            long hi = Hashing.mix(h, 140 + attempt, 0, 0);
            double hx = cx * (double) cellSize + Hashing.range(Hashing.mix(hi, 1, 0, 0), 0.12, 0.88) * cellSize;
            double hz = cz * (double) cellSize + Hashing.range(Hashing.mix(hi, 2, 0, 0), 0.12, 0.88) * cellSize;
            hx = clampCell(hx, cx); hz = clampCell(hz, cz);
            if (skeleton.distToRouteXZ(hx, hz) < routeHalfWidth + 24) continue;
            boolean nearRefuge = false;
            for (Chamber c : chambers) if (c.kind() == Chamber.Kind.REFUGE && c.distXZ(hx, hz) < c.radius() + 50) nearRefuge = true;
            if (nearRefuge) continue;
            double r = Hashing.range(Hashing.mix(hi, 3, 0, 0), 11, 14);
            double hy = clamp(Hashing.range(Hashing.mix(hi, 4, 0, 0), minY, maxY), layer.bottom() + r * 0.6 + 3, layer.top() - r * 0.6 - 3);
            chambers.add(new Chamber(Chamber.Kind.HUNTING_RUIN, hx, hy, hz, r, 0.6, ruinIndex++));
            double[] near = skeleton.nearestRoutePoint(hx, hz);
            links.add(new Tunnel(new Segment(near[0], near[1], near[2], hx, hy - r * 0.2, hz), 3.5, 3.5, 1.2, Tunnel.Kind.LINK));
            placed++;
        }

        return new CellPlan(layer, cx, cz, entry, exit, bedrock, route, links, chambers, routeHalfWidth, 10);
    }

    private double clampCell(double v, int cell) {
        double lo = cell * (double) cellSize + CELL_MARGIN, hi = (cell + 1) * (double) cellSize - CELL_MARGIN;
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** {minRadius, maxRadius, yScale} of route tunnels per style. */
    private static double[] styleRadii(Layer.Style style) {
        return switch (style) {
            case HALLS -> new double[]{9, 14, 1.15};
            case RIFT -> new double[]{6, 9, 2.4};
            case BASIN -> new double[]{12, 17, 0.55};
            case GALLERIES -> new double[]{8, 12, 1.0};
        };
    }

    /** {radius, yScale} of the cathedral chamber per style. */
    private static double[] cathedral(Layer.Style style) {
        return switch (style) {
            case HALLS -> new double[]{38, 1.0};
            case RIFT -> new double[]{26, 2.4};
            case BASIN -> new double[]{44, 0.5};
            case GALLERIES -> new double[]{34, 1.1};
        };
    }

    // ------------------------------------------------------------------ queries

    /** Signed distance to planned air inside a layer. */
    public double layerCarveDistance(Layer layer, double x, double y, double z) {
        return cellAt(layer.index(), x, z).carveDistance(x, y, z);
    }

    /** The biome for a block in the deep world. */
    public ResourceLocation biomeAt(int x, int y, int z) {
        Layer layer = DeepWorld.layerAt(y);
        if (layer == null) {
            int band = DeepWorld.bandAt(y);
            if (band >= 1 && band - 1 < DeepWorld.layers().size()) layer = DeepWorld.layers().get(band - 1);
            else if (y < DeepWorld.floorY()) layer = DeepWorld.layers().get(DeepWorld.layers().size() - 1);
            else return null;
        }
        CellPlan plan = cellAt(layer.index(), x, z);
        return switch (plan.roleAt(x, z)) {
            case REFUGE -> layer.refugeBiome();
            case ROUTE -> layer.routeBiome();
            case HUNTING -> huntingBiome(layer, x, z);
        };
    }

    public ResourceLocation huntingBiome(Layer layer, double x, double z) {
        List<ResourceLocation> hs = layer.huntingBiomes();
        if (hs.isEmpty()) return layer.routeBiome();
        if (hs.size() == 1) return hs.get(0);
        double v = (noise.fbm2(x + layer.index() * 9173.0, z - layer.index() * 4111.0, 190) + 1) * 0.5;
        int idx = (int) Math.floor(v * hs.size());
        return hs.get(Math.max(0, Math.min(hs.size() - 1, idx)));
    }

    /** Whether the point lies inside a refuge chamber (dry, plantless). */
    /** True in the lower half of the bedrock landing's shell: the floor and lower walls that are painted bedrock. */
    public boolean bedrockLandingFloor(int x, int y, int z) {
        List<Layer> layers = DeepWorld.layers();
        if (layers.isEmpty()) return false;
        Layer last = layers.get(layers.size() - 1);
        if (y < last.bottom() || y > last.top()) return false;
        Chamber c = cellAt(last.index(), x, z).bedrockLanding;
        return c != null && y < c.y() && c.dist(x, y, z) < 4;
    }

    public boolean insideRefuge(int x, int y, int z) {
        Layer layer = DeepWorld.layerAt(y);
        return layer != null && cellAt(layer.index(), x, z).insideRefuge(x, y, z);
    }

    /** Chambers with ruins whose centre lies in a chunk of a layer. */
    public List<Chamber> ruinsInChunk(Layer layer, int chunkX, int chunkZ) {
        CellPlan plan = cellAt(layer.index(), chunkX * 16 + 8, chunkZ * 16 + 8);
        List<Chamber> out = new ArrayList<>();
        for (Chamber c : plan.chambersInChunk(chunkX, chunkZ)) if (c.hasRuin()) out.add(c);
        return out;
    }
}
