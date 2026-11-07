package com.explorationremix.world.terrain;

import java.util.List;

/**
 * The vertical layout of the deep world: the pack's old floor, the bands under it and the layers between them.
 * Band {@code b} (1-based) sits directly above layer {@code b - 1} (0-based). All heights are inclusive block Ys.
 */
public final class Shape {
    public final int packFloor;
    public final int floorY;
    public final int bandThickness;
    public final int layerCount;
    private final int[] top, bottom, water;
    /** Thickness of band b at index b - 1: 0 for the band the Wasteland and the Galleries no longer have (0.5.1). */
    private final int[] thick;
    private final boolean[] lava;
    private final String[] ids;
    /** For every y from floorY to packFloor - 1: layer index (0..3), or -band (-1..-4) inside a band. */
    private final byte[] lookup;

    public Shape(TerrainSettings s, int packFloor) {
        this.packFloor = packFloor;
        this.bandThickness = s.bandThickness();
        List<LayerSettings> ls = s.layers();
        this.layerCount = ls.size();
        top = new int[layerCount];
        bottom = new int[layerCount];
        water = new int[layerCount];
        lava = new boolean[layerCount];
        ids = new String[layerCount];
        thick = new int[layerCount];
        for (int b = 1; b <= layerCount; b++) thick[b - 1] = s.bandThickness(b);
        int t = packFloor - thick[0] - 1;
        for (int i = 0; i < layerCount; i++) {
            LayerSettings l = ls.get(i);
            // A layer over an empty band absorbs the band's rows, so every row under it stays where it was.
            int h = Math.max(64, (l.height() / 16) * 16) + (i + 1 < layerCount ? bandThickness - thick[i + 1] : 0);
            top[i] = t;
            bottom[i] = t - h + 1;
            water[i] = bottom[i] + Math.max(6, Math.min(h / 3, l.fluidDepth()));
            lava[i] = l.lava();
            ids[i] = l.id();
            t = bottom[i] - (i + 1 < layerCount ? thick[i + 1] : 0) - 1;
        }
        // The world's floor must sit on a 16-block section boundary: the last layer takes up any slack.
        int last = layerCount - 1;
        int aligned = Math.floorDiv(bottom[last], 16) * 16;
        if (aligned != bottom[last]) {
            bottom[last] = aligned;
            int h = top[last] - aligned + 1;
            water[last] = aligned + Math.max(6, Math.min(h / 3, ls.get(last).fluidDepth()));
        }
        this.floorY = bottom[last];
        lookup = new byte[packFloor - floorY];
        for (int y = floorY; y < packFloor; y++) {
            int v = 0;
            boolean found = false;
            for (int i = 0; i < layerCount; i++) {
                if (y <= top[i] && y >= bottom[i]) { v = i; found = true; break; }
            }
            if (!found) {
                for (int b = 1; b <= layerCount; b++) {
                    if (y <= bandTop(b) && y >= bandBottom(b)) { v = -b; found = true; break; }
                }
            }
            lookup[y - floorY] = (byte) (found ? v : 0);
        }
    }

    public int top(int layer) { return top[layer]; }
    public int bottom(int layer) { return bottom[layer]; }
    /** The first block above the layer's fluid: water or lava fills every open block below this Y. */
    public int waterY(int layer) { return water[layer]; }
    public boolean lava(int layer) { return lava[layer]; }
    public String id(int layer) { return ids[layer]; }
    public int height(int layer) { return top[layer] - bottom[layer] + 1; }

    /** Top block of band b (1-based). Band 1 sits right under the pack's old floor. */
    public int bandTop(int b) { return b <= 1 ? packFloor - 1 : bottom[b - 2] - 1; }
    /** Bottom block of band b; bandTop(b) + 1 for an empty band, which then has no rows. */
    public int bandBottom(int b) { return bandTop(b) - thick[b - 1] + 1; }
    /** Rows in band b (1-based): 0 for an empty band. */
    public int bandThickness(int b) { return thick[b - 1]; }

    /** Layer index at y, or -1 in a band, above the deep world or below the floor. */
    public int layerAt(int y) {
        if (y >= packFloor || y < floorY) return -1;
        int v = lookup[y - floorY];
        return v >= 0 ? v : -1;
    }

    /** Band (1-based) at y, or 0. */
    public int bandAt(int y) {
        if (y >= packFloor || y < floorY) return 0;
        int v = lookup[y - floorY];
        return v < 0 ? -v : 0;
    }

    public int layerIndex(String id) {
        for (int i = 0; i < layerCount; i++) if (ids[i].equals(id)) return i;
        return -1;
    }
}
