package com.explorationremix.combat.foes;

import com.explorationremix.ExplorationRemix;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.Supplier;

/**
 * The fights' channel (0.23.6), {@code explorationremix:foes}, server to client only: a vanilla mob starting a phase of
 * one of its moves (so each game round it poses the mob's body for it, see client.foes), and whether a player's health
 * is coming back (so their hearts ripple, see combat.recovery).
 */
public final class FoeNet {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "foes"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    /** What a client does with the messages; nothing on a server. */
    public interface ClientSide {
        void move(Move m);

        void healing(boolean on);

        ClientSide NONE = new ClientSide() {
            @Override public void move(Move m) {}

            @Override public void healing(boolean on) {}
        };
    }

    public static ClientSide client = ClientSide.NONE;

    private FoeNet() {}

    public static void register() {
        CHANNEL.messageBuilder(Move.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Move::write).decoder(Move::read).consumerMainThread(Move::handle).add();
        CHANNEL.messageBuilder(Healing.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(Healing::write).decoder(Healing::read).consumerMainThread(Healing::handle).add();
    }

    /** To everyone who sees {@code e}: it is in {@code phase} of {@code move} for {@code ticks} from now. */
    public static void move(Entity e, FoeMove move, FoeMove.Phase phase, int ticks) { send(e, move, phase, ticks, -1); }

    /**
     * For pictures (the {@code /remix foes hold} command): every game that sees {@code e} poses it as {@code at} ticks
     * into {@code phase} of {@code move} (of {@code ticks}), and keeps it so until told otherwise.
     */
    static void hold(Entity e, FoeMove move, FoeMove.Phase phase, int ticks, float at) {
        send(e, move, phase, ticks, Math.round(Math.max(0.0F, Math.min(3000.0F, at) * 10.0F)));
    }

    private static void send(Entity e, FoeMove move, FoeMove.Phase phase, int ticks, int heldTenths) {
        if (e.level().isClientSide) return;
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> e), new Move(e.getId(), (byte) move.ordinal(), (byte) phase.ordinal(),
                (short) Math.max(0, Math.min(Short.MAX_VALUE, ticks)), (short) heldTenths));
    }

    /** To {@code p} alone: their health is (or stops) coming back. */
    public static void healing(ServerPlayer p, boolean on) {
        if (p.connection == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> p), new Healing(on));
    }

    /**
     * Mob {@code entity} is in {@code phase} of {@code move} for {@code ticks} from now; or, with {@code held} 0 or more,
     * held still that many tenths of a tick into it (for pictures).
     */
    public record Move(int entity, byte move, byte phase, short ticks, short held) {
        void write(FriendlyByteBuf b) {
            b.writeVarInt(entity);
            b.writeByte(move);
            b.writeByte(phase);
            b.writeShort(ticks);
            b.writeShort(held);
        }

        static Move read(FriendlyByteBuf b) { return new Move(b.readVarInt(), b.readByte(), b.readByte(), b.readShort(), b.readShort()); }

        void handle(Supplier<NetworkEvent.Context> ctx) { client.move(this); }

        public FoeMove foeMove() { return FoeMove.of(move); }

        public FoeMove.Phase foePhase() { return FoeMove.Phase.of(phase); }
    }

    /** The player's health is coming back now ({@code on}), or not. */
    public record Healing(boolean on) {
        void write(FriendlyByteBuf b) { b.writeBoolean(on); }

        static Healing read(FriendlyByteBuf b) { return new Healing(b.readBoolean()); }

        void handle(Supplier<NetworkEvent.Context> ctx) { client.healing(on); }
    }
}
