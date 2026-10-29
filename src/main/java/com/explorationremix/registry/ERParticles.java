package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** The cave particles: steam off hot pools, embers off lava, pollen in daylight shafts. Spawned by each client. */
public final class ERParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, ExplorationRemix.MOD_ID);

    private ERParticles() {}

    public static final RegistryObject<SimpleParticleType> STEAM = PARTICLES.register("steam", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> EMBER = PARTICLES.register("ember", () -> new SimpleParticleType(false));
    public static final RegistryObject<SimpleParticleType> POLLEN = PARTICLES.register("pollen", () -> new SimpleParticleType(false));
}
