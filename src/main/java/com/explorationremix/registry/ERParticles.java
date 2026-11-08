package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The cave particles: steam off hot pools, embers off lava, pollen in daylight shafts, (0.8.0) the volcanic smoke of
 * the Galleries and the Wasteland, and (0.9.0) the grit of the quakes and the lava spatter of the eruptions. Spawned by
 * each client.
 */
public final class ERParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, ExplorationRemix.MOD_ID);

    private ERParticles() {}

    public static final RegistryObject<SimpleParticleType> STEAM = PARTICLES.register("steam", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> EMBER = PARTICLES.register("ember", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> POLLEN = PARTICLES.register("pollen", () -> new SimpleParticleType(false));
    /** Seen across the halls, so never held back by distance (the client keeps its own count, SmokeBudget). */
    public static final RegistryObject<SimpleParticleType> VOLCANIC_SMOKE = PARTICLES.register("volcanic_smoke", () -> new SimpleParticleType(true));
    /**
     * 0.9.0: a small grey pebble falling, the quakes' dust in the air and the grit that warns of falling rock (forced by
     * hand only where it warns, so held back by distance and the Particles option otherwise).
     */
    public static final RegistryObject<SimpleParticleType> GRIT = PARTICLES.register("grit", () -> new SimpleParticleType(false));
    /**
     * 0.9.0: a gob of glowing lava thrown up by an eruption's fountain, off a lava bomb in flight, or where something falls
     * into the lava (forced by hand only for the far part of a fountain, so held back by distance and the Particles option
     * otherwise).
     */
    public static final RegistryObject<SimpleParticleType> LAVA_SPATTER = PARTICLES.register("lava_spatter", () -> new SimpleParticleType(false));
}
