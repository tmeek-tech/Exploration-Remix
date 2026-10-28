package com.explorationremix.rune.cast;

import com.explorationremix.combat.CombatTracker;
import com.explorationremix.combat.Poise;
import com.explorationremix.creature.NoiseBus;
import com.explorationremix.registry.ERTags;
import com.explorationremix.rune.RuneIds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** What each effect rune does. Numbers come from the rune's JSON params; modifiers come folded into the context. */
public final class RuneEffects {
    private RuneEffects() {}

    public static void apply(EffectContext ec) {
        switch (ec.effect.id().getPath()) {
            case RuneIds.SPARK -> spark(ec);
            case RuneIds.FROST -> frost(ec);
            case RuneIds.EMBER -> ember(ec);
            case RuneIds.SHOVE -> shove(ec);
            case RuneIds.WARD -> ward(ec);
            case RuneIds.MEND -> mend(ec);
            case RuneIds.SMOKE -> smoke(ec);
            case RuneIds.LURE -> lure(ec);
            case RuneIds.LEAP -> leap(ec);
            case RuneIds.DASH -> dash(ec);
            case RuneIds.GLIDE -> glide(ec);
            case RuneIds.CONFETTI -> confetti(ec);
            case RuneIds.BLAST -> blast(ec);
            default -> {}
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void sound(EffectContext ec, Vec3 at, SoundEvent sound, float volume, float pitch, double noise) {
        ec.level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, ec.quiet ? volume * 0.3F : volume, pitch);
        if (!ec.quiet) NoiseBus.emit(ec.level, at, noise, ec.player);
    }

    private static DamageSource source(EffectContext ec, net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType> type) {
        return new DamageSource(ec.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getHolderOrThrow(type), ec.player, ec.player);
    }

    /** Starting at the first targets, hop to creatures nearby for every Chain in front of the effect. */
    private static List<LivingEntity> chain(EffectContext ec, List<LivingEntity> first, double extraRangeForWet) {
        if (ec.chainJumps <= 0) return first;
        Set<LivingEntity> seen = new HashSet<>(first);
        List<LivingEntity> out = new ArrayList<>(first);
        List<LivingEntity> frontier = new ArrayList<>(first);
        int jumps = ec.chainJumps;
        while (jumps-- > 0 && !frontier.isEmpty()) {
            List<LivingEntity> next = new ArrayList<>();
            for (LivingEntity from : frontier) {
                double range = ec.chainRange * ec.areaScale + (CombatTracker.isWet(from) ? extraRangeForWet : 0);
                for (LivingEntity e : ec.nearby(from.position(), range, from)) {
                    if (seen.add(e)) { next.add(e); break; }
                }
            }
            out.addAll(next);
            frontier = next;
        }
        return out;
    }

    private static void line(ServerLevel level, Vec3 a, Vec3 b, net.minecraft.core.particles.ParticleOptions particle) {
        double len = a.distanceTo(b);
        int n = (int) Math.max(2, len * 2);
        for (int i = 0; i <= n; i++) {
            Vec3 p = a.lerp(b, i / (double) n);
            level.sendParticles(particle, p.x, p.y, p.z, 1, 0.05, 0.05, 0.05, 0.0);
        }
    }

    private static Vec3 center(LivingEntity e) { return e.position().add(0, e.getBbHeight() * 0.5, 0); }

    // ------------------------------------------------------------------ effects

    private static void spark(EffectContext ec) {
        double range = ec.param("range", 5) * ec.areaScale;
        List<LivingEntity> first = ec.targets(range);
        if (first.isEmpty()) {
            ec.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, ec.origin.x, ec.origin.y, ec.origin.z, 20, 0.6, 0.6, 0.6, 0.2);
            sound(ec, ec.origin, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.4F, 1.6F, 8);
            return;
        }
        // Spark always arcs a little on its own; Chain makes it arc much further.
        int ownJumps = (int) ec.param("jumps", 2);
        List<LivingEntity> hit = new ArrayList<>(first);
        Set<LivingEntity> seen = new HashSet<>(first);
        LivingEntity from = first.get(0);
        for (int i = 0; i < ownJumps; i++) {
            double r = (CombatTracker.isWet(from) || from.isInWater() ? ec.param("wet_range", 10) : 3.5) * ec.areaScale;
            LivingEntity next = null;
            for (LivingEntity e : ec.nearby(from.position(), r, from)) if (!seen.contains(e)) { next = e; break; }
            if (next == null) break;
            seen.add(next); hit.add(next); from = next;
        }
        hit = chain(ec, hit, ec.param("wet_range", 10));
        float dmg = (float) (ec.param("damage", 6) * ec.force);
        Vec3 prev = ec.player.getEyePosition();
        for (LivingEntity e : hit) {
            float d = CombatTracker.isWet(e) ? dmg * 1.5F : dmg;
            e.hurt(source(ec, DamageTypes.LIGHTNING_BOLT), d);
            if (ec.heavy > 0) Poise.damage(e, ec.poise, ec.player, ec.tool);
            line(ec.level, prev, center(e), ParticleTypes.ELECTRIC_SPARK);
            ec.level.sendParticles(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getEyeY(), e.getZ(), 15, 0.4, 0.4, 0.4, 0.3);
            prev = center(e);
        }
        sound(ec, ec.origin, SoundEvents.LIGHTNING_BOLT_IMPACT, 0.6F, 1.4F, 14);
    }

