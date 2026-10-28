package com.explorationremix.world.plan;

import com.explorationremix.world.Layer;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything planned for one layer inside one gate cell: the landing you arrive on, the route to the way down,
 * refuges and ruins beside it, and the hunting ruins out in the dark. All geometry stays inside the cell with a
 * margin, so a block only ever needs its own cell's plan.
 */
public final class CellPlan {
    public enum Role { REFUGE, ROUTE, HUNTING }

    public final Layer layer;
    public final int cellX, cellZ;
    public final Gate entryGate;
    @Nullable public final Gate exitGate;
    @Nullable public final Chamber bedrockLanding;
    public final List<Tunnel> route;
    public final List<Tunnel> links;
    public final List<Chamber> chambers;
    public final double routeHalfWidth;
    public final double refugeMargin;
    private final double[] routeCumulative;
    private final double routeLength;

    CellPlan(Layer layer, int cellX, int cellZ, Gate entryGate, @Nullable Gate exitGate, @Nullable Chamber bedrockLanding,
             List<Tunnel> route, List<Tunnel> links, List<Chamber> chambers, double routeHalfWidth, double refugeMargin) {
        this.layer = layer;
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.entryGate = entryGate;
        this.exitGate = exitGate;
        this.bedrockLanding = bedrockLanding;
        this.route = List.copyOf(route);
        this.links = List.copyOf(links);
        this.chambers = List.copyOf(chambers);
        this.routeHalfWidth = routeHalfWidth;
        this.refugeMargin = refugeMargin;
        this.routeCumulative = new double[route.size() + 1];
        double acc = 0;
        for (int i = 0; i < route.size(); i++) {
            acc += route.get(i).seg().length();
            routeCumulative[i + 1] = acc;
        }
        this.routeLength = acc;
    }

    public double routeLength() { return routeLength; }

    /** Position along the route at a fraction of its length: returns {x, y, z, dirX, dirZ}. */
    public double[] routePoint(double fraction) {
        double target = Math.max(0, Math.min(1, fraction)) * routeLength;
        for (int i = 0; i < route.size(); i++) {
            double start = routeCumulative[i], end = routeCumulative[i + 1];
            if (target <= end || i == route.size() - 1) {
                Segment s = route.get(i).seg();
                double len = Math.max(1e-6, end - start);
                double t = Math.max(0, Math.min(1, (target - start) / len));
                double dx = s.x2() - s.x1(), dz = s.z2() - s.z1();
                double dl = Math.max(1e-6, Math.sqrt(dx * dx + dz * dz));
                return new double[]{s.xAt(t), s.yAt(t), s.zAt(t), dx / dl, dz / dl};
            }
        }
        Segment s = route.get(0).seg();
        return new double[]{s.x1(), s.y1(), s.z1(), 1, 0};
    }

    /** Horizontal distance to the route centre line (landings included). */
    public double distToRouteXZ(double x, double z) {
        double best = Double.MAX_VALUE;
        for (Tunnel t : route) {
            Segment s = t.seg();
            double tt = s.closestTXZ(x, z);
            double dx = x - s.xAt(tt), dz = z - s.zAt(tt);
            best = Math.min(best, Math.sqrt(dx * dx + dz * dz));
        }
        best = Math.min(best, entryGate.distXZLower(x, z));
        if (exitGate != null) best = Math.min(best, exitGate.distXZUpper(x, z));
        if (bedrockLanding != null) best = Math.min(best, bedrockLanding.distXZ(x, z));
        return best;
    }

    /** The closest route point to (x,z): {x, y, z}. */
    public double[] nearestRoutePoint(double x, double z) {
        double best = Double.MAX_VALUE;
        double[] out = {entryGate.lowerX(), entryGate.lowerY(), entryGate.lowerZ()};
        for (Tunnel t : route) {
            Segment s = t.seg();
            double tt = s.closestTXZ(x, z);
            double dx = x - s.xAt(tt), dz = z - s.zAt(tt);
            double d = dx * dx + dz * dz;
            if (d < best) {
                best = d;
                out = new double[]{s.xAt(tt), s.yAt(tt), s.zAt(tt)};
            }
        }
        return out;
    }

