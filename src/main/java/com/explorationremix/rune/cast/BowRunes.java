package com.explorationremix.rune.cast;

import com.explorationremix.rune.RuneRegistry;
import com.explorationremix.rune.effect.BoltLogic;
import com.explorationremix.rune.effect.RuneBolt;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.Act;
import com.explorationremix.rune.engine.Element;
import com.explorationremix.rune.engine.Plan;
import com.explorationremix.rune.engine.RuneId;
import com.explorationremix.rune.world.RopeBlock;
import com.explorationremix.rune.world.TempBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A bow's or crossbow's active runes. Each goes off where its arrow lands (ArrowRides lets it go there, Executor sends
 * it here):
 * <ul>
 *   <li>Glow: a flare. On rock, a light that burns for a minute; on a creature, it glows through walls for a minute.
 *   Its second phase scatters lights onto the rock all round, and makes the foes near a marked one glow too.</li>
 *   <li>Rope: a rope of light hangs from where the arrow stuck down to the floor, up over the lip of a wall when the
 *   top is near, or down off the edge of a ledge it landed on; you climb it as you climb a ladder. Its second phase
 *   hangs it twice as long and you climb it twice as fast.</li>
 *   <li>Snare: roots of light hold every creature round where it lands in place (players and bosses never). Its
 *   second phase holds twice as long, and what it holds takes a third more damage.</li>
 *   <li>Volley: arrows of light rain down round where it lands, each striking for itself. Its second phase sends a
 *   wider rain after the first.</li>
 *   <li>Mine: a mine stays where it lands, wakes after a second and goes off when a hostile creature comes near. A
 *   player has a few out at most. Its second phase throws three small mines round it as it goes off, which go off a
 *   moment later.</li>
 * </ul>
 */
final class BowRunes {
    /** Glow's scatter: lights sprayed round, how far they reach, and how far a mark spreads. */
    static final int SCATTER_LIGHTS = 5;
    static final double SCATTER_REACH = 10.0, MARK_REACH = 8.0;
    /** Rope: how far up a wall it climbs to reach a lip, and the most blocks it hangs. */
    static final int ROPE_UP = 4, ROPE_LENGTH = 24;
    /** Rope: how many blocks it drops a tick as it unrolls. */
    static final int ROPE_DROP = 3;
    /** Snare: how far round it holds, and how long. */
    static final double SNARE_RADIUS = 3.0;
    static final int SNARE_TICKS = 60;
    /** Volley: arrows of light, more with Wide; how far round they fall, how far above they start, and the second rain's wait. */
    static final int VOLLEY_ARROWS = 8, VOLLEY_WIDE = 4, SECOND_RAIN = 15, VOLLEY_SLOT = 30;
    static final double VOLLEY_RADIUS = 3.0, VOLLEY_HEIGHT = 9.0, VOLLEY_SPEED = 1.3;
    /** Mine: its wait before it wakes, how near a creature sets it off, its blast, how long it waits, and how many a player has out. */
    static final int MINE_ARM = 20, MINE_LIFE = 600, MINES = 4, CLUSTER = 3, CLUSTER_FUSE = 12, CLUSTER_SLOT = 40;
    static final double MINE_REACH = 2.0, MINE_BLAST = 3.0, CLUSTER_BLAST = 2.0;
    static final float CLUSTER_DAMAGE = 0.5F;

    private BowRunes() {}

    /** The rune {@code node} goes off at {@code o}, where its arrow landed; what follows on from it goes from the returned place. */
    static Origin run(CastContext ctx, Plan.Node node, Origin o) {
        Act a = node.act;
        return switch (a.rune) {
            case GLOW -> glow(ctx, a, o);
            case ROPE -> rope(ctx, a, o);
            case SNARE -> snare(ctx, a, o);
            case VOLLEY -> volley(ctx, node, a, o);
            case MINE -> mine(ctx, a, o);
            default -> o;
        };
    }

