package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.EREntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The Wasteland's ember urchins kept up near the players there (0.23.7): they are set down with the world
 * (world.feature.EmberUrchinsFeature), and where some have been killed (or a world was made before them) a new one walks
 * in now and then, always out of every player's sight.
 *
 * <p>Every {@link #EVERY} ticks each player in the Wasteland is looked round: with fewer than {@link #TARGET} urchins
 * within {@link #COUNT_REACH} blocks of them (across, and {@link #COUNT_UP} up and down: about their own sheet of
 * tunnels), one in {@link #ROLL} looks sets one down on a tunnel's floor {@link #RING_MIN} to {@link #RING_MAX} blocks
 * off, where no player has a clear line to it and no other urchin is near. About as many as the world is made with (in
 * a new world, 0 to 4 so near, 1.5 on average, counted round its spawn), so it makes up for those gone rather than
 * crowding them in. As vanilla's own monsters: none in Peaceful, or with mob spawning off.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class EmberRefill {
    private EmberRefill() {}

    static final int EVERY = 300;
    static final double COUNT_REACH = 64.0, COUNT_UP = 20.0;
    public static final int TARGET = 2;
    static final int ROLL = 2;
    static final double RING_MIN = 40.0, RING_MAX = 64.0;
    /** Columns tried at each look, and the rows up and down from the player's each is scanned. */
    static final int TRIES = 10, SCAN = 16;
    /** No other urchin this near a spot. */
    static final double APART = 16.0;
    /** For /remix ember counts: looks round players, urchins set down by them. */
    public static long LOOKS, PLACED;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long time = level.getGameTime();
        for (ServerPlayer p : level.players())
            if (Math.floorMod(time + p.getId() * 13L + 57, EVERY) == 0) lookRound(level, p, false);
    }

    /** Looks round {@code p} once: an urchin set down out of sight if few are near (with {@code force}, whatever the count, rules and roll). */
    public static int lookRound(ServerLevel level, Player p, boolean force) {
        int[] rows = EmberSpots.rows();
        if (rows == null || p.isSpectator()) return 0;
        if (p.getY() < rows[0] || p.getY() > rows[1]) return 0;
        if (!force && (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) || !level.getServer().isSpawningMonsters()
                || level.getDifficulty() == Difficulty.PEACEFUL)) return 0;
        LOOKS++;
        AABB box = new AABB(p.getX() - COUNT_REACH, p.getY() - COUNT_UP, p.getZ() - COUNT_REACH, p.getX() + COUNT_REACH, p.getY() + COUNT_UP, p.getZ() + COUNT_REACH);
        int have = level.getEntitiesOfClass(EmberUrchinEntity.class, box, Entity::isAlive).size();
        if (!force && (have >= TARGET || level.random.nextInt(ROLL) != 0)) return 0;
        EmberSpots.LevelGround g = new EmberSpots.LevelGround(level);
        int top = Math.min(rows[1] - 1, Mth.floor(p.getY()) + SCAN), bottom = Math.max(rows[0] + 1, Mth.floor(p.getY()) - SCAN);
        for (int i = 0; i < TRIES; i++) {
            double a = level.random.nextDouble() * Math.PI * 2.0, r = RING_MIN + level.random.nextDouble() * (RING_MAX - RING_MIN);
            int x = Mth.floor(p.getX() + Math.cos(a) * r), z = Mth.floor(p.getZ() + Math.sin(a) * r);
            if (!level.isPositionEntityTicking(new BlockPos(x, Mth.floor(p.getY()), z))) continue;
            for (EmberSpots.Spot s : EmberSpots.floors(g, x, z, top, bottom)) {
                Vec3 at = new Vec3(s.x(), s.y(), s.z());
                if (!level.getEntitiesOfClass(EmberUrchinEntity.class, new AABB(at, at).inflate(APART), Entity::isAlive).isEmpty()) continue;
                if (!EmberSpots.wayThrough(g, s) || !EmberSpots.roams(g, s) || seen(level, at)) continue;
                EmberUrchinEntity e = EREntities.EMBER_URCHIN.get().create(level);
                if (e == null) return 0;
                e.setDown(s.x(), s.y(), s.z(), level.random.nextDouble() * Math.PI * 2.0);
                e.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(at)), MobSpawnType.NATURAL, null, null);
                level.addFreshEntity(e);
                PLACED++;
                return 1;
            }
        }
        return 0;
    }

    /** Whether any player within 96 blocks has a clear line to its middle or to the top of its spines' reach. */
    static boolean seen(ServerLevel level, Vec3 at) {
        Vec3 high = at.add(0, 2.5, 0);
        for (Player p : level.players()) {
            if (p.isSpectator() || p.position().distanceToSqr(at) > 96.0 * 96.0) continue;
            Vec3 eye = p.getEyePosition();
            if (clear(level, eye, at, p) || clear(level, eye, high, p)) return true;
        }
        return false;
    }

    private static boolean clear(ServerLevel level, Vec3 from, Vec3 to, Player p) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }
}
