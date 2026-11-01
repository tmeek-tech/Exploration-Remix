package com.explorationremix;

import com.explorationremix.config.ERClientConfig;
import com.explorationremix.config.ERConfig;
import com.explorationremix.devtools.DevAutoRun;
import com.explorationremix.devtools.RemixCommands;
import com.explorationremix.registry.ERBiomeModifiers;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERRemaps;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.world.gen.DeepWorldInstaller;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Exploration Remix, milestone 1: the overworld's underground extended down through four deep cave layers, each
 * sealed off by an unbreakable band with natural gate passages through it.
 */
@Mod(ExplorationRemix.MOD_ID)
public final class ExplorationRemix {
    public static final String MOD_ID = "explorationremix";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ExplorationRemix() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ERConfig.SPEC, MOD_ID + "-world.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ERClientConfig.SPEC, MOD_ID + "-client.toml");
        ERBlocks.BLOCKS.register(modBus);
        ERBlocks.ITEMS.register(modBus);
        ERBlocks.TABS.register(modBus);
        ERWorldgen.FEATURES.register(modBus);
        ERWorldgen.PLACEMENT_TYPES.register(modBus);
        ERWorldgen.STATE_PROVIDERS.register(modBus);
        ERWorldgen.HEIGHT_PROVIDERS.register(modBus);
        ERBiomeModifiers.SERIALIZERS.register(modBus);
        ERSounds.SOUNDS.register(modBus);
        ERParticles.PARTICLES.register(modBus);
        MinecraftForge.EVENT_BUS.register(DeepWorldInstaller.class);
        MinecraftForge.EVENT_BUS.register(RemixCommands.class);
        MinecraftForge.EVENT_BUS.register(ERRemaps.class);
        if (DevAutoRun.enabled()) MinecraftForge.EVENT_BUS.register(DevAutoRun.class);
    }
}
