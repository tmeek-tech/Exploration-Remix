package com.explorationremix.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Per-player settings for how the deep world looks and sounds: {@code config/explorationremix-client.toml}. */
public final class ERClientConfig {
    public static final ForgeConfigSpec SPEC;
    public static final ERClientConfig INSTANCE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        INSTANCE = new ERClientConfig(b);
        SPEC = b.build();
    }

    public final ForgeConfigSpec.BooleanValue caveFog;
    public final ForgeConfigSpec.DoubleValue fogDistance;
    public final ForgeConfigSpec.BooleanValue caveParticles;

    private ERClientConfig(ForgeConfigSpec.Builder b) {
        b.push("caves");
        caveFog = b.comment("Tint the distance in the deep layers with each biome's fog colour.").define("caveFog", true);
        fogDistance = b.comment("How far you can see through cave fog, in blocks (steam and smoke are thicker).").defineInRange("fogDistance", 72.0, 24.0, 512.0);
        caveParticles = b.comment("Steam over hot pools, embers and ash in the Basalt Galleries.").define("caveParticles", true);
        b.pop();
    }
}
