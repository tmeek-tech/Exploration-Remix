package com.explorationremix.rune.cast;

import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.engine.Siphon;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.HashSet;
import java.util.Set;

/**
 * One cast while it plays out: who cast it, from which gear, what it cost (for Siphon's refunds), and which creature
 * each rune has already struck (one rune strikes a creature once per cast, however many copies Fan made).
 */
public final class CastContext {
    public final ServerPlayer owner;
    public final ServerLevel level;
    public final Host host;
    /** The gear's rune damage multiplier times the server's. */
    public final float power;
    /** The gear's id: Siphon's motes go back to it. */
    public final int gearId;
    public final CastReading cast;
    public final Siphon siphon;
    private final Set<Long> struck = new HashSet<>(), pulsed = new HashSet<>(), plants = new HashSet<>();
    /** How many plants of each kind (RunePlants) this cast has set off. */
    private final int[] plantCounts = new int[4];
    private long dingTick = -1;

    public CastContext(ServerPlayer owner, Host host, float gearPower, int gearId, CastReading cast, int paid) {
        this.owner = owner;
        this.level = owner.serverLevel();
        this.host = host;
        this.power = gearPower * (float) RuneConfig.get(RuneConfig.INSTANCE.runeDamage);
        this.gearId = gearId;
        this.cast = cast;
        this.siphon = new Siphon(paid);
    }

    /** True the first time this rune (its slot) strikes this creature in this cast. */
    public boolean firstStrike(Act a, Entity e) { return struck.add(((long) (a.slot + 1) << 32) ^ (e.getId() & 0xFFFFFFFFL)); }

    /**
     * For a Field's pulses: true the first time this rune strikes this creature in this half-second, so a creature
     * standing in two of a cast's fields takes one pulse, not two.
     */
    public boolean firstPulse(Act a, Entity e, long gameTime) {
        long key = ((long) (a.slot + 1) << 48) ^ ((gameTime / 10) << 24) ^ (e.getId() & 0xFFFFFFL);
        return pulsed.add(key);
    }

    /**
     * For the plants a cast touches (RunePlants): true the first time it touches the plant at {@code pos}, while fewer
     * than {@code cap} of that kind ({@code kind}, 0 to 3) have been set off, so a Fan's copies through one fern unroll
     * it once and a blast in a patch of glowbells rings a few, not all.
     */
    public boolean firstPlant(long pos, int kind, int cap) {
        if (plantCounts[kind] >= cap || !plants.add(pos)) return false;
        plantCounts[kind]++;
        return true;
    }

    /** The caster hears a soft ding when a rune lands (once a tick), a brighter one for a reaction. */
    public void confirmHit(boolean reaction) {
        long now = level.getGameTime();
        if (now == dingTick) return;
        dingTick = now;
        owner.playNotifySound(net.minecraft.sounds.SoundEvents.ARROW_HIT_PLAYER, net.minecraft.sounds.SoundSource.PLAYERS,
                reaction ? 0.45F : 0.25F, reaction ? 1.9F : 1.5F);
    }
}
