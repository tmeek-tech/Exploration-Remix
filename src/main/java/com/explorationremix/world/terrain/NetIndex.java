package com.explorationremix.world.terrain;

import com.explorationremix.world.terrain.Net.Chamber;
import com.explorationremix.world.terrain.Net.Shaft;
import com.explorationremix.world.terrain.Net.Way;

import java.util.ArrayList;
import java.util.List;

/** The per-cell bucket grid the column lookups use: which chambers, way segments and shafts reach a bucket. */
final class NetIndex {
    private NetIndex() {}

    static final double REACH = 12; // roughness and smooth union reach beyond a primitive's own size

    static void build(Network w, Net n) {
        double x0 = n.cx * w.cell - Net.APRON, z0 = n.cz * w.cell - Net.APRON;
        int gn = (int) Math.ceil((w.cell + 2 * Net.APRON) / Net.BK);
        n.ix0 = x0;
        n.iz0 = z0;
        n.gn = gn;
        List<List<Integer>> bc = lists(gn), bw = lists(gn), bs = lists(gn), bh = lists(gn);
        for (Chamber c : n.ch) {
            if (!c.keep || c.landing() || c.kind == Net.Kind.CANYON || c.dark >= 0) continue;
            double r = c.R() + REACH;
            if (c.pillars.length > 0) r += 4;
            cover(n, c.x - r, c.z - r, c.x + r, c.z + r, bc, c.id, -1, null);
        }
        for (Way wy : n.ways) {
            for (int i = 0; i + 1 < wy.n; i++) {
                double pad = Math.max(wy.w[i], wy.w[i + 1]) * 1.3 + REACH;
                double minX = Math.min(wy.x[i], wy.x[i + 1]) - pad, maxX = Math.max(wy.x[i], wy.x[i + 1]) + pad;
                double minZ = Math.min(wy.z[i], wy.z[i + 1]) - pad, maxZ = Math.max(wy.z[i], wy.z[i + 1]) + pad;
                cover(n, minX, minZ, maxX, maxZ, bw, wy.id, i, bh);
            }
        }
        for (Shaft s : n.shafts) {
            double r = s.r + REACH;
            cover(n, s.x - r, s.z - r, s.x + r, s.z + r, bs, s.id, -1, null);
        }
        n.bCh = toArr(bc);
        n.bWay = toArr(bw);
        n.bWaySeg = toArr(bh);
        n.bSh = toArr(bs);
    }

    private static List<List<Integer>> lists(int gn) {
        List<List<Integer>> l = new ArrayList<>(gn * gn);
        for (int i = 0; i < gn * gn; i++) l.add(new ArrayList<>());
        return l;
    }

    private static void cover(Net n, double minX, double minZ, double maxX, double maxZ, List<List<Integer>> b, int id, int seg, List<List<Integer>> segs) {
        int bx0 = Math.max(0, (int) Math.floor((minX - n.ix0) / Net.BK)), bx1 = Math.min(n.gn - 1, (int) Math.floor((maxX - n.ix0) / Net.BK));
        int bz0 = Math.max(0, (int) Math.floor((minZ - n.iz0) / Net.BK)), bz1 = Math.min(n.gn - 1, (int) Math.floor((maxZ - n.iz0) / Net.BK));
        for (int bx = bx0; bx <= bx1; bx++) for (int bz = bz0; bz <= bz1; bz++) {
            List<Integer> l = b.get(bx * n.gn + bz);
            if (segs == null) {
                if (!l.contains(id)) l.add(id);
            } else {
                l.add(id);
                segs.get(bx * n.gn + bz).add(seg);
            }
        }
    }

    private static int[][] toArr(List<List<Integer>> l) {
        int[][] out = new int[l.size()][];
        for (int i = 0; i < l.size(); i++) {
            List<Integer> a = l.get(i);
            int[] r = new int[a.size()];
            for (int k = 0; k < r.length; k++) r[k] = a.get(k);
            out[i] = r;
        }
        return out;
    }

    static int bucket(Net n, double x, double z) {
        int bx = (int) Math.floor((x - n.ix0) / Net.BK), bz = (int) Math.floor((z - n.iz0) / Net.BK);
        if (bx < 0 || bz < 0 || bx >= n.gn || bz >= n.gn) return -1;
        return bx * n.gn + bz;
    }
}
