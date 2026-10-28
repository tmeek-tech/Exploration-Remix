package com.explorationremix.world;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * One of the four deep layers. {@code top} and {@code bottom} are inclusive block heights.
 * The band above a layer occupies (top, top + bandThickness].
 */
public record Layer(int index, String id, int top, int bottom, FluidKind fluid, boolean lavaPockets, Style style,
                    ResourceLocation refugeBiome, ResourceLocation routeBiome, List<ResourceLocation> huntingBiomes,
                    ResourceLocation baseStone, ResourceLocation caveFunction, double cavernScale, int fluidLevel) {

    public enum FluidKind { WATER, LAVA, NONE;
        public static FluidKind parse(String s) {
            return switch (s.toLowerCase()) { case "lava" -> LAVA; case "none" -> NONE; default -> WATER; };
        }
    }

    public enum Style { HALLS, RIFT, BASIN, GALLERIES;
        public static Style parse(String s) {
            return switch (s.toLowerCase()) { case "rift" -> RIFT; case "basin" -> BASIN; case "galleries" -> GALLERIES; default -> HALLS; };
        }
    }

    public int height() { return top - bottom + 1; }
    public int centerY() { return (top + bottom) / 2; }
    public boolean contains(int y) { return y >= bottom && y <= top; }
    /** Human-readable name used in messages and the run summary. */
    public String displayKey() { return "layer.explorationremix." + id; }
}
