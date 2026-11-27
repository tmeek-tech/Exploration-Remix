package com.explorationremix.combat.foes;

import com.explorationremix.combat.recovery.Recovery;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The dev tools for vanilla's hostile mobs' fights and a player's recovery (0.23.8, {@code /remix foes}): the reworked
 * mobs round you, what each is doing and who it is after, how many hold a turn at you and how many wait, and how long
 * since you were last hurt. {@code hold <targets> <move> <phase> <ticks> <at>} poses mobs still {@code at} ticks into
 * a phase of a move on every game that sees them, for pictures (give them NoAI first, or their own fight tells the
 * games otherwise); {@code release <targets>} lets them go.
 */
public final class FoeCommands {
    private static final double NEAR = 24.0;

    private FoeCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("foes")
                .executes(FoeCommands::report)
                .then(Commands.literal("hold").then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("move", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(FoeMove.values()).map(FoeCommands::name), b))
                                .then(Commands.argument("phase", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(Arrays.stream(FoeMove.Phase.values()).map(FoeCommands::name), b))
                                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 400))
                                                .then(Commands.argument("at", FloatArgumentType.floatArg(0.0F, 400.0F))
                                                        .executes(FoeCommands::hold)))))))
                .then(Commands.literal("release").then(Commands.argument("targets", EntityArgument.entities())
                        .executes(FoeCommands::release)));
    }

    private static String name(Enum<?> e) { return e.name().toLowerCase(Locale.ROOT); }

    private static int report(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel l = src.getLevel();
        Vec3 at = src.getPosition();
        List<Mob> mobs = l.getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(NEAR), m -> FoeKind.of(m) != null);
        mobs.sort(Comparator.comparingDouble(m -> m.distanceToSqr(at)));
        StringBuilder out = new StringBuilder();
        out.append(mobs.size()).append(" reworked mob").append(mobs.size() == 1 ? "" : "s").append(" within ").append((int) NEAR)
                .append(" blocks").append(mobs.isEmpty() ? "." : ":");
        for (Mob m : mobs.subList(0, Math.min(12, mobs.size()))) {
            FoeKind k = FoeKind.of(m);
            LivingEntity t = m.getTarget();
            out.append("\n  ").append(m.getType().getDescription().getString())
                    .append(String.format(Locale.ROOT, " %.1f blocks off", Math.sqrt(m.distanceToSqr(at))))
                    .append(k != null && Foes.enabled(k) ? "" : " (fights as vanilla's)")
                    .append(": ").append(Foes.doing(m))
                    .append(String.format(Locale.ROOT, ", health %.1f of %.1f", m.getHealth(), m.getMaxHealth()))
                    .append(", after ").append(t == null ? "nothing" : t.getName().getString());
        }
        ServerPlayer p = src.getPlayer();
        if (p != null) {
            int most = Foes.strikers();
            out.append("\nAt you: ").append(FoeTurns.strikers(p)).append(" with a turn (at most ")
                    .append(most <= 0 ? "any number" : String.valueOf(most)).append("), ").append(FoeTurns.waiting(p)).append(" waiting.");
            long since = Recovery.sinceHurt(p);
            out.append("\nRecovery: last hurt ").append(since > 1_000_000L ? "never" : String.format(Locale.ROOT, "%.1f s ago", since / 20.0))
                    .append(Recovery.healing(p) ? ", healing now." : ", not healing.");
        }
        String text = out.toString();
        src.sendSuccess(() -> Component.literal(text), false);
        return mobs.size();
    }

    private static int hold(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(c, "targets");
        String moveName = StringArgumentType.getString(c, "move"), phaseName = StringArgumentType.getString(c, "phase");
        FoeMove move;
        FoeMove.Phase phase;
        try {
            move = FoeMove.valueOf(moveName.toUpperCase(Locale.ROOT));
            phase = FoeMove.Phase.valueOf(phaseName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            c.getSource().sendFailure(Component.literal("Unknown move or phase: " + moveName + " " + phaseName));
            return 0;
        }
        int ticks = IntegerArgumentType.getInteger(c, "ticks");
        float at = FloatArgumentType.getFloat(c, "at");
        int n = 0;
        for (Entity e : targets) {
            if (!(e instanceof LivingEntity)) continue;
            // as the fight would have it: a vindicator's axe out, a skeleton's bow up (a zombie's arms hang, see FoeMeleeGoal)
            if (e instanceof Mob m) m.setAggressive(move != FoeMove.SLAM && move != FoeMove.NONE);
            FoeNet.hold(e, move, phase, ticks, at);
            n++;
        }
        int held = n;
        c.getSource().sendSuccess(() -> Component.literal("Held " + held + " in " + name(move) + " " + name(phase)
                + String.format(Locale.ROOT, ", %.1f of %d ticks in", at, ticks)).withStyle(ChatFormatting.GRAY), false);
        return n;
    }

    private static int release(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        int n = 0;
        for (Entity e : EntityArgument.getEntities(c, "targets")) {
            if (e instanceof Mob m) m.setAggressive(false);
            FoeNet.move(e, FoeMove.NONE, FoeMove.Phase.IDLE, 0);
            n++;
        }
        int let = n;
        c.getSource().sendSuccess(() -> Component.literal("Let " + let + " go").withStyle(ChatFormatting.GRAY), false);
        return n;
    }
}
