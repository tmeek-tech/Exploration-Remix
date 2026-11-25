package com.explorationremix.block;

/**
 * The Hollow Choir's two colours (0.21.9), kept in one place so the blocks, the boss, its effects and its art agree:
 * a pale light blue glow and a bone white, and never sculk's own teal (Taran's standing look rules: light blue, no
 * warm colours and no cyan).
 */
public final class ChoirLook {
    /** The glow: the braziers' cold flame, the sockets' inlay, the open core, the tolls' rings. */
    public static final int GLOW = 0x8EC2FF;
    /** The bone white of the crown's plates, the sprouts' stems and the brightest part of a toll. */
    public static final int BONE = 0xF0EFF4;
    /** The glow as the three floats a particle or a shader wants. */
    public static final float[] GLOW_RGB = {((GLOW >> 16) & 255) / 255F, ((GLOW >> 8) & 255) / 255F, (GLOW & 255) / 255F};
    public static final float[] BONE_RGB = {((BONE >> 16) & 255) / 255F, ((BONE >> 8) & 255) / 255F, (BONE & 255) / 255F};

    private ChoirLook() {}
}
