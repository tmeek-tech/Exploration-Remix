package com.explorationremix.creature;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * STUB (Skein branch only): the contract the Skein codes against, with the firefly builder's signatures. The firefly
 * branch's real FireflySwarm replaces this whole file at the merge; nothing here is meant to ship. It never lands,
 * holds no fireflies and spawns nothing, so the Skein never finds a swarm to eat until the real class is in.
 */
public class FireflySwarm extends Entity {
    public FireflySwarm(EntityType<? extends FireflySwarm> type, Level level) {
        super(type, level);
    }

    /** Whether the swarm has settled on rock. */
    public boolean landed() { return false; }

    /** How many fireflies are in it. */
    public int count() { return 0; }

    /** The swarm's centre now. */
    public Vec3 centre() { return position(); }

    /** Takes up to {@code n} fireflies (eaten by {@code eater}), scatters the rest; returns how many were taken. */
    public int bite(int n, Entity eater) { return 0; }

    /** Scatters the swarm away from {@code from}. */
    public void scatter(Vec3 from) { }

    /** Creates one swarm of {@code count} at {@code at} and adds it to the level; returns it, or null. */
    @Nullable
    public static FireflySwarm spawn(ServerLevel level, Vec3 at, int count) { return null; }

    @Override protected void defineSynchedData() { }
    @Override protected void readAdditionalSaveData(CompoundTag tag) { }
    @Override protected void addAdditionalSaveData(CompoundTag tag) { }
}
