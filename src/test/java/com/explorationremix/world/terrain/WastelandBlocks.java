package com.explorationremix.world.terrain;

/**
 * The Wasteland's blocks over a square of columns, worked out offline as the game would make them (its noise, with its
 * lava pools and hanging columns laid on as their features lay them, {@link DecorBox}), for tests outside this package
 * (the ember urchin's walks, 0.23.7).
 */
public final class WastelandBlocks {
    public static final int ROCK = NoiseVox.ROCK, AIR = NoiseVox.AIR, WATER = NoiseVox.WATER, LAVA = NoiseVox.LAVA;

    public final Terrain terrain;
    /** The Wasteland's rows, and the square's corner and size. */
    public final int bottom, top, x0, z0, size;
    private final NoiseVox v;

    private WastelandBlocks(Terrain t, NoiseVox v, int bottom, int top, int x0, int z0, int size) {
        this.terrain = t;
        this.v = v;
        this.bottom = bottom;
        this.top = top;
        this.x0 = x0;
        this.z0 = z0;
        this.size = size;
    }

    /** The square of {@code size} columns round (cx, cz) for {@code seed}, its features laid on with {@code decorate}. */
    public static WastelandBlocks build(long seed, int cx, int cz, int size, boolean decorate) {
        Terrain t = WastelandFieldTest.terrain(TerrainSettings.defaults(), seed);
        int bot = t.shape.bottom(Terrain.WASTELAND), top = t.shape.top(Terrain.WASTELAND);
        int x0 = cx - size / 2, z0 = cz - size / 2;
        NoiseVox v = NoiseVox.build(t, x0, z0, size, size, bot - 4, top + 1);
        if (decorate) {
            v.graph();
            new DecorBox(t, v).run(seed, x0 + 16, z0 + 16, x0 + size - 16, z0 + size - 16).finish();
        }
        return new WastelandBlocks(t, v, bot, top, x0, z0, size);
    }

    /** The material at a block ({@link #ROCK}, {@link #AIR}, {@link #WATER}, {@link #LAVA}); rock outside the box. */
    public int mat(int x, int y, int z) { return v.mat(x, y, z); }

    public boolean rock(int x, int y, int z) { return v.rock(x, y, z); }

    /** Whether a column lies in the Ash Hollows. */
    public boolean hollows(int x, int z) { return terrain.wasteland.biome(x, 0, z) == DeepBiome.ASH_HOLLOWS; }

    /** Whether a row lies in the Wasteland's layer. */
    public boolean inLayer(int y) { return terrain.shape.layerAt(y) == Terrain.WASTELAND; }

    /** The Wasteland's sheet (0 lowest) whose tunnel or cave holds a block, or -1. */
    public int sheet(int x, int y, int z) { return terrain.wasteland.sheetAt(x, y, z); }
}
