package com.explorationremix.combat.swing;

import com.explorationremix.ExplorationRemix;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The timed swings' messages. A player's own game times their swings and says when one starts (so the players round
 * them see it wind up), when it lands and on what, and what a strong attack's lunge runs into; the server checks each
 * landing ({@link SwingRules}) and deals the hits ({@link Swordplay}), and tells the players round the swinger what to
 * draw.
 */
public final class SwingNet {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "swings"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    /** The most targets one message names. */
    static final int MAX_TARGETS = 6;

    /** What a client does with another player's swing; nothing on a server. */
    public interface ClientSide {
        void show(Show s);

        ClientSide NONE = s -> {};
    }

    public static ClientSide client = ClientSide.NONE;

    private SwingNet() {}

    public static void register() {
        CHANNEL.messageBuilder(Start.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Start::write).decoder(Start::read).consumerMainThread(Start::handle).add();
        CHANNEL.messageBuilder(Land.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(Land::write).decoder(Land::read).consumerMainThread(Land::handle).add();
        CHANNEL.messageBuilder(Show.class, 2, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Show::write).decoder(Show::read).consumerMainThread(Show::handle).add();
    }

    /** What happened to a swing: it started (or changed kind), became strong, was dropped. */
    public static final byte STARTED = 0, UPGRADED = 1, CANCELLED = 2, LANDED = 3;

    /** The swinger's game: swing {@code seq} {@code what} (a kind and a place in the chain with STARTED). */
    public record Start(int seq, byte what, byte kind, byte combo) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(seq);
            b.writeByte(what);
            b.writeByte(kind);
            b.writeByte(combo);
        }

        static Start read(FriendlyByteBuf b) { return new Start(b.readVarInt(), b.readByte(), b.readByte(), b.readByte()); }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) Swordplay.started(p, seq, what, SwingKind.of(kind), combo);
        }
    }

    /**
     * Swing {@code seq} landed ({@code lunge} false), or its lunge ran into more ({@code lunge} true): the entities it
     * hit, by id, the one aimed at first.
     */
    public record Land(int seq, byte kind, byte combo, boolean lunge, int[] targets) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(seq);
            b.writeByte(kind);
            b.writeByte(combo);
            b.writeBoolean(lunge);
            b.writeVarInt(targets.length);
            for (int id : targets) b.writeVarInt(id);
        }

        static Land read(FriendlyByteBuf b) {
            int seq = b.readVarInt();
            byte kind = b.readByte(), combo = b.readByte();
            boolean lunge = b.readBoolean();
            int n = b.readVarInt();
            if (n < 0 || n > MAX_TARGETS) throw new IllegalArgumentException("a swing names " + n + " targets");
            int[] t = new int[n];
            for (int i = 0; i < n; i++) t[i] = b.readVarInt();
            return new Land(seq, kind, combo, lunge, t);
        }

        void handle(Supplier<NetworkEvent.Context> ctx) {
            ServerPlayer p = ctx.get().getSender();
            if (p != null) Swordplay.landed(p, seq, SwingKind.of(kind), combo, lunge, targets);
        }
    }

    /** To the players round {@code player}: their swing {@code seq} {@code what}. */
    public record Show(int player, int seq, byte what, byte kind, byte combo) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(player);
            b.writeVarInt(seq);
            b.writeByte(what);
            b.writeByte(kind);
            b.writeByte(combo);
        }

        static Show read(FriendlyByteBuf b) { return new Show(b.readVarInt(), b.readVarInt(), b.readByte(), b.readByte(), b.readByte()); }

        void handle(Supplier<NetworkEvent.Context> ctx) { client.show(this); }

        public SwingKind swingKind() { return SwingKind.of(kind); }
    }
}
