package com.explorationremix.rune.cast;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** What a trigger knows when it fires: who cast, what was hit (if anything), and where. */
public record TriggerContext(ServerPlayer player, @Nullable LivingEntity target, Vec3 origin, Vec3 direction, float damage) {
    public static TriggerContext of(ServerPlayer p, @Nullable LivingEntity target) {
        Vec3 origin = target != null ? target.position().add(0, target.getBbHeight() * 0.5, 0) : p.position().add(0, p.getBbHeight() * 0.5, 0);
        return new TriggerContext(p, target, origin, p.getLookAngle(), 0);
    }

    public TriggerContext withDamage(float amount) { return new TriggerContext(player, target, origin, direction, amount); }
}
