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
    public final ForgeConfigSpec.BooleanValue caveLight;
    /**
     * The view shakes in the Basalt Galleries while the lava is about to rise and rising (client.LavaWarnings), in quakes
     * and tremors and when an eruption bursts nearby (0.9.0).
     */
    public final ForgeConfigSpec.BooleanValue screenShake;
    /** The view shakes when a rune blast, a slam or a stagger goes off near you, and kicks when your hit lands (client.rune.fx.RuneCamera). */
    public final ForgeConfigSpec.BooleanValue runeShake;
    /** How strong that shaking is, 0 to 1 (0.9.0). */
    public final ForgeConfigSpec.DoubleValue shakeStrength;
    /** Which light tremors this player feels (0.9.0; big quakes always shake). */
    public final ForgeConfigSpec.EnumValue<TremorEffects> tremorEffects;

    /** Which light tremors and climb answers a player feels ({@code tremorEffects}). */
    public enum TremorEffects {
        /** Every one, in full. */
        ALL,
        /** In full only those that topple something within 32 blocks; one in three of the rest faintly. */
        WHERE_BLOCKS_FALL,
        /** None (big quakes and a build's creak still play). */
        NONE
    }

    private ERClientConfig(ForgeConfigSpec.Builder b) {
        b.push("caves");
        caveFog = b.comment("Tint the distance in the deep layers with each biome's fog colour.").define("caveFog", true);
        fogDistance = b.comment("How far you can see through cave fog, in blocks (steam and smoke are thicker).").defineInRange("fogDistance", 160.0, 24.0, 512.0);
        caveParticles = b.comment("Steam over hot pools, embers and ash in the Basalt Galleries, the dust of quakes and the smoke of eruptions.",
                "Turned off, an eruption's fountain stays (thinner) and the grit that warns of falling rock still shows.").define("caveParticles", true);
        caveLight = b.comment("Tint the light of glowing blocks with each deep biome's own colour: cold blue in the Blue Reaches, red in",
                "the Rust Springs and the Basalt Galleries.").define("caveLight", true);
        b.pop();
        b.push("lavaCycle");
        screenShake = b.comment("Shake the view in the Basalt Galleries while the lava is about to rise and rising, in quakes and tremors, and",
                "when an eruption bursts nearby (scaled by the game's Distortion Effects).").define("screenShake", true);
        shakeStrength = b.comment("How strong that shaking is.").defineInRange("shakeStrength", 1.0, 0.0, 1.0);
        tremorEffects = b.comment("Which light tremors you feel: all of them; in full only those that topple something near you, with one in",
                "three of the rest felt faintly; or none (big quakes still shake).").defineEnum("tremorEffects", TremorEffects.WHERE_BLOCKS_FALL);
        b.pop();
        b.push("runes");
        runeShake = b.comment("Shake the view a little when a rune blast, a wall slam or a stagger goes off near you, and kick it when your own",
                "hit lands (scaled by the game's Distortion Effects setting).").define("runeShake", true);
        b.pop();
    }
}
