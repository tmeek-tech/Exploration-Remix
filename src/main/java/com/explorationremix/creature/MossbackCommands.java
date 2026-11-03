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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.ClipContext;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * /remix mossback: looking after the Mossbacks while testing (operators only).
 * <ul>
 *     <li>{@code /remix mossback spawn [moss|frost]}: one on the floor where you look (no den)</li>
 *     <li>{@code /remix mossback list [radius]}: the Mossbacks round you and what each is doing</li>
 *     <li>{@code /remix mossback state <name> [ticks]}: sets the nearest one's state (roam, wait, chase, search...)</li>
 *     <li>{@code /remix mossback put floor|wall|ceiling}: sets the nearest one on the nearest floor, wall or ceiling
 *     within 10 blocks (heading up a wall) and freezes it there (for pictures)</li>
 *     <li>{@code /remix view mossback [n]}: puts you a few blocks from the nearest one, looking at it (for the shot
 *     driver): even n from its side, odd n from in front; 4 + n / 2 blocks away</li>
 *     <li>{@code /remix mossback freeze|unfreeze}: holds the Mossbacks within 64 blocks still where they are, in the
 *     state they are in, or lets them go on (for pictures)</li>
 *     <li>{@code /remix mossback dens [chunks]}: the dens in the loaded chunks round you, nearest first, how each stands
 *     and its family (each member's role, and where it is or was last seen)</li>
 *     <li>{@code /remix mossback den}: takes you to the nearest den in the loaded chunks within 8 chunks, and lists its family</li>
 *     <li>{@code /remix mossback away <blocks>}: puts you on open floor about that far from the nearest den, in sight of
 *     it (far enough for the den to bring out its family, near enough for them to come for you)</li>
 *     <li>{@code /remix mossback catch}: gives the nearest roamer a chicken in its jaws and sends it home with it (to try
 *     following one home)</li>
 *     <li>{@code /remix mossback foray}: sends the nearest roamer out on a foray now (a guard stays at home)</li>
 * </ul>
 */
public final class MossbackCommands {
    private MossbackCommands() {}

    /** The state names by id: the list {@link MossbackEntity#describe} reads too, so the two never drift apart. */
    private static final String[] STATES = MossbackEntity.STATE_NAMES;

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("mossback")
                .then(Commands.literal("spawn").executes(c -> spawn(c, MossbackEntity.MOSS))
                        .then(Commands.literal("moss").executes(c -> spawn(c, MossbackEntity.MOSS)))
                        .then(Commands.literal("frost").executes(c -> spawn(c, MossbackEntity.FROST))))
                .then(Commands.literal("list").executes(c -> list(c, 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("state").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(STATES, b))
                        .executes(c -> state(c, StringArgumentType.getString(c, "name"), 200))
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 12000))
                                .executes(c -> state(c, StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "ticks"))))))
                .then(Commands.literal("put").then(Commands.argument("where", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(new String[]{"floor", "wall", "ceiling"}, b))
                        .executes(c -> put(c, StringArgumentType.getString(c, "where")))))
                .then(Commands.literal("freeze").executes(c -> freeze(c, true)))
                .then(Commands.literal("unfreeze").executes(c -> freeze(c, false)))
                .then(Commands.literal("dens").executes(c -> dens(c, 6, false))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 16)).executes(c -> dens(c, IntegerArgumentType.getInteger(c, "chunks"), false))))
                .then(Commands.literal("den").executes(c -> dens(c, 8, true)))
                .then(Commands.literal("away").then(Commands.argument("blocks", IntegerArgumentType.integer(17, 90))
                        .executes(c -> away(c, IntegerArgumentType.getInteger(c, "blocks")))))
                .then(Commands.literal("catch").executes(MossbackCommands::giveCatch))
                .then(Commands.literal("foray").executes(MossbackCommands::foray));
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
    }

    private static int spawn(CommandContext<CommandSourceStack> c, byte variant) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 eye = c.getSource().getPosition();
        Vec3 look = Vec3.directionFromRotation(c.getSource().getRotation());
        if (c.getSource().getEntity() != null) eye = c.getSource().getEntity().getEyePosition();
        BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(look.scale(24)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, null));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? eye.add(look.scale(6)) : hit.getLocation().subtract(look.scale(1.2));
        MossbackEntity m = EREntities.MOSSBACK.get().create(level);
        if (m == null) return 0;
        // on the nearest open floor round that point, where its whole box fits (never half in the rock)
        BlockPos near = BlockPos.containing(at);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -5; dx <= 5; dx++)
            for (int dy = -4; dy <= 4; dy++)
                for (int dz = -5; dz <= 5; dz++) {
                    BlockPos n = near.offset(dx, dy, dz);
                    if (com.explorationremix.creature.climb.ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 2, 1, 2)
                            != com.explorationremix.creature.climb.ClimbNodeEvaluator.FLOOR) continue;
                    double d = Vec3.atLowerCornerOf(n).add(1.0, 0.0, 1.0).distanceToSqr(at);
                    if (d < bestD) { bestD = d; best = n; }
                }
        if (best != null) at = new Vec3(best.getX() + 1.0, best.getY(), best.getZ() + 1.0);
        m.moveTo(at.x, at.y, at.z, c.getSource().getRotation().y + 180.0F, 0.0F);
        if (!level.noCollision(m)) { c.getSource().sendFailure(Component.literal("No room for a Mossback near " + near.toShortString())); return 0; }
        m.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.COMMAND, null, null);
        m.setVariant(variant);
        level.addFreshEntity(m);
        say(c, "Mossback (" + (variant == MossbackEntity.FROST ? "frost" : "moss") + ") at " + BlockPos.containing(at).toShortString());
        return 1;
    }

    private static List<MossbackEntity> near(CommandContext<CommandSourceStack> c, double radius) {
        Vec3 p = c.getSource().getPosition();
        List<MossbackEntity> all = c.getSource().getLevel().getEntitiesOfClass(MossbackEntity.class, new AABB(p, p).inflate(radius));
        all.sort(Comparator.comparingDouble(e -> e.distanceToSqr(p)));
        return all;
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        List<MossbackEntity> all = near(c, radius);
        if (all.isEmpty()) { say(c, "No Mossback within " + radius + " blocks."); return 0; }
        Vec3 p = c.getSource().getPosition();
        for (MossbackEntity m : all)
            say(c, String.format(Locale.ROOT, "%s, %.0f blocks: %s", m.blockPosition().toShortString(), Math.sqrt(m.distanceToSqr(p)), m.describe()));
        return all.size();
    }

    private static int state(CommandContext<CommandSourceStack> c, String name, int ticks) {
        int s = Arrays.asList(STATES).indexOf(name.toLowerCase(Locale.ROOT));
        if (s < 0) { c.getSource().sendFailure(Component.literal("No state " + name + "; one of " + String.join(", ", STATES))); return 0; }
        List<MossbackEntity> all = near(c, 64);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Mossback within 64 blocks.")); return 0; }
        MossbackEntity m = all.get(0);
        if (s == MossbackEntity.CHASE || s == MossbackEntity.ALERT || s == MossbackEntity.SEARCH) {
            if (c.getSource().getEntity() instanceof net.minecraft.world.entity.LivingEntity me) m.setTarget(me);
        }
        m.forceState((byte) s, ticks);
        say(c, "Now: " + m.describe());
        return 1;
    }

    /** The nearest roamer of a den within 64 blocks, or null (with a message why). */
    @javax.annotation.Nullable
    private static MossbackEntity nearestRoamer(CommandContext<CommandSourceStack> c) {
        for (MossbackEntity m : near(c, 64)) if (m.isRoamer()) return m;
        c.getSource().sendFailure(Component.literal("No roamer within 64 blocks (a guard stays at home, and a stray has no den)."));
        return null;
    }

    /** Gives the nearest roamer a chicken in its jaws and sends it home with it, to follow it to its den. */
    private static int giveCatch(CommandContext<CommandSourceStack> c) {
        MossbackEntity m = nearestRoamer(c);
        if (m == null || !m.giveCatch(net.minecraft.world.entity.EntityType.CHICKEN)) return 0;
        say(c, "Carrying a chicken home: " + m.describe());
        return 1;
    }

    /** Sends the nearest roamer out on a foray now. */
    private static int foray(CommandContext<CommandSourceStack> c) {
        MossbackEntity m = nearestRoamer(c);
        if (m == null || !m.sendOut()) return 0;
        say(c, "Out on a foray: " + m.describe());
        return 1;
    }

    /** Puts the player on open floor about {@code blocks} from the nearest loaded den, in sight of it. */
    private static int away(CommandContext<CommandSourceStack> c, int blocks) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer pl = c.getSource().getPlayerOrException();
        ServerLevel level = c.getSource().getLevel();
        Vec3 p = c.getSource().getPosition();
        ChunkPos centre = new ChunkPos(BlockPos.containing(p));
        BlockPos den = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++)
            for (int dz = -8; dz <= 8; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values())
                    if (be instanceof MossbackDen.Tile && be.getBlockPos().distToCenterSqr(p) < bestD) { bestD = be.getBlockPos().distToCenterSqr(p); den = be.getBlockPos(); }
            }
        if (den == null) { c.getSource().sendFailure(Component.literal("No den in the loaded chunks within 8 chunks.")); return 0; }
        Vec3 seen = Vec3.atCenterOf(den).add(0, 0.6, 0);
        // open floor in sight of the den first, then any open floor, then the air above it
        for (int pass = 0; pass < 2; pass++)
        for (int ring = 0; ring < 6; ring++)
            for (int k = 0; k < 24; k++) {
                double a = Math.toRadians(15 * k), r = blocks + ring * 2;
                int x = den.getX() + (int) Math.round(Math.cos(a) * r), z = den.getZ() + (int) Math.round(Math.sin(a) * r);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int dy = -8; dy <= 8; dy++) {
                    BlockPos f = new BlockPos(x, den.getY() + dy, z);
                    if (!level.getBlockState(f.below()).isFaceSturdy(level, f.below(), Direction.UP)) continue;
                    if (!level.getBlockState(f).getCollisionShape(level, f).isEmpty() || !level.getBlockState(f.above()).getCollisionShape(level, f.above()).isEmpty()) continue;
                    if (!level.getFluidState(f).isEmpty()) continue;
                    Vec3 eye = new Vec3(x + 0.5, f.getY() + 1.62, z + 0.5);
                    if (pass == 0 && level.clip(new ClipContext(eye, seen, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, pl)).getType() != HitResult.Type.MISS) continue;
                    Vec3 to = seen.subtract(eye);
                    float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
                    pl.teleportTo(level, x + 0.5, f.getY(), z + 0.5, yaw, 10.0F);
                    say(c, "Away from the den at " + den.toShortString() + ": at " + f.toShortString());
                    return 1;
                }
            }
        pl.teleportTo(level, den.getX() + 0.5, den.getY() + blocks, den.getZ() + 0.5, pl.getYRot(), 90.0F);
        say(c, "Away from the den at " + den.toShortString() + ": no open floor near, so " + blocks + " blocks above it");
        return 1;
    }

    /** Sets the nearest Mossback on the nearest floor, wall or ceiling node near it and freezes it there. */
    private static int put(CommandContext<CommandSourceStack> c, String where) {
        List<MossbackEntity> all = near(c, 64);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Mossback within 64 blocks.")); return 0; }
        MossbackEntity m = all.get(0);
        byte want = switch (where) {
            case "wall" -> com.explorationremix.creature.climb.ClimbNodeEvaluator.WALL;
            case "ceiling" -> com.explorationremix.creature.climb.ClimbNodeEvaluator.CEILING;
            default -> com.explorationremix.creature.climb.ClimbNodeEvaluator.FLOOR;
        };
        ServerLevel level = c.getSource().getLevel();
        BlockPos at = m.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -10; dx <= 10; dx++)
            for (int dy = -6; dy <= 6; dy++)
                for (int dz = -10; dz <= 10; dz++) {
                    BlockPos n = at.offset(dx, dy, dz);
                    if (!level.isLoaded(n)) continue;
                    if (com.explorationremix.creature.climb.ClimbNodeEvaluator.classify(level, n.getX(), n.getY(), n.getZ(), 2, 1, 2) != want) continue;
                    double d = n.distSqr(at);
                    if (d < bestD) { bestD = d; best = n; }
                }
        if (best == null) { c.getSource().sendFailure(Component.literal("No " + where + " within 10 blocks of it.")); return 0; }
        // which side the rock is on, and where the box sits against it
        Direction rock = Direction.DOWN;
        double x = best.getX() + 1.0, y = best.getY(), z = best.getZ() + 1.0;
        if (want == com.explorationremix.creature.climb.ClimbNodeEvaluator.CEILING) {
            rock = Direction.UP;
            y = best.getY() + 1.0 - m.getBbHeight() - 0.01;
        } else if (want == com.explorationremix.creature.climb.ClimbNodeEvaluator.WALL) {
            double half = m.getBbWidth() / 2.0;
            rock = rockSide(level, best);
            switch (rock) {
                case WEST -> x = best.getX() + half + 0.01;
                case EAST -> x = best.getX() + 2.0 - half - 0.01;
                case NORTH -> z = best.getZ() + half + 0.01;
                default -> z = best.getZ() + 2.0 - half - 0.01;
            }
            y = best.getY() + 0.05;
        }
        Vec3 fwd = rock == Direction.DOWN || rock == Direction.UP ? Vec3.directionFromRotation(0, m.getYRot()) : new Vec3(0, 1, 0);
        m.pose(new Vec3(x, y, z), rock, fwd);
        say(c, "Put it on the " + where + " at " + BlockPos.containing(x, y, z).toShortString() + " (rock " + rock.getName() + "), frozen");
        return 1;
    }

    private static Direction rockSide(ServerLevel level, BlockPos n) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            int ox = d == Direction.WEST ? -1 : d == Direction.EAST ? 2 : 0;
            int oz = d == Direction.NORTH ? -1 : d == Direction.SOUTH ? 2 : 0;
            for (int i = 0; i < 2; i++) {
                BlockPos q = new BlockPos(n.getX() + (ox != 0 ? ox : i), n.getY(), n.getZ() + (oz != 0 ? oz : i));
                if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return d;
            }
        }
        return Direction.SOUTH;
    }

    /** Puts the player a few blocks from the nearest Mossback, in open air with a clear view, looking at it. */
    public static int look(CommandContext<CommandSourceStack> c, net.minecraft.server.level.ServerPlayer pl, int n) {
        List<MossbackEntity> all = near(c, 96);
        if (all.isEmpty()) { c.getSource().sendFailure(Component.literal("No Mossback within 96 blocks.")); return 0; }
        MossbackEntity m = all.get(0);
        ServerLevel level = c.getSource().getLevel();
        Vec3 body = m.bodyCentre();
        Vec3 up = m.up(), fwd = m.lookDir();
        Vec3 side = fwd.cross(up);
        if (side.lengthSqr() < 1.0E-4) side = new Vec3(1, 0, 0);
        side = side.normalize();
        double dist = 4.0 + n / 2;
        boolean front = n % 2 == 1;
        java.util.List<Vec3> dirs = new java.util.ArrayList<>();
        // side (or front) first, a little above its back; then round about it
        Vec3 main = front ? fwd.scale(0.8).add(side.scale(0.6)) : side;
        for (double lift : new double[]{0.45, 0.25, 0.7, 0.0})
            for (int k = 0; k < 12; k++) {
                double a = Math.toRadians(30 * ((k + 1) / 2) * (k % 2 == 0 ? 1 : -1));
                Vec3 d = rotate(main.normalize(), up, a).add(up.scale(lift));
                dirs.add(d.normalize());
            }
        for (Vec3 d : dirs) {
            Vec3 eye = body.add(d.scale(dist));
            BlockPos eb = BlockPos.containing(eye);
            if (!level.getBlockState(eb).getCollisionShape(level, eb).isEmpty()) continue;
            BlockHitResult hit = level.clip(new ClipContext(eye, body, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, m));
            if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceTo(body) > 0.9) continue;
            Vec3 to = body.subtract(eye);
            float yaw = (float) (Math.toDegrees(Math.atan2(to.z, to.x)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)));
            pl.teleportTo(level, eye.x, eye.y - pl.getEyeHeight(), eye.z, yaw, pitch);
            say(c, String.format(Locale.ROOT, "Looking at the Mossback at %s from %.1f %.1f %.1f (%s)", m.blockPosition().toShortString(), eye.x, eye.y, eye.z, m.describe()));
            return 1;
        }
        c.getSource().sendFailure(Component.literal("No clear view of the Mossback at " + m.blockPosition().toShortString()));
        return 0;
    }

    /** Rotates v about the unit axis k by angle a (Rodrigues). */
    private static Vec3 rotate(Vec3 v, Vec3 k, double a) {
        double cos = Math.cos(a), sin = Math.sin(a);
        return v.scale(cos).add(k.cross(v).scale(sin)).add(k.scale(k.dot(v) * (1 - cos)));
    }

    private static int freeze(CommandContext<CommandSourceStack> c, boolean on) {
        List<MossbackEntity> all = near(c, 64);
        for (MossbackEntity m : all) m.setFrozen(on);
        say(c, (on ? "Froze " : "Let go of ") + all.size() + " Mossback" + (all.size() == 1 ? "" : "s"));
        return all.size();
    }

    private static int dens(CommandContext<CommandSourceStack> c, int radius, boolean go) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 p = c.getSource().getPosition();
        ChunkPos centre = new ChunkPos(BlockPos.containing(p));
        record Found(BlockPos pos, MossbackDen.Tile tile) {}
        java.util.ArrayList<Found> found = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(centre.x + dx, centre.z + dz);   // loaded chunks only
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values())
                    if (be instanceof MossbackDen.Tile t) found.add(new Found(be.getBlockPos(), t));
            }
        found.sort(Comparator.comparingDouble(f -> f.pos.distToCenterSqr(p)));
        if (found.isEmpty()) { say(c, "No den within " + radius + " chunks."); return 0; }
        long now = level.getGameTime();
        if (go) {
            Found f = found.get(0);
            BlockPos at = f.pos;
            if (c.getSource().getEntity() != null)
                c.getSource().getEntity().teleportTo(at.getX() + 0.5, at.getY() + 0.1, at.getZ() + 0.5);
            say(c, "Den at " + at.toShortString() + " (" + level.getBiome(at).unwrapKey().map(k -> k.location().getPath()).orElse("?") + "): " + f.tile.describe(level, now));
            for (String line : f.tile.describeMembers(level)) say(c, "  " + line);
            return 1;
        }
        for (Found f : found) {
            say(c, String.format(Locale.ROOT, "%s, %.0f blocks, %s: %s", f.pos.toShortString(), Math.sqrt(f.pos.distToCenterSqr(p)),
                    level.getBiome(f.pos).unwrapKey().map(k -> k.location().getPath()).orElse("?"), f.tile.describe(level, now)));
            for (String line : f.tile.describeMembers(level)) say(c, "  " + line);
        }
        return found.size();
    }
}
