package com.explorationremix.creature;

import com.explorationremix.creature.body.EmberWalk;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.feature.EmberUrchinsFeature;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /remix ember: looking after the Wasteland's ember urchins while testing (operators only).
 * <ul>
 *     <li>{@code /remix ember spawn}: one where you look, stood up on its spines over the floor there</li>
 *     <li>{@code /remix ember list [radius]}: the urchins round you, nearest first: where, and what each is doing</li>
 *     <li>{@code /remix ember tp [n] [distance]}: to the nth nearest urchin (from 1), standing that far off it (8
 *     blocks, or the distance given) on the floor of its tunnel ahead of it, facing it</li>
 *     <li>{@code /remix ember flare}: the nearest flares (as if hurt) for ten seconds</li>
 *     <li>{@code /remix ember refill}: one set down out of sight round you now, as the top-up would, whatever the count</li>
 *     <li>{@code /remix ember counts}: floors looked at and urchins set down with the world since the game started, and
 *     by the top-up</li>
 * </ul>
 */
public final class EmberCommands {
    private EmberCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("ember")
                .then(Commands.literal("spawn").executes(EmberCommands::spawn))
                .then(Commands.literal("list").executes(c -> list(c, 64))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("tp").executes(c -> tp(c, 1, 8))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 64)).executes(c -> tp(c, IntegerArgumentType.getInteger(c, "n"), 8))
                                .then(Commands.argument("distance", IntegerArgumentType.integer(3, 32))
                                        .executes(c -> tp(c, IntegerArgumentType.getInteger(c, "n"), IntegerArgumentType.getInteger(c, "distance"))))))
                .then(Commands.literal("flare").executes(EmberCommands::flare))
                .then(Commands.literal("refill").executes(EmberCommands::refill))
                .then(Commands.literal("counts").executes(EmberCommands::counts));
    }

    private static int spawn(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        Vec3 eye = src.getEntity() != null ? src.getEntity().getEyePosition() : src.getPosition().add(0, 1.6, 0);
        Vec3 look = src.getEntity() != null ? src.getEntity().getLookAngle() : new Vec3(0, 0, 1);
        Vec3 to = eye.add(look.scale(7.0));
        HitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, src.getEntity()));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation().subtract(look.scale(1.6));
        // stood up on its spines over the floor there, if there is one under it
        EmberSpots.LevelGround g = new EmberSpots.LevelGround(level);
        g.keepToWasteland = false;
        double y = EmberWalk.standAt(g, at.x, at.y, at.z);
        if (!Double.isNaN(y)) at = new Vec3(at.x, y, at.z);
        EmberUrchinEntity e = EREntities.EMBER_URCHIN.get().create(level);
        if (e == null) return 0;
        e.setDown(at.x, at.y, at.z, Math.atan2(look.z, look.x));
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.COMMAND, null, null);
        e.setDown(at.x, at.y, at.z, Math.atan2(look.z, look.x));
        level.addFreshEntity(e);
        Vec3 shown = at;
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "An ember urchin at %.1f %.1f %.1f", shown.x, shown.y, shown.z)), true);
        return 1;
    }

    private static List<EmberUrchinEntity> near(CommandSourceStack src, double radius) {
        Vec3 p = src.getPosition();
        List<EmberUrchinEntity> all = new ArrayList<>(src.getLevel().getEntitiesOfClass(EmberUrchinEntity.class, new AABB(p, p).inflate(radius),
                e -> e.isAlive() && e.position().distanceTo(p) <= radius));
        all.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(p)));
        return all;
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        Vec3 p = src.getPosition();
        List<EmberUrchinEntity> all = near(src, radius);
        StringBuilder b = new StringBuilder(all.size() + " ember urchin(s) within " + radius + " blocks");
        for (EmberUrchinEntity e : all.subList(0, Math.min(all.size(), 16)))
            b.append(String.format(Locale.ROOT, "\n %.1f %.1f %.1f (%d off): %s", e.getX(), e.getY() + EmberUrchinEntity.HALF, e.getZ(),
                    (int) e.position().distanceTo(p), e.describe()));
        src.sendSuccess(() -> Component.literal(b.toString()), false);
        return all.size();
    }

    private static int tp(CommandContext<CommandSourceStack> c, int n, int distance) {
        CommandSourceStack src = c.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Only a player can be sent"));
            return 0;
        }
        List<EmberUrchinEntity> all = near(src, 512);
        if (all.size() < n) {
            src.sendFailure(Component.literal("Only " + all.size() + " ember urchin(s) within 512 blocks"));
            return 0;
        }
        EmberUrchinEntity e = all.get(n - 1);
        Vec3 m = e.middle();
        Vector3d h = e.walk().heading;
        ServerLevel level = src.getLevel();
        EmberSpots.LevelGround g = new EmberSpots.LevelGround(level);
        g.keepToWasteland = false;
        Vec3 spot = null;
        for (int side = 0; side < 2 && spot == null; side++) {
            double sx = side == 0 ? h.x : -h.x, sz = side == 0 ? h.z : -h.z;
            for (double d = distance; d >= 3.0 && spot == null; d -= 1.0) spot = standing(level, g, m.x + sx * d, m.y, m.z + sz * d, m);
        }
        if (spot == null) spot = new Vec3(m.x + h.x * distance, m.y, m.z + h.z * distance);
        double dx = m.x - spot.x, dy = m.y - (spot.y + 1.62), dz = m.z - spot.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo(level, spot.x, spot.y, spot.z, yaw, pitch);
        Vec3 shown = spot;
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%.1f blocks off an ember urchin at %.1f %.1f %.1f: %s",
                shown.distanceTo(m), m.x, m.y, m.z, e.describe())), true);
        return 1;
    }

    /** A place to stand near (x, y, z): on the floor under it with room for a player, seeing {@code look}, or null. */
    @Nullable
    private static Vec3 standing(ServerLevel level, EmberSpots.LevelGround g, double x, double y, double z, Vec3 look) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        for (int by = Mth.floor(y); by > Mth.floor(y) - 6; by--) {
            if (g.solid(bx, by, bz) || !g.solid(bx, by - 1, bz)) continue;
            if (g.solid(bx, by + 1, bz)) return null;
            Vec3 feet = new Vec3(bx + 0.5, by, bz + 0.5);
            Vec3 eye = feet.add(0, 1.62, 0);
            if (level.clip(new ClipContext(eye, look, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null)).getType() != HitResult.Type.MISS) return null;
            return feet;
        }
        return null;
    }

    private static int flare(CommandContext<CommandSourceStack> c) {
        List<EmberUrchinEntity> all = near(c.getSource(), 64);
        if (all.isEmpty()) {
            c.getSource().sendFailure(Component.literal("No ember urchin within 64 blocks"));
            return 0;
        }
        all.get(0).flareFor(200);
        c.getSource().sendSuccess(() -> Component.literal("The nearest ember urchin flares"), false);
        return 1;
    }

    private static int refill(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.literal("Only round a player"));
            return 0;
        }
        int n = EmberRefill.lookRound(src.getLevel(), player, true);
        src.sendSuccess(() -> Component.literal(n > 0 ? "An ember urchin set down out of sight" : "No spot out of sight found this time"), false);
        return n;
    }

    private static int counts(CommandContext<CommandSourceStack> c) {
        c.getSource().sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "With the world: %d floors looked at, %d urchins set down. Top-up: %d looks, %d set down.",
                EmberUrchinsFeature.FLOORS.get(), EmberUrchinsFeature.PLACED.get(), EmberRefill.LOOKS, EmberRefill.PLACED)), false);
        return 1;
    }
}
