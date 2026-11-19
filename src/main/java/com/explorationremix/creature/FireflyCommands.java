package com.explorationremix.creature;

import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.feature.FirefliesFeature;
import com.explorationremix.world.terrain.Terrain;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * /remix fireflies: looking after the blue firefly swarms while testing (operators only).
 * <ul>
 *     <li>{@code /remix fireflies spawn [count]}: a swarm in the open air where you look (count 1 to 160; without
 *     one, a size as the spawner picks them: 30 to 60, now and then 120 to 160)</li>
 *     <li>{@code /remix fireflies list [radius]}: the swarms round you, nearest first: where each is, flying or landed
 *     (and on what), how many fireflies it has of how many it grows back to</li>
 *     <li>{@code /remix fireflies land [seconds]}: the nearest swarm settles at once on the best patch of rock within a
 *     few blocks, for its longest stay (40 s, or the seconds given, or until a player comes near enough to startle
 *     it), wherever the players are</li>
 *     <li>{@code /remix fireflies scatter}: the nearest swarm scatters away from you, as if struck</li>
 *     <li>{@code /remix fireflies startle}: the nearest landed swarm is startled up by you, as if you had walked up to
 *     it: it lifts off in a wave from your side with a flutter, flies off and lands again a little way off</li>
 *     <li>{@code /remix fireflies homes}: the swarms set down with the world since the game started (0.19.2), those in
 *     the loaded world now (kept for good, landed, resting with no player near), and the spawner's refill</li>
 *     <li>{@code /remix fireflies density [radius] [samples]}: how many swarms a player would have round them: at
 *     random spots a player could stand on in the Blue Reaches round you (in loaded chunks, with their neighbours'
 *     entities loaded), the swarms within the spawner's radius of each, by place, against what the caves keep</li>
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
                .then(Commands.literal("land").executes(c -> land(c, 0))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                                .executes(c -> land(c, IntegerArgumentType.getInteger(c, "seconds") * 20))))
                .then(Commands.literal("scatter").executes(FireflyCommands::scatter))
                .then(Commands.literal("startle").executes(FireflyCommands::startle))
                .then(Commands.literal("homes").executes(FireflyCommands::homes))
                .then(Commands.literal("density").executes(c -> density(c, 160, 600))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(48, 1024))
                                .executes(c -> density(c, IntegerArgumentType.getInteger(c, "radius"), 600))
                                .then(Commands.argument("samples", IntegerArgumentType.integer(10, 20000))
                                        .executes(c -> density(c, IntegerArgumentType.getInteger(c, "radius"), IntegerArgumentType.getInteger(c, "samples"))))));
    }

    /** The swarms set down with the world, those loaded now, and the refill (see the class's list). */
    private static int homes(CommandContext<CommandSourceStack> c) {
        ServerLevel level = c.getSource().getLevel();
        int loaded = 0, kept = 0, landed = 0, resting = 0;
        int[] at = new int[BlueSpawnRules.Place.values().length], drawnAt = new int[at.length];
        for (var e : level.getAllEntities()) {
            if (!(e instanceof FireflySwarm s) || !s.isAlive()) continue;
            loaded++;
            if (s.kept()) kept++;
            if (s.landed()) landed++;
            if (s.resting()) resting++;
            if (s.home() == null) continue;
            BlockPos h = BlockPos.containing(s.home());
            // only where the rock round it is loaded (reading past it would make the chunks there)
            int reach = FireflyHomes.DRAW_REACH;
            if (!level.hasChunksAt(h.getX() - reach, h.getZ() - reach, h.getX() + reach, h.getZ() + reach)) continue;
            int place = BlueReachesSpawner.placeAt(level, h).ordinal();
            at[place]++;
            if (FirefliesFeature.drawn(level, h)) drawnAt[place]++;
        }
        long chunks = FirefliesFeature.CHUNKS.get(), placed = FirefliesFeature.PLACED.get(), drawn = FirefliesFeature.DRAWN.get();
        say(c, String.format(Locale.ROOT, "Chunks made since the game started: %d, swarms set down in them: %d (%.2f a chunk, %.0f%% by water or glowbells).",
                chunks, placed, chunks == 0 ? 0.0 : placed / (double) chunks, placed == 0 ? 0.0 : 100.0 * drawn / placed));
        long looked = FirefliesFeature.LOOKED.get(), roomy = FirefliesFeature.ROOMY.get(), perchless = FirefliesFeature.NO_PERCH.get();
        say(c, String.format(Locale.ROOT, "Tries whose roll could make a home: %d, %d of them with room (%.0f%%); %d homes found no rock in reach.",
                looked, roomy, looked == 0 ? 0.0 : 100.0 * roomy / looked, perchless));
        say(c, String.format(Locale.ROOT, "Loaded now: %d swarms, %d kept for good, %d landed, %d resting (no player within %d blocks).",
                loaded, kept, landed, resting, (int) FireflySwarm.REST_RANGE));
        StringBuilder b = new StringBuilder("Their homes by place:");
        for (BlueSpawnRules.Place p : BlueSpawnRules.Place.values())
            if (at[p.ordinal()] > 0) b.append(String.format(Locale.ROOT, " %s %d (%.0f%% by water or glowbells)", p.name().toLowerCase(Locale.ROOT),
                    at[p.ordinal()], 100.0 * drawnAt[p.ordinal()] / at[p.ordinal()]));
        say(c, b.toString());
        say(c, String.format(Locale.ROOT, "Refill: %d looks round a player with fewer swarms near than the caves keep, %d rolls came up, %d swarms came out.",
                BlueReachesSpawner.SWARM_LOOKS, BlueReachesSpawner.SWARM_ROLLS, BlueReachesSpawner.SWARM_IN));
        return loaded;
    }

    /**
     * How many swarms a player would find round them, place by place: at up to {@code samples} random spots a player
     * could stand on (open feet and head, sturdy floor, no water at the feet) in the Blue Reaches within {@code radius}
     * blocks of the source, where the chunks within the count's reach are loaded with their entities, the swarms within
     * {@link BlueSpawnRules#SWARM_RADIUS}; and how many of those spots have water or glowbells by them.
     */
    private static int density(CommandContext<CommandSourceStack> c, int radius, int samples) {
        ServerLevel level = c.getSource().getLevel();
        Vec3 o = c.getSource().getPosition();
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        int top = t != null ? t.shape.top(Terrain.BLUE) : BlueSpawnRules.TOP, bottom = t != null ? t.shape.bottom(Terrain.BLUE) : BlueSpawnRules.BOTTOM;
        double count = BlueSpawnRules.SWARM_RADIUS;
        int reach = (int) Math.ceil(count) + 2;
        EnumMap<BlueSpawnRules.Place, List<Integer>> found = new EnumMap<>(BlueSpawnRules.Place.class);
        EnumMap<BlueSpawnRules.Place, Integer> byWater = new EnumMap<>(BlueSpawnRules.Place.class);
        var r = level.random;
        int got = 0;
        for (int i = 0; i < samples * 40 && got < samples; i++) {
            int x = Mth.floor(o.x + (r.nextDouble() * 2.0 - 1.0) * radius);
            int z = Mth.floor(o.z + (r.nextDouble() * 2.0 - 1.0) * radius);
            if (!entitiesLoaded(level, x - reach, z - reach) || !entitiesLoaded(level, x + reach, z - reach)
                    || !entitiesLoaded(level, x - reach, z + reach) || !entitiesLoaded(level, x + reach, z + reach)) continue;
            int y0 = bottom + 2 + r.nextInt(Math.max(1, top - bottom - 3));
            BlockPos feet = null;
            for (int y = y0; y > y0 - 24 && y > bottom; y--) {
                BlockPos p = new BlockPos(x, y, z);
                if (FireflySwarm.open(level, p) && FireflySwarm.open(level, p.above()) && FireflySwarm.sturdy(level, p, Direction.DOWN)) { feet = p; break; }
            }
            if (feet == null) continue;
            BlueSpawnRules.Place place = BlueReachesSpawner.placeAt(level, feet);
            if (!BlueSpawnRules.swarmsLive(place)) continue;
            Vec3 eye = Vec3.atBottomCenterOf(feet).add(0.0, 1.0, 0.0);
            int n = 0;
            for (FireflySwarm s : level.getEntitiesOfClass(FireflySwarm.class, new AABB(eye, eye).inflate(count)))
                if (s.isAlive() && s.centre().distanceToSqr(eye) <= count * count) n++;
            found.computeIfAbsent(place, k -> new ArrayList<>()).add(n);
            if (FirefliesFeature.drawn(level, feet.above())) byWater.merge(place, 1, Integer::sum);
            got++;
        }
        if (got == 0) { c.getSource().sendFailure(Component.literal("No spot to stand on in the Blue Reaches within " + radius + " blocks with its neighbours loaded.")); return 0; }
        for (var e : found.entrySet()) {
            List<Integer> l = e.getValue();
            l.sort(null);
            double mean = l.stream().mapToInt(Integer::intValue).average().orElse(0.0);
            int kept = BlueSpawnRules.swarmsKept(e.getKey()), max = BlueSpawnRules.swarmsMax(e.getKey());
            long under = l.stream().filter(v -> v < kept).count(), over = l.stream().filter(v -> v > max).count();
            say(c, String.format(Locale.ROOT, "%s: %d spots, %.1f swarms within %d on average (10%% %d, half %d, 90%% %d); %.0f%% under %d, %.0f%% over %d; %.0f%% of the spots by water or glowbells",
                    e.getKey().name().toLowerCase(Locale.ROOT), l.size(), mean, (int) count, l.get(l.size() / 10), l.get(l.size() / 2), l.get(Math.min(l.size() - 1, l.size() * 9 / 10)),
                    100.0 * under / l.size(), kept, 100.0 * over / l.size(), max, 100.0 * byWater.getOrDefault(e.getKey(), 0) / l.size()));
        }
        return got;
    }

    private static boolean entitiesLoaded(ServerLevel level, int x, int z) {
        return level.areEntitiesLoaded(ChunkPos.asLong(x >> 4, z >> 4));
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
        int n = count > 0 ? count : BlueSpawnRules.swarmSize(level.random.nextDouble());
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

    private static int land(CommandContext<CommandSourceStack> c, int ticks) {
        FireflySwarm s = nearest(c);
        if (s == null) return 0;
        if (!(ticks > 0 ? s.landNow(ticks) : s.landNow())) { c.getSource().sendFailure(Component.literal("No rock to land on near the swarm at " + BlockPos.containing(s.centre()).toShortString())); return 0; }
        say(c, "Landed: " + s.describe());
        return 1;
    }

    private static int startle(CommandContext<CommandSourceStack> c) {
        FireflySwarm s = nearest(c);
        if (s == null) return 0;
        if (!s.landed()) { c.getSource().sendFailure(Component.literal("The nearest swarm is flying: " + s.describe())); return 0; }
        if (!(c.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player p)) {
            c.getSource().sendFailure(Component.literal("Only a player can startle a swarm."));
            return 0;
        }
        s.flush(p);
        say(c, "Startled: " + s.describe());
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
