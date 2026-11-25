package com.explorationremix.client.rune;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.table.RuneTableView;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.armor.ArmorEffects;
import com.explorationremix.rune.armor.Sensed;
import com.explorationremix.rune.armor.WornRunes;
import com.explorationremix.rune.cast.LocalPull;
import com.explorationremix.rune.effect.Fx;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.ArmorSet;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.net.RuneNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.ComputeFovModifierEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * What worn runes do in the player's own game, which moves them (the server forgives the falls and marks what is
 * peered at: ArmorEffects):
 * <ul>
 *   <li>Glide (chestplate): hold jump while falling over a drop to sink slowly and drift where you look.</li>
 *   <li>Leap (boots): press jump in the air to jump once more before you land.</li>
 *   <li>Climb (leggings): jump at a ledge up to two blocks over your head and you pull yourself up onto it. A sheer
 *   wall stays sheer: there must be a lip to catch, and room to stand on it.</li>
 *   <li>Far Sight (helmet): hold the peer key (Z) to look far off, as through a spyglass; a creature held in view a
 *   moment is marked, glowing for ten seconds.</li>
 *   <li>Quickdraw on a bow: the view narrows with the draw at its own quicker pace.</li>
 * </ul>
 * The jump in the air, the pull and the creature peered at are told to the server (RuneNet.ArmorAct), which checks the
 * runes itself.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ArmorClient {
    // ---- Glide: the most it sinks a tick, the pace it drifts at, how fast it turns to where you look, and how far
    // below must be clear for it to start (blocks)
    static final double SINK = 0.1, GLIDE_SPEED = 0.36, GLIDE_TURN = 0.12;
    static final int GLIDE_CLEAR = 3;
    // ---- Leap: how hard it jumps, how much it carries you on the way you go, and how long after leaving the ground
    static final double LEAP_UP = 0.5, LEAP_PUSH = 0.12, LEAP_MOST = 0.45;
    static final int LEAP_AFTER = 3;
    // ---- Climb: how far over the head a ledge may be, how fast you rise and step over
    static final double OVER = 2.0, CLIMB_UP = 0.4, CLIMB_OVER = 0.25;
    static final int CLIMB_MOST = 30;
    // ---- Far Sight: how far the view narrows, how far it looks for a creature to mark and how long it must be held
    // in view, and how long before the same one is marked again (ms)
    static final float ZOOM = 0.2F;
    static final double MARK_RANGE = 128.0;
    static final int MARK_HOLD = 8;
    static final long MARK_AGAIN = 10_000L;

    private static boolean jumpWas;
    private static int airTicks, leaps;
    /** The feet's height when last on the ground. */
    private static double groundY = Double.NaN;
    private static boolean gliding;
    @Nullable
    private static Pull pull;
    private static boolean peering;
    private static int markId = -1, markTicks;
    private static final Map<Integer, Long> MARKED = new HashMap<>();

    private ArmorClient() {}

    /** Whether the player is peering through Far Sight now (the mouse turns slowly: PeerTurnMixin). */
    public static boolean peering() { return peering; }

    // ------------------------------------------------------------------------------------------------ moving

    /** A pull up onto a ledge underway: the height of the ledge's top, the way onto it, and how far it has come. */
    private static final class Pull {
        final double top, speed;
        final Direction face;
        int ticks, overTicks;
        double lastY;
        boolean over;

        Pull(double top, Direction face, double speed, double y) {
            this.top = top;
            this.face = face;
            this.speed = speed;
            this.lastY = y;
        }
    }

    /**
     * At the start of each of this game's player's ticks, before they move: a glide, a leap or a pull, from where they
     * really are.
     */
    @SubscribeEvent
    public static void move(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.START || e.side != LogicalSide.CLIENT) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || e.player != p) return;
        ArmorSet set = WornRunes.of(p);
        boolean jump = mc.screen == null && mc.options.keyJump.isDown();
        boolean pressed = jump && !jumpWas;
        jumpWas = jump;
        boolean grounded = p.onGround() || p.isInWater() || p.isInLava() || p.onClimbable();
        if (grounded) {
            airTicks = 0;
            leaps = 0;
            gliding = false;
            if (p.onGround()) groundY = p.getY();
        } else {
            airTicks++;
        }
        boolean free = p.isAlive() && !p.isSpectator() && !p.getAbilities().flying && !p.isPassenger() && !p.isFallFlying() && !p.isSleeping()
                && LocalPull.current() == null && !RuneTableView.active();
        if (pull != null) {
            if (!free || !set.has(RuneId.CLIMB) || p.isShiftKeyDown() || p.hurtTime > 0) pull = null;
            else if (steer(p)) return;
        }
        if (!free || set.isEmpty()) {
            gliding = false;
            return;
        }
        // Climb first: jump at a ledge in reach and you catch it
        if (set.has(RuneId.CLIMB) && !grounded && jump && p.input.forwardImpulse > 0.0F && startPull(p, set)) return;
        if (set.has(RuneId.LEAP) && pressed && !grounded && airTicks >= LEAP_AFTER && leaps < ArmorEffects.LEAPS) {
            leap(p);
            return;
        }
        if (set.has(RuneId.GLIDE) && jump && !grounded) {
            Vec3 v = p.getDeltaMovement();
            if (!gliding && v.y < -0.15 && clearBelow(p, GLIDE_CLEAR)) gliding = true;
            if (gliding) glide(p, set);
        } else {
            gliding = false;
        }
    }

    /** Leap: a jump off the air, on the way they are going; the server forgives the fall they had. */
    private static void leap(LocalPlayer p) {
        leaps++;
        Vec3 v = p.getDeltaMovement();
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        double hx = v.x, hz = v.z;
        if (p.input.forwardImpulse > 0.0F && flat.lengthSqr() > 1.0E-4) {
            flat = flat.normalize();
            hx += flat.x * LEAP_PUSH;
            hz += flat.z * LEAP_PUSH;
        }
        double h = Math.sqrt(hx * hx + hz * hz);
        if (h > LEAP_MOST) {
            hx *= LEAP_MOST / h;
            hz *= LEAP_MOST / h;
        }
        p.setDeltaMovement(hx, LEAP_UP + p.getJumpBoostPower(), hz);
        p.fallDistance = 0.0F;
        gliding = false;
        RuneNet.CHANNEL.sendToServer(new RuneNet.ArmorAct(RuneNet.ArmorAct.LEAP, 0));
        local(p, RuneFx.ARMOR_LEAP, ArmorEffects.ARMOR_BLUE);
        p.level().playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.PHANTOM_FLAP, SoundSource.PLAYERS, 0.45F, 1.7F, false);
    }

    /** Glide: the fall held to a slow sink, drifting on the way they look. */
    private static void glide(LocalPlayer p, ArmorSet set) {
        Vec3 v = p.getDeltaMovement();
        Vec3 look = p.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0, look.z);
        flat = flat.lengthSqr() < 1.0E-4 ? Vec3.ZERO : flat.normalize().scale(GLIDE_SPEED);
        p.setDeltaMovement(v.x + (flat.x - v.x) * GLIDE_TURN, Math.max(v.y, -SINK), v.z + (flat.z - v.z) * GLIDE_TURN);
        p.fallDistance = 0.0F;
        if (p.tickCount % 3 == 0) local(p, RuneFx.ARMOR_GLIDE, ArmorEffects.ARMOR_BLUE);
    }

    /** Whether nothing stands under the player for {@code blocks} blocks: a drop worth gliding over, not a hop. */
    private static boolean clearBelow(LocalPlayer p, int blocks) {
        AABB box = p.getBoundingBox();
        return p.level().noCollision(p, new AABB(box.minX, box.minY - blocks, box.minZ, box.maxX, box.minY, box.maxZ));
    }

    /**
     * Climb: a ledge in reach in front of them, whose top is up to two blocks over their head as they stood (three with
     * Scramble), with room to stand on it and room over them to rise to it. The pull starts at once.
     */
    private static boolean startPull(LocalPlayer p, ArmorSet set) {
        Level l = p.level();
        Direction face = Direction.fromYRot(p.getYRot());
        AABB box = p.getBoundingBox();
        double feet = p.getY();
        double from = Double.isNaN(groundY) ? feet : Math.min(groundY, feet);
        double top = from + p.getBbHeight() + OVER + 0.05;
        Vec3 c = box.getCenter();
        int bx = Mth.floor(c.x + face.getStepX() * (box.getXsize() / 2.0 + 0.4));
        int bz = Mth.floor(c.z + face.getStepZ() * (box.getZsize() / 2.0 + 0.4));
        for (int y = Mth.floor(feet + 0.5); y <= Mth.floor(top); y++) {
            BlockPos at = new BlockPos(bx, y, bz);
            BlockState state = l.getBlockState(at);
            VoxelShape shape = state.getCollisionShape(l, at);
            if (shape.isEmpty()) continue;
            double surface = y + shape.max(Direction.Axis.Y);
            if (surface > top) return false;
            // a step the game climbs itself, or one jumped onto
            if (surface - feet < 0.6) continue;
            AABB stand = p.getDimensions(Pose.STANDING).makeBoundingBox(bx + 0.5, surface + 0.001, bz + 0.5);
            if (!l.noCollision(p, stand)) continue;
            AABB rise = new AABB(box.minX, box.minY, box.minZ, box.maxX, surface + p.getBbHeight() + 0.05, box.maxZ);
            if (!l.noCollision(p, rise)) return false;
            pull = new Pull(surface, face, CLIMB_UP, feet);
            gliding = false;
            RuneNet.CHANNEL.sendToServer(new RuneNet.ArmorAct(RuneNet.ArmorAct.CLIMB, 0));
            local(p, RuneFx.ARMOR_CLIMB, ArmorEffects.ARMOR_BLUE);
            SoundType sound = state.getSoundType(l, at, p);
            l.playLocalSound(p.getX(), p.getY(), p.getZ(), sound.getStepSound(), SoundSource.PLAYERS, sound.getVolume() * 0.8F, sound.getPitch() * 0.9F, false);
            l.playLocalSound(p.getX(), p.getY(), p.getZ(), SoundEvents.ARMOR_EQUIP_LEATHER, SoundSource.PLAYERS, 0.6F, 1.1F, false);
            steer(p);
            return true;
        }
        return false;
    }

    /** The pull's tick: up beside the wall to the ledge's top, then over onto it. False once it is done. */
    private static boolean steer(LocalPlayer p) {
        Pull s = pull;
        s.ticks++;
        if (s.ticks > CLIMB_MOST) {
            pull = null;
            return false;
        }
        if (!s.over) {
            if (p.getY() >= s.top + 0.02) {
                s.over = true;
            } else {
                // stuck (something in the way after all): let go
                if (s.ticks > 3 && p.getY() <= s.lastY + 0.01) {
                    pull = null;
                    return false;
                }
                s.lastY = p.getY();
                p.setDeltaMovement(s.face.getStepX() * 0.03, s.speed, s.face.getStepZ() * 0.03);
                p.fallDistance = 0.0F;
                return true;
            }
        }
        s.overTicks++;
        if ((p.onGround() && s.overTicks > 1) || s.overTicks > 6) {
            pull = null;
            return false;
        }
        p.setDeltaMovement(s.face.getStepX() * CLIMB_OVER, 0.02, s.face.getStepZ() * CLIMB_OVER);
        p.fallDistance = 0.0F;
        return true;
    }

    /** What the player's own game draws at once for them (the server tells everyone else). */
    private static void local(LocalPlayer p, int what, int rgb) {
        if (RuneFx.client != null) RuneFx.client.play(Fx.of(Fx.Kind.ARMOR, p.position(), Vec3.ZERO, rgb, Element.NONE, 1.0F, what, p.getId()));
    }

    // ------------------------------------------------------------------------------------------------ Far Sight

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        boolean was = peering;
        ArmorSet set = p == null ? ArmorSet.NONE : WornRunes.of(p);
        peering = p != null && mc.screen == null && RuneKeys.PEER.isDown() && set.has(RuneId.FAR_SIGHT) && !RuneTableView.active();
        if (p != null && peering != was) {
            p.playSound(peering ? SoundEvents.SPYGLASS_USE : SoundEvents.SPYGLASS_STOP_USING, 0.7F, 1.25F);
        }
        if (peering) spot(p);
        else markId = -1;
    }

    /** Spotter: the creature held in the middle of the view for a moment is marked, once in a while each. */
    private static void spot(LocalPlayer p) {
        Vec3 eye = p.getEyePosition(), look = p.getViewVector(1.0F), end = eye.add(look.scale(MARK_RANGE));
        BlockHitResult block = p.level().clip(new ClipContext(eye, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        Vec3 to = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        AABB box = p.getBoundingBox().expandTowards(to.subtract(eye)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(p, eye, to, box,
                ent -> ent instanceof LivingEntity && !(ent instanceof Player) && ent.isAlive() && !ent.isSpectator(), eye.distanceToSqr(to));
        Entity seen = hit == null ? null : hit.getEntity();
        if (seen == null || seen.getId() != markId) {
            markId = seen == null ? -1 : seen.getId();
            markTicks = 0;
            return;
        }
        if (++markTicks != MARK_HOLD) return;
        long now = System.currentTimeMillis();
        Long last = MARKED.get(markId);
        if (last != null && now - last < MARK_AGAIN) return;
        MARKED.put(markId, now);
        if (MARKED.size() > 64) MARKED.values().removeIf(t -> now - t > MARK_AGAIN);
        RuneNet.CHANNEL.sendToServer(new RuneNet.ArmorAct(RuneNet.ArmorAct.MARK, markId));
    }

    /**
     * The view: narrowed far while peering through Far Sight (Spotter: further); and with Quickdraw on a bow, the draw's
     * own narrowing at its quicker pace (a full draw in ten ticks, not twenty).
     */
    @SubscribeEvent
    public static void fov(ComputeFovModifierEvent e) {
        Player p = e.getPlayer();
        float f = e.getNewFovModifier();
        if (p.isUsingItem() && p.getUseItem().is(Items.BOW) && RuneGear.passive(p.getUseItem()) == RuneId.QUICKDRAW) {
            int used = p.getTicksUsingItem();
            float plain = drawn(used / 20.0F), quick = drawn(used / 10.0F);
            f = f / (1.0F - plain * 0.15F) * (1.0F - quick * 0.15F);
        }
        if (peering && p == Minecraft.getInstance().player) f *= ZOOM;
        e.setNewFovModifier(f);
    }

    /** How the game eases a bow's narrowing: squared until full. */
    private static float drawn(float t) { return t >= 1.0F ? 1.0F : t * t; }

    /** Leaving a world: nothing of this one is kept. */
    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        pull = null;
        gliding = false;
        peering = false;
        groundY = Double.NaN;
        markId = -1;
        MARKED.clear();
        Sensed.clear();
    }
}
