package com.explorationremix.world.eruption;

import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaFlood;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/**
 * The Caldera's eruptions on the server: not built yet. The hooks are in place (the tick after the flood, the command,
 * the config's {@code eruptions} section, which is kept), but this build has no eruptions: the tick does nothing,
 * {@code /remix erupt} says so, and the config's switch and numbers do nothing.
 */
public final class Eruptions {
    private Eruptions() {}

    /** What {@code /remix erupt} answers. */
    static final String NOT_BUILT = "Eruptions are not built yet: there are none in this build, and the eruptions section of "
            + "explorationremix-world.toml does nothing.";

    /** Every overworld tick, after the flood ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {}

    /** {@code /remix erupt}: says eruptions are not built yet. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("erupt").executes(c -> {
            c.getSource().sendFailure(Component.literal(NOT_BUILT));
            return 0;
        });
    }

    /** When the server stops. */
    public static void reset() {}
}
