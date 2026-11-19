package com.explorationremix.creature;

import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Route;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * /remix giant: looking after the giant Skeins while testing (operators only).
 * <ul>
 *     <li>{@code /remix giant spawn}: one on the rock where you look (a wall if there is one near), lying still, its
 *     body laid along the rock the way there is most room (in a rift it lives in that rift)</li>
 *     <li>{@code /remix giant place}: sets down the nearest rift's own giant now, at the first of its home spots that
 *     will do, near you or not (as if a player had come by with none near); not for a rift that has had one</li>
 *     <li>{@code /remix giant homes}: the rifts that have had their giant (and which were killed), and the nearest
 *     rift's record</li>
 *     <li>{@code /remix giant forget}: forgets that the nearest rift has had its giant, so it gets one again</li>
 *     <li>{@code /remix giant list [radius]}: the giants round you, where each is and what it is doing</li>
 *     <li>{@code /remix giant state <name> [ticks]}: sets the nearest one's state (rest, crawl, alert, hunt, rear,
 *     retreat): a hunt or strike is at you</li>
 *     <li>{@code /remix giant trace [ticks]}: logs how the nearest one's body moves each tick, to the server log</li>
 *     <li>{@code /remix giant path}: puts you on its rift's ledge path where the nearest giant sees you, about 16
 *     blocks off (else where the path passes nearest it), facing it (for testing its hunt)</li>
 *     <li>{@code /remix giant stand [blocks]}: puts you on a glass block in the air out from the nearest giant's head,
 *     that many blocks off its rock (6 by default, in reach of its strike), facing it (for testing its notice and
 *     strike at a player)</li>
 *     <li>{@code /remix giant strike}: it rears and strikes at a chicken hung in the air out from its head (it bites
 *     it if its strike lands there; /remix giant list counts its bites)</li>
 *     <li>{@code /remix giant hold <rear|lunge|recover> <0-1>}: holds the nearest one that far through that phase of a
 *     strike out from its head, for pictures; {@code /remix giant hold off} lets it go</li>
 *     <li>{@code /remix view giant [n]}: puts you off the nearest one within 128 blocks, looking at it (n 0: its whole
 *     length from out in the rift; 1: its head; 2: its whole length from further off; 3: its tail; for the shot
 *     driver)</li>
 * </ul>
 */
public final class GiantSkeinCommands {
    private GiantSkeinCommands() {}

