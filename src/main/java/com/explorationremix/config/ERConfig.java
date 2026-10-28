package com.explorationremix.config;

import com.explorationremix.world.terrain.LayerSettings;
import com.explorationremix.world.terrain.TerrainSettings;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * World settings, read when a server starts. Written to {@code config/explorationremix-world.toml}. The sizes are
 * starting values, not limits.
 */
public final class ERConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ERConfig INSTANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        INSTANCE = new ERConfig(b);
        SPEC = b.build();
    }

    public final ForgeConfigSpec.BooleanValue worldEnabled;
    public final ForgeConfigSpec.IntValue bandThickness;
    public final ForgeConfigSpec.IntValue gateSpacing;
    public final ForgeConfigSpec.DoubleValue passageRadius;
    public final ForgeConfigSpec.DoubleValue landingRadius;
    public final ForgeConfigSpec.IntValue structureFloorY;
    public final ForgeConfigSpec.BooleanValue replaceVanillaCaveBiomes;
    public final ForgeConfigSpec.BooleanValue removeCreateStriations;
    public final LayerConfig[] layers = new LayerConfig[4];

    public static final class LayerConfig {
        public final String id;
        public final ForgeConfigSpec.IntValue height;
        public final ForgeConfigSpec.IntValue fluidDepth;
        public final ForgeConfigSpec.DoubleValue heightMin, heightMax, widthMin, widthMax;
        final boolean lava;

        LayerConfig(ForgeConfigSpec.Builder b, LayerSettings d, String heightWord, String widthWord) {
            this.id = d.id();
            this.lava = d.lava();
            b.push(d.id());
            height = b.comment("Height of the layer in blocks (rounded down to a multiple of 16).")
                    .defineInRange("height", d.height(), 64, 512);
            fluidDepth = b.comment("The " + (d.lava() ? "lava" : "water") + " table sits this many blocks above the layer's floor.")
                    .defineInRange("fluidDepth", d.fluidDepth(), 6, 64);
            heightMin = b.comment("Smallest " + heightWord + ".").defineInRange("spaceHeightMin", d.heightMin(), 8.0, 400.0);
            heightMax = b.comment("Largest " + heightWord + ".").defineInRange("spaceHeightMax", d.heightMax(), 8.0, 400.0);
            widthMin = b.comment("Smallest " + widthWord + ".").defineInRange("spaceWidthMin", d.widthMin(), 8.0, 400.0);
            widthMax = b.comment("Largest " + widthWord + ".").defineInRange("spaceWidthMax", d.widthMax(), 8.0, 400.0);
            b.pop();
        }

        LayerSettings toSettings() {
            double hMin = Math.min(heightMin.get(), heightMax.get()), hMax = Math.max(heightMin.get(), heightMax.get());
            double wMin = Math.min(widthMin.get(), widthMax.get()), wMax = Math.max(widthMin.get(), widthMax.get());
            return new LayerSettings(id, height.get(), fluidDepth.get(), lava, hMin, hMax, wMin, wMax);
        }
    }

    private ERConfig(ForgeConfigSpec.Builder b) {
        TerrainSettings d = TerrainSettings.defaults();
        b.comment("The deep world below the pack's old floor. Changes apply to newly generated chunks.").push("world");
        worldEnabled = b.comment("Extend the overworld down through the four deep layers.").define("enabled", true);
        bandThickness = b.comment("Thickness of the unbreakable bands between layers.").defineInRange("bandThickness", d.bandThickness(), 8, 64);
        gateSpacing = b.comment("One gate per band in every square of this many blocks.").defineInRange("gateSpacing", d.gateSpacing(), 320, 4096);
        passageRadius = b.comment("Radius of the twisting gate passages.").defineInRange("passageRadius", d.passageRadius(), 2.5, 12.0);
        landingRadius = b.comment("Radius of the landing chambers at each end of a gate passage.").defineInRange("landingRadius", d.landingRadius(), 8.0, 40.0);
        structureFloorY = b.comment("Strongholds and mineshafts stay above this Y so the End stays reachable.").defineInRange("structureFloorY", -56, -64, 0);
        replaceVanillaCaveBiomes = b.comment("Rootvaults replace vanilla's dripstone caves and deep dark above the first band (both moved down into the layers).")
                .define("replaceVanillaCaveBiomes", true);
        removeCreateStriations = b.comment("Remove Create's striped stone layers from the overworld; the layers use Create's stones as patches instead.")
                .define("removeCreateStriations", true);
        layers[0] = new LayerConfig(b, d.layers().get(0), "hall height", "lake width");
        layers[1] = new LayerConfig(b, d.layers().get(1), "rift depth", "geode width");
        layers[2] = new LayerConfig(b, d.layers().get(2), "cavern height", "cavern width");
        layers[3] = new LayerConfig(b, d.layers().get(3), "gallery height", "gallery width");
        b.pop();
    }

    public TerrainSettings toSettings() {
        List<LayerSettings> ls = new ArrayList<>();
        for (LayerConfig lc : layers) ls.add(lc.toSettings());
        return new TerrainSettings(bandThickness.get(), gateSpacing.get(), passageRadius.get(), landingRadius.get(), List.copyOf(ls));
    }
}
