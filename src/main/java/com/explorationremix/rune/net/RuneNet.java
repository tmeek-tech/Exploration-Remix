package com.explorationremix.rune.net;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.armor.ArmorEffects;
import com.explorationremix.rune.armor.Sensed;
import com.explorationremix.rune.cast.CastService;
import com.explorationremix.rune.cast.Grapples;
import com.explorationremix.rune.cast.LocalPull;
import com.explorationremix.rune.cast.Pull;
import com.explorationremix.rune.effect.Fx;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.menu.RuneTableMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The runes' channel, {@code explorationremix:runes}. Small on purpose: a weapon carries its own runes and energy
 * (sent as item data, like any item change), the rune table is a vanilla menu (its changes are menu buttons), and
 * bolts, fields and motes are entities. Only these need words of their own: the run depth (server to client), the
 * cast key going down and up (client to server), Reel's pull on a player (the server starts it and may stop it, the
 * player's own game steers it and says when it is done), effects: one small message per flash, blast or slam, which
 * each nearby client turns into its own particles, rings and shake; and since 0.22.6 what worn runes did in the
 * player's own game (a leap, a pull onto a ledge, a creature peered at), the creatures Sense feels hunting them, and a
 * rune set into a socket at the table (a menu button's one byte can't say which piece and which rune).
 */
public final class RuneNet {
    private static final String VERSION = "6";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "runes"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private RuneNet() {}

    /** Called once from the mod's constructor. */
    public static void register() {
        CHANNEL.messageBuilder(DepthSync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DepthSync::write).decoder(DepthSync::read).consumerMainThread(DepthSync::handle).add();
        CHANNEL.messageBuilder(PullStart.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PullStart::write).decoder(PullStart::read).consumerMainThread(PullStart::handle).add();
        CHANNEL.messageBuilder(PullStop.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PullStop::write).decoder(PullStop::read).consumerMainThread(PullStop::handle).add();
        CHANNEL.messageBuilder(PullDone.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PullDone::write).decoder(PullDone::read).consumerMainThread(PullDone::handle).add();
        CHANNEL.messageBuilder(FxMsg.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(FxMsg::write).decoder(FxMsg::read).consumerMainThread(FxMsg::handle).add();
        CHANNEL.messageBuilder(CastKey.class, 6, NetworkDirection.PLAY_TO_SERVER)
                .encoder(CastKey::write).decoder(CastKey::read).consumerMainThread(CastKey::handle).add();
        CHANNEL.messageBuilder(ArmorAct.class, 7, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ArmorAct::write).decoder(ArmorAct::read).consumerMainThread(ArmorAct::handle).add();
        CHANNEL.messageBuilder(SenseSync.class, 8, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SenseSync::write).decoder(SenseSync::read).consumerMainThread(SenseSync::handle).add();
        CHANNEL.messageBuilder(TableEdit.class, 9, NetworkDirection.PLAY_TO_SERVER)
                .encoder(TableEdit::write).decoder(TableEdit::read).consumerMainThread(TableEdit::handle).add();
    }

    /**
     * What worn runes did in the player's own game, which moves them: a jump in the air ({@link #LEAP}), a pull up onto
     * a ledge ({@link #CLIMB}), or a creature peered at through Far Sight ({@link #MARK}, {@code arg} its id). The
     * server checks the runes and does its part (a fall forgiven, a creature marked).
     */
    public record ArmorAct(int what, int arg) {
        public static final int LEAP = 0, CLIMB = 1, MARK = 2;

        void write(FriendlyByteBuf b) {
            b.writeByte(what);
            b.writeVarInt(arg);
        }

        static ArmorAct read(FriendlyByteBuf b) { return new ArmorAct(b.readByte(), b.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p == null || !p.isAlive() || p.isSpectator()) return;
            switch (what) {
                case LEAP -> ArmorEffects.leapt(p);
                case CLIMB -> ArmorEffects.climbed(p);
                case MARK -> ArmorEffects.marked(p, arg);
                default -> {}
            }
        }
    }

    /** The creatures Sense feels hunting this player now, by entity id (sorted; none: no more). */
    public record SenseSync(int[] ids) {
        /** The most it tells of. */
        static final int MOST = 64;

        void write(FriendlyByteBuf b) { b.writeVarIntArray(ids); }

        static SenseSync read(FriendlyByteBuf b) { return new SenseSync(b.readVarIntArray(MOST)); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            int[] sorted = ids.clone();
            java.util.Arrays.sort(sorted);
            Sensed.set(sorted);
        }
    }

    /**
     * At the enchanting table: set rune {@code rune} (an ordinal of RuneId; -1 takes out what is there) into socket
     * {@code socket} (an ordinal of Socket) of {@code gear}: 0 the weapon in hand, 1 to 4 the worn helmet, chestplate,
     * leggings or boots. For the open table {@code container}; the server decides.
     */
    public record TableEdit(int container, int gear, int socket, int rune) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(container);
            b.writeByte(gear);
            b.writeByte(socket);
            b.writeVarInt(rune);
        }