    private static final String[] STATES = GiantSkeinEntity.STATE_NAMES;
    /** How far round the point you look at it looks for rock to set one on. */
    private static final int SPAWN_REACH = 6;

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("giant")
                .then(Commands.literal("spawn").executes(GiantSkeinCommands::spawn))
                .then(Commands.literal("place").executes(GiantSkeinCommands::place))
                .then(Commands.literal("homes").executes(GiantSkeinCommands::homes))
                .then(Commands.literal("forget").executes(GiantSkeinCommands::forget))
                .then(Commands.literal("list").executes(c -> list(c, 160))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 512)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("state").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(STATES, b))
                        .executes(c -> state(c, StringArgumentType.getString(c, "name"), 400))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 12000))
                                .executes(c -> state(c, StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "ticks"))))))
                .then(Commands.literal("path").executes(GiantSkeinCommands::path))
                .then(Commands.literal("stand").executes(c -> stand(c, 6))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(3, 24)).executes(c -> stand(c, IntegerArgumentType.getInteger(c, "blocks")))))
                .then(Commands.literal("strike").executes(GiantSkeinCommands::strike))
                .then(Commands.literal("hold")
                        .then(Commands.literal("off").executes(GiantSkeinCommands::letGo))
                        .then(Commands.argument("phase", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"rear", "lunge", "recover"}, b))
                                .then(Commands.argument("progress", FloatArgumentType.floatArg(0.0F, 1.0F))
                                        .executes(c -> hold(c, StringArgumentType.getString(c, "phase"), FloatArgumentType.getFloat(c, "progress"))))))
                .then(Commands.literal("trace").executes(c -> trace(c, 600))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(0, 12000)).executes(c -> trace(c, IntegerArgumentType.getInteger(c, "ticks")))));
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static List<GiantSkeinEntity> near(CommandContext<CommandSourceStack> c, double radius) {
        Vec3 p = c.getSource().getPosition();
        List<GiantSkeinEntity> all = c.getSource().getLevel().getEntitiesOfClass(GiantSkeinEntity.class, new AABB(p, p).inflate(radius),
                e -> e.isAlive() && e.distanceToSqr(p) <= radius * radius);
        all.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return all;
    }

    /** The rift whose centre line is nearest you (within 256 blocks), or null. */
    @Nullable
    private static Route nearestRift(CommandContext<CommandSourceStack> c) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null) return null;
        Vec3 p = c.getSource().getPosition();
        Route best = null;
        double bestD = Double.MAX_VALUE;
        for (Route r : t.network.riftsNear(p.x, p.z, 256)) {
            double[] loc = r.isRift() ? r.locate(p.x, p.z) : null;
            if (loc == null || loc[0] >= bestD) continue;
            best = r;
            bestD = loc[0];
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------ setting one down

    private static int spawn(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 eye = c.getSource().getPosition();
        Vec3 look = Vec3.directionFromRotation(c.getSource().getRotation());
        if (c.getSource().getEntity() != null && c.getSource().getEntity().position().distanceToSqr(eye) < 1.0E-6)
            eye = c.getSource().getEntity().getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(48)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(8)) : hit.getLocation().subtract(look.scale(1.2));
        GiantSkeinEntity.Placement spot = nearestSpot(level, at);
        if (spot == null) {
            c.getSource().sendFailure(Component.literal("No rock with room for a giant Skein near " + BlockPos.containing(at).toShortString()));
            return 0;
        }
        Vec3 up = GiantSkeinEntity.normal(spot.rock()), feet = GiantSkeinEntity.placedFeet(EREntities.GIANT_SKEIN.get(), spot);
        Vec3 back = roomiest(level, feet, up, look);
        GiantSkeinEntity g = EREntities.GIANT_SKEIN.get().create(level);
        if (g == null) return 0;
        g.moveTo(spot.pos().x, spot.pos().y, spot.pos().z, 0.0F, 0.0F);
        g.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spot.pos())), MobSpawnType.COMMAND, null, null);
        g.placeOn(level, spot, back.scale(-1.0), back);
        level.addFreshEntity(g);
        say(c, String.format(Locale.ROOT, "Giant Skein on the %s at %s, its body laid back %.0f blocks over the rock (%s)", side(spot.rock()),
                BlockPos.containing(spot.pos()).toShortString(), GiantSkeinEntity.drapeRoom(level, feet, up, back), g.describe()));
        return 1;
    }

    /** The way along the rock from {@code feet} with the most room for its body (of eight), ties going across your look. */
    private static Vec3 roomiest(ServerLevel level, Vec3 feet, Vec3 up, Vec3 look) {
        Vec3 a = Math.abs(up.y) > 0.9 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        Vec3 e1 = a.subtract(up.scale(a.dot(up))).normalize(), e2 = up.cross(e1).normalize();
        Vec3 best = e1;
        double bestScore = -1.0;
        for (int i = 0; i < 8; i++) {
            double ang = Math.PI * 2.0 * i / 8.0;
            Vec3 d = e1.scale(Math.cos(ang)).add(e2.scale(Math.sin(ang)));
            // across the line you look along, so you see its length
            double score = GiantSkeinEntity.drapeRoom(level, feet, up, d) - 2.0 * Math.abs(d.dot(look));
            if (score > bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    /** The node nearest {@code at} it can be set down on (a wall if one will do within the reach), and how it sits there. */
    @Nullable
    private static GiantSkeinEntity.Placement nearestSpot(ServerLevel level, Vec3 at) {
        BlockPos near = BlockPos.containing(at.x - 1.5, at.y - 0.6, at.z - 1.5);
        GiantSkeinEntity.Placement best = null, bestWall = null;
        double bestD = Double.MAX_VALUE, bestWallD = Double.MAX_VALUE;
        for (int dx = -SPAWN_REACH; dx <= SPAWN_REACH; dx++)
            for (int dy = -SPAWN_REACH; dy <= SPAWN_REACH; dy++)
                for (int dz = -SPAWN_REACH; dz <= SPAWN_REACH; dz++) {
                    BlockPos n = near.offset(dx, dy, dz);
                    double d = Vec3.atLowerCornerOf(n).add(1.5, 0.6, 1.5).distanceToSqr(at);
                    if (d >= bestD && d >= bestWallD) continue;
                    GiantSkeinEntity.Placement p = GiantSkeinEntity.placement(level, EREntities.GIANT_SKEIN.get(), n);
                    if (p == null) continue;
                    if (d < bestD) { bestD = d; best = p; }
                    if (p.rock().getAxis() != Direction.Axis.Y && d < bestWallD) { bestWallD = d; bestWall = p; }
                }
        return bestWall != null ? bestWall : best;
    }

    private static String side(Direction rock) {
        return switch (rock) {
            case DOWN -> "floor";
            case UP -> "ceiling";
            default -> rock.getName() + " wall";
        };
    }

    private static int place(CommandContext<CommandSourceStack> c) {
        Route r = nearestRift(c);
        if (r == null) { c.getSource().sendFailure(Component.literal("No Deep Blue Rift within 256 blocks.")); return 0; }
        ServerLevel level = c.getSource().getLevel();
        GiantSkeins.Home h = GiantSkeins.get(level).home(r);
        if (h != null) {
            c.getSource().sendFailure(Component.literal(String.format(Locale.ROOT, "The rift of cell %d %d has had its giant (%s at %s); /remix giant forget first.",
                    r.cellX, r.cellZ, h.killed() ? "killed" : "set down", h.at.toShortString())));
            return 0;
        }
        GiantSkeinEntity g = GiantSkeins.setDown(level, r, true);
        if (g == null) {
            c.getSource().sendFailure(Component.literal(String.format(Locale.ROOT, "None of the home spots of the rift of cell %d %d will do now "
                    + "(each needs its chunks loaded, a wall to cling to and room for its body).", r.cellX, r.cellZ)));
            return 0;
        }
        say(c, String.format(Locale.ROOT, "The giant of the rift of cell %d %d is set down at %s: %s", r.cellX, r.cellZ, g.blockPosition().toShortString(), g.describe()));
        return 1;
    }

    private static int homes(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        List<GiantSkeins.Home> all = GiantSkeins.get(level).homes();
        long killed = all.stream().filter(GiantSkeins.Home::killed).count();
        say(c, String.format(Locale.ROOT, "%d rifts have had their giant, %d of them killed (those stay empty).", all.size(), killed));
        Route r = nearestRift(c);
        if (r == null) { say(c, "No Deep Blue Rift within 256 blocks."); return all.size(); }
        GiantSkeins.Home h = GiantSkeins.get(level).home(r);
        if (h == null) say(c, String.format(Locale.ROOT, "The nearest rift (cell %d %d, %.0f blocks long) has not had its giant yet.", r.cellX, r.cellZ, r.length));
        else {
            GiantSkeinEntity g = h.id == null ? null : level.getEntity(h.id) instanceof GiantSkeinEntity e ? e : null;
            say(c, String.format(Locale.ROOT, "The nearest rift (cell %d %d): its giant was set down at %s %d minutes ago%s.", r.cellX, r.cellZ,
                    h.killed() ? "?" : h.at.toShortString(), (level.getGameTime() - h.placedAt) / 1200,
                    h.killed() ? ", and killed at " + h.at.toShortString() + " (none moves in after it)"
                            : g != null ? "; it is at " + g.blockPosition().toShortString() + ": " + g.describe() : "; it is not loaded now"));
        }
        return all.size();
    }

    private static int forget(CommandContext<CommandSourceStack> c) {
        Route r = nearestRift(c);
        if (r == null) { c.getSource().sendFailure(Component.literal("No Deep Blue Rift within 256 blocks.")); return 0; }
        if (!GiantSkeins.get(c.getSource().getLevel()).forget(r)) {
            say(c, String.format(Locale.ROOT, "The rift of cell %d %d has not had its giant.", r.cellX, r.cellZ));
            return 0;
        }
        say(c, String.format(Locale.ROOT, "Forgot the giant of the rift of cell %d %d: the rift gets one again (one already there stays).", r.cellX, r.cellZ));
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the giants round you

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        List<GiantSkeinEntity> all = near(c, radius);
        if (all.isEmpty()) { say(c, "No giant Skein within " + radius + " blocks."); return 0; }
        Vec3 p = c.getSource().getPosition();
        for (GiantSkeinEntity g : all)
            say(c, String.format(Locale.ROOT, "%s, %.0f blocks: %s", g.blockPosition().toShortString(), Math.sqrt(g.distanceToSqr(p)), g.describe()));
        return all.size();
    }

    private static int state(CommandContext<CommandSourceStack> c, String name, int ticks) {
        int st = Arrays.asList(STATES).indexOf(name.toLowerCase(Locale.ROOT));
        if (st < 0) { c.getSource().sendFailure(Component.literal("No state " + name + "; one of " + String.join(", ", STATES))); return 0; }
        List<GiantSkeinEntity> all = near(c, 128);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 128 blocks.")); return 0; }
        GiantSkeinEntity g = all.get(0);
        LivingEntity me = c.getSource().getEntity() instanceof LivingEntity le ? le : null;
        if (!g.forceState((byte) st, ticks, me)) {
            c.getSource().sendFailure(Component.literal("It cannot " + name + " now (a hunt or strike needs a player in survival; a crawl, a way to crawl)."));
            return 0;
        }
        say(c, "Now: " + g.describe());
        return 1;
    }

    private static int trace(CommandContext<CommandSourceStack> c, int ticks) {
        List<GiantSkeinEntity> all = near(c, 128);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 128 blocks.")); return 0; }
        all.get(0).trace(ticks);
        say(c, "Tracing its body for " + ticks + " ticks, in the server log");
        return 1;
    }

    private static int path(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer pl = c.getSource().getPlayerOrException();
        List<GiantSkeinEntity> all = near(c, 256);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 256 blocks.")); return 0; }
        GiantSkeinEntity g = all.get(0);
        Route r = g.rift();
        if (r == null) { c.getSource().sendFailure(Component.literal("It lives in no rift.")); return 0; }
        Vec3 at = g.bodyCentre();
        double s0 = r.walkArcNear(at.x, at.y, at.z, 400.0, 400.0);
        if (Double.isNaN(s0)) { c.getSource().sendFailure(Component.literal("Its rift's ledge path is nowhere near it.")); return 0; }
        // a point of the path along the stretch round where it passes that it can see from where it lies, about 16
        // blocks off (it notices you there, and has a little way to crawl); else the point nearest it
        Vec3 eyes = g.eyes();
        double best = s0, bestD = Double.MAX_VALUE, near = s0, nearD = Double.MAX_VALUE;
        for (double ds = -80.0; ds <= 80.0; ds += 1.0) {
            double s = Math.max(0.0, Math.min(r.walkLength, s0 + ds));
            double[] q = r.pathAt(s);
            Vec3 eye = new Vec3(q[0], Route.feetAt(q[2]) + pl.getEyeHeight(), q[1]);
            double d = eye.distanceTo(eyes);
            if (d < nearD) { nearD = d; near = s; }
            if (d >= 10.0 && d <= 40.0 && Math.abs(d - 16.0) < bestD && g.sees(eye)) { bestD = Math.abs(d - 16.0); best = s; }
        }
        boolean seen = bestD < Double.MAX_VALUE;
        double[] q = r.pathAt(seen ? best : near);
        Vec3 feet = new Vec3(q[0], Route.feetAt(q[2]), q[1]);
        Vec3 to = at.subtract(feet.add(0.0, pl.getEyeHeight(), 0.0));
        float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        pl.teleportTo(c.getSource().getLevel(), feet.x, feet.y, feet.z, yaw, pitch);
        say(c, String.format(Locale.ROOT, "On the ledge path at %.1f %.1f %.1f, %.0f blocks from the giant at %s, %s (%s)", feet.x, feet.y, feet.z,
                feet.add(0.0, pl.getEyeHeight(), 0.0).distanceTo(eyes), g.blockPosition().toShortString(),
                seen ? "in its sight" : "out of its sight (no point of the path near it is in sight)", g.describe()));
        return 1;
    }

    private static int stand(CommandContext<CommandSourceStack> c, int blocks) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer pl = c.getSource().getPlayerOrException();
        List<GiantSkeinEntity> all = near(c, 128);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 128 blocks.")); return 0; }
        GiantSkeinEntity g = all.get(0);
        ServerLevel level = c.getSource().getLevel();
        Vec3 up = g.up(), look = g.lookDir();
        // out from its rock before its head; for one on a floor, ahead of it
        Vec3 at = Math.abs(up.y) > 0.8 ? g.mouth().add(look.scale(blocks)) : g.mouth().add(up.scale(blocks)).add(look.scale(1.0));
        BlockPos feet = BlockPos.containing(at.x, at.y - 0.5, at.z);
        for (BlockPos p : new BlockPos[]{feet, feet.above()})
            if (!level.getBlockState(p).getCollisionShape(level, p).isEmpty()) {
                c.getSource().sendFailure(Component.literal("Rock where you would stand, at " + p.toShortString()));
                return 0;
            }
        BlockPos below = feet.below();
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty())
            level.setBlockAndUpdate(below, net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
        Vec3 stand = Vec3.atBottomCenterOf(feet);
        Vec3 to = g.eyes().subtract(stand.add(0.0, pl.getEyeHeight(), 0.0));
        float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
        pl.teleportTo(level, stand.x, stand.y, stand.z, yaw, pitch);
        say(c, String.format(Locale.ROOT, "On glass at %s, %d blocks out from the giant's head, %s (%s)", feet.toShortString(), blocks,
                g.sees(stand.add(0.0, pl.getEyeHeight(), 0.0)) ? "in its sight" : "out of its sight", g.describe()));
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ its strike

    /** Where a strike out from its head goes when nothing is there: out from its rock and a little ahead. */
    private static Vec3 outFromHead(GiantSkeinEntity g) {
        return g.mouth().add(g.up().scale(5.0)).add(g.lookDir().scale(3.0));
    }

    private static int strike(CommandContext<CommandSourceStack> c) {
        List<GiantSkeinEntity> all = near(c, 128);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 128 blocks.")); return 0; }
        GiantSkeinEntity g = all.get(0);
        ServerLevel level = c.getSource().getLevel();
        Vec3 at = outFromHead(g);
        net.minecraft.world.entity.animal.Chicken bait = net.minecraft.world.entity.EntityType.CHICKEN.create(level);
        if (bait == null) return 0;
        bait.moveTo(at.x, at.y - 0.35, at.z, 0.0F, 0.0F);
        bait.setNoAi(true);
        bait.setNoGravity(true);
        bait.setPersistenceRequired();
        bait.addTag("giant_bait");
        level.addFreshEntity(bait);
        g.strikeAt(bait);
        say(c, String.format(Locale.ROOT, "It rears at a chicken at %.1f %.1f %.1f: %s", at.x, at.y, at.z, g.describe()));
        return 1;
    }

    private static int hold(CommandContext<CommandSourceStack> c, String phase, float k) {
        byte s = switch (phase.toLowerCase(Locale.ROOT)) {
            case "rear" -> GiantSkeinEntity.REAR;
            case "lunge" -> GiantSkeinEntity.LUNGE;
            case "recover" -> GiantSkeinEntity.RECOVER;
            default -> -1;
        };
        if (s < 0) { c.getSource().sendFailure(Component.literal("No phase " + phase + "; one of rear, lunge, recover")); return 0; }
        List<GiantSkeinEntity> all = near(c, 128);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 128 blocks.")); return 0; }
        GiantSkeinEntity g = all.get(0);
        if (!g.hold(s, k, outFromHead(g))) return 0;
        say(c, "Held: " + g.describe());
        return 1;
    }

    private static int letGo(CommandContext<CommandSourceStack> c) {
        List<GiantSkeinEntity> all = near(c, 128);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 128 blocks.")); return 0; }
        all.get(0).letGo();
        say(c, "Let go: " + all.get(0).describe());
        return 1;
    }

    // ------------------------------------------------------------------------------------------------ the shot driver

    /**
     * For /remix view giant [n] (the shot driver): puts you off the nearest giant within 128 blocks, looking at it: n 0
     * at its whole length from out over the rift, a little above; 1 at its head, close; 2 at its whole length from
     * further off; 3 at its tail end.
     */
    public static int look(CommandContext<CommandSourceStack> c, ServerPlayer pl, int n) {
        List<GiantSkeinEntity> all = near(c, 128);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No giant Skein within 128 blocks.")); return 0; }
        GiantSkeinEntity g = all.get(0);
        ServerLevel level = c.getSource().getLevel();
        if (!g.body().laid()) { c.getSource().sendFailure(Component.literal("Its body is not laid yet.")); return 0; }
        double[] s = new double[6];
        double along = switch (n % 4) {
            case 1 -> 1.0;
            case 3 -> 24.0;
            default -> 13.0;
        };
        g.body().sample(along, 1.0F, s, 0);
        Vec3 up = new Vec3(s[3], s[4], s[5]);
        Vec3 target = new Vec3(s[0], s[1], s[2]).add(up.scale(0.7));
        double[] t = new double[6];
        g.body().sample(along + 1.0, 1.0F, t, 0);
        Vec3 run = new Vec3(s[0] - t[0], s[1] - t[1], s[2] - t[2]);
        run = run.lengthSqr() < 1.0E-6 ? g.lookDir() : run.normalize();
        Vec3 side = run.cross(up);
        side = side.lengthSqr() < 1.0E-4 ? new Vec3(1, 0, 0) : side.normalize();
        double dist = switch (n % 4) {
            case 1 -> 7.0;
            case 2 -> 34.0;
            case 3 -> 12.0;
            default -> 22.0;
        } + 4.0 * (n / 4);
        // best seen from out over the rift and a little above it, for one on a wall; for one on a floor or a ledge, from
        // its side and above (not straight down on it); its head from a little ahead of it
        Vec3 pref = Math.abs(up.y) > 0.8 ? side.scale(0.7).add(up.scale(0.7)) : up.add(0.0, 0.3, 0.0);
        if (n % 4 == 1) pref = pref.normalize().add(run.scale(0.7));
        pref = pref.normalize();
        // what the picture is of: points along its whole length, its front or its back
        double[] arcs = switch (n % 4) {
            case 1 -> new double[]{0.4, 1.6, 2.8, 4.0, 5.2};
            case 3 -> new double[]{20.0, 22.0, 24.0, 26.0, 28.0};
            default -> new double[]{0.5, 4.5, 8.5, 12.5, 16.5, 20.5, 24.5, 28.5};
        };
        List<Vec3> marks = new java.util.ArrayList<>();
        for (double a : arcs) {
            double[] m = new double[6];
            g.body().sample(a, 1.0F, m, 0);
            marks.add(new Vec3(m[0] + m[3] * 0.7, m[1] + m[4] * 0.7, m[2] + m[5] * 0.7));
        }
        // every way round it off its rock, each near and nearer: the camera in open air (no hanging vine across its
        // lens), seeing as much of it as it can past the rift's vines, then as near the best way as it can
        List<Vec3> ways = new java.util.ArrayList<>();
        int count = 200;
        for (int i = 0; i < count; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / count, r = Math.sqrt(1.0 - y * y), a = i * 2.39996323;
            Vec3 d = new Vec3(Math.cos(a) * r, y, Math.sin(a) * r);
            if (d.dot(up) > 0.15 && d.y > -0.4) ways.add(d);
        }
        Vec3 bestEye = null;
        double bestScore = -1.0;
        int bestSeen = 0;
        for (Vec3 d : ways)
            for (double k : new double[]{1.0, 0.8, 0.65}) {
                Vec3 eye = target.add(d.scale(dist * k));
                if (!openAir(level, eye)) continue;
                int seen = 0;
                for (Vec3 m : marks) {
                    BlockHitResult hit = level.clip(new ClipContext(eye, m, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, g));
                    if (hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(m) < 1.0) seen++;
                }
                double score = seen * 4.0 + d.dot(pref) * 3.0 + k;
                if (score > bestScore) { bestScore = score; bestEye = eye; bestSeen = seen; }
            }
        if (bestEye != null && bestSeen * 2 >= marks.size()) {
            Vec3 to = target.subtract(bestEye);
            float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
            pl.teleportTo(level, bestEye.x, bestEye.y - pl.getEyeHeight(), bestEye.z, yaw, pitch);
            say(c, String.format(Locale.ROOT, "Looking at the giant at %s from %.1f %.1f %.1f, %.0f blocks off, %d of %d points of it in clear sight (%s)",
                    g.blockPosition().toShortString(), bestEye.x, bestEye.y, bestEye.z, bestEye.distanceTo(target), bestSeen, marks.size(), g.describe()));
            return 1;
        }
        // none in open air sees half of it: the first way that sees its middle past what only the eye sees through
        final Vec3 best = pref;
        ways.sort(Comparator.comparingDouble(d -> -d.dot(best)));
        for (Vec3 d : ways)
            for (double k : new double[]{1.0, 0.75, 0.55}) {
                Vec3 eye = target.add(d.scale(dist * k));
                BlockPos eb = BlockPos.containing(eye);
                if (!level.isLoaded(eb) || !level.getBlockState(eb).getCollisionShape(level, eb).isEmpty() || !level.getFluidState(eb).isEmpty()) continue;
                BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, g));
                if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(target) > 1.2) continue;
                Vec3 to = target.subtract(eye);
                float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
                pl.teleportTo(level, eye.x, eye.y - pl.getEyeHeight(), eye.z, yaw, pitch);
                say(c, String.format(Locale.ROOT, "Looking at the giant at %s from %.1f %.1f %.1f, %.0f blocks off, through vines (%s)", g.blockPosition().toShortString(),
                        eye.x, eye.y, eye.z, dist * k, g.describe()));
                return 1;
            }
        c.getSource().sendFailure(Component.literal("No clear view of the giant at " + g.blockPosition().toShortString()));
        return 0;
    }

    /** Whether a camera at {@code eye} is in open air: nothing (rock, water, a vine) in the blocks round it. */
    private static boolean openAir(ServerLevel level, Vec3 eye) {
        BlockPos c = BlockPos.containing(eye);
        if (!level.isLoaded(c)) return false;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-1, -1, -1), c.offset(1, 1, 1)))
            if (!level.getBlockState(p).getShape(level, p).isEmpty() || !level.getFluidState(p).isEmpty()) return false;
        return true;
    }
}
