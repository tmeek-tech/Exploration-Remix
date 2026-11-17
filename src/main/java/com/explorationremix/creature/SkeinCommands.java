package com.explorationremix.creature;

import com.explorationremix.registry.EREntities;
import com.explorationremix.world.feature.SkeinsFeature;
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
 *     lunge, bolt, eat, journey): a strike is at you, a bolt away from you, a stalk after the nearest landed swarm, a
 *     journey 40 to 90 blocks along the Deep Blue Rift's ledge path (it must be by it)</li>
 *     <li>{@code /remix skein homes}: the Skeins set down with the world since the game started (home chunks made,
 *     Skeins set down in them), how many are in the loaded world now and how many of those lie dormant, and how the
 *     slow refill has gone (looks with none near, rolls that came up, Skeins moved in)</li>
 *     <li>{@code /remix skein refill}: a Skein is due to move in for you now, as when the slow refill's roll comes up (it
 *     comes at the next look that finds a spot out of sight, while none is within 96 blocks of you and you are in the
 *     Blue Reaches in Survival or Adventure)</li>
 *     <li>{@code /remix skein hungry}: makes the nearest one hungry now, so it goes for a landed swarm near it</li>
 *     <li>{@code /remix skein trace [ticks]}: logs how the nearest one's body moves each tick, to the server log</li>
 *     <li>{@code /remix view skein [n]}: puts you a few blocks from the nearest one within 96 blocks, looking at it (for
 *     the shot driver)</li>
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
                .then(Commands.literal("homes").executes(SkeinCommands::homes))
                .then(Commands.literal("refill").executes(SkeinCommands::refill))
                .then(Commands.literal("hungry").executes(SkeinCommands::hungry))
                .then(Commands.literal("trace").executes(c -> trace(c, 600))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 12000)).executes(c -> trace(c, IntegerArgumentType.getInteger(c, "ticks")))));
    }

    /** Logs how the nearest one's body moves, each tick for {@code ticks} (to the server log). */
    private static int trace(CommandContext<CommandSourceStack> c, int ticks) {
        List<SkeinEntity> all = near(c, 48);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Skein within 48 blocks.")); return 0; }
        all.get(0).trace(ticks);
        say(c, "Tracing its body for " + ticks + " ticks, in the server log");
        return 1;
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
        // the box's corners reach further than the radius: only those within it, as the spawner counts them
        List<SkeinEntity> all = c.getSource().getLevel().getEntitiesOfClass(SkeinEntity.class, new AABB(p, p).inflate(radius),
                e -> e.distanceToSqr(p) <= radius * radius);
        all.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return all;
    }

    /**
     * How the Skeins set down with the world are doing: the home chunks made since the game started and the Skeins set
     * down in them (world.feature.SkeinsFeature), and the Skeins in the loaded world now, those kept for good among them.
     */
    private static int homes(CommandContext<CommandSourceStack> c) {
        int loaded = 0, kept = 0, dormant = 0;
        for (var e : c.getSource().getLevel().getAllEntities()) {
            if (!(e instanceof SkeinEntity s) || !s.isAlive()) continue;
            loaded++;
            if (s.isPersistenceRequired()) kept++;
            if (s.dormant()) dormant++;
        }
        long homes = SkeinsFeature.HOMES.get(), placed = SkeinsFeature.PLACED.get();
        say(c, String.format(Locale.ROOT, "Home chunks made: %d, Skeins set down in them: %d (%.0f%%). Loaded now: %d, %d of them kept for good, %d dormant (no player near).",
                homes, placed, homes == 0 ? 0.0 : 100.0 * placed / homes, loaded, kept, dormant));
        say(c, String.format(Locale.ROOT, "Slow refill: %d looks round a player with none within %d blocks, %d rolls came up, %d Skeins moved in.",
                BlueReachesSpawner.REFILL_LOOKS, (int) BlueSpawnRules.REFILL_RADIUS, BlueReachesSpawner.REFILL_ROLLS, BlueReachesSpawner.REFILL_IN));
        return loaded;
    }

    /**
     * For /remix view skein [n] (the shot driver): puts you a few blocks off the nearest Skein within 96 blocks, beside
     * it (n odd: ahead of it, n 2 or more a little further off), a little above its back, looking at it.
     */
    public static int look(CommandContext<CommandSourceStack> c, net.minecraft.server.level.ServerPlayer pl, int n) {
        List<SkeinEntity> all = near(c, 96);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Skein within 96 blocks.")); return 0; }
        SkeinEntity s = all.get(0);
        ServerLevel level = c.getSource().getLevel();
        Vec3 body = s.bodyCentre(), up = s.up(), fwd = s.lookDir();
        Vec3 side = fwd.cross(up);
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
        double dist = 4.5 + n / 2;
        Vec3 main = n % 2 == 1 ? fwd.scale(0.8).add(side.scale(0.6)).normalize() : side;
        for (double lift : new double[]{0.5, 0.3, 0.8, 0.1})
            for (int k = 0; k < 12; k++) {
                double a = Math.toRadians(30 * ((k + 1) / 2) * (k % 2 == 0 ? 1 : -1));
                Vec3 d = rotate(main, up, a).add(up.scale(lift)).normalize();
                Vec3 eye = body.add(d.scale(dist));
                BlockPos eb = BlockPos.containing(eye);
                if (!level.getBlockState(eb).getCollisionShape(level, eb).isEmpty()) continue;
                BlockHitResult hit = level.clip(new ClipContext(eye, body, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, s));
                if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(body) > 0.9) continue;
                Vec3 to = body.subtract(eye);
                float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
                pl.teleportTo(level, eye.x, eye.y - pl.getEyeHeight(), eye.z, yaw, pitch);
                say(c, String.format(Locale.ROOT, "Looking at the Skein at %s from %.1f %.1f %.1f (%s)", s.blockPosition().toShortString(), eye.x, eye.y, eye.z, s.describe()));
                return 1;
            }
        c.getSource().sendFailure(Component.literal("No clear view of the Skein at " + s.blockPosition().toShortString()));
        return 0;
    }

    /** Rotates v about the unit axis k by angle a (Rodrigues). */
    private static Vec3 rotate(Vec3 v, Vec3 k, double a) {
        double cos = Math.cos(a), sin = Math.sin(a);
        return v.scale(cos).add(k.cross(v).scale(sin)).add(k.scale(k.dot(v) * (1 - cos)));
    }

    /** For testing the slow refill: a Skein is due for the player now (BlueReachesSpawner). */
    private static int refill(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer p = c.getSource().getPlayerOrException();
        BlueReachesSpawner.skeinDue(p);
        double r = BlueSpawnRules.REFILL_RADIUS;
        int n = near(c, r).size();
        say(c, "A Skein is due: it moves in out of sight at the next look that finds a spot"
                + (n > 0 ? ", but none comes while one is within " + (int) r + " blocks of you (" + n + " are, so this is dropped at the next look)" : "") + ".");
        return 1;
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
                    + "a player in survival; a journey, the Deep Blue Rift's ledge path near it, with way to go along it)."));
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
