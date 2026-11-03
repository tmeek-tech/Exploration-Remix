package com.explorationremix.creature;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /remix fireflies: looking after the blue firefly swarms while testing (operators only).
 * <ul>
 *     <li>{@code /remix fireflies spawn [count]}: a swarm in the open air where you look (count 1 to 24; a random 8
 *     to 24 without one)</li>
 *     <li>{@code /remix fireflies list [radius]}: the swarms round you, nearest first: where each is, flying or landed
 *     (and on what), how many fireflies it has of how many it grows back to</li>
 *     <li>{@code /remix fireflies land}: the nearest swarm settles at once on the best patch of rock within a few
 *     blocks, for its longest stay (40 s, or until you come within three blocks)</li>
 *     <li>{@code /remix fireflies scatter}: the nearest swarm scatters away from you, as if struck</li>
 * </ul>
 */
public final class FireflyCommands {
    private FireflyCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("fireflies")
                .then(Commands.literal("spawn").executes(c -> spawn(c, -1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, FireflySwarm.MAX_COUNT))
                                .executes(c -> spawn(c, IntegerArgumentType.getInteger(c, "count")))))
                .then(Commands.literal("list").executes(c -> list(c, 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("land").executes(FireflyCommands::land))
                .then(Commands.literal("scatter").executes(FireflyCommands::scatter));
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static int spawn(CommandContext<CommandSourceStack> c, int count) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 eye = c.getSource().getPosition();
        Vec3 look = Vec3.directionFromRotation(c.getSource().getRotation());
        // from your eyes, unless the command was moved elsewhere ("execute positioned ...")
        if (c.getSource().getEntity() != null && c.getSource().getEntity().position().distanceToSqr(eye) < 1.0E-6)
            eye = c.getSource().getEntity().getEyePosition();
        // a few blocks short of the rock you look at (out of shy range), or six blocks ahead into open air
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(24)), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(6)) : hit.getLocation().subtract(look.scale(Math.min(2.0, hit.getLocation().distanceTo(eye) * 0.5)));
        int n = count > 0 ? count : FireflySwarm.MIN_COUNT + level.random.nextInt(FireflySwarm.MAX_COUNT - FireflySwarm.MIN_COUNT + 1);
        FireflySwarm s = FireflySwarm.spawn(level, at, n);
        if (s == null) { c.getSource().sendFailure(Component.literal("No open air for a swarm near " + BlockPos.containing(at).toShortString())); return 0; }
        say(c, "Blue fireflies (" + s.count() + ") at " + BlockPos.containing(s.centre()).toShortString());
        return 1;
    }

    private static List<FireflySwarm> near(CommandContext<CommandSourceStack> c, double radius) {
        Vec3 p = c.getSource().getPosition();
        List<FireflySwarm> all = c.getSource().getLevel().getEntitiesOfClass(FireflySwarm.class, new AABB(p, p).inflate(radius));
        all.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return all;
    }

    @Nullable
    private static FireflySwarm nearest(CommandContext<CommandSourceStack> c) {
        List<FireflySwarm> all = near(c, 64);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No firefly swarm within 64 blocks.")); return null; }
        return all.get(0);
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        List<FireflySwarm> all = near(c, radius);
        if (all.isEmpty()) { say(c, "No firefly swarm within " + radius + " blocks."); return 0; }
        Vec3 p = c.getSource().getPosition();
        for (FireflySwarm s : all)
            say(c, String.format(Locale.ROOT, "%s, %.0f blocks: %s", BlockPos.containing(s.centre()).toShortString(), Math.sqrt(s.distanceToSqr(p)), s.describe()));
        return all.size();
    }

    private static int land(CommandContext<CommandSourceStack> c) {
        FireflySwarm s = nearest(c);
        if (s == null) return 0;
        if (!s.landNow()) { c.getSource().sendFailure(Component.literal("No rock to land on near the swarm at " + BlockPos.containing(s.centre()).toShortString())); return 0; }
        say(c, "Landed: " + s.describe());
        return 1;
    }

    private static int scatter(CommandContext<CommandSourceStack> c) {
        FireflySwarm s = nearest(c);
        if (s == null) return 0;
        Vec3 from = c.getSource().getEntity() != null ? c.getSource().getEntity().getEyePosition() : c.getSource().getPosition();
        s.scatter(from);
        say(c, "Scattered: " + s.describe());
        return 1;
    }
}
