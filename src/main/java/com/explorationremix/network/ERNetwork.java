package com.explorationremix.network;

import com.explorationremix.ExplorationRemix;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ERNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "main"),
            () -> VERSION, VERSION::equals, VERSION::equals);
    private static int id = 0;

    private ERNetwork() {}

    public static void register() {
        CHANNEL.messageBuilder(RunSummaryPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RunSummaryPacket::write).decoder(RunSummaryPacket::read).consumerMainThread(RunSummaryPacket::handle).add();
        CHANNEL.messageBuilder(RuneSyncPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RuneSyncPacket::write).decoder(RuneSyncPacket::read).consumerMainThread(RuneSyncPacket::handle).add();
        CHANNEL.messageBuilder(RuneEditPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RuneEditPacket::write).decoder(RuneEditPacket::read).consumerMainThread(RuneEditPacket::handle).add();
        CHANNEL.messageBuilder(CombatFxPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(CombatFxPacket::write).decoder(CombatFxPacket::read).consumerMainThread(CombatFxPacket::handle).add();
        CHANNEL.messageBuilder(ComboFinisherPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ComboFinisherPacket::write).decoder(ComboFinisherPacket::read).consumerMainThread(ComboFinisherPacket::handle).add();
        CHANNEL.messageBuilder(RunStatePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RunStatePacket::write).decoder(RunStatePacket::read).consumerMainThread(RunStatePacket::handle).add();
        CHANNEL.messageBuilder(RuneMenuStatePacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RuneMenuStatePacket::write).decoder(RuneMenuStatePacket::read).consumerMainThread(RuneMenuStatePacket::handle).add();
    }
}
