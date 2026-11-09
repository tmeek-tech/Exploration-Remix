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
 * server, so the heights come along too), and when a quake or an eruption starts, holds and ends and when ceiling rock
 * is about to fall, so the client plays it out (0.9.0). Every message goes to the client only, is handled on its main
 * thread, and reaches client code only through {@code DistExecutor}, so a dedicated server never loads a client class.
 *
 * <p>Version 3 (0.9.0): the quake and eruption messages gained their ids, timing, holds and the rest, and the ceiling
 * debris message is new. A client of another version is refused cleanly when it joins.
 */
public final class LavaNet {
    /** The channel's protocol version: any change to a message's fields needs a new one. */
    static final String VERSION = "3";
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
        CHANNEL.messageBuilder(Debris.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Debris::write).decoder(Debris::read).consumerMainThread(Debris::handle).add();
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
     * A quake in the Basalt Galleries, to a player in or near them ({@code world.quake.Quakes}): {@code kind}
     * {@link #TREMOR}, {@link #BIG} or {@link #CLIMB} (the ground answering a climb) starts or goes on, {@link #END}
     * stops quake {@code id} on the client at once. It started at game time {@code start} and lasts {@code duration}
     * ticks at {@code strength} (0 to 1); the client's shake is drawn from {@code seed}. {@code held} is how many ticks
     * into it the quake is held (the client plays that moment steadily), or -1 while it runs; {@code felt} is this
     * player's own: false for a tremor that toppled nothing near them (played faintly now and then, or not at all, by
     * the client's {@code tremorEffects}). Sent when it starts, to players who come near while it runs, and again on a
     * hold and a release. The blocks that fall are the server's own. About 26 bytes.
     */
    public record Quake(byte kind, int id, long start, int duration, float strength, int seed, int held, boolean felt) {
        public static final byte TREMOR = 0, BIG = 1, CLIMB = 2, END = 3;

        /** Stops quake {@code id} on the client. */
        public static Quake end(int id) { return new Quake(END, id, 0, 0, 0, 0, -1, false); }

        public void write(FriendlyByteBuf b) {
            b.writeByte(kind);
            b.writeVarInt(id);
            b.writeLong(start);
            b.writeVarInt(duration);
            b.writeFloat(strength);
            b.writeInt(seed);
            b.writeVarInt(held + 1);
            b.writeBoolean(felt);
        }

        public static Quake read(FriendlyByteBuf b) {
            return new Quake(b.readByte(), b.readVarInt(), b.readLong(), b.readVarInt(), b.readFloat(), b.readInt(), b.readVarInt() - 1, b.readBoolean());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.QuakeEffects.accept(this));
        }
    }

    /**
     * An eruption of one of the Caldera's vents ({@code world.eruption.Eruptions}): {@code kind} {@link #START} or
     * {@link #END} (stops eruption {@code id} on the client). The vent stands at (x, z), its crater floor at
     * {@code floorY}, its mouth (the top of its rock or of the lava over it) at {@code mouthY}, the crater's radius
     * {@code r}. From game time {@code start}: {@code pre} ticks of smoke and rumbling, then a burst of {@code burst} ticks
     * at {@code strength} (0 to 1), its fountain and plume {@code scale} times their size (larger as the lava rises), its
     * particles drawn from {@code seed}. {@code held} is ticks into it while held, or -1; {@code range} is the server's
     * {@code heardRange}, how far the boom, the roar and the far particles reach. The lava bombs are the server's own
     * entities. About 75 bytes.
     */
    public record Eruption(byte kind, int id, double x, double z, int floorY, int mouthY, float r, long start, int pre, int burst,
                           float strength, float scale, long seed, int held, int range) {
        public static final byte START = 0, END = 1;

        /** Stops eruption {@code id} on the client. */
        public static Eruption end(int id) { return new Eruption(END, id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, -1, 0); }

        public void write(FriendlyByteBuf b) {
            b.writeByte(kind);
            b.writeVarInt(id);
            b.writeDouble(x);
            b.writeDouble(z);
            b.writeInt(floorY);
            b.writeInt(mouthY);
            b.writeFloat(r);
            b.writeLong(start);
            b.writeVarInt(pre);
            b.writeVarInt(burst);
            b.writeFloat(strength);
            b.writeFloat(scale);
            b.writeLong(seed);
            b.writeVarInt(held + 1);
            b.writeVarInt(range);
        }

        public static Eruption read(FriendlyByteBuf b) {
            return new Eruption(b.readByte(), b.readVarInt(), b.readDouble(), b.readDouble(), b.readInt(), b.readInt(), b.readFloat(), b.readLong(),
                    b.readVarInt(), b.readVarInt(), b.readFloat(), b.readFloat(), b.readLong(), b.readVarInt() - 1, b.readVarInt());
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.EruptionEffects.accept(this));
        }
    }

    /**
     * A block of rock coming down near a player ({@code world.quake}): {@code kind} {@link #WARN} (grit and a crack
     * overhead: the block at {@code pos}, of block state id {@code state}, drops at game time {@code dropAt} and lands on
     * row {@code landY}, passing the lava's top at {@code lavaY}, or {@code Integer.MIN_VALUE} for no lava),
     * {@link #CANCEL} (that block stays after all), {@link #SHATTER} (it broke on someone at {@code pos}), or
     * {@link #CREAK} (a build a player stands on is about to go: the block under their feet at {@code pos}). {@code held}
     * is -1, or how many blocks the block has fallen while a command holds it. Sent to the players within reach of
     * {@code pos} (WARN and CANCEL 160 blocks, SHATTER 64, CREAK 48). About 31 bytes.
     */
    public record Debris(byte kind, long pos, int state, long dropAt, int landY, int lavaY, int held) {
        public static final byte WARN = 0, CANCEL = 1, SHATTER = 2, CREAK = 3;
        /** How far each kind is sent, in blocks. */
        public static final int WARN_RANGE = 160, SHATTER_RANGE = 64, CREAK_RANGE = 48;

        public void write(FriendlyByteBuf b) {
            b.writeByte(kind);
            b.writeLong(pos);
            b.writeVarInt(state);
            b.writeLong(dropAt);
            b.writeInt(landY);
            b.writeInt(lavaY);
            b.writeVarInt(held + 1);
        }

        public static Debris read(FriendlyByteBuf b) {
            return new Debris(b.readByte(), b.readLong(), b.readVarInt(), b.readLong(), b.readInt(), b.readInt(), b.readVarInt() - 1);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> com.explorationremix.client.QuakeEffects.debris(this));
        }
    }
}
