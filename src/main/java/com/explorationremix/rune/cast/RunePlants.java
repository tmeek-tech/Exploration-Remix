package com.explorationremix.rune.cast;

import com.explorationremix.block.GlowbellBlock;
import com.explorationremix.block.HangingFernBlock;
import com.explorationremix.registry.ERBlocks;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.FrondPlan;
import com.explorationremix.rune.world.FrondBlock;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What runes do to the caves' plants (0.18.7: Taran, "just enough to make the plants more interesting than just
 * decorative"). Any rune that touches one sets it off, whatever the rune: a bolt or a rune-carrying arrow flying through
 * it, a Burst or a Field round it, a Cleave across it.
 * <ul>
 *   <li>A glowbell rings: a ring of light goes out from it, and each creature it passes, out to {@link #BELL_REACH}
 *   blocks, glows through the rock for {@link #BELL_GLOW} ticks. Then it rests (GlowbellBlock), dim, and can't ring.</li>
 *   <li>A silver fern hanging on a wall unrolls its fronds along the rock: up to the lip of the wall and down to the
 *   floor (FrondPlan), a curtain you can climb. A hanging silver frond unrolls down to the floor. They stay
 *   {@link #CURTAIN_TICKS} ticks, held a little longer while someone is on them, shiver, and roll back up.</li>
 *   <li>A frostberry vine bursts in a puff of cold that sinks to the floor or the pool under it: the creatures round
 *   it and under it slow and frost over, and still water there freezes over to walk on (Frost's rune ice) for a while.
 *   Then the vine rests {@link #BERRY_REST} ticks.</li>
 * </ul>
 * A cast sets off a few plants of each kind at most, and only where its caster could change the blocks themself
 * (TempBlocks.mayChange). Nothing here is saved: a curtain left down when the server stops rolls up by its blocks' own
 * timers.
 */
public final class RunePlants {
    static final int BELL = 0, FERN = 1, BERRY = 2;
    /** At most this many of each kind set off by one cast (a curtain is up to a few dozen blocks: two at once). */
    static final int[] PER_CAST = {3, 2, 4};

    /** How far a glowbell's ring shows creatures, how fast it goes out (blocks a tick), and how long they glow (ticks). */
    public static final double BELL_REACH = 16.0, BELL_SPEED = 1.25;
    public static final int BELL_GLOW = 120;

    /** Fronds unroll a block every this many ticks; stay down this long; are held this much longer at most while climbed. */
    static final int UNROLL_EVERY = 2, CURTAIN_TICKS = 600, HOLD_MAX = 200, HOLD_STEP = 20;
    /** They shiver this long before they roll up. */
    static final int SHIVER = 40;

    /** A frostberry vine's cold: how far it reaches, how long the vine rests after, and how long creatures slow. */
    public static final double BERRY_REACH = 3.5;
    static final int BERRY_REST = 600, BERRY_SLOW = 60, BERRY_FROST = 100;
    /** How far down its cold falls (to the floor, or water to freeze), and how wide it freezes the water. */
    static final int BERRY_FALL = 12, BERRY_FREEZE = 3;

    private record Ping(ServerLevel level, int entity, long due) {}

    private static final List<Ping> PINGS = new ArrayList<>();
    private static final List<Curtain> CURTAINS = new ArrayList<>();
    /** The game tick each frostberry vine (by its top block) can burst again. */
    private static final Map<Long, Long> BERRY_RESTS = new HashMap<>();

    private RunePlants() {}

    // ------------------------------------------------------------------------------------------------ touching

    /** A rune flying from {@code a} to {@code b} this tick: the plants it passes through. */
    public static void path(CastContext ctx, Vec3 a, Vec3 b) {
        double len = a.distanceTo(b);
        int n = Math.max(1, Mth.ceil(len / 0.25));
        long last = Long.MIN_VALUE;
        for (int i = 0; i <= n; i++) {
            BlockPos p = BlockPos.containing(a.lerp(b, (double) i / n));
            long k = p.asLong();
            if (k == last) continue;
            last = k;
            touch(ctx, p);
        }
    }

    /** A blast or a field round {@code c}: the plants within {@code r} of it, nearest first, not round a wall. */
    public static void area(CastContext ctx, Vec3 c, double r) {
        touchAll(ctx, c, near(ctx, c, r, null, 0.0, 0.0));
    }

    /**
     * A slash from {@code from} along {@code flat}, reaching {@code range}, its arc {@code cosHalf} (the cosine of half
     * of it): the plants in it.
     */
    public static void arc(CastContext ctx, Vec3 from, Vec3 flat, double range, double cosHalf) {
        touchAll(ctx, from, near(ctx, from, range, flat, cosHalf, 2.5));
    }

    private static List<BlockPos> near(CastContext ctx, Vec3 c, double r, @Nullable Vec3 flat, double cosHalf, double height) {
        ServerLevel l = ctx.level;
        List<BlockPos> found = new ArrayList<>();
        int ri = Mth.ceil(r);
        BlockPos o = BlockPos.containing(c);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = -ri; dy <= ri; dy++) {
            for (int dx = -ri; dx <= ri; dx++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    Vec3 bc = Vec3.atCenterOf(m);
                    Vec3 to = bc.subtract(c);
                    if (to.lengthSqr() > (r + 0.5) * (r + 0.5) || !l.isLoaded(m) || kind(l.getBlockState(m)) < 0) continue;
                    if (flat != null) {
                        if (Math.abs(to.y) > height) continue;
                        Vec3 tf = Origin.flat(to);
                        if (tf != Vec3.ZERO && tf.dot(flat) < cosHalf && to.lengthSqr() > 1.0) continue;
                    }
                    found.add(m.immutable());
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(c)));
        return found;
    }

    private static void touchAll(CastContext ctx, Vec3 c, List<BlockPos> plants) {
        for (BlockPos p : plants) {
            if (RuneHits.clear(ctx.level, c, Vec3.atCenterOf(p), ctx.owner)) touch(ctx, p);
        }
    }

    /** Which kind of plant this is (BELL, FERN, BERRY), or -1 for anything that doesn't answer runes. */
    static int kind(BlockState s) {
        Block b = s.getBlock();
        if (b instanceof GlowbellBlock) return BELL;
        if (b == ERBlocks.SILVER_WALL_FERN.get() || b == ERBlocks.SILVER_FROND.get() || b == ERBlocks.SILVER_FROND_PLANT.get()) return FERN;
        if (b == ERBlocks.FROSTBERRY_VINE.get() || b == ERBlocks.FROSTBERRY_VINE_PLANT.get()) return BERRY;
        return -1;
    }

    /** A rune touches the block at {@code pos}: a plant there answers it, once per cast. */
    static void touch(CastContext ctx, BlockPos pos) {
        ServerLevel l = ctx.level;
        if (!l.isLoaded(pos)) return;
        BlockState s = l.getBlockState(pos);
        int k = kind(s);
        if (k < 0 || !ready(l, pos, s, k)) return;
        if (!TempBlocks.mayChange(l, pos, ctx.owner)) return;
        if (!ctx.firstPlant(pos.asLong(), k, PER_CAST[k])) return;
        switch (k) {
            case BELL -> ring(ctx, pos, s);
            case FERN -> unroll(ctx, pos, s);
            case BERRY -> burst(ctx, pos);
            default -> {}
        }
    }

    /** Whether the plant can answer now: a bell not resting, a fern not unrolled, a vine not resting. */
    private static boolean ready(ServerLevel l, BlockPos pos, BlockState s, int k) {
        return switch (k) {
            case BELL -> !s.getValue(GlowbellBlock.RESTING);
            case FERN -> s.getBlock() instanceof HangingFernBlock ? !s.getValue(HangingFernBlock.UNROLLED) && curtainAt(l, pos) == null
                    : curtainAt(l, frondTip(l, pos)) == null;
            case BERRY -> BERRY_RESTS.getOrDefault(vineTop(l, pos).asLong(), Long.MIN_VALUE) <= l.getGameTime();
            default -> false;
        };
    }

    // ------------------------------------------------------------------------------------------------ glowbells

    private static void ring(CastContext ctx, BlockPos pos, BlockState s) {
        ServerLevel l = ctx.level;
        GlowbellBlock.rest(l, pos, s);
        Vec3 c = Vec3.atCenterOf(pos).add(0.0, 0.05, 0.0);
        RuneFx.bell(l, c, BELL_REACH);
        long now = l.getGameTime();
        for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, new AABB(c, c).inflate(BELL_REACH), e -> shown(ctx.owner, e))) {
            double d = e.getBoundingBox().getCenter().distanceTo(c);
            if (d > BELL_REACH || PINGS.size() >= 512) continue;
            PINGS.add(new Ping(l, e.getId(), now + Math.round(d / BELL_SPEED)));
        }
    }

    /** Who a glowbell's ring shows: every creature but players, bats and the like, and armor stands. */
    static boolean shown(ServerPlayer owner, LivingEntity e) {
        return e != owner && e.isAlive() && !e.isSpectator() && !(e instanceof Player) && !(e instanceof AmbientCreature) && !(e instanceof ArmorStand);
    }

    // ------------------------------------------------------------------------------------------------ silver ferns

    /** One struck fern's or frond's fronds, from unrolling to rolling up again. */
    private static final class Curtain {
        final ServerLevel level;
        final ServerPlayer owner;
        /** The fern (whose state shows it unrolled), or the hanging frond's tip. */
        final BlockPos anchor;
        final boolean fern;
        final List<FrondPlan.Step> steps;
        final BlockPos[] at;
        final BlockState[] states;
        final boolean[] placed;
        final int length;
        int age, rollAt, held, rolled;
        boolean shivered;

        Curtain(ServerLevel level, ServerPlayer owner, BlockPos anchor, boolean fern, List<FrondPlan.Step> steps, BlockPos[] at, BlockState[] states) {
            this.level = level;
            this.owner = owner;
            this.anchor = anchor;
            this.fern = fern;
            this.steps = steps;
            this.at = at;
            this.states = states;
            this.placed = new boolean[steps.size()];
            this.length = FrondPlan.length(steps);
            this.rollAt = (length - 1) * UNROLL_EVERY + CURTAIN_TICKS;
        }
    }

    @Nullable
    private static Curtain curtainAt(ServerLevel l, BlockPos anchor) {
        for (Curtain c : CURTAINS) if (c.level == l && c.anchor.equals(anchor)) return c;
        return null;
    }

    /** The lowest block of the hanging frond {@code pos} is part of (its tip). */
    private static BlockPos frondTip(ServerLevel l, BlockPos pos) {
        BlockPos p = pos;
        for (int i = 0; i < 24; i++) {
            BlockState below = l.getBlockState(p.below());
            if (!below.is(ERBlocks.SILVER_FROND.get()) && !below.is(ERBlocks.SILVER_FROND_PLANT.get())) break;
            p = p.below();
        }
        return p;
    }

    private static void unroll(CastContext ctx, BlockPos pos, BlockState s) {
        ServerLevel l = ctx.level;
        boolean fern = s.getBlock() instanceof HangingFernBlock;
        BlockPos anchor = fern ? pos : frondTip(l, pos);
        Direction facing = fern ? s.getValue(HangingFernBlock.FACING) : Direction.DOWN;
        Direction along = fern ? facing.getClockWise() : Direction.EAST;
        Direction wall = facing.getOpposite();
        FrondPlan.Probe probe = new FrondPlan.Probe() {
            @Override
            public boolean open(int side, int dy) {
                BlockPos p = anchor.relative(along, side).above(dy);
                return l.isLoaded(p) && l.getBlockState(p).isAir();
            }

            @Override
            public boolean rock(int side, int dy) {
                if (!fern) return false;
                BlockPos p = anchor.relative(along, side).above(dy).relative(wall);
                return l.isLoaded(p) && l.getBlockState(p).isFaceSturdy(l, p, facing);
            }
        };
        List<FrondPlan.Step> steps = fern ? FrondPlan.fern(probe, anchor.asLong()) : FrondPlan.frond(probe);
        if (steps.isEmpty()) return;
        BlockPos[] at = new BlockPos[steps.size()];
        BlockState[] states = new BlockState[steps.size()];
        BlockState frond = com.explorationremix.rune.RuneRegistry.FROND.get().defaultBlockState();
        for (int i = 0; i < steps.size(); i++) {
            FrondPlan.Step st = steps.get(i);
            at[i] = anchor.relative(along, st.side()).above(st.dy());
            // a block still growing down shows the fronds' tip until the next one grows below it; a column beside the
            // fern's starts partway up the wall, its highest block showing the fronds' tops
            states[i] = frond.setValue(FrondBlock.FACING, st.onRock() ? facing : Direction.DOWN).setValue(FrondBlock.TIP, st.dy() < 0)
                    .setValue(FrondBlock.TOP, st.side() != 0 && highest(steps, st));
        }
        if (fern) l.setBlock(pos, s.setValue(HangingFernBlock.UNROLLED, true), Block.UPDATE_ALL);
        RuneFx.unroll(l, Vec3.atCenterOf(anchor), new Vec3(0, -1, 0), 0);
        Curtain c = new Curtain(l, ctx.owner, anchor, fern, steps, at, states);
        CURTAINS.add(c);
        step(c);
    }

    /** Whether no step of {@code st}'s column is above it. */
    private static boolean highest(List<FrondPlan.Step> steps, FrondPlan.Step st) {
        for (FrondPlan.Step o : steps) if (o.side() == st.side() && o.dy() > st.dy()) return false;
        return true;
    }

    /** A curtain's tick: unrolling, down, held while someone is on it, shivering, rolling up. True once it is gone. */
    private static boolean step(Curtain c) {
        ServerLevel l = c.level;
        if (!l.isLoaded(c.anchor)) return true;           // its blocks' own timers end it
        int age = c.age++;
        // unrolling: the steps whose time has come
        if (age % UNROLL_EVERY == 0 && age / UNROLL_EVERY < c.length) {
            int order = age / UNROLL_EVERY;
            for (int i = 0; i < c.steps.size(); i++) {
                FrondPlan.Step s = c.steps.get(i);
                if (s.order() != order || c.placed[i] || c.owner.isRemoved()) continue;
                if (!TempBlocks.place(l, c.at[i], c.states[i], c.owner)) continue;
                c.placed[i] = true;
                // the block above it in its column, growing down, is no longer the tip
                if (s.dy() < 0) untip(c, s.side(), s.dy() + 1);
                if (s.side() == 0) RuneFx.unroll(l, Vec3.atCenterOf(c.at[i]), new Vec3(0, s.dy() < 0 ? -1 : 1, 0), order + 1);
            }
            // all down: each column's lowest block that went in is its tip, the rest show the fronds' middle
            if (order == c.length - 1) settleTips(c);
        }
        if (age == c.rollAt - SHIVER) {
            if (c.held < HOLD_MAX && climbed(c)) {
                c.rollAt += HOLD_STEP;
                c.held += HOLD_STEP;
            } else {
                c.shivered = true;
                RuneFx.shiver(l, Vec3.atCenterOf(c.anchor));
                for (int i = 0; i < c.steps.size(); i++) {
                    if (c.placed[i] && c.steps.get(i).side() == 0 && i % 3 == 0) RuneFx.unroll(l, Vec3.atCenterOf(c.at[i]), new Vec3(0, 1, 0), 1);
                }
            }
        }
        if (age >= c.rollAt) {
            // rolling up: the last to unroll go first, so both ends roll in toward the fern
            int order = c.length - 1 - c.rolled++;
            for (int i = 0; i < c.steps.size(); i++) {
                if (c.steps.get(i).order() != order || !c.placed[i]) continue;
                c.placed[i] = false;
                BlockState now = l.getBlockState(c.at[i]);
                if (now.getBlock() instanceof FrondBlock fb && TempBlocks.standing(l.dimension(), c.at[i])) fb.fade(l, c.at[i]);
            }
            if (order % 4 == 0) RuneFx.rollUp(l, Vec3.atCenterOf(c.anchor));
            if (order <= 0) {
                rolledUp(c);
                return true;
            }
        }
        return false;
    }

    /** The block at {@code (side, dy)} in the curtain, if it is down, now shows the fronds' middle, not their tip. */
    private static void untip(Curtain c, int side, int dy) {
        for (int i = 0; i < c.steps.size(); i++) {
            FrondPlan.Step s = c.steps.get(i);
            if (s.side() == side && s.dy() == dy && c.placed[i]) untipAt(c, i);
        }
    }

    /** Once a curtain is all down: a block is drawn as a tip only where no block of its column went in below it. */
    private static void settleTips(Curtain c) {
        for (int i = 0; i < c.steps.size(); i++) {
            FrondPlan.Step s = c.steps.get(i);
            if (!c.placed[i] || s.dy() >= 0) continue;
            for (int j = 0; j < c.steps.size(); j++) {
                FrondPlan.Step b = c.steps.get(j);
                if (c.placed[j] && b.side() == s.side() && b.dy() == s.dy() - 1) {
                    untipAt(c, i);
                    break;
                }
            }
        }
    }

    private static void untipAt(Curtain c, int i) {
        BlockState now = c.level.getBlockState(c.at[i]);
        if (now.getBlock() instanceof FrondBlock && now.getValue(FrondBlock.TIP)) c.level.setBlock(c.at[i], now.setValue(FrondBlock.TIP, false), Block.UPDATE_CLIENTS);
    }

    /** Whether a player is on the curtain now (in one of its blocks, or the unrolled fern). */
    private static boolean climbed(Curtain c) {
        Set<BlockPos> blocks = new HashSet<>();
        blocks.add(c.anchor);
        for (int i = 0; i < c.at.length; i++) if (c.placed[i]) blocks.add(c.at[i]);
        for (ServerPlayer p : c.level.players()) {
            if (p.isSpectator() || p.distanceToSqr(Vec3.atCenterOf(c.anchor)) > 32 * 32) continue;
            if (blocks.contains(p.blockPosition()) || blocks.contains(BlockPos.containing(p.getX(), p.getY() + 0.9, p.getZ()))) return true;
        }
        return false;
    }

    private static void rolledUp(Curtain c) {
        if (!c.fern) return;
        BlockState s = c.level.getBlockState(c.anchor);
        if (s.getBlock() instanceof HangingFernBlock && s.getValue(HangingFernBlock.UNROLLED)) {
            c.level.setBlock(c.anchor, s.setValue(HangingFernBlock.UNROLLED, false), Block.UPDATE_ALL);
        }
    }

    // ------------------------------------------------------------------------------------------------ frostberries

    /** The top block of the frostberry vine {@code pos} is part of: the vine rests as one. */
    private static BlockPos vineTop(ServerLevel l, BlockPos pos) {
        BlockPos p = pos;
        for (int i = 0; i < 24; i++) {
            BlockState above = l.getBlockState(p.above());
            if (!above.is(ERBlocks.FROSTBERRY_VINE.get()) && !above.is(ERBlocks.FROSTBERRY_VINE_PLANT.get())) break;
            p = p.above();
        }
        return p;
    }

    private static void burst(CastContext ctx, BlockPos pos) {
        ServerLevel l = ctx.level;
        BERRY_RESTS.put(vineTop(l, pos).asLong(), l.getGameTime() + BERRY_REST);
        Vec3 c = Vec3.atCenterOf(pos);
        // the cold sinks: down through the open air (and the vine) to the floor or the pool under it
        BlockPos below = groundBelow(l, pos);
        double floor = below == null ? c.y - BERRY_FALL : below.getY() + 1.0;
        RuneFx.frostPuff(l, c, BERRY_REACH, c.y - floor);
        // the creatures round it, and under it where the cold falls, slow and frost over
        AABB area = new AABB(c.x - BERRY_REACH, floor - 0.5, c.z - BERRY_REACH, c.x + BERRY_REACH, c.y + BERRY_REACH, c.z + BERRY_REACH);
        for (RuneHits.Touch t : RuneHits.touched(ctx, area, c)) {
            LivingEntity e = t.body();
            Vec3 m = RuneHits.middle(t.box());
            boolean round = t.distSqr() <= BERRY_REACH * BERRY_REACH;
            boolean under = m.y <= c.y && Math.hypot(m.x - c.x, m.z - c.z) - t.box().getBbWidth() / 2.0 <= BERRY_REACH;
            if (!(round || under) || !RuneHits.clear(l, c, m, ctx.owner)) continue;
            e.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, BERRY_SLOW, 1, false, false, true), ctx.owner);
            e.setTicksFrozen(Math.min(e.getTicksRequiredToFreeze() - 1, Math.max(e.getTicksFrozen(), 0) + BERRY_FROST));
        }
        // still water there freezes over (rune ice, as Frost makes)
        if (below == null || !stillWater(l.getBlockState(below))) return;
        int made = 0;
        for (int dx = -BERRY_FREEZE; dx <= BERRY_FREEZE; dx++) {
            for (int dz = -BERRY_FREEZE; dz <= BERRY_FREEZE; dz++) {
                if (dx * dx + dz * dz > BERRY_FREEZE * BERRY_FREEZE + 1) continue;
                if (TempBlocks.place(l, below.offset(dx, 0, dz), TempBlock.Kind.ICE, ctx.owner)) made++;
            }
        }
        if (made > 0) RuneFx.play(l, Vec3.atCenterOf(below).add(0, 0.5, 0), net.minecraft.sounds.SoundEvents.GLASS_PLACE, 0.6F, 1.6F);
    }

    /** The first block straight below a vine that is not open air or the vine itself (the floor, a pool), or null. */
    @Nullable
    private static BlockPos groundBelow(ServerLevel l, BlockPos pos) {
        BlockPos p = pos;
        for (int i = 0; i < BERRY_FALL; i++) {
            p = p.below();
            if (!l.isLoaded(p)) return null;
            BlockState s = l.getBlockState(p);
            if (s.isAir() || kind(s) == BERRY) continue;
            return p;
        }
        return null;
    }

    private static boolean stillWater(BlockState s) {
        return s.is(Blocks.WATER) && s.getFluidState().isSource() && s.getFluidState().getType() == Fluids.WATER;
    }

    // ------------------------------------------------------------------------------------------------ ticking

    /** Each server tick: the glowbells' rings reaching creatures, and the curtains unrolling and rolling up. */
    public static void tick(MinecraftServer server) {
        if (!PINGS.isEmpty()) {
            for (Iterator<Ping> it = PINGS.iterator(); it.hasNext(); ) {
                Ping p = it.next();
                if (p.level.getGameTime() < p.due) continue;
                it.remove();
                Entity e = p.level.getEntity(p.entity);
                if (!(e instanceof LivingEntity le) || !le.isAlive()) continue;
                le.addEffect(new MobEffectInstance(MobEffects.GLOWING, BELL_GLOW, 0, false, false, true));
                RuneFx.ping(p.level, le.getBoundingBox().getCenter(), le.getBbWidth());
            }
        }
        if (!CURTAINS.isEmpty()) CURTAINS.removeIf(RunePlants::step);
        if (!BERRY_RESTS.isEmpty() && server.getTickCount() % 200 == 0) {
            long now = server.overworld().getGameTime();
            BERRY_RESTS.values().removeIf(t -> t < now - 20);
        }
    }

    public static void clear() {
        PINGS.clear();
        CURTAINS.clear();
        BERRY_RESTS.clear();
    }
}