    @Nullable
    public Chamber nearestRefugeXZ(double x, double z) {
        Chamber best = null;
        double bd = Double.MAX_VALUE;
        for (Chamber c : chambers) {
            if (c.kind() != Chamber.Kind.REFUGE) continue;
            double d = c.distXZ(x, z);
            if (d < bd) { bd = d; best = c; }
        }
        return best;
    }

    public Role roleAt(double x, double z) {
        for (Chamber c : chambers) {
            if (c.kind() == Chamber.Kind.REFUGE && c.distXZ(x, z) < c.radius() + refugeMargin) return Role.REFUGE;
        }
        if (distToRouteXZ(x, z) < routeHalfWidth) return Role.ROUTE;
        for (Chamber c : chambers) {
            if (c.kind() == Chamber.Kind.CATHEDRAL && c.distXZ(x, z) < c.radius() + 4) return Role.ROUTE;
        }
        return Role.HUNTING;
    }

    /** True when the point lies inside a refuge chamber (used to keep refuges dry and plantless). */
    public boolean insideRefuge(double x, double y, double z) {
        for (Chamber c : chambers) {
            if (c.kind() == Chamber.Kind.REFUGE && c.dist(x, y, z) < 2) return true;
        }
        return false;
    }

    /** True inside a gate landing or the bedrock landing (kept dry). */
    public boolean insideLanding(double x, double y, double z) {
        if (entryGate.distToLowerLanding(x, y, z) < 1) return true;
        if (exitGate != null && exitGate.distToUpperLanding(x, y, z) < 1) return true;
        return bedrockLanding != null && bedrockLanding.dist(x, y, z) < 1;
    }

    /** Signed distance to the nearest planned void inside this layer: negative inside planned air. */
    public double carveDistance(double x, double y, double z) {
        double d = entryGate.distToLowerLanding(x, y, z);
        double tube = entryGate.distToTube(x, y, z);
        if (tube != Double.MAX_VALUE) d = Math.min(d, tube - entryGate.radius());
        if (exitGate != null) {
            d = Math.min(d, exitGate.distToUpperLanding(x, y, z));
            tube = exitGate.distToTube(x, y, z);
            if (tube != Double.MAX_VALUE) d = Math.min(d, tube - exitGate.radius());
        }
        if (bedrockLanding != null) d = Math.min(d, bedrockLanding.dist(x, y, z));
        for (Tunnel t : route) {
            if (d > -1 && boxReject(t.seg(), t.reach(), x, z)) continue;
            d = Math.min(d, t.dist(x, y, z));
        }
        for (Tunnel t : links) {
            if (d > -1 && boxReject(t.seg(), t.reach(), x, z)) continue;
            d = Math.min(d, t.dist(x, y, z));
        }
        for (Chamber c : chambers) {
            double reach = c.reach();
            if (Math.abs(x - c.x()) > reach + 4 || Math.abs(z - c.z()) > reach + 4) continue;
            d = Math.min(d, c.dist(x, y, z));
        }
        return d;
    }

    private static boolean boxReject(Segment s, double reach, double x, double z) {
        return x < s.minX() - reach - 4 || x > s.maxX() + reach + 4 || z < s.minZ() - reach - 4 || z > s.maxZ() + reach + 4;
    }

    /** Chambers whose centre block lies inside the given chunk. */
    public List<Chamber> chambersInChunk(int chunkX, int chunkZ) {
        List<Chamber> out = new ArrayList<>();
        for (Chamber c : chambers) {
            if (((int) Math.floor(c.x()) >> 4) == chunkX && ((int) Math.floor(c.z()) >> 4) == chunkZ) out.add(c);
        }
        if (bedrockLanding != null && ((int) Math.floor(bedrockLanding.x()) >> 4) == chunkX && ((int) Math.floor(bedrockLanding.z()) >> 4) == chunkZ) {
            out.add(bedrockLanding);
        }
        return out;
    }
}
