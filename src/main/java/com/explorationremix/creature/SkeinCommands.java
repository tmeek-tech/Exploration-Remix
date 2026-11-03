package com.explorationremix.creature;

import com.explorationremix.registry.EREntities;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /remix skein: looking after the Skeins while testing (operators only).
 * <ul>
 *     <li>{@code /remix skein spawn}: one on the rock where you look (floor, wall or ceiling), lying still, its body
 *     laid out behind it the way there is most room</li>
 *     <li>{@code /remix skein list [radius]}: the Skeins round you, where each is and what it is doing</li>
 *     <li>{@code /remix skein state <name> [ticks]}: sets the nearest one's state (still, skitter, stalk, rear,
 *     lunge, bolt, eat): a strike is at you, a bolt away from you, a stalk after the nearest landed swarm</li>
 *     <li>{@code /remix skein hungry}: makes the nearest one hungry now, so it goes for a landed swarm near it</li>
 * </ul>
 */
public final class SkeinCommands {
    private SkeinCommands() {}

    private static final String[] STATES = SkeinEntity.STATE_NAMES;
    /** How far round the point you look at it looks for rock to set one on. */
    private static final int SPAWN_REACH = 5;

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("skein")
                .then(Commands.literal("spawn").executes(SkeinCommands::spawn))
                .then(Commands.literal("list").executes(c -> list(c, 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("state").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(STATES, b))
                        .executes(c -> state(c, StringArgumentType.getString(c, "name"), 400))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 12000))
                                .executes(c -> state(c, StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "ticks"))))))
                .then(Commands.literal("hungry").executes(SkeinCommands::hungry));
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static int spawn(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 eye = c.getSource().getPosition();
        Vec3 look = Vec3.directionFromRotation(c.getSource().getRotation());
        // from your eyes, unless the command was moved elsewhere ("execute positioned ...")
        if (c.getSource().getEntity() != null && c.getSource().getEntity().position().distanceToSqr(eye) < 1.0E-6)
            eye = c.getSource().getEntity().getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(24)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(6)) : hit.getLocation().subtract(look.scale(0.6));
        SkeinEntity.Placement spot = nearestSpot(level, at);
        if (spot == null) {
            c.getSource().sendFailure(Component.literal("No rock with room for a Skein near " + BlockPos.containing(at).toShortString()));
            return 0;
        }
        SkeinEntity s = EREntities.SKEIN.get().create(level);
        if (s == null) return 0;
        s.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spot.pos())), MobSpawnType.COMMAND, null, null);
        s.placeOn(spot.pos(), spot.rock(), SkeinEntity.openHeading(level, EREntities.SKEIN.get(), spot, level.random::nextDouble));
        level.addFreshEntity(s);
        say(c, "Skein on the " + side(spot) + " at " + BlockPos.containing(spot.pos()).toShortString());
        return 1;
    }

    /** The climbing node nearest {@code at} that a Skein can be set down on, and how it sits there. */
    @Nullable
    private static SkeinEntity.Placement nearestSpot(ServerLevel level, Vec3 at) {
        BlockPos near = BlockPos.containing(at.x - 1.0, at.y - 0.35, at.z - 1.0);
        SkeinEntity.Placement best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SPAWN_REACH; dx <= SPAWN_REACH; dx++)
            for (int dy = -SPAWN_REACH; dy <= SPAWN_REACH; dy++)
                for (int dz = -SPAWN_REACH; dz <= SPAWN_REACH; dz++) {
                    BlockPos n = near.offset(dx, dy, dz);
                    double d = Vec3.atLowerCornerOf(n).add(1.0, 0.35, 1.0).distanceToSqr(at);
                    if (d >= bestD) continue;
                    SkeinEntity.Placement p = SkeinEntity.placement(level, EREntities.SKEIN.get(), n);
                    if (p == null) continue;
                    bestD = d;
                    best = p;
                }
        return best;
    }

    private static String side(SkeinEntity.Placement p) {
        return switch (p.rock()) {
            case DOWN -> "floor";
            case UP -> "ceiling";
            default -> p.rock().getName() + " wall";
        };
    }

    private static List<SkeinEntity> near(CommandContext<CommandSourceStack> c, double radius) {
        Vec3 p = c.getSource().getPosition();
        List<SkeinEntity> all = c.getSource().getLevel().getEntitiesOfClass(SkeinEntity.class, new AABB(p, p).inflate(radius));
        all.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return all;
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        List<SkeinEntity> all = near(c, radius);
        if (all.isEmpty()) { say(c, "No Skein within " + radius + " blocks."); return 0; }
        Vec3 p = c.getSource().getPosition();
        for (SkeinEntity s : all)
            say(c, String.format(Locale.ROOT, "%s, %.0f blocks: %s", s.blockPosition().toShortString(), Math.sqrt(s.distanceToSqr(p)), s.describe()));
        return all.size();
    }

    private static int state(CommandContext<CommandSourceStack> c, String name, int ticks) {
        int st = Arrays.asList(STATES).indexOf(name.toLowerCase(Locale.ROOT));
        if (st < 0) { c.getSource().sendFailure(Component.literal("No state " + name + "; one of " + String.join(", ", STATES))); return 0; }
        List<SkeinEntity> all = near(c, 64);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Skein within 64 blocks.")); return 0; }
        SkeinEntity s = all.get(0);
        LivingEntity me = c.getSource().getEntity() instanceof LivingEntity le ? le : null;
        if (!s.forceState((byte) st, ticks, me)) {
            c.getSource().sendFailure(Component.literal("It cannot " + name + " now (a stalk needs a landed swarm near it; a strike, "
                    + "a player in survival)."));
            return 0;
        }
        say(c, "Now: " + s.describe());
        return 1;
    }

    private static int hungry(CommandContext<CommandSourceStack> c) {
        List<SkeinEntity> all = near(c, 64);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Skein within 64 blocks.")); return 0; }
        all.get(0).starve();
        say(c, "Hungry: " + all.get(0).describe());
        return 1;
    }
}
