package com.explorationremix.world.quake;

import com.explorationremix.world.lava.LavaCycle;
import com.explorationremix.world.lava.LavaFlood;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

/** The Basalt Galleries' quakes on the server (0.5.1; filled in by the quake build). */
public final class Quakes {
    private Quakes() {}

    /** Every overworld tick, after the flood ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {}

    /** {@code /remix quake}. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() { return Commands.literal("quake").executes(c -> 0); }

    /** When the server stops. */
    public static void reset() {}
}
