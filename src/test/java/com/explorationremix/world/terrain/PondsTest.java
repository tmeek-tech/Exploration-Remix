package com.explorationremix.world.terrain;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ponds on the Blue Reaches' rift shelves (0.21.7; Taran's cards: shelves "Mixed, 4 to 16 blocks out", ponds that
 * spill "About half"). Over the 2 x 2 Blue cells the voxel tests use there are several to a cell, on the noise rifts and
 * on the Deep Blue Rift, and about half spill. Block by block each holds its water: its basin full to its top block,
 * no water over an open block, none beside an open block but at its spill's mouth (where the water runs off the
 * platform, PondSpillFeature's falls), room over the water to stand in, and a spill that reaches the open air.
 * Positions follow the ponds' own rule, a block's corner (x, z) against the pond's centre.
 */
class PondsTest {
    @Test
    @Tag("voxel")
    void pondsHoldTheirWaterAndAboutHalfSpill() {
        long seed = NetworkTest.SEEDS[0];
        Terrain t = NetworkTest.terrain(seed);
        Network w = t.network;
        int cell = (int) w.cell, x0 = 0, z0 = -3 * cell, size = 2 * cell;
        List<Network.PondInfo> ps = new ArrayList<>();
        for (Network.PondInfo p : w.pondsIn(x0, z0, x0 + size - 1, z0 + size - 1))
            if (p.x() >= x0 && p.x() < x0 + size && p.z() >= z0 && p.z() < z0 + size) ps.add(p);
        List<String> fails = new ArrayList<>();
        int spills = 0, main = 0, opensBehind = 0, mouths = 0;
        for (Network.PondInfo p : ps) {
            if (p.spills()) spills++;
            if (p.main()) main++;
            int H = 24, bx = (int) Math.floor(p.x()) - H, bz = (int) Math.floor(p.z()) - H;
            VoxelHarness v = VoxelHarness.build(t, Terrain.BLUE, bx, bz, 2 * H, 2 * H);
            int dry = 0, roofLow = 0, leakDown = 0, leakSide = 0, mouthSide = 0, mouth = 0, behind = 0;
            for (int x = bx; x < bx + 2 * H; x++) for (int z = bz; z < bz + 2 * H; z++) {
                double ox = x - p.x(), oz = z - p.z(), r = Math.hypot(ox, oz);
                double al = ox * p.sx() + oz * p.sz(), across = Math.abs(ox * p.sz() - oz * p.sx());
                boolean inSpill = p.spills() && al > p.r() - 1.5 && across < p.sw() + 0.01;
                if (r < p.r() - 0.05) {
                    if (v.mat(x, p.top(), z) != VoxelHarness.WATER) dry++;
                    for (int y = p.top() + 1; y <= p.feet() + 1; y++) if (!v.open(x, y, z)) roofLow++;
                }
                for (int y = p.top() - p.depth(); y <= p.top(); y++) {
                    if (v.mat(x, y, z) != VoxelHarness.WATER || (r > p.r() + 0.6 && !inSpill)) continue;
                    if (v.mat(x, y - 1, z) == VoxelHarness.AIR) leakDown++;
                    for (int d = 0; d < 4; d++) {
                        int qx = x + VoxelHarness.DX[d], qz = z + VoxelHarness.DZ[d];
                        if (v.mat(qx, y, qz) != VoxelHarness.AIR) continue;
                        double qa = (qx - p.x()) * p.sx() + (qz - p.z()) * p.sz();
                        if (inSpill && qa > al) mouth++;
                        else if (inSpill && al > p.wet() - 3) mouthSide++;   // a block beside the mouth: its fall a block wider
                        else leakSide++;
                    }
                }
                // behind the pond: open at its feet with a drop under (another rift's slot, a cave)
                if (al < -(p.r() + 2) && al > -(p.r() + 8) && across < p.r()
                        && v.open(x, p.feet(), z) && v.open(x, p.feet() - 1, z) && v.open(x, p.feet() - 4, z)) behind++;
            }
            String at = String.format(java.util.Locale.ROOT, "pond at %.1f %.1f (r %.1f, top %d, %s%s)", p.x(), p.z(), p.r(), p.top(),
                    p.main() ? "Deep Blue Rift" : "noise rift", p.spills() ? ", spills" : "");
            if (dry > 2) fails.add(at + ": " + dry + " columns of its basin dry at its top block");
            if (roofLow > 4) fails.add(at + ": " + roofLow + " blocks of rock over its water");
            if (leakDown > 0) fails.add(at + ": water over " + leakDown + " open blocks");
            if (leakSide > 0) fails.add(at + ": water beside " + leakSide + " open blocks away from its spill's mouth");
            if (p.spills() && mouth == 0) fails.add(at + ": its spill never reaches the open air");
            if (behind > 3) opensBehind++;
            mouths += mouthSide;
        }
        System.out.printf(java.util.Locale.ROOT, "PondsTest: %d ponds in 2 x 2 Blue cells, %d on the Deep Blue Rift, %d spilling; %d open beside a mouth, %d with a drop just behind%n",
                ps.size(), main, spills, mouths, opensBehind);
        // measured 19 (8 on the Deep Blue Rift, 8 spilling) when this was written; over 3 seeds x 9 cells 4.5 to 6.5 a cell
        if (ps.size() < 12) fails.add("only " + ps.size() + " ponds in 4 cells");
        if (main < 2) fails.add("only " + main + " ponds on the Deep Blue Rift");
        double share = spills / (double) Math.max(1, ps.size());
        if (share < 0.3 || share > 0.7) fails.add(spills + " of " + ps.size() + " ponds spill");
        if (opensBehind * 8 > ps.size()) fails.add(opensBehind + " ponds have a drop just behind them");
        assertTrue(fails.isEmpty(), fails.size() + " failures:\n" + String.join("\n", fails));
    }
}
