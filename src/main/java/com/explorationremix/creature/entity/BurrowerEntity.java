package com.explorationremix.creature.entity;

import com.explorationremix.creature.NoiseBus;
import com.explorationremix.creature.goal.RemixHuntGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The Ember Gardens' hunter: it swims through stone. Underground it cannot be hurt except by a blast; it follows
 * footsteps from below, bursts out beside you, fights for a while, then digs back in.
 */
public class BurrowerEntity extends RemixCreature {
    public static final int SURFACE = 0, BURROWED = 1, DIGGING = 3;
    private int surfaceTicks;
    private int digTicks;
    private Vec3 wander;
    private int wanderTicks;

    public BurrowerEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        setMaxUpStep(1.0F);
    }

    public static AttributeSupplier.Builder attributes() { return baseAttributes(35.0, 0.28, 7.0, 4.0, 0.5, 24.0); }

    @Override public boolean isHunter() { return true; }
    @Override public boolean isHidden() { return animState() == BURROWED; }

    public void startBurrowed() { setAnimState(BURROWED); noPhysics = true; setNoGravity(true); }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(2, new RemixHuntGoal(this, 1.0, 1.3, false, 3.5, 10) {
            @Override public boolean canUse() { return animState() == SURFACE && super.canUse(); }
            @Override public boolean canContinueToUse() { return animState() == SURFACE && super.canContinueToUse(); }
        });
        this.goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (animState() == BURROWED && !source.is(DamageTypeTags.IS_EXPLOSION) && !source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return super.hurt(source, amount);
    }

    @Override
    public boolean isPushable() { return animState() != BURROWED; }

    private boolean solid(BlockPos pos) {
        BlockState s = level().getBlockState(pos);
        return !s.getCollisionShape(level(), pos).isEmpty() && s.getFluidState().isEmpty();
    }

    /** Where the burrower wants to be: under its target, under the last noise, or on a slow wander. */
    private Vec3 goal() {
        LivingEntity t = getTarget();
        if (t != null) return t.position();
        if (hasFreshNoise() && noisePos() != null) return noisePos();
        if (wander == null || --wanderTicks <= 0) {
            wander = position().add(random.nextInt(25) - 12, 0, random.nextInt(25) - 12);
            wanderTicks = 200 + random.nextInt(200);
        }
        return wander;
    }

    private void burrowTick() {
        noPhysics = true;
        setNoGravity(true);
        setDeltaMovement(Vec3.ZERO);
        Vec3 goal = goal();
        Vec3 flat = goal.subtract(position()).multiply(1, 0, 1);
        double dist = flat.length();
        LivingEntity t = getTarget();
        if (t == null && tickCount % 10 == 0) {
            Player p = level().getNearestPlayer(getX(), getY(), getZ(), 10.0, e -> e instanceof Player pl && canTargetEntity(pl) && !isRefuge(pl.blockPosition()));
            if (p != null) setTarget(p);
        }
        if (t != null && dist < 2.5 && Math.abs(t.getY() - getY()) < 6) { emerge(); return; }
        if (dist > 0.5) {
            Vec3 step = flat.normalize().scale(0.22);
            // stay a block or two under the nearest floor around the goal's height
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(getX() + step.x, Math.min(goal.y + 2, getY() + 2), getZ() + step.z);
            int floorY = Integer.MIN_VALUE;
            for (int i = 0; i < 14; i++) {
                if (solid(probe) && !solid(probe.above())) { floorY = probe.getY(); break; }
                probe.move(0, -1, 0);
            }
            double ny = floorY == Integer.MIN_VALUE ? getY() : floorY - 1.0;
            if (Math.abs(ny - getY()) > 0.6) ny = getY() + Math.signum(ny - getY()) * 0.3;
            setPos(getX() + step.x, ny, getZ() + step.z);
            setYRot((float) (Math.atan2(step.z, step.x) * 180 / Math.PI) - 90);
            setYBodyRot(getYRot());
        }
        if (level() instanceof ServerLevel sl && tickCount % 3 == 0) {
            BlockPos at = BlockPos.containing(getX(), getY() + 1.0, getZ());
            BlockState s = level().getBlockState(at.below());
            if (!s.isAir()) sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), getX(), at.getY() + 0.1, getZ(), 4, 0.4, 0.1, 0.4, 0.05);
            if (tickCount % 24 == 0) sl.playSound(null, at, SoundEvents.DRIPSTONE_BLOCK_BREAK, SoundSource.HOSTILE, 0.5F, 0.5F);
        }
    }

    private void emerge() {
        // find air above the head within six blocks
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos(getX(), getY(), getZ());
        for (int i = 0; i < 7; i++) {
            if (!solid(probe) && !solid(probe.above())) {
                setPos(getX(), probe.getY(), getZ());
                break;
            }
            probe.move(0, 1, 0);
        }
        noPhysics = false;
        setNoGravity(false);
        setAnimState(SURFACE);
        surfaceTicks = 0;
        setDeltaMovement(0, 0.45, 0);
        if (level() instanceof ServerLevel sl) {
            BlockState s = level().getBlockState(blockPosition().below());
            sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s.isAir() ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : s), getX(), getY() + 0.5, getZ(), 40, 0.6, 0.4, 0.6, 0.15);
            sl.playSound(null, blockPosition(), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 1.2F, 0.6F);
            NoiseBus.emit(sl, position(), 24, this);
            for (LivingEntity le : sl.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(2.0), e -> e != this && canTargetEntity(e))) {
                le.hurt(damageSources().mobAttack(this), 6.0F);
                le.push((le.getX() - getX()) * 0.3, 0.4, (le.getZ() - getZ()) * 0.3);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        switch (animState()) {
            case BURROWED -> burrowTick();
            case SURFACE -> {
                surfaceTicks++;
                boolean done = getTarget() == null ? surfaceTicks > 60 : surfaceTicks > 240;
                if (done && onGround()) { setAnimState(DIGGING); digTicks = 0; getNavigation().stop(); setTarget(null); }
            }
            case DIGGING -> {
                digTicks++;
                setDeltaMovement(Vec3.ZERO);
                if (level() instanceof ServerLevel sl && digTicks % 2 == 0) {
                    BlockState s = level().getBlockState(blockPosition().below());
                    if (!s.isAir()) sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, s), getX(), getY() + 0.2, getZ(), 6, 0.4, 0.1, 0.4, 0.1);
                }
                if (digTicks > 15) {
                    startBurrowed();
                    setPos(getX(), getY() - 1.5, getZ());
                    if (level() instanceof ServerLevel sl) sl.playSound(null, blockPosition(), SoundEvents.DEEPSLATE_BREAK, SoundSource.HOSTILE, 0.8F, 0.5F);
                }
            }
            default -> {}
        }
    }

    @Override
    public void move(MoverType type, Vec3 delta) {
        if (animState() == BURROWED) return;
        super.move(type, delta);
    }

    @Override public boolean causeFallDamage(float distance, float multiplier, DamageSource source) { return false; }
    @Override public boolean isInWall() { return animState() != BURROWED && super.isInWall(); }
    @Override protected SoundEvent getAmbientSound() { return animState() == SURFACE ? SoundEvents.RAVAGER_AMBIENT : null; }
    @Override protected SoundEvent getHurtSound(DamageSource source) { return SoundEvents.RAVAGER_HURT; }
    @Override protected SoundEvent getDeathSound() { return SoundEvents.RAVAGER_DEATH; }
    @Override public float getVoicePitch() { return 1.4F; }
}
