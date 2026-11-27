package com.explorationremix.creature;

import com.explorationremix.block.Neon;
import com.explorationremix.creature.body.LurkerRig;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.ChimneyLurkersFeature;
import com.explorationremix.world.terrain.GardensField;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * /remix lurker: looking after the Deep Gardens' chimney spiders (the vent urchins of 0.21.3 to 0.23.3; the chimney lurkers' id kept) while testing (operators only).
 * <ul>
 *     <li>{@code /remix lurker spawn [colour]}: one where you look; it takes the nearest live vent with no lurker of its
 *     own and goes home to it (or, with none near, lurks where it stands)</li>
 *     <li>{@code /remix lurker list [radius]}: the lurkers round you, nearest first: where, colour, what each is doing,
 *     its vent</li>
 *     <li>{@code /remix lurker vents [radius]}: the live vents round you, nearest first: which hold a lurker, whether it
 *     is there now, a death on record; and the counts of those set down with the world and moved in since</li>
 *     <li>{@code /remix lurker tp [n] [distance]}: to the nth nearest vent that holds a lurker (from 1), standing that
 *     far off its axis (12 blocks, or the distance given) on its floor, facing its rim</li>
 *     <li>{@code /remix lurker inside}: down inside the nearest spider's vent, on its chamber's floor a little off its
 *     flue's foot, looking up its flue at it (as someone come in through a crack)</li>
 *     <li>{@code /remix lurker place}: a lurker into the nearest vent that holds one and has none now (as the refill
 *     would, seen or not)</li>
 *     <li>{@code /remix lurker hunt [target]}: the nearest lurker notices you (or the target: any creature, for tests
 *     with no player in reach) and comes for it now</li>
 *     <li>{@code /remix lurker home}: the lurkers within 48 blocks give up and go home</li>
 *     <li>{@code /remix lurker strike}: the nearest waiting lurker strikes at a stilt mantis it can see by its vent</li>
 *     <li>{@code /remix lurker refill}: every empty vent round you that holds a lurker gets one now (out of sight only)</li>
 * </ul>
 */
