package com.explorationremix.world.lava;

import com.explorationremix.config.ERConfig;
import com.explorationremix.world.DeepWorld;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /remix lava}: the Basalt Galleries' lava cycle, for operators testing a world.
 * <ul>
 *     <li>{@code /remix lava}: the phase, the time left in it, the lava's height, how many chunks round the players in
 *     or near the Galleries are still catching up, and what the flood has cost per tick</li>
 *     <li>{@code /remix lava set <calm|warning|rise|full|drain> [seconds]}: jumps the clock to that far into a phase
 *     (it stays held if it was frozen)</li>
 *     <li>{@code /remix lava level <0-40>}: jumps the lava to that height over its table and holds the clock there</li>
 *     <li>{@code /remix lava freeze}, {@code unfreeze}: holds the clock, or lets it run on</li>
 *     <li>{@code /remix lava length <minutes>}: the cycle's length from now on, keeping the place in the phase (0: back
 *     to the config's)</li>
 * </ul>
 * Replies that refuse start with "No " or "There ", which the picture taker (DevShots) skips.
 */
public final class LavaCommands {
    private LavaCommands() {}

    /** The command, which RemixCommands adds under /remix. */
    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("lava").executes(LavaCommands::status)
                .then(Commands.literal("set").then(Commands.argument("phase", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(LavaCycle.Phase.values()).map(LavaCycle.Phase::id), b))
                        .executes(c -> set(c, StringArgumentType.getString(c, "phase"), 0))
                        .then(Commands.argument("seconds", DoubleArgumentType.doubleArg(0, 24 * 3600))
                                .executes(c -> set(c, StringArgumentType.getString(c, "phase"), DoubleArgumentType.getDouble(c, "seconds"))))))
                .then(Commands.literal("level").then(Commands.argument("height", IntegerArgumentType.integer(0, LavaFlood.RISE))
                        .executes(c -> level(c, IntegerArgumentType.getInteger(c, "height")))))
                .then(Commands.literal("freeze").executes(c -> freeze(c, true)))
                .then(Commands.literal("unfreeze").executes(c -> freeze(c, false)))
                .then(Commands.literal("length").then(Commands.argument("minutes", DoubleArgumentType.doubleArg(0, 240))
                        .executes(c -> length(c, DoubleArgumentType.getDouble(c, "minutes")))));
    }

    // ================================================================================================ the commands

    private static int status(CommandContext<CommandSourceStack> c) {
        ServerLevel level = overworld(c);
        LavaClock clock = level == null ? null : clock(c, level);
        if (clock == null) return 0;
        LavaFlood.Band b = LavaFlood.band();
        LavaCycle cycle = clock.cycle(b.rise());
        LavaCycle.State s = cycle.at(clock.tick(level.getGameTime()));
        boolean on = ERConfig.INSTANCE.lavaCycleEnabled.get();
        int h = on ? s.level() : 0;
        say(c, String.format(Locale.ROOT, "Lava cycle%s: %s, %s left of %s; the lava %d of %d blocks over its table (top block y %d, table y %d)%s",
                on ? "" : " (turned off in explorationremix-world.toml: the lava stays at its table)", s.phase().id(),
                LavaFlood.minutes(s.left()), LavaFlood.minutes(s.phaseLength()), h, b.rise(), b.tableY() + h - 1, b.tableY() - 1,
                clock.frozen() ? ", clock held" : ""));
        say(c, String.format(Locale.ROOT, "  cycle %s (%s)%s, %d cycles ended", LavaFlood.minutes(cycle.length()), LavaFlood.describe(cycle),
                clock.lengthOverride() > 0 ? ", length set by command" : "", clock.cycles()));
        LavaFlood.Stats st = LavaFlood.stats();
        say(c, String.format(Locale.ROOT, "  chunks round players in or near the Galleries: %d, %d still catching up", LavaFlood.nearChunks(), LavaFlood.pending()));
        if (st.ticks() > 0) {
            say(c, String.format(Locale.ROOT, "  over the last %d ticks with work: %.2f ms per tick (at most %.2f), %.0f blocks changed and %.0f looked at per tick",
                    st.ticks(), st.avgMs(), st.maxMs(), st.avgChanges(), st.avgChecks()));
        }
        Vec3 p = c.getSource().getPosition();
        int here = LavaFlood.chunkLevel(level, (int) Math.floor(p.x), (int) Math.floor(p.z));
        if (here >= 0) say(c, String.format(Locale.ROOT, "  this chunk's lava: %d over the table", here));
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> c, String name, double seconds) {
        LavaCycle.Phase p = LavaCycle.Phase.byId(name.toLowerCase(Locale.ROOT));
        if (p == null) {
            c.getSource().sendFailure(Component.literal("There is no phase " + name + ": calm, warning, rise, full or drain."));
            return 0;
        }
        ServerLevel level = overworld(c);
        LavaClock clock = level == null ? null : running(c, level);
        if (clock == null) return 0;
        LavaCycle cycle = clock.cycle(LavaFlood.band().rise());
        int len = cycle.length(p);
        int into = (int) Math.round(seconds * 20);
        if (len == 0 || into >= len) {
            c.getSource().sendFailure(Component.literal(len == 0 ? "There is no " + p.id() + " in this cycle (its share is 0)."
                    : "There are only " + LavaFlood.minutes(len) + " in the " + p.id() + "."));
            return 0;
        }
        long now = level.getGameTime();
        clock.set(now, cycle.tickOf(p, into));
        LavaFlood.clockChanged(level);
        return report(c, level, clock, "Lava cycle set");
    }

    private static int level(CommandContext<CommandSourceStack> c, int h) {
        ServerLevel level = overworld(c);
        LavaClock clock = level == null ? null : running(c, level);
        if (clock == null) return 0;
        long now = level.getGameTime();
        LavaCycle cycle = clock.cycle(LavaFlood.band().rise());
        int t = cycle.tickForLevel(h);
        if (cycle.level(t) != Math.min(h, cycle.rise())) {
            c.getSource().sendFailure(Component.literal("There is no moment in this cycle with the lava " + h
                    + " blocks over its table (its rise and drain are empty or too short); the nearest under it is " + cycle.level(t) + "."));
            return 0;
        }
        clock.freeze(now);
        clock.set(now, t);
        LavaFlood.clockChanged(level);
        return report(c, level, clock, "Lava held");
    }

    private static int freeze(CommandContext<CommandSourceStack> c, boolean hold) {
        ServerLevel level = overworld(c);
        LavaClock clock = level == null ? null : running(c, level);
        if (clock == null) return 0;
        long now = level.getGameTime();
        if (hold) clock.freeze(now);
        else clock.unfreeze(now);
        LavaFlood.clockChanged(level);
        return report(c, level, clock, hold ? "Lava cycle held" : "Lava cycle running");
    }

    private static int length(CommandContext<CommandSourceStack> c, double minutes) {
        ServerLevel level = overworld(c);
        LavaClock clock = level == null ? null : running(c, level);
        if (clock == null) return 0;
        if (minutes > 0 && minutes < 1) {
            c.getSource().sendFailure(Component.literal("There must be at least 1 minute in a cycle."));
            return 0;
        }
        int override = (int) Math.round(minutes * LavaCycle.MINUTE);
        clock.rescale(level.getGameTime(), LavaFlood.lengthsFor(override), override);
        LavaFlood.clockChanged(level);
        return report(c, level, clock, override > 0 ? "Lava cycle length set" : "Lava cycle length back to the config's");
    }

    // ================================================================================================ helpers

    private static int report(CommandContext<CommandSourceStack> c, ServerLevel level, LavaClock clock, String what) {
        LavaFlood.Band b = LavaFlood.band();
        LavaCycle cycle = clock.cycle(b.rise());
        LavaCycle.State s = cycle.at(clock.tick(level.getGameTime()));
        say(c, String.format(Locale.ROOT, "%s: %s, %s into it (%s left), the lava %d of %d blocks over its table%s; cycle %s (%s)", what,
                s.phase().id(), LavaFlood.minutes(s.into()), LavaFlood.minutes(s.left()), s.level(), b.rise(),
                clock.frozen() ? ", clock held" : "", LavaFlood.minutes(cycle.length()), LavaFlood.describe(cycle)));
        return 1;
    }

    private static ServerLevel overworld(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getServer().getLevel(Level.OVERWORLD);
        if (level == null || !DeepWorld.installed()) {
            c.getSource().sendFailure(Component.literal("No deep world in this world, so no lava cycle (see the server log)."));
            return null;
        }
        return level;
    }

    private static LavaClock clock(CommandContext<CommandSourceStack> c, ServerLevel level) {
        LavaClock clock = LavaFlood.clock(level);
        if (clock == null || LavaFlood.band() == null) {
            c.getSource().sendFailure(Component.literal("No lava cycle in this world: it has no Basalt Galleries with a lava table (see the server log)."));
            return null;
        }
        return clock;
    }

    /** The clock, for commands that move it: refused while the cycle is turned off in the config. */
    private static LavaClock running(CommandContext<CommandSourceStack> c, ServerLevel level) {
        LavaClock clock = clock(c, level);
        if (clock != null && !ERConfig.INSTANCE.lavaCycleEnabled.get()) {
            c.getSource().sendFailure(Component.literal("No lava cycle: it is turned off in explorationremix-world.toml (lavaCycle.enabled)."));
            return null;
        }
        return clock;
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }
}
