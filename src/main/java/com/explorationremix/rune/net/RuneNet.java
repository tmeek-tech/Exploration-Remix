package com.explorationremix.rune.net;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.rune.RunDepth;
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
 * as item data, like any item change), the rune screen is a vanilla menu, and effects are vanilla particles and
 * entities. Only two things need words of their own: the run depth (server to client), and "open the rune screen"
 * (client to server, which the server is free to refuse).
 */
public final class RuneNet {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(ExplorationRemix.MOD_ID, "runes"),
            () -> VERSION, VERSION::equals, VERSION::equals);

    private RuneNet() {}

    /** Called once from the mod's constructor. */
    public static void register() {
        CHANNEL.messageBuilder(DepthSync.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DepthSync::write).decoder(DepthSync::read).consumerMainThread(DepthSync::handle).add();
        CHANNEL.messageBuilder(OpenEditor.class, 1, NetworkDirection.PLAY_TO_SERVER)
                .encoder(OpenEditor::write).decoder(OpenEditor::read).consumerMainThread(OpenEditor::handle).add();
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
}
