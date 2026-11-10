package com.explorationremix.rune.net;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.cast.Grapples;
import com.explorationremix.rune.cast.LocalPull;
import com.explorationremix.rune.cast.Pull;
import com.explorationremix.rune.effect.Fx;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.menu.RuneEditorMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The runes' channel, {@code explorationremix:runes}. Small on purpose: the gear carries its own row and energy (sent
 * as item data, like any item change), the rune screen is a vanilla menu, and bolts, fields and motes are entities.
 * Only these need words of their own: the run depth (server to client), "open the rune screen" (client to server,
 * which the server is free to refuse), Reel's pull on a player (the server starts it and may stop it, the player's own
 * game steers it and says when it is done), and effects: one small message per flash, blast or slam, which each nearby
 * client turns into its own particles, rings and shake.
 */
public final class RuneNet {
    private static final String VERSION = "3";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "runes"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private RuneNet() {}

    /** Called once from the mod's constructor. */
    public static void register() {
        CHANNEL.messageBuilder(DepthSync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DepthSync::write).decoder(DepthSync::read).consumerMainThread(DepthSync::handle).add();
        CHANNEL.messageBuilder(OpenEditor.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(OpenEditor::write).decoder(OpenEditor::read).consumerMainThread(OpenEditor::handle).add();
        CHANNEL.messageBuilder(PullStart.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PullStart::write).decoder(PullStart::read).consumerMainThread(PullStart::handle).add();
        CHANNEL.messageBuilder(PullStop.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PullStop::write).decoder(PullStop::read).consumerMainThread(PullStop::handle).add();
        CHANNEL.messageBuilder(PullDone.class, 4, NetworkDirection.PLAY_TO_SERVER)
                .encoder(PullDone::write).decoder(PullDone::read).consumerMainThread(PullDone::handle).add();
        CHANNEL.messageBuilder(FxMsg.class, 5, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(FxMsg::write).decoder(FxMsg::read).consumerMainThread(FxMsg::handle).add();
    }

    /** An effect to draw ({@link Fx}); null when it was of a kind this game doesn't know. */
    public record FxMsg(@javax.annotation.Nullable Fx fx) {
        void write(FriendlyByteBuf b) { fx.write(b); }

        static FxMsg read(FriendlyByteBuf b) { return new FxMsg(Fx.read(b)); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            if (fx != null && fx.sane()) RuneFx.client.play(fx);
        }
    }

    /** The player's run depth (0 to 2), which says how many slots their gear has awake. */
    public record DepthSync(int depth) {
        void write(FriendlyByteBuf b) { b.writeVarInt(depth); }

        static DepthSync read(FriendlyByteBuf b) { return new DepthSync(b.readVarInt()); }

        void handle(Supplier<NetworkEvent.Context> ctx) { RunDepth.acceptClient(depth); }
    }

    /** The rune key was pressed: open the rune screen if the player may (out of combat). */
    public record OpenEditor() {
        void write(FriendlyByteBuf b) {}

        static OpenEditor read(FriendlyByteBuf b) { return new OpenEditor(); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) RuneEditorMenu.open(p);
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
