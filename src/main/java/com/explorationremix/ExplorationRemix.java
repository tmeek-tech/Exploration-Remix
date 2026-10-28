package com.explorationremix;

import com.explorationremix.config.ERConfig;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.world.gen.DeepWorldInstaller;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ExplorationRemix.MOD_ID)
public final class ExplorationRemix {
    public static final String MOD_ID = "explorationremix";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ExplorationRemix() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ERConfig.SPEC);
        ERBlocks.BLOCKS.register(modBus);
        com.explorationremix.registry.ERItems.init();
        ERBlocks.ITEMS.register(modBus);
        com.explorationremix.registry.ERBlockEntities.BLOCK_ENTITIES.register(modBus);
        com.explorationremix.registry.ERWorldgen.FEATURES.register(modBus);
        com.explorationremix.registry.ERWorldgen.PLACEMENT_TYPES.register(modBus);
        com.explorationremix.registry.ERWorldgen.STATE_PROVIDERS.register(modBus);
        com.explorationremix.registry.ERBiomeModifiers.SERIALIZERS.register(modBus);
        com.explorationremix.registry.ERMenus.MENUS.register(modBus);
        com.explorationremix.registry.ERLootModifiers.GLM.register(modBus);
        com.explorationremix.registry.EREntities.ENTITIES.register(modBus);
        com.explorationremix.registry.ERItems.TABS.register(modBus);
        modBus.addListener(com.explorationremix.registry.EREntities::onAttributes);
        modBus.addListener(ExplorationRemix::onCommonSetup);
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist == net.minecraftforge.api.distmarker.Dist.CLIENT)
            modBus.register(com.explorationremix.client.ClientSetup.class);
        MinecraftForge.EVENT_BUS.register(DeepWorldInstaller.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.run.RunManager.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.run.RunXp.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.run.HomeReturn.class);
        MinecraftForge.EVENT_BUS.register(DataEvents.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.rune.cast.RuneCaster.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.rune.cast.RuneEvents.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.combat.CombatEvents.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.enchant.EnchantingEvents.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.loophole.LoopholeEvents.class);
        com.explorationremix.creature.CreatureDirector.init();
        MinecraftForge.EVENT_BUS.register(com.explorationremix.creature.CreatureDirector.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.creature.HunterDirector.class);
        MinecraftForge.EVENT_BUS.register(com.explorationremix.devtools.RemixCommands.class);
        if (com.explorationremix.devtools.DevAutoRun.enabled()) MinecraftForge.EVENT_BUS.register(com.explorationremix.devtools.DevAutoRun.class);
    }

    private static void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) {
        e.enqueueWork(() -> {
            com.explorationremix.network.ERNetwork.register();
            net.minecraftforge.common.crafting.CraftingHelper.register(com.explorationremix.enchant.ConfigCondition.Serializer.INSTANCE);
        });
    }

    /** Datapack-driven data: runes load with every reload and sync to each client. */
    public static final class DataEvents {
        private DataEvents() {}

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onAddReloadListeners(net.minecraftforge.event.AddReloadListenerEvent e) {
            e.addListener(new com.explorationremix.rune.RuneRegistry());
        }

        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onDatapackSync(net.minecraftforge.event.OnDatapackSyncEvent e) {
            com.explorationremix.network.RuneSyncPacket packet = com.explorationremix.network.RuneSyncPacket.all();
            if (e.getPlayer() != null) com.explorationremix.network.ERNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(e::getPlayer), packet);
            else com.explorationremix.network.ERNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.ALL.noArg(), packet);
        }
    }
}
