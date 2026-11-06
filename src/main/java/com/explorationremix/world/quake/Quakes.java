package com.explorationremix.world.quake;

import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaFlood;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * The Basalt Galleries' quakes on the server: not built yet. The hooks are in place (the tick after the flood, the
 * command, the config's {@code quakes} section, which is kept), but this build has no quakes: the tick does nothing,
 * {@code /remix quake} says so, and the config's switch and numbers do nothing.
 */
public final class Quakes {
    private Quakes() {}

    /** What {@code /remix quake} answers. */
    static final String NOT_BUILT = "Quakes are not built yet: there are none in this build, and the quakes section of "
            + "explorationremix-world.toml does nothing.";

    /** Every overworld tick, after the flood ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {}

    /** {@code /remix quake}: says quakes are not built yet. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("quake").executes(c -> {
            c.getSource().sendFailure(Component.literal(NOT_BUILT));
            return 0;
        });
    }

    /** When the server stops. */
    public static void reset() {}
}
