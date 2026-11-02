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
 * server, so the heights come along too), and when a quake or an eruption starts, so the client plays it out (0.5.1).
 */
public final class LavaNet {
    private static final String VERSION = "2";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "lava"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private LavaNet() {}

    /** Called once from the mod's constructor. */
    public static void register() {
        CHANNEL.messageBuilder(Sync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Sync::write).decoder(Sync::read).consumerMainThread(Sync::handle).add();
        CHANNEL.messageBuilder(Quake.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Quake::write).decoder(Quake::read).consumerMainThread(Quake::handle).add();
        CHANNEL.messageBuilder(Eruption.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Eruption::write).decoder(Eruption::read).consumerMainThread(Eruption::handle).add();
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

    /**
     * A quake has started in the Basalt Galleries: it lasts {@code duration} ticks at {@code strength} (0 to 1). Sent to
     * each player in or near the Galleries when it starts; the client shakes the view, rumbles and lets dust fall
     * ({@code client.QuakeEffects}). The blocks that fall are the server's own.
     */
    public record Quake(int duration, float strength) {
        public void write(FriendlyByteBuf b) {
            b.writeVarInt(duration);
            b.writeFloat(strength);
        }

        public static Quake read(FriendlyByteBuf b) { return new Quake(b.readVarInt(), b.readFloat()); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.QuakeEffects.accept(this));
        }
    }

    /**
     * A vent of the Caldera erupts at (x, y, z) (the top of its rock or lava): {@code pre} ticks of smoke and rumbling,
     * then a burst of {@code burst} ticks at {@code strength} (0 to 1), its particles drawn from {@code seed}. Sent once,
     * when it starts, to each player within range; the client plays the whole of it ({@code client.EruptionEffects}).
     * The lava bombs are the server's own entities.
     */
    public record Eruption(double x, double y, double z, int pre, int burst, float strength, long seed) {
        public void write(FriendlyByteBuf b) {
            b.writeDouble(x);
            b.writeDouble(y);
            b.writeDouble(z);
            b.writeVarInt(pre);
            b.writeVarInt(burst);
            b.writeFloat(strength);
            b.writeLong(seed);
        }

        public static Eruption read(FriendlyByteBuf b) {
            return new Eruption(b.readDouble(), b.readDouble(), b.readDouble(), b.readVarInt(), b.readVarInt(), b.readFloat(), b.readLong());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.EruptionEffects.accept(this));
        }
    }
}
