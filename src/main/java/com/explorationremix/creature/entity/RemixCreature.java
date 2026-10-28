package com.explorationremix.creature.entity;

import com.explorationremix.config.ERConfig;
import com.explorationremix.creature.CreatureDirector;
import com.explorationremix.creature.NoiseBus;
import com.explorationremix.world.DeepWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Base of every creature this mod adds. It hears through cave air, loses interest in players who reach a refuge,
 * never wanders into one, and lives only as long as the run or nest that spawned it.
 */
public abstract class RemixCreature extends Monster implements CreatureAnim {
    private static final EntityDataAccessor<Integer> DATA_STATE = SynchedEntityData.defineId(RemixCreature.class, EntityDataSerializers.INT);

    private long runId;
    private String nestKey = "";
    @Nullable private Vec3 noisePos;
    private long noiseTime;
    private double noiseLoudness;
    private boolean noiseLure;
    private int inRefugeTicks;
    private long lastContactTime;
    private int sightCacheTick = -100;
    private boolean sightCache;

    protected RemixCreature(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 0;
    }

    public static AttributeSupplier.Builder baseAttributes(double health, double speed, double attack, double armor, double knockbackResistance, double followRange) {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, health)
                .add(Attributes.MOVEMENT_SPEED, speed)
                .add(Attributes.ATTACK_DAMAGE, attack)
                .add(Attributes.ARMOR, armor)
                .add(Attributes.KNOCKBACK_RESISTANCE, knockbackResistance)
                .add(Attributes.FOLLOW_RANGE, followRange);
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(DATA_STATE, 0);
    }

    @Override
    public int animState() { return this.entityData.get(DATA_STATE); }

    public void setAnimState(int state) { this.entityData.set(DATA_STATE, state); }

    // ------------------------------------------------------------------ ownership

    public long runId() { return runId; }
    public void setRunId(long runId) { this.runId = runId; }
    public String nestKey() { return nestKey; }
    public void setNestKey(String key) { this.nestKey = key == null ? "" : key; }
    public boolean isHunter() { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putLong("RunId", runId);
        tag.putString("NestKey", nestKey);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        runId = tag.getLong("RunId");
        nestKey = tag.getString("NestKey");
    }

    // ------------------------------------------------------------------ hearing and sight

    /** Called by the director for every noise that reaches this creature, with the loudness left after the walls. */
    public void hear(NoiseBus.Noise noise, double effective) {
        long now = level().getGameTime();
        if (noise.lure()) {
            noisePos = noise.pos(); noiseTime = now; noiseLoudness = effective; noiseLure = true;
            setTarget(null);
            return;
        }
        if (noiseLure && now - noiseTime < 200) return;          // a lure holds attention
        if (effective > noiseLoudness || now - noiseTime > 40) {
            noisePos = noise.pos(); noiseTime = now; noiseLoudness = effective; noiseLure = false;
        }
        if (getTarget() == null && noise.source() instanceof LivingEntity source && source != this && effective > 18
                && canTargetEntity(source) && distanceToSqr(source) < 20 * 20) {
            setTarget(source);
            lastContactTime = now;
        } else if (getTarget() != null && noise.source() == getTarget()) {
            lastContactTime = now;
        }
    }

    public boolean hasFreshNoise() { return noisePos != null && level().getGameTime() - noiseTime < 300; }
    @Nullable public Vec3 noisePos() { return noisePos; }
    public boolean noiseIsLure() { return noiseLure; }
    public void consumeNoise() { noisePos = null; noiseLure = false; noiseLoudness = 0; }

    public boolean isBlind() { return false; }

    /** Sight: line of sight, within follow range, and not through a smoke cloud. Cached for ten ticks. */
    public boolean canSeeTarget(LivingEntity t) {
        if (isBlind()) return false;
        if (tickCount - sightCacheTick < 10) return sightCache;
        sightCacheTick = tickCount;
        double range = getAttributeValue(Attributes.FOLLOW_RANGE);
        sightCache = distanceToSqr(t) <= range * range && getSensing().hasLineOfSight(t) && !inSmoke(t);
        return sightCache;
    }

    public boolean inSmoke(LivingEntity t) {
        return !level().getEntitiesOfClass(AreaEffectCloud.class, t.getBoundingBox().inflate(3.0), c -> c.getTags().contains("er_smoke")).isEmpty();
    }

    public boolean canTargetEntity(LivingEntity e) {
        if (e instanceof Player p) return !p.isCreative() && !p.isSpectator() && p.isAlive();
        return !(e instanceof RemixCreature) && e.isAlive();
    }

    // ------------------------------------------------------------------ refuges

    public static boolean isRefuge(BlockPos pos) {
        if (!DeepWorld.installed() || DeepWorld.plans() == null || pos.getY() >= DeepWorld.packFloor()) return false;
        return DeepWorld.plans().insideRefuge(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public float getWalkTargetValue(BlockPos pos, LevelReader level) {
        return isRefuge(pos) ? -20.0F : super.getWalkTargetValue(pos, level);
    }

    // ------------------------------------------------------------------ goals

    @Override
    protected void registerGoals() {
        registerTargetGoals();
    }

    protected void registerTargetGoals() {
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        if (!isBlind()) {
            this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, 10, true, false,
                    e -> canTargetEntity(e) && canSeeTarget(e)));
        }
    }

    // ------------------------------------------------------------------ life cycle

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide || tickCount % 10 != 0) return;
        long now = level().getGameTime();
        LivingEntity target = getTarget();
        if (target != null) {
            if (!target.isAlive() || !canTargetEntity(target)) { setTarget(null); return; }
            if (canSeeTarget(target) || distanceToSqr(target) < 9) lastContactTime = now;
            boolean refuge = isRefuge(target.blockPosition());
            inRefugeTicks = refuge ? inRefugeTicks + 10 : 0;
            int giveUp = (refuge ? ERConfig.INSTANCE.hunterRefugeGiveUpSeconds.get() : ERConfig.INSTANCE.hunterGiveUpSeconds.get()) * 20;
            if (now - lastContactTime > giveUp || inRefugeTicks > giveUp) {
                setTarget(null);
                inRefugeTicks = 0;
                onLostTarget();
            }
        } else if (tickCount % 40 == 0) {
            CreatureDirector.checkLifetime(this);
        }
    }

    /** The target got away: hunters start searching, nest creatures drift home. */
    protected void onLostTarget() {}

    @Override
    public void setTarget(@Nullable LivingEntity target) {
        super.setTarget(target);
        if (target != null) lastContactTime = level().getGameTime();
    }

    @Override
    public boolean removeWhenFarAway(double dist) { return false; }

    @Override
    public boolean requiresCustomPersistence() { return true; }

    @Override
    public boolean shouldDropExperience() { return false; }

    @Override
    public int getExperienceReward() { return 0; }

    @Override
    protected boolean shouldDespawnInPeaceful() { return true; }
}
