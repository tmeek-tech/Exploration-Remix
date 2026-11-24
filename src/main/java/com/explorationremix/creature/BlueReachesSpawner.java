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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Brings the Blue Reaches' creatures back near the players there, where the caves have lost theirs: now and then a
 * Skein on the rock (floor, wall or ceiling) where none is left, and a swarm of blue fireflies in the open air where
 * the caves are short of theirs (both are set down with the world, {@link SkeinHomes} and {@link FireflyHomes}), by
 * the counts and distances of {@link BlueSpawnRules}. Its own spawner run from the server tick, not the biomes' spawn
 * lists, so the counts stay as they are meant and the pack's other spawns are untouched.
 *
 * <p>Every {@link #EVERY} ticks it looks round each player in the Blue Reaches (one player a tick at most, staggered)
 * and tries a few spots for each kind due there: a ring {@link BlueSpawnRules#NEAR} blocks and more away, only out of
 * every player's sight. A Skein is set down lying still, its body laid out behind it the way there is most room; a
 * swarm comes out through {@link FireflySwarm#spawn}. Both stay, as those set down with the world do.
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

    /** Whether a block of the Deep Blue Rift lies in its great chasm (0.20.8), where the swarms are fewer. */
    static boolean inChasm(Place place, BlockPos pos) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        return place == Place.RIFT && t != null && t.inRiftChasm(pos.getX(), pos.getY(), pos.getZ());
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
        int skeins = countNear(level, SkeinEntity.class, p.position(), BlueSpawnRules.REFILL_RADIUS);
        UUID id = p.getUUID();
        if (skeins == 0) REFILL_LOOKS++;
        if (BlueSpawnRules.wantSkein(skeins, peaceful, mobSpawning && server.isSpawningMonsters(), SKEIN_DUE.contains(id), level.random.nextDouble())) {
            if (!SKEIN_DUE.contains(id)) REFILL_ROLLS++;
            // due until a spot out of every player's sight turns up (at this look or a later one)
            if (spawnSkein(level, p, top, bottom)) { SKEIN_DUE.remove(id); REFILL_IN++; }
            else SKEIN_DUE.add(id);
        } else if (skeins > 0) SKEIN_DUE.remove(id);
        int swarms = countNear(level, FireflySwarm.class, p.position(), BlueSpawnRules.SWARM_RADIUS);
        // a player in the rift's great chasm is kept fewer swarms than one in the tunnels off it (0.20.8)
        boolean chasm = inChasm(place, p.blockPosition());
        boolean short_ = swarms < BlueSpawnRules.swarmsKept(place, chasm);
        if (short_) SWARM_LOOKS++;
        if (BlueSpawnRules.wantSwarm(place, chasm, swarms, mobSpawning && server.isSpawningAnimals(), SWARM_DUE.contains(id), level.random.nextDouble())) {
            if (!SWARM_DUE.contains(id)) SWARM_ROLLS++;
            // due until a spot out of every player's sight turns up, as for a Skein
            if (spawnSwarm(level, p, chasm, top, bottom)) { SWARM_DUE.remove(id); SWARM_IN++; }
            else SWARM_DUE.add(id);
        } else if (!short_) SWARM_DUE.remove(id);
    }

    private static int countNear(ServerLevel level, Class<? extends net.minecraft.world.entity.Entity> kind, Vec3 at, double r) {
        int n = 0;
        for (var e : level.getEntitiesOfClass(kind, new AABB(at, at).inflate(r)))
            if (e.isAlive() && e.position().distanceToSqr(at) <= r * r) n++;
        return n;
    }

    // ------------------------------------------------------------------------------------------------ a Skein

    /**
     * The players a Skein is due to move in for: the refill's roll came up, but no spot out of every player's sight
     * was found yet. Kept until one is found, or a Skein is near the player again.
     */
    private static final Set<UUID> SKEIN_DUE = new HashSet<>();

    /**
     * For /remix skein homes, since the game started: looks round a player with no Skein within the refill radius, the
     * refill's rolls that came up, and the Skeins that moved in.
     */
    public static long REFILL_LOOKS, REFILL_ROLLS, REFILL_IN;

    /** For /remix skein refill: a Skein is due for {@code p} now (it comes at the next look that finds a spot). */
    public static void skeinDue(ServerPlayer p) { SKEIN_DUE.add(p.getUUID()); }

    /** A Skein moves in out of every player's sight, or not this time (false). */
    private static boolean spawnSkein(ServerLevel level, ServerPlayer p, int top, int bottom) {
        for (int i = 0; i < SKEIN_TRIES; i++) {
            SkeinEntity.Placement spot = skeinSpot(level, p, top, bottom);
            if (spot != null && !seenByAnyone(level, spot.pos().add(0, 0.35, 0))) { setDown(level, spot); return true; }
        }
        return false;
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
            // its body is laid straight back from its head: not where most of it would lie in a step or over a drop
            if (SkeinEntity.openLength(level, EREntities.SKEIN.get(), spot) < SkeinEntity.BODY_ROOM) return null;
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
        // it has moved in: it stays, as the Skeins set down with the world do
        s.setPersistenceRequired();
        level.addFreshEntity(s);
    }

    // ------------------------------------------------------------------------------------------------ a swarm

    /** The players a swarm is due to come out for (the roll came up, no hidden spot found yet), as for a Skein. */
    private static final Set<UUID> SWARM_DUE = new HashSet<>();

    /**
     * For /remix fireflies homes, since the game started: looks round a player with fewer swarms near than the caves
     * keep, the rolls that came up, and the swarms that came out.
     */
    public static long SWARM_LOOKS, SWARM_ROLLS, SWARM_IN;

    /**
     * A swarm comes out out of every player's sight, kept for good, or not this time (false); {@code chasm}, for a
     * player standing in the rift's great chasm (only they are brought swarms out there).
     */
    private static boolean spawnSwarm(ServerLevel level, ServerPlayer p, boolean chasm, int top, int bottom) {
        for (int i = 0; i < SWARM_TRIES; i++) {
            Vec3 spot = swarmSpot(level, p, chasm, top, bottom);
            if (spot != null && !seenByAnyone(level, spot))
                return FireflySwarm.spawn(level, spot, BlueSpawnRules.swarmSize(level.random.nextDouble()), true) != null;
        }
        return false;
    }

    /** A point of open cave air on the ring round {@code p}, with rock not far under it, where a swarm may come out. */
    @Nullable
    private static Vec3 swarmSpot(ServerLevel level, ServerPlayer p, boolean chasm, int top, int bottom) {
        Vec3 c = ringPoint(level, p, BlueSpawnRules.SWARM_FAR, top, bottom);
        BlockPos at = BlockPos.containing(c);
        if (!level.hasChunksAt(at.getX() - 2, at.getZ() - 2, at.getX() + 2, at.getZ() + 2)) return null;
        if (!FireflySwarm.roomAt(level, at)) return null;
        Vec3 mid = Vec3.atCenterOf(at);
        // it is counted where its box stands, half a box under its centre
        double fromPlayer = mid.subtract(0.0, FireflySwarm.HALF, 0.0).distanceTo(p.position());
        Place place = placeAt(level, at);
        return BlueSpawnRules.swarmSpot(place, inChasm(place, at), chasm, mid.y, top, bottom, nearestPlayer(level, mid), fromPlayer) ? mid : null;
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
