package com.explorationremix.creature.entity;

import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/** The spitter's shot: a lump that hurts a little and slows a lot. */
public class SpitProjectile extends ThrowableItemProjectile {
    public SpitProjectile(EntityType<? extends ThrowableItemProjectile> type, Level level) { super(type, level); }

    public SpitProjectile(EntityType<? extends ThrowableItemProjectile> type, LivingEntity owner, Level level) { super(type, owner, level); }

    @Override
    protected Item getDefaultItem() { return Items.SLIME_BALL; }

    @Override
    public void tick() {
        super.tick();
        if (level() instanceof ServerLevel sl && tickCount % 2 == 0) {
            sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.SLIME_BALL)), getX(), getY(), getZ(), 1, 0.05, 0.05, 0.05, 0.01);
        }
        if (tickCount > 100) discard();
    }

    @Override
    protected void onHitEntity(EntityHitResult hit) {
        super.onHitEntity(hit);
        Entity e = hit.getEntity();
        Entity owner = getOwner();
        if (e instanceof LivingEntity le && e != owner) {
            le.hurt(damageSources().mobProjectile(this, owner instanceof LivingEntity lo ? lo : null), 4.0F);
            le.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 50, 1));
        }
    }

    @Override
    protected void onHit(HitResult hit) {
        super.onHit(hit);
        if (!level().isClientSide) {
            if (level() instanceof ServerLevel sl) sl.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.SLIME_BALL)), getX(), getY(), getZ(), 8, 0.2, 0.2, 0.2, 0.08);
            discard();
        }
    }
}
