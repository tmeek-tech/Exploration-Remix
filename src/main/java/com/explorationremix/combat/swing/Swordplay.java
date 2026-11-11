package com.explorationremix.combat.swing;

import com.explorationremix.mixin.accessor.LivingEntityAccessor;
import com.explorationremix.registry.ERSounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The timed swings on the server. A player's game says when each swing lands and what it hit; here each landing is
 * checked against the rules ({@link SwingRules}: no sooner than a swing can land), each target against the player's
 * reach, and the hits are dealt through the game's own attack, at full strength, so everything that hooks a melee hit
 * (the combat layer, the runes' energy, other mods) sees an ordinary one. While a swing's hit is being dealt,
 * {@link #landing} says which swing it is, for the combat layer's damage, poise and the strong attack's interrupt.
 */
public final class Swordplay {
    /** A swing whose hit is being dealt now. */
    public record Landing(ServerPlayer player, SwingKind kind, int combo, WeaponProfile weapon) {
        /** The chain's last light swing (the chop). */
        public boolean finisher() { return kind == SwingKind.LIGHT && combo >= weapon.chain() - 1; }

        public boolean strong() { return kind == SwingKind.STRONG; }
    }

    /** A strong attack's thrust hits at most this many creatures. */
    static final int STRONG_TARGETS = SwingNet.MAX_TARGETS;
    /** Reach to spare (blocks): the player's game saw the target a moment ago, and where they stood. */
    static final double LIGHT_SPARE = 1.5, LUNGE_SPARE = 2.5;

    private static final class P {
        final SwingRules.State rules = new SwingRules.State();
        int landSeq = Integer.MIN_VALUE;
        long landTick;
        SwingKind landKind = SwingKind.LIGHT;
        int landCombo;
        final Set<Integer> hit = new HashSet<>();
    }

    private static final Map<UUID, P> MAP = new HashMap<>();
    @Nullable private static Landing current;

    private Swordplay() {}

    /** The swing of {@code p}'s whose hit is being dealt right now, or null (a vanilla swing, another player's). */
    @Nullable
    public static Landing landing(ServerPlayer p) { return current != null && current.player == p ? current : null; }

    // ------------------------------------------------------------------------------------------------ messages

    static void started(ServerPlayer p, int seq, byte what, SwingKind kind, int combo) {
        if (!p.isAlive() || p.isSpectator() || Weapons.profile(p.getMainHandItem()) == null) return;
        p.resetLastActionTime();
        show(p, seq, what, kind, combo);
        if (what == SwingNet.UPGRADED) sound(p, ERSounds.SWING_CHARGE.get(), 0.6F, 1.0F);
    }

    static void landed(ServerPlayer p, int seq, SwingKind kind, int combo, boolean lunge, int[] targets) {
        if (!p.isAlive() || p.isSpectator() || !(p.level() instanceof ServerLevel level)) return;
        WeaponProfile w = Weapons.profile(p.getMainHandItem());
        if (w == null) return;
        P s = MAP.computeIfAbsent(p.getUUID(), u -> new P());
        long now = level.getGameTime();
        if (lunge) {
            // more of a strong attack's thrust, while its lunge lasts
            if (seq != s.landSeq || s.landKind != SwingKind.STRONG || now - s.landTick > w.lungeTicks() + SwingRules.SLACK + 2) return;
        } else {
            if (!SwingRules.land(s.rules, w, kind, seq, now)) return;
            s.landSeq = seq;
            s.landTick = now;
            s.landKind = kind;
            s.landCombo = Math.max(0, Math.min(combo, w.chain() - 1));
            s.hit.clear();
            show(p, seq, SwingNet.LANDED, kind, s.landCombo);
            whoosh(p, kind, s.landCombo, w);
        }
        int most = s.landKind == SwingKind.STRONG ? STRONG_TARGETS : 1;
        for (int id : targets) {
            if (s.hit.size() >= most) break;
            Entity e = level.getEntityOrPart(id);
            if (!fair(p, e, s.landKind, w)) continue;
            Entity root = e instanceof PartEntity<?> part ? part.getParent() : e;
            if (!s.hit.add(root.getId())) continue;
            strike(p, e, s.landKind, s.landCombo, w);
        }
    }

    /** Whether {@code p} may hit {@code e} with this swing: as the game's own attack allows, and within reach. */
    private static boolean fair(ServerPlayer p, @Nullable Entity e, SwingKind kind, WeaponProfile w) {
        if (e == null || e == p || !e.isAlive() || e.level() != p.level() || !e.isAttackable()) return false;
        if (e instanceof ItemEntity || e instanceof ExperienceOrb || e instanceof AbstractArrow) return false;
        if (e instanceof PartEntity<?> part && (part.getParent() == p || !part.getParent().isAlive())) return false;
        double spare = kind == SwingKind.STRONG ? w.strongReach() + LUNGE_SPARE : LIGHT_SPARE;
        return p.canReach(e, spare);
    }

    /** One hit, through the game's own attack at full strength. */
    private static void strike(ServerPlayer p, Entity e, SwingKind kind, int combo, WeaponProfile w) {
        ((LivingEntityAccessor) p).er$setAttackStrengthTicker(1 << 16);
        Landing before = current;
        current = new Landing(p, kind, combo, w);
        try {
            p.attack(e);
        } finally {
            current = before;
        }
        p.resetLastActionTime();
    }

    // ------------------------------------------------------------------------------------------------ the players round

    private static void show(ServerPlayer p, int seq, byte what, SwingKind kind, int combo) {
        SwingNet.CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> p),
                new SwingNet.Show(p.getId(), seq, what, (byte) kind.ordinal(), (byte) combo));
    }

    /** The swing's whoosh, for everyone near but the swinger (their own game plays it the moment it lands). */
    private static void whoosh(ServerPlayer p, SwingKind kind, int combo, WeaponProfile w) {
        if (kind == SwingKind.STRONG) sound(p, ERSounds.SWING_THRUST.get(), 1.0F, 1.0F);
        else if (combo >= w.chain() - 1) sound(p, ERSounds.SWING_HEAVY.get(), 0.9F, 1.0F);
        else sound(p, ERSounds.SWING_LIGHT.get(), 0.8F, 1.0F);
    }

    private static void sound(ServerPlayer p, SoundEvent s, float volume, float pitch) {
        p.level().playSound(p, p.getX(), p.getEyeY() - 0.3, p.getZ(), s, SoundSource.PLAYERS, volume, pitch * (0.92F + p.getRandom().nextFloat() * 0.16F));
    }

    public static void forget(ServerPlayer p) { MAP.remove(p.getUUID()); }

    public static void clear() {
        MAP.clear();
        current = null;
    }
}
