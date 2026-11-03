package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.BlueSpawnRules.Place;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;

/**
 * Brings the Blue Reaches' creatures out near the players there: Skeins on the rock (floor, wall or ceiling) and swarms
 * of blue fireflies in the open air of the caves, by the counts and distances of {@link BlueSpawnRules}. Its own
 * spawner run from the server tick, not the biomes' spawn lists, so the counts stay as they are meant and the pack's
 * other spawns are untouched.
 *
 * <p>Every {@link #EVERY} ticks it looks round each player in the Blue Reaches (one player a tick at most, staggered)
 * and tries a few spots for each kind still wanted there: a ring {@link BlueSpawnRules#NEAR} blocks and more away, a
 * spot out of every player's sight first, one in sight only when none is hidden. A Skein is set down lying still, its
 * body laid out behind it the way there is most room; a swarm comes out through {@link FireflySwarm#spawn}.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class BlueReachesSpawner {
    private BlueReachesSpawner() {}

    /** How often each player is looked round (ticks). */
    static final int EVERY = 40;
    /** Spots tried for one creature at each look. */
    static final int SKEIN_TRIES = 12, SWARM_TRIES = 10;
    /** How far up and down from the ring's height it looks for rock to set a Skein on. */
    static final int SKEIN_SCAN = 10;
    /** A spot is tried this far above or below the player at most. */
    static final double HEIGHT_SPREAD = 14.0;
    /** A swarm comes out in open air this many blocks round, with rock no further below than this. */
    static final int SWARM_CLEAR = 1, SWARM_FLOOR = 10;
    /** Players further than this from a spot are not asked whether they see it. */
    static final double SIGHT = 64.0;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long time = level.getGameTime();
        for (ServerPlayer p : level.players())
            if (Math.floorMod(time + p.getId() * 7L, EVERY) == 0) lookRound(level, p);
    }

    /** The Blue Reaches' top and bottom where the deep world is in, else the usual heights. */
    private static int top() {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        return t != null ? t.shape.top(Terrain.BLUE) : BlueSpawnRules.TOP;
    }

    private static int bottom() {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        return t != null ? t.shape.bottom(Terrain.BLUE) : BlueSpawnRules.BOTTOM;
    }

    static Place placeAt(ServerLevel level, BlockPos pos) {
        return BlueSpawnRules.place(level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse(""));
    }

    private static void lookRound(ServerLevel level, ServerPlayer p) {
        int top = top(), bottom = bottom();
        if (!level.isLoaded(p.blockPosition())) return;
        Place place = placeAt(level, p.blockPosition());
        if (!BlueSpawnRules.playerCounts(p.isSpectator(), p.isCreative(), place, p.getY(), top, bottom)) return;
        boolean mobSpawning = level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING);
        boolean peaceful = level.getDifficulty() == Difficulty.PEACEFUL;
        // the server's spawn-monsters and spawn-animals, as vanilla's spawns keep them: a Skein is a monster, and a
        // swarm is ambient life, which vanilla brings out only with the animals
        MinecraftServer server = level.getServer();
        int skeins = countNear(level, SkeinEntity.class, p.position(), BlueSpawnRules.SKEIN_RADIUS);
        if (BlueSpawnRules.wantSkein(skeins, peaceful, mobSpawning && server.isSpawningMonsters(), level.random.nextDouble())) spawnSkein(level, p, top, bottom);
        int swarms = countNear(level, FireflySwarm.class, p.position(), BlueSpawnRules.SWARM_RADIUS);
        if (BlueSpawnRules.wantSwarm(place, swarms, mobSpawning && server.isSpawningAnimals(), level.random.nextDouble())) spawnSwarm(level, p, top, bottom);
    }

    private static int countNear(ServerLevel level, Class<? extends net.minecraft.world.entity.Entity> kind, Vec3 at, double r) {
        int n = 0;
        for (var e : level.getEntitiesOfClass(kind, new AABB(at, at).inflate(r)))
            if (e.isAlive() && e.position().distanceToSqr(at) <= r * r) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------------ a Skein

    private static void spawnSkein(ServerLevel level, ServerPlayer p, int top, int bottom) {
        SkeinEntity.Placement seen = null;
        for (int i = 0; i < SKEIN_TRIES; i++) {
            SkeinEntity.Placement spot = skeinSpot(level, p, top, bottom);
            if (spot == null) continue;
            if (!seenByAnyone(level, spot.pos().add(0, 0.35, 0))) { setDown(level, spot); return; }
            if (seen == null) seen = spot;
        }
        if (seen != null) setDown(level, seen);
    }

    /** A spot on the ring round {@code p} where a Skein may come out, or null. */
    @Nullable
    private static SkeinEntity.Placement skeinSpot(ServerLevel level, ServerPlayer p, int top, int bottom) {
        Vec3 c = ringPoint(level, p, BlueSpawnRules.SKEIN_FAR, top, bottom);
        int x = Mth.floor(c.x) - 1, z = Mth.floor(c.z) - 1, y0 = Mth.floor(c.y);
        if (!level.hasChunksAt(x - 6, z - 6, x + 8, z + 8)) return null;
        for (int k = 0; k <= 2 * SKEIN_SCAN; k++) {
            // out from the ring's height, up and down by turns
            int y = y0 + ((k & 1) == 0 ? k / 2 : -(k + 1) / 2);
            SkeinEntity.Placement spot = SkeinEntity.placement(level, EREntities.SKEIN.get(), new BlockPos(x, y, z));
            if (spot == null) continue;
            BlockPos at = BlockPos.containing(spot.pos());
            int there = countNear(level, SkeinEntity.class, spot.pos(), BlueSpawnRules.SKEIN_RADIUS);
            if (BlueSpawnRules.skeinSpot(placeAt(level, at), spot.pos().y, top, bottom, nearestPlayer(level, spot.pos()), spot.pos().distanceTo(p.position()), there)) return spot;
            return null;
        }
        return null;
    }

    private static void setDown(ServerLevel level, SkeinEntity.Placement spot) {
        SkeinEntity s = EREntities.SKEIN.get().create(level);
        if (s == null) return;
        s.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spot.pos())), MobSpawnType.NATURAL, null, null);
        s.placeOn(spot.pos(), spot.rock(), SkeinEntity.openHeading(level, EREntities.SKEIN.get(), spot, level.random::nextDouble));
        level.addFreshEntity(s);
    }

    // ------------------------------------------------------------------------------------------------ a swarm

    private static void spawnSwarm(ServerLevel level, ServerPlayer p, int top, int bottom) {
        Vec3 seen = null;
        for (int i = 0; i < SWARM_TRIES; i++) {
            Vec3 spot = swarmSpot(level, p, top, bottom);
            if (spot == null) continue;
            if (!seenByAnyone(level, spot)) { FireflySwarm.spawn(level, spot, BlueSpawnRules.swarmSize(level.random.nextDouble())); return; }
            if (seen == null) seen = spot;
        }
        if (seen != null) FireflySwarm.spawn(level, seen, BlueSpawnRules.swarmSize(level.random.nextDouble()));
    }

    /** A point of open cave air on the ring round {@code p}, with rock not far under it, where a swarm may come out. */
    @Nullable
    private static Vec3 swarmSpot(ServerLevel level, ServerPlayer p, int top, int bottom) {
        Vec3 c = ringPoint(level, p, BlueSpawnRules.SWARM_FAR, top, bottom);
        BlockPos at = BlockPos.containing(c);
        if (!level.hasChunksAt(at.getX() - 2, at.getZ() - 2, at.getX() + 2, at.getZ() + 2)) return null;
        if (!clear(level, at) || !rockBelow(level, at)) return null;
        Vec3 mid = Vec3.atCenterOf(at);
        // it is counted where its box stands, half a box under its centre
        double fromPlayer = mid.subtract(0.0, FireflySwarm.HALF, 0.0).distanceTo(p.position());
        return BlueSpawnRules.swarmSpot(placeAt(level, at), mid.y, top, bottom, nearestPlayer(level, mid), fromPlayer, level.random.nextDouble()) ? mid : null;
    }

    /** Nothing solid and no water or lava in the blocks round {@code at}. */
    private static boolean clear(ServerLevel level, BlockPos at) {
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-SWARM_CLEAR, -SWARM_CLEAR, -SWARM_CLEAR), at.offset(SWARM_CLEAR, SWARM_CLEAR, SWARM_CLEAR))) {
            BlockState s = level.getBlockState(q);
            if (!s.getCollisionShape(level, q).isEmpty() || !s.getFluidState().isEmpty()) return false;
        }
        return true;
    }

    /** Rock within {@link #SWARM_FLOOR} under {@code at}: it is in a cave, not out over a drop. */
    private static boolean rockBelow(ServerLevel level, BlockPos at) {
        BlockPos.MutableBlockPos q = at.mutable();
        for (int i = 0; i < SWARM_FLOOR; i++) {
            q.move(0, -1, 0);
            if (!level.getBlockState(q).getCollisionShape(level, q).isEmpty()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------ shared

    /** A random point on the ring round {@code p}, from {@link BlueSpawnRules#NEAR} to {@code far}, its height near the player's. */
    private static Vec3 ringPoint(ServerLevel level, ServerPlayer p, double far, int top, int bottom) {
        double a = level.random.nextDouble() * Math.PI * 2.0;
        double r = BlueSpawnRules.ringDistance(level.random.nextDouble(), far);
        double y = Mth.clamp(p.getY() + (level.random.nextDouble() * 2.0 - 1.0) * HEIGHT_SPREAD, bottom + 1, top - 1);
        return new Vec3(p.getX() + Math.cos(a) * r, y, p.getZ() + Math.sin(a) * r);
    }

    private static double nearestPlayer(ServerLevel level, Vec3 at) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer p : level.players()) if (!p.isSpectator()) best = Math.min(best, p.position().distanceToSqr(at));
        return Math.sqrt(best);
    }

    /** Whether any player near enough has a clear line from the eyes to {@code at}. */
    private static boolean seenByAnyone(ServerLevel level, Vec3 at) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.position().distanceToSqr(at) > SIGHT * SIGHT) continue;
            HitResult hit = level.clip(new ClipContext(p.getEyePosition(), at, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.MISS) return true;
        }
        return false;
    }
}