    // ------------------------------------------------------------------------------------------------ Glow

    private static Origin glow(CastContext ctx, Act a, Origin o) {
        ServerLevel l = ctx.level;
        LivingEntity hit = RuneHits.body(o.hit());
        int colour = RuneFx.colour(a.rune, a.element);
        if (hit != null && RuneHits.canHit(ctx.owner, hit)) {
            // on a creature it sticks: it glows, through walls, for a minute
            hit.addEffect(new MobEffectInstance(MobEffects.GLOWING, FlyingRune.GLOW_TAG_TICKS, 0, false, false, true), ctx.owner);
            RuneFx.impact(l, o.pos(), o.dir().scale(-1), a.rune, a.element, 1.0F, true);
            if (a.empowered) {
                for (LivingEntity e : l.getEntitiesOfClass(LivingEntity.class, hit.getBoundingBox().inflate(MARK_REACH), e -> e != hit && RuneHits.isFoe(ctx.owner, e))) {
                    if (e.distanceTo(hit) > MARK_REACH) continue;
                    e.addEffect(new MobEffectInstance(MobEffects.GLOWING, FlyingRune.GLOW_TAG_TICKS, 0, false, false, true), ctx.owner);
                    RuneFx.beam(l, RuneHits.middle(hit), RuneHits.middle(e), colour, a.element);
                }
            }
            return o;
        }
        Vec3 at = o.pos();
        List<BlockPos> lit = new ArrayList<>();
        BlockPos p = BlockPos.containing(at);
        if (TempBlocks.place(l, p, TempBlock.Kind.LIGHT, ctx.owner)) lit.add(p);
        else if (TempBlocks.place(l, p.above(), TempBlock.Kind.LIGHT, ctx.owner)) lit.add(p.above());
        RuneFx.impact(l, at, o.dir(), a.rune, a.element, 1.0F + 0.3F * a.count(RuneId.WIDE), false);
        // Wide: two more lights a little way off; the second phase sprays more onto the rock all round
        int more = (a.empowered ? SCATTER_LIGHTS : 0) + 2 * a.count(RuneId.WIDE);
        if (more > 0) scatter(ctx, a, at, more, a.empowered ? SCATTER_REACH : 6.0, lit);
        return o;
    }

    /** Lights sprayed from {@code at} onto the rock round it, along rays spread evenly over a sphere, at least three blocks apart. */
    private static void scatter(CastContext ctx, Act a, Vec3 at, int lights, double reach, List<BlockPos> lit) {
        ServerLevel l = ctx.level;
        int colour = RuneFx.colour(a.rune, a.element), made = 0;
        int rays = 18;
        for (int i = 0; i < rays && made < lights; i++) {
            double y = 1.0 - 2.0 * (i + 0.5) / rays, r = Math.sqrt(1.0 - y * y), t = i * 2.399963;
            Vec3 d = new Vec3(Math.cos(t) * r, y, Math.sin(t) * r);
            BlockHitResult bh = FlyingRune.clip(l, at, at.add(d.scale(reach)), ctx.owner);
            if (bh.getType() == HitResult.Type.MISS) continue;
            BlockPos q = bh.getBlockPos().relative(bh.getDirection());
            boolean close = false;
            for (BlockPos k : lit) close |= k.distSqr(q) < 9.0;
            if (close || !TempBlocks.place(l, q, TempBlock.Kind.LIGHT, ctx.owner)) continue;
            lit.add(q);
            made++;
            RuneFx.beam(l, at, Vec3.atCenterOf(q), colour, a.element);
        }
        if (made > 0) RuneFx.play(l, at, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.8F, 1.4F);
    }

    // ------------------------------------------------------------------------------------------------ Rope