        static TableEdit read(FriendlyByteBuf b) { return new TableEdit(b.readVarInt(), b.readByte(), b.readByte(), b.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null && p.containerMenu instanceof RuneTableMenu m && m.containerId == container) m.edit(p, gear, socket, rune);
        }
    }

    /** An effect to draw ({@link Fx}); null when it was of a kind this game doesn't know. */
    public record FxMsg(@javax.annotation.Nullable Fx fx) {
        void write(FriendlyByteBuf b) { fx.write(b); }

        static FxMsg read(FriendlyByteBuf b) { return new FxMsg(Fx.read(b)); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            if (fx != null && fx.sane()) RuneFx.client.play(fx);
        }
    }

    /** The player's run depth (0 to 2): kept, though the sockets don't wait on it for now. */
    public record DepthSync(int depth) {
        void write(FriendlyByteBuf b) { b.writeVarInt(depth); }

        static DepthSync read(FriendlyByteBuf b) { return new DepthSync(b.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> ctx) { RunDepth.acceptClient(depth); }
    }

    /**
     * The cast key went down (the runed sword in hand casts now, and again each time it is ready) or came up (no more).
     * The server decides every cast, as ever.
     */
    public record CastKey(boolean down) {
        void write(FriendlyByteBuf b) { b.writeBoolean(down); }

        static CastKey read(FriendlyByteBuf b) { return new CastKey(b.readBoolean()); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) CastService.castKey(p, down);
        }
    }

    /** Reel pulls this player along {@code way} (where their feet go, x, y, z each), to stand or hang at its end. */
    public record PullStart(int id, boolean stand, double speed, int maxTicks, double[][] way) {
        /** The most points a way has. */
        static final int MAX_POINTS = 4;

        void write(FriendlyByteBuf b) {
            b.writeVarInt(id);
            b.writeBoolean(stand);
            b.writeDouble(speed);
            b.writeVarInt(maxTicks);
            b.writeVarInt(way.length);
            for (double[] w : way) {
                b.writeDouble(w[0]);
                b.writeDouble(w[1]);
                b.writeDouble(w[2]);
            }
        }

        static PullStart read(FriendlyByteBuf b) {
            int id = b.readVarInt();
            boolean stand = b.readBoolean();
            double speed = b.readDouble();
            int maxTicks = b.readVarInt();
            int n = b.readVarInt();
            if (n < 1 || n > MAX_POINTS) throw new IllegalArgumentException("a pull's way has " + n + " points");
            double[][] way = new double[n][3];
            for (double[] w : way) {
                w[0] = b.readDouble();
                w[1] = b.readDouble();
                w[2] = b.readDouble();
            }
            return new PullStart(id, stand, speed, maxTicks, way);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            if (!(speed > 0.0 && speed <= 4.0) || maxTicks < 0 || maxTicks > 400) return;
            for (double[] w : way) if (!Double.isFinite(w[0]) || !Double.isFinite(w[1]) || !Double.isFinite(w[2])) return;
            LocalPull.start(new Pull.Steer(id, way, stand, speed, maxTicks));
        }
    }

    /** The server let go of pull {@code id} (sneak, a hit that throws, taking too long): stop steering it. */
    public record PullStop(int id) {
        void write(FriendlyByteBuf b) { b.writeVarInt(id); }

        static PullStop read(FriendlyByteBuf b) { return new PullStop(b.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> ctx) { LocalPull.stop(id); }
    }

    /** The player's game is done steering pull {@code id} (they got there, hung their time, or something was in the way). */
    public record PullDone(int id) {
        void write(FriendlyByteBuf b) { b.writeVarInt(id); }

        static PullDone read(FriendlyByteBuf b) { return new PullDone(b.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) Grapples.done(p, id);
        }
    }
}