    private static void frost(EffectContext ec) {
        double radius = ec.param("radius", 3) * ec.areaScale;
        int duration = (int) (ec.param("duration", 80) * Math.max(1, ec.force));
        int slow = (int) ec.param("slow", 2);
        List<LivingEntity> targets = ec.target != null ? chain(ec, ec.nearby(center(ec.target), radius, null), 0) : ec.nearby(ec.origin, radius, null);
        if (ec.target != null && ec.target.isAlive() && !targets.contains(ec.target)) targets.add(0, ec.target);
        long now = ec.level.getGameTime();
        for (LivingEntity e : targets) {
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, slow, false, true));
            e.hurt(source(ec, DamageTypes.FREEZE), (float) (2 * ec.force));
            CombatTracker.State s = CombatTracker.of(e);
            if (CombatTracker.isWet(e)) {
                s.frozenUntil = now + duration;
                e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, duration, 10, false, false));
                ec.level.sendParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getEyeY(), e.getZ(), 30, 0.5, 0.6, 0.5, 0.05);
            }
            e.setTicksFrozen(Math.max(e.getTicksFrozen(), duration * 2));
            ec.level.sendParticles(ParticleTypes.SNOWFLAKE, e.getX(), e.getEyeY(), e.getZ(), 12, 0.4, 0.5, 0.4, 0.02);
        }
        // Frost blooms ice over water around the origin (frosted ice melts on its own).
        Vec3 at = ec.target != null ? ec.target.position() : ec.origin;
        int r = (int) Math.ceil(radius);
        BlockPos c = BlockPos.containing(at);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -2; dy <= 1; dy++) {
            if (dx * dx + dz * dz > r * r) continue;
            BlockPos p = c.offset(dx, dy, dz);
            BlockState st = ec.level.getBlockState(p);
            if (st.is(Blocks.WATER) && st.getFluidState().isSource() && ec.level.getBlockState(p.above()).isAir())
                ec.level.setBlock(p, Blocks.FROSTED_ICE.defaultBlockState(), 3);
        }
        ec.level.sendParticles(ParticleTypes.SNOWFLAKE, at.x, at.y + 0.5, at.z, (int) (20 * ec.areaScale), radius * 0.5, 0.3, radius * 0.5, 0.02);
        sound(ec, at, SoundEvents.GLASS_PLACE, 0.8F, 0.6F, 6);
    }

    private static void ember(EffectContext ec) {
        double radius = ec.param("radius", 2.5) * ec.areaScale;
        int seconds = (int) (ec.param("seconds", 5) * Math.max(1, ec.force));
        List<LivingEntity> targets = chain(ec, ec.targets(radius), 0);
        long now = ec.level.getGameTime();
        for (LivingEntity e : targets) {
            e.setSecondsOnFire(seconds);
            CombatTracker.State s = CombatTracker.of(e);
            s.emberUntil = now + seconds * 20L;
            s.emberSource = ec.player.getUUID();
            e.hurt(source(ec, DamageTypes.ON_FIRE), (float) (1.5 * ec.force));
            ec.level.sendParticles(ParticleTypes.FLAME, e.getX(), e.getEyeY(), e.getZ(), 12, 0.3, 0.4, 0.3, 0.03);
        }
        Vec3 at = ec.target != null ? center(ec.target) : ec.origin;
        ec.level.sendParticles(ParticleTypes.FLAME, at.x, at.y, at.z, (int) (10 * ec.areaScale), radius * 0.4, 0.3, radius * 0.4, 0.05);
        sound(ec, at, SoundEvents.FIRECHARGE_USE, 0.7F, 1.1F, 8);
    }

    private static void shove(EffectContext ec) {
        double radius = ec.param("radius", 3.5) * ec.areaScale;
        double force = ec.param("force", 1.4) * ec.force;
        double poise = ec.param("poise", 8) + ec.poise;
        Vec3 from = ec.player.position();
        for (LivingEntity e : ec.nearby(from, radius, null)) {
            Vec3 dir = e.position().subtract(from);
            dir = new Vec3(dir.x, 0, dir.z);
            if (dir.lengthSqr() < 1e-4) dir = ec.direction;
            dir = dir.normalize();
            double strength = force * (1.0 - Math.min(0.6, e.distanceTo(ec.player) / (radius * 2)));
            e.push(dir.x * strength, 0.25 * strength + 0.1, dir.z * strength);
            e.hurtMarked = true;
            CombatTracker.State s = CombatTracker.of(e);
            s.slamUntil = ec.level.getGameTime() + 20;
            s.slamSpeed = strength;
            Poise.damage(e, poise, ec.player, ec.tool);
            e.hurt(source(ec, DamageTypes.PLAYER_ATTACK), (float) (1.0 * ec.force));
        }
        ec.level.sendParticles(ParticleTypes.CLOUD, from.x, from.y + 1, from.z, (int) (20 * ec.areaScale), radius * 0.4, 0.3, radius * 0.4, 0.15);
        sound(ec, from, SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 0.7F, 10);
    }

    private static void ward(EffectContext ec) {
        int duration = (int) (ec.param("duration", 100) * Math.max(1, ec.areaScale));
        int amp = (int) ec.param("absorption", 1) + ec.heavy;
        ec.player.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, duration, amp, false, true));
        ec.player.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, Math.min(duration, 60), Math.min(2, ec.heavy), false, true));
        ec.level.sendParticles(ParticleTypes.END_ROD, ec.player.getX(), ec.player.getY() + 1, ec.player.getZ(), 20, 0.5, 0.6, 0.5, 0.02);
        sound(ec, ec.player.position(), SoundEvents.BEACON_POWER_SELECT, 0.6F, 1.5F, 3);
    }

    private static void mend(EffectContext ec) {
        float heal = (float) (ec.param("heal", 4) * ec.force);
        int regen = (int) (ec.param("regen", 60) * Math.max(1, ec.areaScale));
        ec.player.heal(heal);
        ec.player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, regen, 1, false, true));
        ec.level.sendParticles(ParticleTypes.HEART, ec.player.getX(), ec.player.getY() + 1.2, ec.player.getZ(), 6, 0.4, 0.4, 0.4, 0.0);
        sound(ec, ec.player.position(), SoundEvents.PLAYER_LEVELUP, 0.4F, 1.8F, 2);
    }

    private static void smoke(EffectContext ec) {
        double radius = ec.param("radius", 4) * ec.areaScale;
        int duration = (int) (ec.param("duration", 200) * Math.max(1, ec.force));
        Vec3 at = ec.target != null ? ec.target.position() : ec.player.position();
        AreaEffectCloud cloud = new AreaEffectCloud(ec.level, at.x, at.y, at.z);
        cloud.setRadius((float) radius);
        cloud.setDuration(duration);
        cloud.setWaitTime(0);
        cloud.setRadiusPerTick(0);
        cloud.setParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE);
        cloud.setOwner(ec.player);
        cloud.addTag("er_smoke");
        ec.level.addFreshEntity(cloud);
        sound(ec, at, SoundEvents.FIRE_EXTINGUISH, 0.8F, 0.8F, 4);
    }

    private static void lure(EffectContext ec) {
        double range = ec.param("range", 32) * ec.areaScale * ec.force;
        int duration = (int) ec.param("duration", 160);
        Vec3 at = ec.target != null ? ec.target.position() : ec.player.position().add(ec.direction.scale(6));
        AreaEffectCloud decoy = new AreaEffectCloud(ec.level, at.x, at.y, at.z);
        decoy.setRadius(0.5F);
        decoy.setDuration(duration);
        decoy.setWaitTime(0);
        decoy.setParticle(ParticleTypes.NOTE);
        decoy.setOwner(ec.player);
        decoy.addTag("er_lure");
        ec.level.addFreshEntity(decoy);
        ec.level.playSound(null, at.x, at.y, at.z, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.2F, 0.6F);
        NoiseBus.emit(ec.level, at, range, decoy, true);
    }

    private static void leap(EffectContext ec) {
        double power = ec.param("power", 0.9) * ec.force;
        Vec3 dm = ec.player.getDeltaMovement();
        ec.player.setDeltaMovement(dm.x, Math.max(dm.y, 0) + power, dm.z);
        ec.player.hurtMarked = true;
        ec.player.fallDistance = 0;
        SafeLanding.grant(ec.player, 120);
        ec.level.sendParticles(ParticleTypes.CLOUD, ec.player.getX(), ec.player.getY(), ec.player.getZ(), 10, 0.3, 0.1, 0.3, 0.05);
        sound(ec, ec.player.position(), SoundEvents.PHANTOM_FLAP, 0.8F, 1.3F, ec.quiet ? 0 : 4);
    }

    private static void dash(EffectContext ec) {
        double power = ec.param("power", 1.5) * ec.force;
        Vec3 look = ec.player.getLookAngle();
        Vec3 dir = new Vec3(look.x, Math.max(-0.2, look.y * 0.4), look.z).normalize();
        ec.player.setDeltaMovement(dir.scale(power).add(0, 0.12, 0));
        ec.player.hurtMarked = true;
        ec.player.fallDistance = 0;
        SafeLanding.grant(ec.player, 100);
        ec.level.sendParticles(ParticleTypes.CLOUD, ec.player.getX(), ec.player.getY() + 0.5, ec.player.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
        sound(ec, ec.player.position(), SoundEvents.PHANTOM_FLAP, 0.8F, 1.8F, ec.quiet ? 0 : 4);
    }

    private static void glide(EffectContext ec) {
        int duration = (int) (ec.param("duration", 80) * Math.max(1, ec.areaScale) * Math.max(1, ec.force));
        ec.player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, duration, 0, false, true));
        ec.player.fallDistance = 0;
        ec.level.sendParticles(ParticleTypes.END_ROD, ec.player.getX(), ec.player.getY() + 0.5, ec.player.getZ(), 8, 0.3, 0.3, 0.3, 0.01);
    }

    private static void confetti(EffectContext ec) {
        int count = (int) (ec.param("count", 60) * ec.areaScale * ec.force);
        Vec3 at = ec.target != null ? center(ec.target) : ec.player.position().add(0, 1.5, 0);
        RandomSource r = ec.level.random;
        ec.level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y, at.z, count, 0.5, 0.5, 0.5, 0.35);
        for (int i = 0; i < count / 6; i++)
            ec.level.sendParticles(ParticleTypes.NOTE, at.x + r.nextGaussian() * 0.6, at.y + r.nextDouble(), at.z + r.nextGaussian() * 0.6, 1, 0, 0, 0, r.nextDouble());
        ec.level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1.0F, 0.8F + r.nextFloat() * 0.6F);
        ec.level.playSound(null, at.x, at.y, at.z, SoundEvents.VILLAGER_YES, SoundSource.PLAYERS, 0.5F, 1.4F);
        if (!ec.quiet) NoiseBus.emit(ec.level, at, 6, ec.player);
    }

    private static void blast(EffectContext ec) {
        float power = (float) (ec.param("power", 3.2) * Math.sqrt(ec.force) * Math.sqrt(ec.areaScale));
        Vec3 at;
        if (ec.target != null) at = center(ec.target);
        else {
            // Aim where the player looks: the first solid block within 6 blocks, or 4 blocks ahead.
            net.minecraft.world.phys.HitResult hit = ec.player.pick(6.0, 0.0F, false);
            at = hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK ? hit.getLocation().add(ec.direction.scale(-0.5)) : ec.player.getEyePosition().add(ec.direction.scale(4));
        }
        ec.level.explode(ec.player, ec.level.damageSources().explosion(ec.player, ec.player), new RuneExplosionCalculator(), at.x, at.y, at.z, power, false, Level.ExplosionInteraction.TNT);
        // Debris: loosen a few ceiling blocks over the crater so they fall and hurt what they land on.
        int debris = (int) (ec.param("debris", 6) * ec.areaScale);
        RandomSource r = ec.level.random;
        int radius = (int) Math.ceil(power * 1.3);
        BlockPos c = BlockPos.containing(at);
        int dropped = 0;
        for (int i = 0; i < debris * 6 && dropped < debris; i++) {
            BlockPos p = c.offset(r.nextInt(radius * 2 + 1) - radius, r.nextInt(radius + 2), r.nextInt(radius * 2 + 1) - radius);
            BlockState st = ec.level.getBlockState(p);
            if (!st.is(ERTags.LAYER_STONE) && !st.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)) continue;
            if (!ec.level.getBlockState(p.below()).isAir()) continue;
            FallingBlockEntity f = FallingBlockEntity.fall(ec.level, p, st);
            f.setHurtsEntities(2.0F, 40);
            f.addTag("er_debris");
            dropped++;
        }
        ec.level.sendParticles(ParticleTypes.POOF, at.x, at.y, at.z, 30, radius * 0.5, radius * 0.5, radius * 0.5, 0.1);
        if (!ec.quiet) NoiseBus.emit(ec.level, at, 48, ec.player);
    }
}
