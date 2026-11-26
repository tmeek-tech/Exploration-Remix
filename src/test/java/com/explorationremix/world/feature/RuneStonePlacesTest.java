package com.explorationremix.world.feature;

import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Network;
import com.explorationremix.world.terrain.Terrain;
import com.explorationremix.world.terrain.TerrainSettings;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the rune stones go (0.23.1) on the test seed's plan: about one vent in eight with a way into its chamber hides
 * one (card "One in eight"), about one pond in three (card "One in three"), and a structure piece's stone is set by
 * the one chunk holding most of it, so the chunks of a piece never set two. (Whether each place's blocks give its
 * stone a spot is read from the made world by /remix runestones; the Galleries' ruins are GalleriesRuinsTest's.)
 */
class RuneStonePlacesTest {
    static final long SEED = 8675309L;
    static final Terrain T = new Terrain(TerrainSettings.defaults(), -64, SEED);

    @Test
    void aboutOneVentInEightWithAWayInHidesAStone() {
        List<GardensField.VentChamber> vs = T.gardens.ventChambers(-1536, -1536, 1536, 1536);
        int open = 0, hide = 0;
        for (GardensField.VentChamber v : vs) {
            if (v.cracks().length > 0) open++;
            if (RuneStoneSites.ventHides(v)) {
                hide++;
                assertTrue(v.cracks().length > 0, "a vent with no way in hides a stone");
            }
        }
        double share = hide / (double) Math.max(1, open);
        System.out.printf(Locale.ROOT, "RuneStonePlacesTest: %d vents, %d with a way in, %d hide a stone (%.3f)%n", vs.size(), open, hide, share);
        assertTrue(open >= 100, "only " + open + " vents with a way in");
        assertTrue(share > 0.07 && share < 0.2, hide + " of " + open + " vents with a way in hide a stone");
    }

    @Test
    void aboutOnePondInThreeHidesAStone() {
        int cell = (int) T.network.cellSize(), x0 = -cell, z0 = -cell, size = 3 * cell;
        int all = 0, hide = 0;
        for (Network.PondInfo p : T.network.pondsIn(x0, z0, x0 + size - 1, z0 + size - 1)) {
            if (p.x() < x0 || p.x() >= x0 + size || p.z() < z0 || p.z() >= z0 + size) continue;
            all++;
            if (RuneStoneSites.pondHides(SEED, p)) hide++;
        }
        double share = hide / (double) Math.max(1, all);
        System.out.printf(Locale.ROOT, "RuneStonePlacesTest: %d ponds in 3 x 3 Blue cells, %d hide a stone (%.3f)%n", all, hide, share);
        assertTrue(all >= 25, "only " + all + " ponds in 9 cells");
        assertTrue(share > 0.15 && share < 0.55, hide + " of " + all + " ponds hide a stone");
    }

    /** Of the chunks a piece's box reaches, exactly one is its home: the one holding most of its columns. */
    @Test
    void aPieceHasOneHomeChunk() {
        assertEquals(new ChunkPos(0, 0), RuneStoneStructures.homeOf(new BoundingBox(2, 0, 2, 9, 5, 9)));
        // 6 columns of x in chunk 0 (10..15), 10 in chunk 1 (16..25)
        assertEquals(new ChunkPos(1, 0), RuneStoneStructures.homeOf(new BoundingBox(10, 0, 3, 25, 5, 7)));
        // across four chunks, most in the north-west one
        assertEquals(new ChunkPos(0, -1), RuneStoneStructures.homeOf(new BoundingBox(4, 0, -12, 18, 5, 2)));
        // a tie: the first, west then north
        assertEquals(new ChunkPos(-1, 0), RuneStoneStructures.homeOf(new BoundingBox(-4, 0, 0, 3, 5, 7)));
        for (int x0 = -40; x0 <= 40; x0 += 7)
            for (int w = 1; w <= 40; w += 5) {
                BoundingBox b = new BoundingBox(x0, 0, -x0, x0 + w, 10, -x0 + 2 * w);
                ChunkPos h = RuneStoneStructures.homeOf(b);
                assertTrue(h.getMaxBlockX() >= b.minX() && h.getMinBlockX() <= b.maxX() && h.getMaxBlockZ() >= b.minZ() && h.getMinBlockZ() <= b.maxZ(),
                        "home " + h + " outside " + b);
            }
    }
}
