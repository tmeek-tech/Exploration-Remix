package com.explorationremix.registry;

import com.explorationremix.ExplorationRemix;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The cave particles: steam off hot pools, embers off lava, pollen in daylight shafts, (0.8.0) the volcanic smoke of
 * the Galleries and the Wasteland, and (0.9.0) the grit of the quakes and the lava spatter of the eruptions, and the
 * eruptions' lava gobs, fireballs and dark ash, the blasts' ash and the crashes' rock dust, the blasts' flash and the
 * rubble. Spawned by each client.
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
    /**
     * 0.9.0 (pack look fixes): a clot of glowing lava half a block to a block across, the body of an eruption's fountain,
     * a vent's lava pops, a bomb's burst and its far trail (forced by hand beyond 32 blocks, as the spatter).
     */
    public static final RegistryObject<SimpleParticleType> LAVA_GOB = PARTICLES.register("lava_gob", () -> new SimpleParticleType(false));
    /** 0.9.0: a ball of fire at a vent's mouth, in its burst's flash and at a bomb's blast. */
    public static final RegistryObject<SimpleParticleType> FIREBALL = PARTICLES.register("fireball", () -> new SimpleParticleType(false));
    /**
     * 0.9.0: a puff of an eruption's dark ash, the plume and the warning's smoke; seen across the Caldera, so never held
     * back by distance (counted in the client's smoke, SmokeBudget, as the volcanic smoke is).
     */
    public static final RegistryObject<SimpleParticleType> ERUPTION_ASH = PARTICLES.register("eruption_ash", () -> new SimpleParticleType(true));
    /** 0.9.0: the dark billow of ash and rock dust a lava bomb's blast throws up. */
    public static final RegistryObject<SimpleParticleType> BLAST_ASH = PARTICLES.register("blast_ash", () -> new SimpleParticleType(false));
    /** 0.9.0: the pale billow of rock dust where a ceiling block crashes down. */
    public static final RegistryObject<SimpleParticleType> ROCK_DUST = PARTICLES.register("rock_dust", () -> new SimpleParticleType(false));
    /**
     * 0.9.0 (eruption effects, round 2): the blaze of a lava bomb's blast and of an eruption's first ticks, and the glow
     * building at a vent's mouth while it warns.
     */
    public static final RegistryObject<SimpleParticleType> FLASH = PARTICLES.register("flash", () -> new SimpleParticleType(false));
    /** 0.9.0 (eruption effects, round 2): a chunk of broken rock thrown out by a blast or a crashed block, tinted as the rock. */
    public static final RegistryObject<SimpleParticleType> RUBBLE = PARTICLES.register("rubble", () -> new SimpleParticleType(false));
}
