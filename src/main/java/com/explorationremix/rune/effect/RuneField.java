package com.explorationremix.rune.effect;

import com.explorationremix.rune.engine.Element;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * A Field: a lingering patch on the ground that pulses its element into whatever stands in it. The server decides who
 * it touches; the client is told its element, its radius and how long it lasts, and draws a glowing ring. Never saved:
 * it is gone when its chunk unloads.
 */
public class RuneField extends Entity {
    private static final EntityDataAccessor<Byte> DATA_ELEMENT = SynchedEntityData.defineId(RuneField.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<Float> DATA_RADIUS = SynchedEntityData.defineId(RuneField.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_LIFE = SynchedEntityData.defineId(RuneField.class, EntityDataSerializers.INT);

    @Nullable
    private FieldLogic logic;

    public RuneField(EntityType<? extends RuneField> type, Level level) {
        super(type, level);
        noPhysics = true;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(DATA_ELEMENT, (byte) 0);
        entityData.define(DATA_RADIUS, 2.0F);
        entityData.define(DATA_LIFE, 60);
    }

    /** Server: starts it with its logic and its look. */
    public void start(FieldLogic logic, Element element, float radius, int life) {
        this.logic = logic;
        entityData.set(DATA_ELEMENT, (byte) element.ordinal());
        entityData.set(DATA_RADIUS, radius);
        entityData.set(DATA_LIFE, life);
    }

    public Element element() {
        int i = entityData.get(DATA_ELEMENT);
        return i >= 0 && i < Element.values().length ? Element.values()[i] : Element.NONE;
    }

    /** Its radius in blocks. */
    public float radius() { return entityData.get(DATA_RADIUS); }

    /** How many ticks it lasts in all. */
    public int life() { return entityData.get(DATA_LIFE); }

    /** 0 when it starts, 1 when it ends. */
    public float progress(float partialTick) { return Math.min(1.0F, (tickCount + partialTick) / Math.max(1, life())); }

    @Nullable
    public FieldLogic logic() { return logic; }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            RuneFx.fieldAmbient(this);
            return;
        }
        if (logic == null) {
            discard();
            return;
        }
        logic.tick(this);
    }

    @Override
    public void onRemovedFromWorld() {
        super.onRemovedFromWorld();
        if (logic != null) logic.removed(this);
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double d) { return d < 96 * 96; }

    @Override
    public boolean isPickable() { return false; }

    @Override
    public boolean isAttackable() { return false; }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {}
}
