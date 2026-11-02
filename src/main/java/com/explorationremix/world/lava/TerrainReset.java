package com.explorationremix.world.lava;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;

/** The Basalt Galleries' terrain reset on the server (0.5.1; filled in by the reset build). */
public final class TerrainReset {
    private TerrainReset() {}

    /** Every overworld tick, after the flood ({@code LavaFlood.tick}). */
    public static void tick(ServerLevel level, LavaFlood.Band band, LavaCycle.State state, long now) {}

    /** {@code /remix reset}. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() { return Commands.literal("reset").executes(c -> 0); }

    /** When the server stops. */
    public static void reset() {}
}
