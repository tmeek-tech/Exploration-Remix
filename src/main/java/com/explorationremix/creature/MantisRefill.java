package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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

import javax.annotation.Nullable;
import java.util.List;

/**
 * The stilt mantis herds grow back slowly after losses (0.20.0), as the Skeins do: a herd short of its size gets one
 * mantis back now and then (about one an in-game day), at its home, only while no player is near enough to see it come.
 * Older worlds, made before the herds, fill this way too, one mantis at a time.
 *
 * <p>Every {@link #EVERY} ticks each player in the Deep Gardens is looked round: the herds of the cells round them,
 * {@link #NEAR_MIN} to {@link #NEAR_MAX} blocks off, in chunks that tick entities. Vanilla's own rules hold: no
 * refill with mob spawning off, or with the server's animals off.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID)
public final class MantisRefill {
    private MantisRefill() {}

    /** How often each player is looked round (ticks). */
    static final int EVERY = 200;
    /** A herd is refilled only between these distances from the nearest player. */
    static final double NEAR_MIN = 48.0, NEAR_MAX = 128.0;
    /** Each look gives a short herd one chance in this to get a mantis back (about one an in-game day). */
    static final int ROLL = 120;
    /** Mantises this near its home count as the herd's (blocks). */
    static final double HERD_REACH = 40.0;
    /** For /remix mantis homes: looks at short herds, rolls come up, mantises moved in. */
    public static long LOOKS, ROLLS, MOVED_IN;

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        long time = level.getGameTime();
        for (ServerPlayer p : level.players())
            if (Math.floorMod(time + p.getId() * 13L, EVERY) == 0) lookRound(level, p, false);
    }

    /** Looks round {@code p} once; with {@code force} every short herd near gets one back, roll or no roll. */
    public static int lookRound(ServerLevel level, Player p, boolean force) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || t.gardens == null || p.isSpectator()) return 0;
        int bottom = t.shape.bottom(Terrain.GARDENS), top = t.shape.top(Terrain.GARDENS);
        if (p.getY() < bottom - 24 || p.getY() > top + 24) return 0;
        if (!force && (!level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING) || !level.getServer().isSpawningAnimals())) return 0;
        int pcx = MantisHerds.cell(p.getBlockX()), pcz = MantisHerds.cell(p.getBlockZ());
        int reach = (int) Math.ceil(NEAR_MAX / MantisHerds.CELL);
        int in = 0;
        for (int cx = pcx - reach; cx <= pcx + reach; cx++)
            for (int cz = pcz - reach; cz <= pcz + reach; cz++) {
                // a cell not planned yet is planned off the server thread, and looked at next time
                List<MantisHerds.Home> homes = MantisHerds.homesIfPlanned(t, cx, cz);
                if (homes == null) continue;
                for (MantisHerds.Home h : homes) {
                    Vec3 home = new Vec3(h.x() + 0.5, h.y(), h.z() + 0.5);
                    double d = nearestPlayer(level, home);
                    if (d < NEAR_MIN || d > NEAR_MAX) continue;
                    // the whole herd's reach must be loaded, or members away in unloaded ground go uncounted and the herd overfills
                    if (!herdLoaded(level, h)) continue;
                    if (members(level, h) >= h.size()) continue;
                    LOOKS++;
                    if (!force && level.random.nextInt(ROLL) != 0) continue;
                    ROLLS++;
                    if (moveIn(level, h)) { MOVED_IN++; in++; }
                }
            }
        return in;
    }

    /** The herd's living mantises near its home. */
    public static int members(ServerLevel level, MantisHerds.Home h) {
        int n = 0;
        AABB box = new AABB(h.x(), h.y(), h.z(), h.x() + 1, h.y() + 1, h.z() + 1).inflate(HERD_REACH);
        for (StiltMantisEntity m : level.getEntitiesOfClass(StiltMantisEntity.class, box))
            if (m.isAlive() && m.herd() == h.id()) n++;
        return n;
    }

    /** Whether the ground within {@link #HERD_REACH} of the home ticks entities (a player's loaded ground is square, so its corners tell). */
    private static boolean herdLoaded(ServerLevel level, MantisHerds.Home h) {
        int r = (int) HERD_REACH;
        for (int dx = -r; dx <= r; dx += 2 * r)
            for (int dz = -r; dz <= r; dz += 2 * r)
                if (!level.isPositionEntityTicking(new BlockPos(h.x() + dx, h.y(), h.z() + dz))) return false;
        return level.isPositionEntityTicking(new BlockPos(h.x(), h.y(), h.z()));
    }

    private static double nearestPlayer(ServerLevel level, Vec3 at) {
        double best = Double.MAX_VALUE;
        for (Player p : level.players()) if (!p.isSpectator()) best = Math.min(best, p.position().distanceTo(at));
        return best;
    }

    /** One mantis comes back to the herd, at a floor by its home no player sees. */
    private static boolean moveIn(ServerLevel level, MantisHerds.Home h) {
        for (int k = 0; k < 12; k++) {
            int x = h.x() + level.random.nextInt(9) - 4, z = h.z() + level.random.nextInt(9) - 4;
            if (!level.isPositionEntityTicking(new BlockPos(x, h.y(), z))) continue;
            BlockPos at = StiltMantisEntity.stand(level, x, h.y(), z, 4);
            if (at == null || seen(level, at)) continue;
            StiltMantisEntity m = EREntities.STILT_MANTIS.get().create(level);
            if (m == null) return false;
            float yaw = level.random.nextFloat() * 360.0F;
            m.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, yaw, 0.0F);
            m.setYHeadRot(yaw);
            m.setYBodyRot(yaw);
            m.joinHerd(h);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.NATURAL, null, null);
            level.addFreshEntityWithPassengers(m);
            return true;
        }
        return false;
    }

    /** Whether any player within 96 blocks has a clear line to the spot (its body's middle). */
    private static boolean seen(ServerLevel level, BlockPos at) {
        Vec3 c = new Vec3(at.getX() + 0.5, at.getY() + 1.8, at.getZ() + 0.5);
        for (Player p : level.players()) {
            if (p.isSpectator() || p.position().distanceToSqr(c) > 96.0 * 96.0) continue;
            Vec3 eye = p.getEyePosition();
            if (clear(level, eye, c, p)) return true;
        }
        return false;
    }

    private static boolean clear(ServerLevel level, Vec3 from, Vec3 to, @Nullable Player p) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }
}
