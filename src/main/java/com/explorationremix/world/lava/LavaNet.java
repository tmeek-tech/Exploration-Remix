package com.explorationremix.world.lava;

import com.explorationremix.ExplorationRemix;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The mod's network channel, {@code explorationremix:lava}: the server tells each client where the lava cycle is, so
 * the client can play the warning signs without asking again every tick (the deep world's terrain exists only on the
 * server, so the heights come along too).
 */
public final class LavaNet {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "lava"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private LavaNet() {}

    /** Called once from the mod's constructor. */
    public static void register() {
        CHANNEL.messageBuilder(Sync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Sync::write).decoder(Sync::read).consumerMainThread(Sync::handle).add();
    }

    /**
     * Where the lava cycle is: whether it runs at all, the cycle tick when sent, the phase lengths, whether the clock is
     * held, the lava table's Y, how far the lava rises, and the Galleries' top and bottom Y. The client counts on from
     * the tick with its own game time until the next one; sent on joining, on each phase change, after a command and
     * every few seconds to players in or near the Galleries. About 40 bytes.
     */
    public record Sync(boolean on, int tick, int[] lengths, boolean frozen, int tableY, int rise, int top, int bottom) {
        /** Tells the client the cycle does not run here (no deep world, or turned off). */
        static Sync off() { return new Sync(false, 0, new int[]{1, 0, 0, 0, 0}, true, 0, 0, 0, 0); }

        void write(FriendlyByteBuf b) {
            b.writeBoolean(on);
            b.writeVarInt(tick);
            b.writeVarIntArray(lengths);
            b.writeBoolean(frozen);
            b.writeInt(tableY);
            b.writeVarInt(rise);
            b.writeInt(top);
            b.writeInt(bottom);
        }

        static Sync read(FriendlyByteBuf b) {
            return new Sync(b.readBoolean(), b.readVarInt(), b.readVarIntArray(), b.readBoolean(), b.readInt(), b.readVarInt(), b.readInt(), b.readInt());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.LavaWarnings.accept(this));
        }
    }
}
