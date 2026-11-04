package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The cave particles: steam off hot pools, embers off lava, pollen in daylight shafts, and (0.8.0) the volcanic smoke of
 * the Galleries and the Wasteland. Spawned by each client.
 */
public final class ERParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, ExplorationRemix.MOD_ID);

    private ERParticles() {}

    public static final RegistryObject<SimpleParticleType> STEAM = PARTICLES.register("steam", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> EMBER = PARTICLES.register("ember", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> POLLEN = PARTICLES.register("pollen", () -> new SimpleParticleType(false));
    /** Seen across the halls, so never held back by distance (the client keeps its own count, SmokeBudget). */
    public static final RegistryObject<SimpleParticleType> VOLCANIC_SMOKE = PARTICLES.register("volcanic_smoke", () -> new SimpleParticleType(true));
}