    /**
     * Rope. Its arrow stuck in a ceiling: it hangs down from there. In a wall: from there, or from the wall's lip when
     * that is a few blocks above, so its top reaches the top. In the ground: from the edge of what it landed on, toward
     * the shooter, so it hangs down the ledge's face. Over a lip or an edge, its top block bends over onto the top
     * (RopeBlock.LIP). Never from a creature. It unrolls down to the floor, a few blocks a tick.
     */
    private static Origin rope(CastContext ctx, Act a, Origin o) {
        ServerLevel l = ctx.level;
        if (!o.wall()) {
            Executor.fizzle(ctx, o.pos());
            return o;
        }
        BlockPos cell = BlockPos.containing(o.pos());
        Direction stuck = stuckIn(l, cell, o.pos());
        if (stuck == null || !l.getBlockState(cell).isAir()) {
            Executor.fizzle(ctx, o.pos());
            return o;
        }
        BlockPos top = cell;
        // the way the rope's top bends over onto what it hangs from: the wall's top, or the ledge the arrow landed on
        Direction lip = null;
        if (stuck.getAxis().isHorizontal()) {
            // up the wall to its lip, if the top is near: then the rope's top block stands level with the top
            for (int k = 1; k <= ROPE_UP; k++) {
                BlockPos up = cell.above(k);
                if (!l.getBlockState(up).isAir()) break;
                if (!solid(l, up.relative(stuck))) {
                    top = up;
                    lip = stuck;
                    break;
                }
            }
        } else if (stuck == Direction.DOWN) {
            // in the ground: over the edge nearest the shooter, then down its face
            Vec3 toShooter = Origin.flat(ctx.owner.position().subtract(o.pos()));
            Direction way = toShooter == Vec3.ZERO ? Direction.NORTH : Direction.getNearest(toShooter.x, 0, toShooter.z);
            BlockPos edge = null;
            for (int k = 1; k <= 3; k++) {
                BlockPos c = cell.relative(way, k);
                if (!l.getBlockState(c).isAir()) break;
                if (l.getBlockState(c.below()).isAir()) {
                    edge = c;
                    break;
                }
            }
            if (edge == null) {
                Executor.fizzle(ctx, o.pos());
                return o;
            }
            top = edge;
            lip = way.getOpposite();
        }
        // down to the floor
        List<BlockPos> rope = new ArrayList<>();
        rope.add(top);
        for (BlockPos c = top.below(); rope.size() < ROPE_LENGTH + (top.getY() - cell.getY()); c = c.below()) {
            if (!l.getBlockState(c).isAir()) break;
            rope.add(c);
        }
        if (rope.size() < 2) {
            Executor.fizzle(ctx, o.pos());
            return o;
        }
        int life = a.empowered ? 2 * TempBlock.Kind.ROPE.life : TempBlock.Kind.ROPE.life;
        RuneFx.impact(l, Vec3.atCenterOf(top), new Vec3(0, -1, 0), a.rune, a.element, 1.0F, false);
        RuneFx.play(l, Vec3.atCenterOf(top), SoundEvents.CHAIN_PLACE, 1.0F, 1.3F);
        unroll(ctx, rope, 0, a.empowered, life, lip);
        return new Origin(Vec3.atCenterOf(rope.get(rope.size() - 1)), new Vec3(0, -1, 0), null, true);
    }

    /** The rope drops a few blocks a tick, from the top. */
    private static void unroll(CastContext ctx, List<BlockPos> rope, int from, boolean fast, int life, @Nullable Direction lip) {
        if (Executor.gone(ctx) || from >= rope.size()) return;
        ServerLevel l = ctx.level;
        int to = Math.min(rope.size(), from + ROPE_DROP);
        for (int i = from; i < to; i++) {
            BlockState s = RuneRegistry.ROPE.get().defaultBlockState().setValue(RopeBlock.FAST, fast).setValue(RopeBlock.END, i == rope.size() - 1);
            if (i == 0 && lip != null) s = s.setValue(RopeBlock.LIP, true).setValue(RopeBlock.FACING, lip);
            if (!TempBlocks.place(l, rope.get(i), s, ctx.owner, life)) return;
        }
        if (to < rope.size()) CastService.later(ctx.owner, 1, () -> unroll(ctx, rope, to, fast, life, lip));
        else RuneFx.play(l, Vec3.atCenterOf(rope.get(rope.size() - 1)), SoundEvents.CHAIN_STEP, 0.8F, 1.2F);
    }

