package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.combat.SwingAnim.Hand;
import com.explorationremix.client.rune.fx.RuneCamera;
import com.explorationremix.combat.swing.SwingKind;
import com.explorationremix.combat.swing.SwingMachine;
import com.explorationremix.combat.swing.SwingNet;
import com.explorationremix.combat.swing.WeaponProfile;
import com.explorationremix.combat.swing.Weapons;
import com.explorationremix.mixin.accessor.LivingEntityAccessor;
import com.explorationremix.registry.ERSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.entity.PartEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The timed swings on the player's own game: the attack button, held or tapped, runs the swing machine
 * ({@link SwingMachine}) instead of vanilla's attack; a swing finds what it hits as it lands ({@link SwingAim}) and
 * tells the server; a strong attack's lunge moves the player. The swings of everyone in sight are kept here for drawing
 * ({@link #swing}), the player's own and other players' (the server says when theirs start and land).
 *
 * <p>A press on a block the weapon is meant for (an axe on wood, a sword on cobweb or leaves) with no creature in
 * reach still mines it the vanilla way, for as long as the button is held.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SwingClient {
    private static final SwingMachine MACHINE = new SwingMachine(WeaponProfile.SWORD);
    /** This game's ticks (they stop while it is paused): the swings' clock. */
    private static int clock;
    /** This hold of the attack button mines the vanilla way. */
    private static boolean mining;
    /** The item the machine is swinging, so another taken in hand drops the swing. */
    private static ItemStack swinging = ItemStack.EMPTY;
    private static int swingingSlot = -1;
    /** A strong attack's lunge: the way, and the creatures it already struck. */
    private static Vec3 lungeWay = Vec3.ZERO;
    private static final Set<Integer> lungeStruck = new HashSet<>();
    /** When a press could start nothing (for the HUD's flicker). */
    static int refusedAt = -100;
    /** The swings' clock held still at this (dev pictures), or below 0: running. */
    private static float heldAt = -1.0F;

    /** One swing to draw: the player's own or another player's. */
    static final class Swing {
        SwingKind kind;
        int combo;
        final int lightCombo;
        final WeaponProfile weapon;
        /** On the swings' clock, with the frame's part tick: when it started, when it became strong (ticks in), cancelled. */
        float start, upgradeAt = -1.0F, cancelAt = -1.0F;
        Hand from, cancelFrom = Hand.REST;
        int seq;

        Swing(int seq, SwingKind kind, int combo, WeaponProfile weapon, float start, Hand from) {
            this.seq = seq;
            this.kind = kind;
            this.combo = combo;
            this.lightCombo = combo;
            this.weapon = weapon;
            this.start = start;
            this.from = from;
        }

        float land() { return weapon.windup(kind); }

        /** How long after landing the hand stays at the end of its cut: a light swing's recovery (a chained swing starts
         *  from there), a strong attack's lunge. */
        float hold() { return kind == SwingKind.STRONG ? weapon.lungeTicks() : weapon.lightRecover(); }

        SwingAnim.Keys keys() { return SwingAnim.keys(kind, combo, weapon); }

        boolean over(float now) {
            if (cancelAt >= 0.0F) return now - cancelAt > CANCEL_EASE;
            return now - start > SwingAnim.length(land(), hold(), kind == SwingKind.STRONG);
        }
    }

    /** A dropped swing's hand goes back to rest in this many ticks. */
    static final float CANCEL_EASE = 4.0F;

    /** Everyone's swings, by player id. */
    private static final Map<Integer, Swing> SWINGS = new HashMap<>();

    private SwingClient() {}

    /** The swings' clock now, with the frame's part tick (held still for a dev picture, see {@link #devPose}). */
    static float now(float partialTick) { return heldAt >= 0.0F ? heldAt : clock + partialTick; }

    @Nullable
    static Swing swing(int playerId) { return SWINGS.get(playerId); }

    static SwingMachine machine() { return MACHINE; }

    // ------------------------------------------------------------------------------------------------ the attack button

    /** Minecraft.startAttack, first thing: true if this press is a timed swing's (vanilla's attack is then skipped). */
    public static boolean press() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || p.isSpectator() || mc.level == null) return false;
        WeaponProfile w = Weapons.profile(p.getMainHandItem());
        if (w == null) return false;
        if (mines(mc, p, w)) {
            mining = true;
            return false;
        }
        mining = false;
        follow(p, w);
        MACHINE.press(OUT);
        return true;
    }

    /** Minecraft.continueAttack, first thing: true to skip vanilla's mining while the button is held for a swing. */
    public static boolean holdOnBlock(boolean held) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (!held || p == null || mining) return false;
        return Weapons.profile(p.getMainHandItem()) != null;
    }

    /**
     * Whether this press mines: the crosshair on a block the weapon is meant for (or, in creative, any block, with an
     * axe), and no creature in the swing's reach to swing at instead.
     */
    private static boolean mines(Minecraft mc, LocalPlayer p, WeaponProfile w) {
        HitResult hr = mc.hitResult;
        if (!(hr instanceof BlockHitResult bhr) || hr.getType() != HitResult.Type.BLOCK) return false;
        BlockState st = p.level().getBlockState(bhr.getBlockPos());
        if (st.isAir()) return false;
        ItemStack stack = p.getMainHandItem();
        boolean meant = stack.getDestroySpeed(st) > 1.0F || (p.isCreative() && !(stack.getItem() instanceof SwordItem));
        return meant && SwingAim.best(p, reach(p, SwingKind.LIGHT, w), w.lightCone()) == null;
    }

    static double reach(LocalPlayer p, SwingKind kind, WeaponProfile w) {
        double base = p.getEntityReach();
        return kind == SwingKind.STRONG ? base + w.strongReach() : base + 0.25;
    }

    /** The machine follows the weapon in hand: another one drops the swing under way (the cooldown carries on). */
    private static void follow(LocalPlayer p, WeaponProfile w) {
        ItemStack main = p.getMainHandItem();
        int slot = p.getInventory().selected;
        if (MACHINE.busy() && (slot != swingingSlot || !ItemStack.isSameItem(main, swinging) || MACHINE.profile() != w)) MACHINE.cancel(OUT);
        MACHINE.profile(w);
        swinging = main;
        swingingSlot = slot;
    }

    // ------------------------------------------------------------------------------------------------ time

    /** The player's own tick, before they move: the machine's tick, and a lunge's push. */
    @SubscribeEvent
    public static void playerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.START || e.side != LogicalSide.CLIENT) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || e.player != p) return;
        WeaponProfile w = Weapons.profile(p.getMainHandItem());
        if (w != null) {
            // vanilla's charge is gone for these weapons: kept full, so its bar never shows and the hand never dips
            ((LivingEntityAccessor) p).er$setAttackStrengthTicker(1 << 16);
        }
        if (!p.isAlive()) {
            MACHINE.reset();
            return;
        }
        if (MACHINE.busy()) {
            boolean other = w == null || p.getInventory().selected != swingingSlot || !ItemStack.isSameItem(p.getMainHandItem(), swinging);
            if (other || p.isUsingItem() || mc.screen != null || p.isSpectator()) MACHINE.cancel(OUT);
        }
        if (w != null) MACHINE.profile(w);
        boolean held = mc.screen == null && mc.options.keyAttack.isDown() && mc.mouseHandler.isMouseGrabbed();
        if (!held) mining = false;
        MACHINE.tick(held && !mining, OUT);
    }

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) return;
        clock++;
        float now = now(0.0F);
        for (Iterator<Map.Entry<Integer, Swing>> it = SWINGS.entrySet().iterator(); it.hasNext(); ) {
            if (it.next().getValue().over(now)) it.remove();
        }
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        MACHINE.reset();
        SWINGS.clear();
        mining = false;
    }

    // ------------------------------------------------------------------------------------------------ the machine's news

    private static final SwingMachine.Out OUT = new SwingMachine.Out() {
        @Override
        public void start(int seq, SwingKind kind, int combo) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return;
            begin(p.getId(), seq, kind, combo, MACHINE.profile());
            send(new SwingNet.Start(seq, SwingNet.STARTED, (byte) kind.ordinal(), (byte) combo));
        }

        @Override
        public void upgrade(int seq) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return;
            upgraded(p.getId(), seq);
            send(new SwingNet.Start(seq, SwingNet.UPGRADED, (byte) SwingKind.STRONG.ordinal(), (byte) 0));
            play(p, ERSounds.SWING_CHARGE.get(), 0.55F, 1.0F);
        }

        @Override
        public void land(int seq, SwingKind kind, int combo) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (p == null) return;
            WeaponProfile w = MACHINE.profile();
            landed(p.getId(), seq);
            double cone = kind == SwingKind.STRONG ? w.strongCone() : combo >= w.chain() - 1 ? w.lightCone() * 0.75 : w.lightCone();
            List<SwingAim.Target> found = SwingAim.find(p, reach(p, kind, w), cone);
            int most = kind == SwingKind.STRONG ? 6 : 1;
            List<Integer> ids = new ArrayList<>();
            lungeStruck.clear();
            for (SwingAim.Target t : found) {
                if (ids.size() >= most) break;
                ids.add(t.entity().getId());
                lungeStruck.add(root(t.entity()).getId());
            }
            send(new SwingNet.Land(seq, (byte) kind.ordinal(), (byte) combo, false, ids.stream().mapToInt(Integer::intValue).toArray()));
            boolean finisher = kind == SwingKind.LIGHT && combo >= w.chain() - 1;
            if (kind == SwingKind.STRONG) {
                play(p, ERSounds.SWING_THRUST.get(), 0.9F, 1.0F);
                Vec3 look = p.getViewVector(1.0F);
                Vec3 flat = new Vec3(look.x, 0.0, look.z);
                lungeWay = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize();
                RuneCamera.lunge();
            } else {
                play(p, finisher ? ERSounds.SWING_HEAVY.get() : ERSounds.SWING_LIGHT.get(), finisher ? 0.85F : 0.7F, 1.0F);
            }
            if (!ids.isEmpty()) {
                float k = kind == SwingKind.STRONG ? 1.8F : finisher ? 1.2F : 0.8F;
                RuneCamera.kick(k, (p.getRandom().nextFloat() - 0.5F) * 0.5F * k);
                if (kind == SwingKind.STRONG) RuneCamera.shake(0.3F);
            }
        }

        @Override
        public void lunge(int seq, int tick, double speed) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return;
            push(p, speed);
            // what the thrust runs into as the lunge carries it
            WeaponProfile w = MACHINE.profile();
            List<Integer> ids = new ArrayList<>();
            for (SwingAim.Target t : SwingAim.find(p, reach(p, SwingKind.STRONG, w), w.strongCone())) {
                if (lungeStruck.size() >= 6) break;
                if (lungeStruck.add(root(t.entity()).getId())) ids.add(t.entity().getId());
            }
            if (!ids.isEmpty()) {
                // the blade bit: the lunge stops in it
                lungeWay = Vec3.ZERO;
                Vec3 v = p.getDeltaMovement();
                p.setDeltaMovement(v.x * 0.2, v.y, v.z * 0.2);
                send(new SwingNet.Land(seq, (byte) SwingKind.STRONG.ordinal(), (byte) 0, true, ids.stream().mapToInt(Integer::intValue).toArray()));
                RuneCamera.kick(1.8F, 0.0F);
            }
        }

        @Override
        public void cancel(int seq) {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p == null) return;
            cancelled(p.getId(), seq);
            send(new SwingNet.Start(seq, SwingNet.CANCELLED, (byte) 0, (byte) 0));
        }

        @Override
        public void refused() {
            refusedAt = clock;
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) play(p, SoundEvents.UI_BUTTON_CLICK.get(), 0.08F, 1.8F);
        }
    };

    /**
     * The lunge's push along its way, at {@code speed} blocks a tick. It stops short of a drop: never off a ledge more
     * than a couple of blocks high (a cave is full of them), and never while the player hangs on a ladder or swims.
     */
    private static void push(LocalPlayer p, double speed) {
        if (lungeWay == Vec3.ZERO || p.isInWater() || p.onClimbable() || p.isFallFlying() || p.isPassenger()) return;
        Vec3 step = lungeWay.scale(speed);
        // the thrust is in what it struck: no further into it
        AABB next = p.getBoundingBox().move(step.x * 1.5, 0.0, step.z * 1.5);
        if (!p.level().getEntities(p, next, e -> lungeStruck.contains(root(e).getId())).isEmpty()) {
            lungeWay = Vec3.ZERO;
            return;
        }
        if (p.onGround()) {
            AABB ahead = p.getBoundingBox().move(step.x * 2.0, 0.0, step.z * 2.0).expandTowards(0.0, -2.6, 0.0);
            if (p.level().noCollision(p, ahead)) {
                lungeWay = Vec3.ZERO;
                return;
            }
        }
        Vec3 v = p.getDeltaMovement();
        // never slower than the player already was going that way
        double along = v.x * lungeWay.x + v.z * lungeWay.z;
        if (along >= speed) return;
        p.setDeltaMovement(step.x + (v.x - lungeWay.x * along), v.y, step.z + (v.z - lungeWay.z * along));
    }

    private static Entity root(Entity e) { return e instanceof PartEntity<?> part ? part.getParent() : e; }

    private static void send(Object msg) {
        if (Minecraft.getInstance().getConnection() != null) SwingNet.CHANNEL.sendToServer(msg);
    }

    private static void play(LocalPlayer p, SoundEvent s, float volume, float pitch) {
        p.level().playLocalSound(p.getX(), p.getEyeY() - 0.3, p.getZ(), s, SoundSource.PLAYERS, volume,
                pitch * (0.94F + p.getRandom().nextFloat() * 0.12F), false);
    }

    // ------------------------------------------------------------------------------------------------ swings to draw

    private static void begin(int player, int seq, SwingKind kind, int combo, WeaponProfile w) {
        float now = clock + Minecraft.getInstance().getFrameTime();
        Swing old = SWINGS.get(player);
        if (old != null && old.seq == seq && old.cancelAt < 0.0F) {
            // the same swing, changed (a strong attack out of the cooldown let go as the cooldown ran out: a light one)
            old.kind = kind;
            old.combo = combo;
            old.upgradeAt = -1.0F;
            return;
        }
        Hand from = old == null ? Hand.REST : SwingRender.handAt(old, now);
        SWINGS.put(player, new Swing(seq, kind, combo, w, now, from));
    }

    private static void upgraded(int player, int seq) {
        Swing s = SWINGS.get(player);
        if (s == null || s.seq != seq || s.kind == SwingKind.STRONG) return;
        s.upgradeAt = clock + Minecraft.getInstance().getFrameTime() - s.start;
        s.kind = SwingKind.STRONG;
    }

    private static void landed(int player, int seq) {
        Swing s = SWINGS.get(player);
        if (s == null || s.seq != seq) return;
        // the cut is drawn crossing the middle as the swing lands
        float now = clock + Minecraft.getInstance().getFrameTime();
        float tau = now - s.start;
        if (Math.abs(tau - s.land()) > 0.5F) s.start = now - s.land();
    }

    private static void cancelled(int player, int seq) {
        Swing s = SWINGS.get(player);
        if (s == null || s.seq != seq || s.cancelAt >= 0.0F) return;
        float now = clock + Minecraft.getInstance().getFrameTime();
        s.cancelFrom = SwingRender.handAt(s, now);
        s.cancelAt = now;
    }

    /**
     * Dev pictures only (the shot driver's "! swing" step): the player's own swing of {@code kind}, {@code combo} in the
     * chain, shown {@code tau} ticks in and held there, the swings' clock stopped (a strong one became strong where a
     * held press does); {@code tau} below 0 lets the clock run again and drops it.
     */
    public static void devPose(SwingKind kind, int combo, float tau) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        if (tau < 0.0F) {
            heldAt = -1.0F;
            SWINGS.remove(p.getId());
            return;
        }
        WeaponProfile w = Weapons.profile(p.getMainHandItem());
        if (w == null) return;
        heldAt = clock;
        SWINGS.remove(p.getId());
        int c = kind == SwingKind.STRONG ? 0 : Math.max(0, Math.min(combo, w.chain() - 1));
        Swing s = new Swing(-1, kind == SwingKind.STRONG ? SwingKind.LIGHT : kind, c, w, heldAt - tau, Hand.REST);
        if (kind == SwingKind.STRONG) {
            s.kind = SwingKind.STRONG;
            s.upgradeAt = w.holdAt() - 1.0F;
        }
        SWINGS.put(p.getId(), s);
        swinging = p.getMainHandItem();
        swingingSlot = p.getInventory().selected;
    }

    /** Another player's swing, as the server tells it. */
    static void show(SwingNet.Show s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || (mc.player != null && s.player() == mc.player.getId())) return;
        Entity e = mc.level.getEntity(s.player());
        if (!(e instanceof net.minecraft.world.entity.player.Player other)) return;
        WeaponProfile w = Weapons.profile(other.getMainHandItem());
        if (w == null) w = WeaponProfile.SWORD;
        switch (s.what()) {
            case SwingNet.STARTED -> begin(s.player(), s.seq(), s.swingKind(), s.combo(), w);
            case SwingNet.UPGRADED -> upgraded(s.player(), s.seq());
            case SwingNet.LANDED -> {
                Swing sw = SWINGS.get(s.player());
                if (sw == null || sw.seq != s.seq()) begin(s.player(), s.seq(), s.swingKind(), s.combo(), w);
                landed(s.player(), s.seq());
            }
            case SwingNet.CANCELLED -> cancelled(s.player(), s.seq());
            default -> {}
        }
    }
}
