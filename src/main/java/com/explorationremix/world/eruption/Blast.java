package com.explorationremix.world.eruption;

import com.explorationremix.block.GlowBlock;
import com.explorationremix.config.ERConfig;
import com.explorationremix.entity.LavaBomb;
import com.explorationremix.registry.ERTags;
import com.explorationremix.world.feature.Walkway;
import com.explorationremix.world.lava.Falling;
import com.explorationremix.world.lava.GalleryChanges;
import com.explorationremix.world.quake.Quakes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.ProtectionEnchantment;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * A lava bomb bursts (0.9.0, spec 5.4.4 to 5.4.6): a vanilla blast at the point it struck, through the game's own
 * {@code ServerLevel.explode} (whose packet brings the blast's sound and smoke to the players within 64 blocks), with
 * three things of the mod's own:
 * <ul>
 *     <li>{@link #CALC}: the Galleries' rock is soft to it ({@code eruptions.craterRock}), and it never touches a block
 *     outside the layer, an ore, gilded blackstone or obsidian;</li>
 *     <li>the {@link #detonate} handler, between the rays and the game's own work: it strikes the living things itself,
 *     with the game's numbers scaled by {@code bombDamage} and {@code bombKnockback}, and takes the crater's blocks out
 *     of the game's list ({@link Crater#part}) so that none drops an item;</li>
 *     <li>after the blast, the crater: its blocks set to air (none next to a fluid), sometimes a magma block at its
 *     bottom, and a few of its rocks thrown out as falling blocks ({@link Falling#EJECTA}), all recorded in the change
 *     log like any other change, so the terrain reset puts them back.</li>
 * </ul>
 * Only with {@code breaks}: the reset on ({@code terrainDamage}) and no way down within 3 blocks of the burst (a way
 * down is the way out when the lava rises). Without it the blast is {@code NONE}, and the mod's own code honours that
 * too: it touches no block, so the bomb only hurts and pushes. No fire, ever.
 */
public final class Blast {
    private Blast() {}

    /** How far from the struck face the blast's centre lies, and how far it steps back to leave a block. */
    static final double OUT = 0.1, BACK_STEP = 0.1;
    /** The way guard: no crater within this many blocks across of a way down's deck, foot, head or throat, over rows y-4 to y+4. */
    static final int WAY_ROWS = 4, WAY_MARGIN = 3;

    /**
     * The blast's calculator: blocks of the Galleries' rock inside the layer resist as {@code craterRock} (or the
     * fluid in them, if more); outside the layer nothing is broken, nor any ore, gilded blackstone ({@code
     * #reset_as_rock}: they would come back as rock for good) or obsidian; everything else as the game's.
     */
    static final ExplosionDamageCalculator CALC = new ExplosionDamageCalculator() {
        @Override
        public Optional<Float> getBlockExplosionResistance(Explosion e, BlockGetter g, BlockPos p, BlockState s, FluidState f) {
            if (GalleryChanges.inLayer(p.getY()) && s.is(ERTags.GALLERIES_ROCK)) {
                return Optional.of(Math.max(ERConfig.INSTANCE.eruptionCraterRock.get().floatValue(), f.getExplosionResistance(g, p, e)));
            }
            return super.getBlockExplosionResistance(e, g, p, s, f);
        }

        @Override
        public boolean shouldBlockExplode(Explosion e, BlockGetter g, BlockPos p, BlockState s, float power) {
            if (!GalleryChanges.inLayer(p.getY()) || kept(s)) return false;
            return super.shouldBlockExplode(e, g, p, s, power);
        }
    };

    /** True for a block no blast of a bomb takes: an ore, gilded blackstone, obsidian. */
    static boolean kept(BlockState s) {
        return s.is(ERTags.RESET_AS_ROCK) || s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN);
    }

    /** Whether the terrain reset puts things back, which craters need (read live, spec 3.1 gate 2). */
    static boolean terrainDamage() {
        ERConfig cfg = ERConfig.INSTANCE;
        return cfg.resetEnabled.get() && cfg.lavaCycleEnabled.get();
    }

    // ================================================================================================ the burst

    /**
     * Bomb {@code b} struck {@code hit} (its {@code onHit}, server only): the blast at the centre ({@link #centre}),
     * with {@code breaks} kept on the bomb for the handler, then the crater. Nothing with a blast power of 0.
     */
    public static void burst(LavaBomb b, HitResult hit) {
        if (!(b.level() instanceof ServerLevel level)) return;
        ERConfig cfg = ERConfig.INSTANCE;
        double power = cfg.eruptionBlastPower.get();
        b.crater.clear();
        b.craterStates.clear();
        if (power <= 0) return;
        Random r = b.draws();
        Vec3 c = centre(level, b, hit);
        int cx = Mth.floor(c.x), cy = Mth.floor(c.y), cz = Mth.floor(c.z);
        boolean way = Walkway.nearWayDown(cx, cz, cy - WAY_ROWS, cy + WAY_ROWS, WAY_MARGIN, WAY_MARGIN);
        b.breaks = terrainDamage() && !way;
        b.power = (float) (power * (0.9 + 0.2 * r.nextDouble()));
        level.explode(b, level.damageSources().explosion(b, null), CALC, c.x, c.y, c.z, b.power, false,
                b.breaks ? Level.ExplosionInteraction.BLOCK : Level.ExplosionInteraction.NONE);
        try {
            if (b.breaks && !b.crater.isEmpty()) crater(level, b, hit, c, r, cfg);
        } finally {
            b.crater.clear();
            b.craterStates.clear();
        }
    }

    /**
     * The blast's centre: a block struck, the point struck a tenth of a block out along its face (the entity's own
     * position is up to a tick's motion short of it); a living thing struck, the point struck a tenth over it. Never in
     * a block: if it is, it steps back along the bomb's motion until it is not (at most a block), so the blast's rays
     * to the players are not stopped at once.
     */
    static Vec3 centre(ServerLevel level, LavaBomb b, HitResult hit) {
        Vec3 c;
        if (hit instanceof BlockHitResult bh) {
            Direction d = bh.getDirection();
            c = hit.getLocation().add(d.getStepX() * OUT, d.getStepY() * OUT, d.getStepZ() * OUT);
        } else {
            c = hit.getLocation().add(0, OUT, 0);
        }
        Vec3 v = b.getDeltaMovement();
        double len = v.length();
        if (len < 1e-6) return c;
        Vec3 back = v.scale(-BACK_STEP / len);
        for (int i = 0; i < 10 && !open(level, c); i++) c = c.add(back);
        return c;
    }

    /** True for the cell holding {@code p} being open (a chunk not loaded counts as open: nothing is read there). */
    private static boolean open(ServerLevel level, Vec3 p) {
        BlockPos at = BlockPos.containing(p);
        LevelChunk ch = level.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
        return ch == null || Vents.open(ch, at, ch.getBlockState(at));
    }

    // ================================================================================================ the handler

    /**
     * Inside a bomb's blast, after the rays filled its block list and before the game's own entity loop (spec 5.4.5):
     * first the entities (other bombs out of it; living things struck here, out of it), then, only with the bomb's
     * {@code breaks}, the crater's blocks moved out of the list to the bomb.
     */
    @SubscribeEvent
    public static void detonate(ExplosionEvent.Detonate event) {
        Explosion x = event.getExplosion();
        if (!(x.getExploder() instanceof LavaBomb b) || !(event.getLevel() instanceof ServerLevel level)) return;
        ERConfig cfg = ERConfig.INSTANCE;
        Vec3 c = x.getPosition();
        DamageSource source = x.getDamageSource();
        double damage = cfg.eruptionBombDamage.get(), knock = cfg.eruptionBombKnockback.get();
        for (Iterator<Entity> it = event.getAffectedEntities().iterator(); it.hasNext(); ) {
            Entity e = it.next();
            if (e instanceof LavaBomb) {
                it.remove();
            } else if (e instanceof LivingEntity l) {
                it.remove();
                strike(l, c, b.power, source, damage, knock);
            }
        }
        if (!b.breaks) return;
        for (Iterator<BlockPos> it = event.getAffectedBlocks().iterator(); it.hasNext(); ) {
            BlockPos p = it.next();
            LevelChunk ch = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (ch == null) {
                it.remove();
                continue;
            }
            BlockState s = ch.getBlockState(p);
            int part = Crater.part(bits(ch, p, s), true);
            if (part == Crater.VANILLA) continue;
            it.remove();
            if (part == Crater.CRATER) {
                b.crater.add(p.immutable());
                b.craterStates.add(s);
            }
        }
    }

    /**
     * Strikes {@code l} as the game's blast of power {@code p} at {@code c} would ({@code Explosion.explode}'s entity
     * loop), its damage times {@code damage} and its push times {@code knock}; a player's push only when they are neither
     * a spectator nor flying in creative, as the game sends it, and every push sent at once ({@code hurtMarked}).
     */
    static void strike(LivingEntity l, Vec3 c, float p, DamageSource source, double damage, double knock) {
        if (l.ignoreExplosion()) return;
        double dist = Math.sqrt(l.distanceToSqr(c));
        double dx = l.getX() - c.x, dy = l.getEyeY() - c.y, dz = l.getZ() - c.z, d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > 2 * p || d == 0) return;
        double q = Crater.exposure(dist, p, Explosion.getSeenPercent(c, l));
        if (q < 0) return;
        l.hurt(source, (float) (Crater.damage(q, p) * damage));
        double k = ProtectionEnchantment.getExplosionKnockbackAfterDampener(l, q) * knock;
        if (l instanceof Player pl && (pl.isSpectator() || pl.isCreative() && pl.getAbilities().flying)) return;
        l.setDeltaMovement(l.getDeltaMovement().add(dx / d * k, dy / d * k, dz / d * k));
        l.hurtMarked = true;
    }

    // ================================================================================================ the crater

    /**
     * Removes the crater the handler took out of the blast's list (spec 5.4.5): each block still there, in the layer and
     * with no fluid next to it, set to air (no drops, no break particles), the lowest of the middle column sometimes to
     * magma instead; then up to {@code ejecta} of its rocks thrown out from the open cell one step out along the struck
     * face (for a living thing struck, the cell holding the blast's centre). With {@code toppleAtOnce}, the cell over each
     * removed block is checked next.
     */
    private static void crater(ServerLevel level, LavaBomb b, HitResult hit, Vec3 c, Random r, ERConfig cfg) {
        BlockState air = Blocks.AIR.defaultBlockState();
        Crater.Cells cells = (x, y, z) -> {
            BlockPos p = new BlockPos(x, y, z);
            LevelChunk ch = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            return ch == null ? Crater.FLUID | Crater.SOLID : bits(ch, p, ch.getBlockState(p));
        };
        List<int[]> crater = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < b.crater.size(); i++) {
            BlockPos p = b.crater.get(i);
            LevelChunk ch = level.getChunkSource().getChunkNow(p.getX() >> 4, p.getZ() >> 4);
            if (ch == null || ch.getBlockState(p) != b.craterStates.get(i)) continue;
            crater.add(new int[]{p.getX(), p.getY(), p.getZ()});
            states.add(b.craterStates.get(i));
        }
        List<int[]> removed = Crater.removed(crater, cells);
        if (removed.isEmpty()) return;
        // the states of the removed blocks, by their place in the crater list
        List<BlockState> removedStates = new ArrayList<>();
        for (int[] p : removed) removedStates.add(states.get(crater.indexOf(p)));
        int magma = r.nextDouble() < cfg.eruptionCraterMagma.get() ? Crater.magma(removed, Mth.floor(c.x), Mth.floor(c.z), cells) : -1;
        Direction face = hit instanceof BlockHitResult bh ? bh.getDirection() : Direction.UP;
        int[] thrown = Crater.ejecta(removed, cells, magma, c.x, c.y, c.z, face.getStepX(), face.getStepY(), face.getStepZ(), cfg.eruptionEjecta.get());
        int blocks = 0, glow = 0;
        for (int i = 0; i < removed.size(); i++) {
            int[] q = removed.get(i);
            BlockPos p = new BlockPos(q[0], q[1], q[2]);
            if (removedStates.get(i).getBlock() instanceof GlowBlock) glow++;
            else blocks++;
            level.setBlock(p, i == magma ? Blocks.MAGMA_BLOCK.defaultBlockState() : air, 3);
            Quakes.tookAway(p);
        }
        Eruptions.cratered(b, blocks, glow);
        if (thrown.length == 0) return;
        // the cell across the face struck, which holds the blast's centre; a living thing struck, the centre's own cell
        BlockPos from;
        if (hit instanceof BlockHitResult bh) {
            BlockPos s = bh.getBlockPos();
            int[] cell = Crater.ejectaCell(s.getX(), s.getY(), s.getZ(), face.getStepX(), face.getStepY(), face.getStepZ());
            from = new BlockPos(cell[0], cell[1], cell[2]);
        } else {
            from = BlockPos.containing(c);
        }
        Eruptions.Eruption e = Eruptions.eruptionOf(b);
        for (int i : thrown) throwOut(level, from, removedStates.get(i), face, r, e);
    }

    /**
     * Throws one rock of a crater from {@code from}, which must be air or the glow (the falling block writes air there)
     * and on entity-ticking ground: no item if it cannot land, tagged and tracked as ejecta (it breaks on whoever it
     * reaches), and its motion sent at once, as the falling block was added still.
     */
    private static void throwOut(ServerLevel level, BlockPos from, BlockState state, Direction face, Random r, @Nullable Eruptions.Eruption e) {
        LevelChunk ch = level.getChunkSource().getChunkNow(from.getX() >> 4, from.getZ() >> 4);
        if (ch == null || !GlowBlock.open(ch.getBlockState(from)) || !level.isPositionEntityTicking(from) || !GalleryChanges.inLayer(from.getY())) return;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) || !state.getFluidState().isEmpty()) return;
        FallingBlockEntity f = FallingBlockEntity.fall(level, from, state);
        f.dropItem = false;
        f.addTag(Falling.TAG);
        double[] v = Crater.ejectaVelocity(face.getStepX(), face.getStepY(), face.getStepZ(), r);
        f.setDeltaMovement(v[0], v[1], v[2]);
        f.hurtMarked = true;
        Falling.track(f, Falling.EJECTA, Eruptions.ejectaOwner(e, f));
    }

    /** The cell bits {@link Crater} asks of the block {@code s} at {@code p} in chunk {@code ch}. */
    static int bits(LevelChunk ch, BlockPos p, BlockState s) {
        int b = 0;
        if (s.isAir()) b |= Crater.AIR;
        if (GalleryChanges.inLayer(p.getY())) b |= Crater.IN_LAYER;
        if (GalleryChanges.original(ch, p) != null) b |= Crater.HELD;
        if (s.is(ERTags.GALLERIES_ROCK)) b |= Crater.ROCK;
        boolean fluid = !s.getFluidState().isEmpty();
        if (fluid) b |= Crater.FLUID;
        if (s.is(ERTags.QUAKE_DEBRIS) && !fluid && !s.hasProperty(BlockStateProperties.WATERLOGGED)) b |= Crater.DEBRIS;
        if (kept(s)) b |= Crater.KEEP;
        if (s.getBlock() instanceof GlowBlock) b |= Crater.GLOW;
        if (!Vents.open(ch, p, s)) b |= Crater.SOLID;
        return b;
    }
}