    /** The block the arrow stuck in, beside {@code cell} (the air it stopped in, at {@code at}): null if none is near. */
    @Nullable
    private static Direction stuckIn(ServerLevel l, BlockPos cell, Vec3 at) {
        Direction best = null;
        double bestD = 0.35;
        for (Direction d : Direction.values()) {
            BlockPos n = cell.relative(d);
            if (!solid(l, n)) continue;
            // how far the point is from that block's face
            double dist = switch (d) {
                case DOWN -> at.y - cell.getY();
                case UP -> cell.getY() + 1 - at.y;
                case NORTH -> at.z - cell.getZ();
                case SOUTH -> cell.getZ() + 1 - at.z;
                case WEST -> at.x - cell.getX();
                case EAST -> cell.getX() + 1 - at.x;
            };
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    private static boolean solid(ServerLevel l, BlockPos p) {
        BlockState s = l.getBlockState(p);
        return !s.getCollisionShape(l, p).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ Snare

    private static Origin snare(CastContext ctx, Act a, Origin o) {
        ServerLevel l = ctx.level;
        double r = SNARE_RADIUS + a.count(RuneId.WIDE);
        Vec3 c = LingeringField.ground(l, o.pos(), 3.0, ctx);
        int ticks = a.empowered ? 2 * SNARE_TICKS : SNARE_TICKS;
        int colour = RuneFx.colour(a.rune, a.element), held = 0;
        AABB box = new AABB(c.x - r, c.y - 1.5, c.z - r, c.x + r, c.y + 3.0, c.z + r);
        for (RuneHits.Touch t : RuneHits.touched(ctx, box, c)) {
            LivingEntity e = t.body();
            if (Math.hypot(e.getX() - c.x, e.getZ() - c.z) > r + e.getBbWidth() * 0.5) continue;
            if (!RuneHits.clear(l, c.add(0, 0.5, 0), RuneHits.middle(t.box()), ctx.owner) || !ctx.firstStrike(a, e)) continue;
            // hunters shake it off in half the time
            Statuses.bind(e, Statuses.hunter(e) ? ticks / 2 : ticks, a.empowered);
            // its element still takes, though it deals no damage of its own
            RuneHits.strike(ctx, a, e, t.box(), RuneHits.middle(t.box()), new Vec3(0, -1, 0), 0.0F, 0.0F, null, c);
            RuneFx.beam(l, new Vec3(e.getX(), c.y + 0.05, e.getZ()), RuneHits.middle(e), colour, a.element);
            held++;
        }
        RuneFx.snare(l, c, (float) r, colour, a.element);
        RuneFx.play(l, c, SoundEvents.CHAIN_PLACE, 0.9F, 0.8F);
        RuneFx.play(l, c, SoundEvents.AMETHYST_BLOCK_RESONATE, 0.7F, 0.7F);
        if (held > 0) RuneFx.play(l, c, SoundEvents.CHAIN_FALL, 0.7F, 1.4F);
        return new Origin(c.add(0, 0.5, 0), o.dir(), o.hit(), o.wall());
    }

    // ------------------------------------------------------------------------------------------------ Volley

    private static Origin volley(CastContext ctx, Plan.Node node, Act a, Origin o) {
        int wide = a.count(RuneId.WIDE);
        int n = VOLLEY_ARROWS + VOLLEY_WIDE * wide;
        double r = VOLLEY_RADIUS + wide;
        Vec3 c = o.pos();
        rain(ctx, node, a, c, n, r, VOLLEY_SLOT);
        RuneFx.play(ctx.level, c, SoundEvents.ARROW_SHOOT, 0.8F, 0.6F);
        if (a.empowered) {
            CastService.later(ctx.owner, SECOND_RAIN, () -> {
                if (Executor.gone(ctx)) return;
                rain(ctx, node, a, c, n, r * 1.6, VOLLEY_SLOT + n);
                RuneFx.play(ctx.level, c, SoundEvents.ARROW_SHOOT, 0.8F, 0.5F);
            });
        }
        return o;
    }

    /**
     * {@code n} arrows of light falling round {@code c}, {@code r} across, from up to {@link #VOLLEY_HEIGHT} above
     * (below the ceiling), a few each tick; each strikes for itself (its own strike number from {@code slot}).
     */
    private static void rain(CastContext ctx, Plan.Node node, Act a, Vec3 c, int n, double r, int slot) {
        ServerLevel l = ctx.level;
        for (int i = 0; i < n; i++) {
            // spread evenly over the disc (a golden-angle spiral), with a little jitter
            double t = i * 2.399963 + l.random.nextDouble() * 0.4, d = r * Math.sqrt((i + 0.5) / n);
            Vec3 ground = c.add(Math.cos(t) * d, 0.0, Math.sin(t) * d);
            Vec3 base = ground.add(0, 0.6, 0);
            BlockHitResult up = FlyingRune.clip(l, base, base.add(0, VOLLEY_HEIGHT, 0), ctx.owner);
            Vec3 from = up.getType() == HitResult.Type.MISS ? base.add(0, VOLLEY_HEIGHT, 0) : up.getLocation().subtract(0, 0.3, 0);
            if (from.y - ground.y < 1.5) continue;
            Vec3 dir = new Vec3((l.random.nextDouble() - 0.5) * 0.08, -1.0, (l.random.nextDouble() - 0.5) * 0.08).normalize();
            int life = (int) Math.ceil((from.y - ground.y + 4.0) / VOLLEY_SPEED);
            Act arrow = a.withSlot(slot + i);
            CastService.later(ctx.owner, i % 4, () -> {
                if (!Executor.gone(ctx)) FlyingRune.fall(ctx, node, arrow, from, dir, VOLLEY_SPEED, life);
            });
        }
    }

    // ------------------------------------------------------------------------------------------------ Mine

    private static Origin mine(CastContext ctx, Act a, Origin o) {
        ServerLevel l = ctx.level;
        Caster c = CastService.caster(ctx.owner);
        // past the most a player has out, the oldest fizzles
        c.mines.removeIf(RuneBolt::isRemoved);
        while (c.mines.size() >= MINES) {
            RuneBolt old = c.mines.remove(0);
            Executor.fizzle(ctx, old.position());
            old.discard();
        }
        Vec3 at = o.pos();
        if (!l.isPositionEntityTicking(BlockPos.containing(at))) {
            Executor.fizzle(ctx, at);
            return o;
        }
        Mine m = new Mine(ctx, a, at, MINE_ARM, MINE_LIFE, a.count(RuneId.WIDE) > 0 ? MINE_REACH + 1.0 : MINE_REACH,
                MINE_BLAST + a.count(RuneId.WIDE), FlyingRune.damage(ctx, a), a.empowered, false);
        RuneBolt b = new RuneBolt(RuneRegistry.BOLT.get(), l);
        b.setPos(at);
        b.setDeltaMovement(Vec3.ZERO);
        b.start(m, a.rune, a.element, 0.9F);
        if (l.addFreshEntity(b)) c.mines.add(b);
        RuneFx.play(l, at, SoundEvents.AMETHYST_BLOCK_PLACE, 0.8F, 0.7F);
        return o;
    }

    /**
     * A mine's logic: it sits where it landed, wakes after {@code arm} ticks, and goes off when a hostile creature (or
     * any foe fighting its player) comes within {@code reach}, or at once when {@code timed} (a cluster's small mine,
     * which goes off when its fuse runs out). Unwoken past its life, it fizzles.
     */
    private static final class Mine implements BoltLogic {
        final CastContext ctx;
        final Act a;
        final Vec3 at;
        final int arm, life;
        final double reach, blast;
        final float damage;
        final boolean cluster, timed;
        int age;
        boolean done;

        Mine(CastContext ctx, Act a, Vec3 at, int arm, int life, double reach, double blast, float damage, boolean cluster, boolean timed) {
            this.ctx = ctx;
            this.a = a;
            this.at = at;
            this.arm = arm;
            this.life = life;
            this.reach = reach;
            this.blast = blast;
            this.damage = damage;
            this.cluster = cluster;
            this.timed = timed;
        }

        @Override
        public void tick(RuneBolt b) {
            if (done || Executor.gone(ctx)) {
                done = true;
                b.discard();
                return;
            }
            age++;
            b.setPos(at);
            b.setDeltaMovement(Vec3.ZERO);
            if (timed) {
                if (age >= arm) explode(b);
                return;
            }
            if (age == arm) {
                // awake: a soft chime, and a flicker of its light
                RuneFx.play(ctx.level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 0.7F, 1.8F);
                RuneFx.pierce(ctx.level, at, new Vec3(0, 1, 0), a.rune, a.element);
            }
            if (age > life) {
                done = true;
                Executor.fizzle(ctx, at);
                b.discard();
                return;
            }
            if (age < arm || age % 2 != 0) return;
            for (RuneHits.Touch t : RuneHits.touched(ctx, new AABB(at, at).inflate(reach), at)) {
                LivingEntity e = t.body();
                if (!(e instanceof Enemy) && !RuneHits.isFoe(ctx.owner, e)) continue;
                if (t.distSqr() > reach * reach || !RuneHits.clear(ctx.level, at, RuneHits.middle(t.box()), ctx.owner)) continue;
                explode(b);
                return;
            }
        }

        private void explode(RuneBolt b) {
            done = true;
            b.discard();
            Instants.blast(ctx, a, at, blast, damage, 0.8F, false);
            if (!cluster) return;
            // the cluster: three small mines thrown round it, which go off a moment later
            ServerLevel l = ctx.level;
            for (int i = 0; i < CLUSTER; i++) {
                double t = i * Math.PI * 2 / CLUSTER + l.random.nextDouble() * 0.6;
                Vec3 out = at.add(Math.cos(t) * 2.5, 0.8, Math.sin(t) * 2.5);
                BlockHitResult side = FlyingRune.clip(l, at.add(0, 0.3, 0), out, ctx.owner);
                Vec3 p = side.getType() == HitResult.Type.MISS ? out : side.getLocation().subtract(out.subtract(at).normalize().scale(0.2));
                Vec3 g = LingeringField.ground(l, p, 4.0, ctx).add(0, 0.15, 0);
                Mine small = new Mine(ctx, a.withSlot(CLUSTER_SLOT + i), g, CLUSTER_FUSE + 3 * i, CLUSTER_FUSE + 3 * i + 1, 0.0, CLUSTER_BLAST,
                        damage * CLUSTER_DAMAGE, false, true);
                RuneBolt sb = new RuneBolt(RuneRegistry.BOLT.get(), l);
                sb.setPos(g);
                sb.setDeltaMovement(Vec3.ZERO);
                sb.start(small, a.rune, a.element, 0.55F);
                l.addFreshEntity(sb);
                RuneFx.beam(l, at, g, RuneFx.colour(a.rune, a.element), a.element);
            }
        }

        @Override
        public void removed(RuneBolt b) {
            done = true;
            Caster c = CastService.casterOrNull(ctx.owner);
            if (c != null) c.mines.remove(b);
        }
    }

    /** Whether this element makes a rope or a snare do something more (for now, nothing: kept so the readings agree). */
    static boolean elemental(Element e) { return e != Element.NONE; }
}
