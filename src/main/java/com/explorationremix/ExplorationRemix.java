package com.explorationremix;

import com.explorationremix.config.ERClientConfig;
import com.explorationremix.config.ERConfig;
import com.explorationremix.devtools.DevAutoRun;
import com.explorationremix.devtools.RemixCommands;
import com.explorationremix.registry.ERBiomeModifiers;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.registry.EREntities;
import com.explorationremix.registry.ERParticles;
import com.explorationremix.registry.ERRemaps;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.registry.ERWorldgen;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.net.RuneNet;
import com.explorationremix.world.eruption.Blast;
import com.explorationremix.world.eruption.Eruptions;
import com.explorationremix.world.gen.DeepWorldInstaller;
import com.explorationremix.world.lava.Falling;
import com.explorationremix.world.lava.LavaChunk;
import com.explorationremix.world.lava.LavaFlood;
import com.explorationremix.world.lava.LavaNet;
import com.explorationremix.world.quake.Quakes;
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
        EREntities.ENTITIES.register(modBus);
        EREntities.BLOCK_ENTITIES.register(modBus);
        modBus.addListener(EREntities::onAttributes);
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
        // The Basalt Galleries' lava cycle: its per-chunk record, the server side and the channel to the clients.
        modBus.addListener(LavaChunk::register);
        MinecraftForge.EVENT_BUS.register(LavaFlood.class);
        // 0.9.0: the quakes' placement and break listeners (climb answers, toppleAtOnce)
        MinecraftForge.EVENT_BUS.register(Quakes.class);
        MinecraftForge.EVENT_BUS.register(Falling.class);
        // the eruptions' bombs, let go and discarded before the server's last save, and their blasts' handler
        MinecraftForge.EVENT_BUS.register(Eruptions.class);
        MinecraftForge.EVENT_BUS.register(Blast.class);
        LavaNet.register();
        // Runes (0.10.0): the rune items, the blocks and entities runes make, the rune screen; the server's rune settings
        // (sent to each client that joins); the runes' channel
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, RuneConfig.SPEC, MOD_ID + "-runes.toml");
        RuneRegistry.register(modBus);
        RuneNet.register();
        if (DevAutoRun.enabled()) MinecraftForge.EVENT_BUS.register(DevAutoRun.class);
    }
}
