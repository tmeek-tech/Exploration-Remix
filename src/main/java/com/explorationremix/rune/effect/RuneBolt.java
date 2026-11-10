package com.explorationremix.rune.effect;

import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * A flying rune: a Spark, Shard, Orb or Glow mote. The server moves it and decides what it hits; the client is told
 * only what it looks like (which rune, its element, its size) and draws it with a ribbon of light behind it, from the
 * places it has been ({@link #trail}). Never saved: a bolt is gone
 * when its chunk unloads.
 */
public class RuneBolt extends Entity {
    private static final EntityDataAccessor<Byte> DATA_RUNE = SynchedEntityData.defineId(RuneBolt.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Byte> DATA_ELEMENT = SynchedEntityData.defineId(RuneBolt.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_SIZE = SynchedEntityData.defineId(RuneBolt.class, EntityDataSerializers.FLOAT);

    /** How many of its last places the client keeps, for the ribbon of light behind it. */
    public static final int TRAIL = 10;
    /** Client only: where it was at the end of each of its last ticks, newest first ({@link #trailCount} of them). */
    public final Vec3[] trail = new Vec3[TRAIL];
    public int trailCount;

    /** Server only: what it does in flight and what it carries (null on the client). */
    @Nullable
    private BoltLogic logic;

    public RuneBolt(EntityType<? extends RuneBolt> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_RUNE, (byte) RuneId.SPARK.ordinal());
        entityData.define(DATA_ELEMENT, (byte) 0);
        entityData.define(DATA_SIZE, 1.0F);
    }

    /** Which rune it is (Spark, Shard, Orb or Glow). */
    public RuneId rune() {
        int i = entityData.get(DATA_RUNE);
        return i >= 0 && i < RuneId.values().length ? RuneId.values()[i] : RuneId.SPARK;
    }

    public Element element() {
        int i = entityData.get(DATA_ELEMENT);
        return i >= 0 && i < Element.values().length ? Element.values()[i] : Element.NONE;
    }

    /** How big it is drawn: 1 normal, more with Wide. */
    public float size() { return entityData.get(DATA_SIZE); }

    /** Server: sets it flying with its logic and its look. */
    public void start(BoltLogic logic, RuneId rune, Element element, float size) {
        this.logic = logic;
        setLook(rune, element, size);
    }

    @Nullable
    public BoltLogic logic() { return logic; }

    void setLook(RuneId rune, Element element, float size) {
        entityData.set(DATA_RUNE, (byte) rune.ordinal());
        entityData.set(DATA_ELEMENT, (byte) element.ordinal());
        entityData.set(DATA_SIZE, size);
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // the client moves it along its last known motion between updates, so it flies smoothly
            setPos(getX() + getDeltaMovement().x, getY() + getDeltaMovement().y, getZ() + getDeltaMovement().z);
            pushTrail();
            RuneFx.client.boltTick(this);
            return;
        }
        if (logic == null) {
            discard();
            return;
        }
        logic.tick(this);
    }

    private void pushTrail() {
        System.arraycopy(trail, 0, trail, 1, TRAIL - 1);
        trail[0] = position();
        if (trailCount < TRAIL) trailCount++;
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        if (logic != null) logic.removed(this);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) { return d < 128 * 128; }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