public final class LurkerCommands {
    private LurkerCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("lurker")
                .then(Commands.literal("spawn").executes(c -> spawn(c, null))
                        .then(Commands.argument("colour", StringArgumentType.word()).executes(c -> spawn(c, StringArgumentType.getString(c, "colour")))))
                .then(Commands.literal("list").executes(c -> list(c, 64))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 256)).executes(c -> list(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("vents").executes(c -> vents(c, 160))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(16, 512)).executes(c -> vents(c, IntegerArgumentType.getInteger(c, "radius")))))
                .then(Commands.literal("tp").executes(c -> tp(c, 1, 12))
                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 64)).executes(c -> tp(c, IntegerArgumentType.getInteger(c, "n"), 12))
                                .then(Commands.argument("distance", IntegerArgumentType.integer(3, 40))
                                        .executes(c -> tp(c, IntegerArgumentType.getInteger(c, "n"), IntegerArgumentType.getInteger(c, "distance"))))))
                .then(Commands.literal("inside").executes(LurkerCommands::inside))
                .then(Commands.literal("place").executes(LurkerCommands::place))
                .then(Commands.literal("hunt").executes(c -> hunt(c, null))
                        .then(Commands.argument("target", EntityArgument.entity()).executes(c -> hunt(c, EntityArgument.getEntity(c, "target")))))
                .then(Commands.literal("home").executes(LurkerCommands::home))
                .then(Commands.literal("strike").executes(LurkerCommands::strike))
                .then(Commands.literal("refill").executes(LurkerCommands::refill))
                .then(Commands.literal("sides").executes(LurkerCommands::sides))
                .then(Commands.literal("trial").executes(LurkerCommands::trial))
                .then(Commands.literal("sight").executes(LurkerCommands::sight));
    }

    @Nullable
    private static Terrain terrain(CommandSourceStack src) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.gardens == null) {
            src.sendFailure(Component.literal("The deep world is not in this level"));
            return null;
        }
        return t;
    }

    private static int spawn(CommandContext<CommandSourceStack> c, @Nullable String colour) {
        CommandSourceStack src = c.getSource();
        ServerLevel level = src.getLevel();
        Vec3 eye = src.getPosition().add(0, 1.6, 0);
        Vec3 look = src.getEntity() != null ? src.getEntity().getLookAngle() : new Vec3(0, 0, 1);
        Vec3 to = eye.add(look.scale(6.0));
        HitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, src.getEntity()));
        Vec3 at = hit.getType() == HitResult.Type.MISS ? to : hit.getLocation().subtract(look.scale(1.2));
        ChimneyLurkerEntity e = EREntities.CHIMNEY_LURKER.get().create(level);
        if (e == null) return 0;
        float yaw = (float) Math.toDegrees(Math.atan2(-look.x, look.z)) + 180.0F;
        e.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        e.setYBodyRot(yaw);
        e.setYHeadRot(yaw);
        e.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.COMMAND, null, null);
        if (colour != null) {
            Neon n = null;
            for (Neon k : Neon.values()) if (k.getSerializedName().equals(colour.toLowerCase(Locale.ROOT))) n = k;
            if (n == null) {
                src.sendFailure(Component.literal("Colours: magenta, violet, lime, light_blue"));
                return 0;
            }
            e.setColour(n);
        }
        level.addFreshEntity(e);
        src.sendSuccess(() -> Component.literal("A chimney spider at " + BlockPos.containing(at).toShortString() + ": " + e.describe()), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        Vec3 p = src.getPosition();
        List<ChimneyLurkerEntity> all = new ArrayList<>(src.getLevel().getEntitiesOfClass(ChimneyLurkerEntity.class, new AABB(p, p).inflate(radius),
                e -> e.isAlive() && e.position().distanceTo(p) <= radius));
        all.sort(Comparator.comparingDouble(e -> e.position().distanceToSqr(p)));
        StringBuilder b = new StringBuilder(all.size() + " chimney spider(s) within " + radius + " blocks");
        for (ChimneyLurkerEntity e : all.subList(0, Math.min(all.size(), 16)))
            b.append(String.format(Locale.ROOT, "\n %.1f %.1f %.1f (%d off): %s", e.getX(), e.getY(), e.getZ(),
                    (int) e.position().distanceTo(p), e.describe()));
        src.sendSuccess(() -> Component.literal(b.toString()), false);
        return all.size();
    }

    private record Found(GardensField.LiveVent vent, double top, double dist) {}

    /** The live vents within {@code radius} of p (across), nearest first. */
    private static List<Found> near(Terrain t, Vec3 p, int radius) {
        int bottom = t.shape.bottom(Terrain.GARDENS);
        List<Found> out = new ArrayList<>();
        int r = (int) Math.ceil(radius / (double) LurkerRefill.CELL);
        int pcx = Math.floorDiv((int) Math.floor(p.x), LurkerRefill.CELL), pcz = Math.floorDiv((int) Math.floor(p.z), LurkerRefill.CELL);
        for (int cx = pcx - r; cx <= pcx + r; cx++)
            for (int cz = pcz - r; cz <= pcz + r; cz++)
                for (GardensField.LiveVent v : LurkerRefill.vents(t, cx, cz)) {
                    double d = Math.hypot(v.x() - p.x, v.z() - p.z);
                    if (d <= radius) out.add(new Found(v, v.topRow() + bottom, d));
                }
        out.sort(Comparator.comparingDouble(Found::dist));
        return out;
    }

    /**
     * Finds the vents with {@code near} and hands them to {@code then} on the server thread, off it first (a fresh tile
     * of the vent field takes seconds to make, and a command that waited for it would hold the whole game).
     */
    private static void whenFound(CommandSourceStack src, Terrain t, Vec3 p, int radius, Consumer<List<Found>> then) {
        MinecraftServer server = src.getServer();
        CompletableFuture.supplyAsync(() -> near(t, p, radius), Util.backgroundExecutor()).whenComplete((found, err) -> server.execute(() -> {
            if (err != null) src.sendFailure(Component.literal("Could not work out the vents: " + err.getMessage()));
            else then.accept(found);
        }));
    }

    private static int vents(CommandContext<CommandSourceStack> c, int radius) {
        CommandSourceStack src = c.getSource();
        Terrain t = terrain(src);
        if (t == null) return 0;
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        whenFound(src, t, p, radius, found -> {
            int held = 0, there = 0;
            StringBuilder lines = new StringBuilder();
            int shown = 0;
            for (Found f : found) {
                GardensField.LiveVent v = f.vent;
                boolean h = LurkerVents.held(v);
                ChimneyLurkerEntity e = h ? LurkerRefill.lurkerOf(level, v.key(), v.x(), f.top, v.z()) : null;
                if (h) held++;
                if (e != null) there++;
                if (shown++ >= 14) continue;
                Long died = LurkerRefill.diedAt(level, v.key());
                String state = !h ? (v.flue() < LurkerVents.MIN_FLUE ? "its mouth too narrow for one" : v.flue() > LurkerVents.MAX_FLUE ? "its mouth too wide for one" : "holds none")
                        : e != null ? "its spider: " + e.describe()
                        : died != null ? String.format(Locale.ROOT, "empty, its spider killed %.1f days ago", (level.getGameTime() - died) / 24000.0)
                        : "empty (none set down yet, or out of reach)";
                lines.append(String.format(Locale.ROOT, "\n %.0f %.0f, rim %.0f (%d off): %s, flue %.1f from its axis, %d crack(s); %s", v.x(), v.z(), f.top, (int) f.dist,
                        Neon.ofCode(v.colour()).getSerializedName(), v.flue(), v.cracks().length, state));
            }
            String head = String.format(Locale.ROOT, "%d live vent(s) within %d blocks, %d hold a spider, %d of them there now (loaded)",
                    found.size(), radius, held, there);
            String tail = String.format(Locale.ROOT, "\nSet down with the world since the game started: %d held vents met, %d spiders. Refill: %d looks at empty vents, %d moved in.",
                    ChimneyLurkersFeature.VENTS.get(), ChimneyLurkersFeature.PLACED.get(), LurkerRefill.LOOKS, LurkerRefill.MOVED_IN);
            src.sendSuccess(() -> Component.literal(head + lines + tail), false);
        });
        return 1;
    }

    /** The nearest live vent's seat (within 64) and each side of it, and why each will or will not do for a spider's way down, from the loaded blocks. */
    private static int sides(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        Terrain t = terrain(src);
        if (t == null) return 0;
        ServerLevel level = src.getLevel();
        int bottom = t.shape.bottom(Terrain.GARDENS);
        whenFound(src, t, src.getPosition(), 64, found -> {
            if (found.isEmpty()) {
                src.sendFailure(Component.literal("No live vent within 64 blocks"));
                return;
            }
            GardensField.LiveVent v = found.get(0).vent;
            String b = LurkerVents.sides(level, v, bottom, (x, z) -> level.hasChunk(x >> 4, z >> 4));
            src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "The %s vent at %.1f %.1f (held %s):", Neon.ofCode(v.colour()).getSerializedName(),
                    v.x(), v.z(), LurkerVents.held(v)) + b), false);
        });
        return 1;
    }

    /** The nearest spider's way down and back from over its rim on its side, run as trials over the loaded blocks. */
    private static int trial(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ChimneyLurkerEntity e = nearest(src, 64, false);
        if (e == null || e.home() == null) {
            src.sendFailure(Component.literal("No chimney spider with a vent within 64 blocks"));
            return 0;
        }
        LurkerVents.Home h = e.home();
        String r = LurkerVents.trial(src.getLevel(), h);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Its seat %.1f %.1f %.1f (rim %.0f, mouth %.1f): %s", h.waitX(), h.waitY(), h.waitZ(), h.top(),
                h.mouth(), r)), false);
        return 1;
    }

    /** How the nearest lurker waiting in its vent sees each player near: from over its rim, and from its rim's edge. */
    private static int sight(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ChimneyLurkerEntity e = nearest(src, 64, false);
        if (e == null || e.home() == null) {
            src.sendFailure(Component.literal("No chimney spider with a vent within 64 blocks"));
            return 0;
        }
        StringBuilder b = new StringBuilder("How it sees the players within 64 blocks:");
        int n = 0;
        for (ServerPlayer p : src.getLevel().players()) {
            if (p.distanceToSqr(e) > 64.0 * 64.0) continue;
            b.append("\n ").append(e.sightReport(p));
            n++;
        }
        if (n == 0) b.append(" none");
        String r = b.toString();
        src.sendSuccess(() -> Component.literal(r), false);
        return 1;
    }

    private static int tp(CommandContext<CommandSourceStack> c, int n, int distance) {
        CommandSourceStack src = c.getSource();
        Terrain t = terrain(src);
        if (t == null || !(src.getEntity() instanceof ServerPlayer player)) return 0;
        ServerLevel level = src.getLevel();
        Vec3 p = src.getPosition();
        whenFound(src, t, p, 384, found -> {
            found.removeIf(f -> !LurkerVents.held(f.vent));
            if (found.size() < n) {
                src.sendFailure(Component.literal("Only " + found.size() + " vent(s) holding a spider within 384 blocks"));
                return;
            }
            if (player.hasDisconnected() || player.level() != level) return;
            standOff(src, player, level, found.get(n - 1), distance);
        });
        return 1;
    }

    /** Puts the player on the floor {@code distance} off the vent's axis, facing its rim: the first way round with a clear view of it. */
    private static void standOff(CommandSourceStack src, ServerPlayer player, ServerLevel level, Found f, int distance) {
        GardensField.LiveVent v = f.vent;
        double a0 = (v.key() & 0xFF) / 256.0 * Math.PI * 2.0;
        Vec3 rim = new Vec3(v.x(), f.top + 0.5, v.z());
        BlockPos spot = null;
        for (int k = 0; k < 24; k++) {
            double a = a0 + k * Math.PI * 2.0 / 24.0;
            int x = (int) Math.floor(v.x() + Math.cos(a) * distance), z = (int) Math.floor(v.z() + Math.sin(a) * distance);
            level.getChunk(x >> 4, z >> 4);
            BlockPos at = floor(level, x, (int) Math.floor(f.top), z, 40);
            if (at == null) continue;
            if (spot == null) spot = at;
            Vec3 eye = new Vec3(at.getX() + 0.5, at.getY() + 1.62, at.getZ() + 0.5);
            if (level.clip(new ClipContext(eye, rim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() == HitResult.Type.MISS) {
                spot = at;
                break;
            }
        }
        if (spot == null) {
            src.sendFailure(Component.literal(String.format(Locale.ROOT, "No floor round the vent at %.0f %.0f", v.x(), v.z())));
            return;
        }
        BlockPos at = spot;
        double dx = v.x() - (at.getX() + 0.5), dz = v.z() - (at.getZ() + 0.5), dy = f.top + 0.5 - (at.getY() + 1.62);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, pitch);
        src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "At the %s vent at %.0f %.0f (rim %.0f), from %s",
                Neon.ofCode(v.colour()).getSerializedName(), v.x(), v.z(), f.top, at.toShortString())), true);
    }

    /**
     * Down inside the nearest spider's vent (0.21.4): on its chamber's floor two to four blocks off its flue's foot, in
     * sight of its underside, looking up its flue at it.
     */
    private static int inside(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) return 0;
        ChimneyLurkerEntity e = nearest(src, 64, false);
        LurkerVents.Home h = e == null ? null : e.home();
        if (h == null) {
            src.sendFailure(Component.literal("No chimney spider with a vent within 64 blocks"));
            return 0;
        }
        ServerLevel level = src.getLevel();
        List<Vector3d> way = LurkerVents.flueWay(level, h);
        if (way == null) {
            src.sendFailure(Component.literal("No way down its flue: " + e.describe()));
            return 0;
        }
        Vector3d foot = way.get(way.size() - 1);
        int fy = (int) Math.floor(foot.y - LurkerRig.shared().spec.ride);
        Vec3 under = new Vec3(h.waitX(), h.waitY() - LurkerRig.shared().spec.halfHeight * 0.9, h.waitZ());
        for (double r : new double[]{2.5, 3.0, 2.0, 3.5, 4.0}) {
            for (int k = 0; k < 16; k++) {
                double a = k * Math.PI / 8.0;
                BlockPos at = floor(level, (int) Math.floor(foot.x + Math.cos(a) * r), fy + 1, (int) Math.floor(foot.z + Math.sin(a) * r), 4);
                if (at == null) continue;
                Vec3 eye = new Vec3(at.getX() + 0.5, at.getY() + 1.62, at.getZ() + 0.5);
                if (level.clip(new ClipContext(eye, under, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player)).getType() != HitResult.Type.MISS) continue;
                double dx = under.x - eye.x, dz = under.z - eye.z, dy = under.y - eye.y;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, pitch);
                double depth = h.waitY() - foot.y;
                src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "Inside its vent at %s, its flue %.1f deep over you: %s", at.toShortString(), depth,
                        e.describe())), true);
                return 1;
            }
        }
        src.sendFailure(Component.literal(String.format(Locale.ROOT, "No floor in sight of it inside its vent (its flue's foot at %.1f %.1f %.1f)", foot.x, foot.y, foot.z)));
        return 0;
    }

    /** The highest floor (an open block with another over it, on a solid one) at or under {@code y}, within {@code depth} rows. */
    @Nullable
    private static BlockPos floor(ServerLevel level, int x, int y, int z, int depth) {
        BlockPos.MutableBlockPos q = new BlockPos.MutableBlockPos();
        for (int yy = y; yy > y - depth; yy--) {
            if (!level.getBlockState(q.set(x, yy - 1, z)).isCollisionShapeFullBlock(level, q)) continue;
            if (!level.getBlockState(q.set(x, yy, z)).getCollisionShape(level, q).isEmpty()) continue;
            if (!level.getBlockState(q.set(x, yy + 1, z)).getCollisionShape(level, q).isEmpty()) continue;
            return new BlockPos(x, yy, z);
        }
        return null;
    }

    private static int place(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        Terrain t = terrain(src);
        if (t == null) return 0;
        ServerLevel level = src.getLevel();
        int bottom = t.shape.bottom(Terrain.GARDENS);
        whenFound(src, t, src.getPosition(), 192, found -> {
            for (Found f : found) {
                GardensField.LiveVent v = f.vent;
                if (!LurkerVents.held(v) || LurkerRefill.lurkerOf(level, v.key(), v.x(), f.top, v.z()) != null) continue;
                LurkerVents.Home h = LurkerVents.find(level, v, bottom, (x, z) -> level.hasChunk(x >> 4, z >> 4));
                if (h == null) continue;
                ChimneyLurkerEntity e = EREntities.CHIMNEY_LURKER.get().create(level);
                if (e == null) return;
                e.settleAt(h);
                e.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(h.waitX(), h.waitY(), h.waitZ())), MobSpawnType.COMMAND, null, null);
                level.addFreshEntity(e);
                src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "A spider into the vent at %.0f %.0f (%d off), waiting at %.1f %.1f %.1f",
                        v.x(), v.z(), (int) f.dist, h.waitX(), h.waitY(), h.waitZ())), true);
                return;
            }
            src.sendFailure(Component.literal("No empty vent that holds a spider within 192 blocks (with its chunks loaded)"));
        });
        return 1;
    }

    @Nullable
    private static ChimneyLurkerEntity nearest(CommandSourceStack src, double radius, boolean waitingOnly) {
        Vec3 p = src.getPosition();
        ChimneyLurkerEntity best = null;
        for (ChimneyLurkerEntity e : src.getLevel().getEntitiesOfClass(ChimneyLurkerEntity.class, new AABB(p, p).inflate(radius))) {
            if (!e.isAlive()) continue;
            if (waitingOnly && e.state() != ChimneyLurkerEntity.WAIT && e.state() != ChimneyLurkerEntity.STIR) continue;
            if (best == null || e.position().distanceToSqr(p) < best.position().distanceToSqr(p)) best = e;
        }
        return best;
    }

    private static int hunt(CommandContext<CommandSourceStack> c, @Nullable Entity target) throws CommandSyntaxException {
        CommandSourceStack src = c.getSource();
        Entity t = target != null ? target : src.getEntityOrException();
        if (!(t instanceof LivingEntity le)) {
            src.sendFailure(Component.literal("It hunts only living things"));
            return 0;
        }
        ChimneyLurkerEntity e = nearest(src, 64, false);
        if (e == null) {
            src.sendFailure(Component.literal("No chimney spider within 64 blocks"));
            return 0;
        }
        e.huntNow(le);
        src.sendSuccess(() -> Component.literal("It comes for " + le.getName().getString() + ": " + e.describe()), true);
        return 1;
    }

    private static int home(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        Vec3 p = src.getPosition();
        int n = 0;
        for (ChimneyLurkerEntity e : src.getLevel().getEntitiesOfClass(ChimneyLurkerEntity.class, new AABB(p, p).inflate(48))) {
            if (!e.isAlive()) continue;
            e.sendHome();
            n++;
        }
        int count = n;
        src.sendSuccess(() -> Component.literal(count + " chimney spider(s) going home"), true);
        return n;
    }

    private static int strike(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        ChimneyLurkerEntity e = nearest(src, 64, true);
        if (e == null) {
            src.sendFailure(Component.literal("No chimney spider waiting in its vent within 64 blocks"));
            return 0;
        }
        if (!e.strikeNow()) {
            src.sendFailure(Component.literal("It sees no stilt mantis by its vent: " + e.describe()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("It strikes at a stilt mantis: " + e.describe()), true);
        return 1;
    }

    private static int refill(CommandContext<CommandSourceStack> c) {
        CommandSourceStack src = c.getSource();
        if (!(src.getEntity() instanceof ServerPlayer p)) return 0;
        int in = LurkerRefill.lookRound(src.getLevel(), p, true);
        src.sendSuccess(() -> Component.literal(in + " spider(s) moved into empty vents 48 to 128 blocks off, out of sight"), false);
        return in;
    }
}
