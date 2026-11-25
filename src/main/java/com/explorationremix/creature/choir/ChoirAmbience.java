package com.explorationremix.creature.choir;

import com.explorationremix.registry.ERSounds;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.terrain.ChoirSites;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * The toll of a sleeping arena (0.21.9): now and then a Hollow Choir's bowl rings, once, and it carries
 * {@link ChoirSummon#HEARD} blocks through the deep dark, so you hear an arena before you find one. With the pale blue
 * of its braziers, which shows across a cavern, that is the whole of the hint (Taran's card pick: a pale blue glow and
 * a toll heard about 150 blocks off, no sculk trails and no pull on the keys).
 *
 * <p>Only the arena in a player's own cell is ever heard. An arena lies at least 96 blocks inside its cell, so most
 * of the 150-block reach is its own cell anyway, and the last stretch of it over the border is given up on purpose:
 * a tick that ran every 200 ticks for every player and asked the neighbouring cells for plans would plan cells
 * nobody is near, which is far dearer than a toll you have to walk a little further for. An arena whose Choir is up
 * is silent here: the fight has tolls of its own.
 */
public final class ChoirAmbience {
    /** How often a player's surroundings are looked at (ticks), and how often one arena may toll. */
    private static final int LOOK_EVERY = 200;
    private static final int TOLL_MIN = 700, TOLL_MAX = 1500;

    /** Per arena (its cell), the game time its next toll may start. Server thread only. */
    private static final Map<Long, Long> next = new HashMap<>();

    private ChoirAmbience() {}

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.level instanceof ServerLevel level)) return;
        if (!level.dimension().equals(Level.OVERWORLD)) return;
        if (level.getGameTime() % LOOK_EVERY != 0) return;
        Terrain t = DeepWorld.terrain();
        if (t == null || !DeepWorld.installed()) return;
        if (level.players().isEmpty()) {
            if (!next.isEmpty()) next.clear();
            return;
        }
        long now = level.getGameTime();
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            ChoirSites.Site s = t.network.choirSite(t.plans.cellOf(p.getX()), t.plans.cellOf(p.getZ()));
            if (s == null) continue;
            double d = Math.sqrt(p.distanceToSqr(s.x(), s.floorY() + 3, s.z()));
            if (d > ChoirSummon.HEARD) continue;
            long key = (((long) s.cellX()) << 32) ^ (s.cellZ() & 0xFFFFFFFFL);
            long when = next.getOrDefault(key, 0L);
            // a new world whose clock is behind the last one's
            if (when - now > TOLL_MAX) when = 0;
            if (now < when) continue;
            if (ChoirSummon.fighting(level, s) != null) continue;
            next.put(key, now + TOLL_MIN + level.random.nextInt(TOLL_MAX - TOLL_MIN));
            // played at the dais, so it comes from the arena and Sound Physics muffles it behind rock
            level.playSound(null, s.x(), s.floorY() + 3, s.z(), ERSounds.CHOIR_TOLL_FAR.get(), SoundSource.HOSTILE,
                    1.0F, 0.95F + level.random.nextFloat() * 0.1F);
        }
    }
}
