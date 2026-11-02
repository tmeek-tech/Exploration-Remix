package com.explorationremix.creature;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.creature.climb.ClimbNodeEvaluator;
import com.explorationremix.registry.EREntities;
import com.explorationremix.world.DeepWorld;
import com.explorationremix.world.gen.SurfaceOracle;
import com.explorationremix.world.terrain.Plans;
import com.explorationremix.world.terrain.Terrain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A Mossback's den: a patch of trampled moss and gnawed bone on the upper landing of a den gate, the way down from the
 * Upper Caves to the Blue Reaches. A den keeps a family of two or three: a guard that never leaves it, so the den is
 * always a fight, and roamers that go out far and come home with what they catch, which is how a player finds the
 * gate. The den brings its family out one at a time when a player comes within a few chunks (never in plain sight).
 * When the guard dies the roamers are called home and the first back takes its place; when the whole family is gone
 * the den lies empty for a few in-game days before a new family moves in. Breaking the den drops nothing.
 *
 * <p>Who belongs to which den is kept in the level's {@link DenRegistry}, not in the den, so deaths and check-ins
 * far from home count while the den's chunk is unloaded. A den that is not on a den gate's landing (the plain
 * plant-cave dens of 0.6.0, in worlds made then) takes itself away on its first tick; its lizard roams on as a stray.
 */
public class MossbackDen extends BaseEntityBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 1, 16);
    /** How long a den lies empty after its whole family is gone: three in-game days. */
    public static final long EMPTY_TICKS = 3 * 24000L;
    /** A member killed while the rest of its family lives is replaced after this long (an in-game day). */
    public static final long REFILL_TICKS = 24000L;
    /**
     * A member the den has looked for this long, where it was last seen and with that place loaded, without finding it
     * is lost (gone without dying where the den would hear of it). One frozen in an unloaded chunk is never lost.
     */
    static final long LOST_TICKS = 6000L;
    /** A den this near a den gate's upper landing centre (blocks across) and its floor (blocks up or down) is that gate's. */
    static final double GATE_REACH = 24.0, GATE_DEPTH = 10.0;
    /** A player this far from the den brings its family out: near enough to meet them, never near enough to watch. */
    static final double SPAWN_NEAR = 16.0, SPAWN_FAR = 96.0;
    /** The most scraps of bone a den's meals leave round it, and how far from the den they lie. */
    public static final int MAX_SCRAPS = 24, SCRAPS_REACH = 8;
    private static final ResourceLocation SCRAPS = new ResourceLocation(ExplorationRemix.MOD_ID, "bone_scraps");
    private static final int PASS = 40;

    public MossbackDen(Properties props) {
        super(props);
    }

    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPE; }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new Tile(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, EREntities.DEN_TILE.get(), (l, p, s, t) -> t.tick((ServerLevel) l));
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        // the den itself is gone: so is its record, and its family become strays as they check in
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel sl) DenRegistry.get(sl).remove(pos);
        super.onRemove(state, level, pos, newState, moved);
    }

    // ------------------------------------------------------------------------------------------------ the den's place

    public static int familySize(BlockPos pos) {
        return DenRegistry.familySize(pos);
    }

    /** Whether a den here lies on a den gate's upper landing (the only dens there are, from 0.6.1 on). */
    static boolean isGateDen(ServerLevel level, BlockPos pos) {
        Terrain t = DeepWorld.installed() ? DeepWorld.terrain() : null;
        if (t == null || level.dimension() != Level.OVERWORLD) return false;
        double x = pos.getX() + 1.0, z = pos.getZ() + 1.0;           // the middle of the den's two by two
        Plans.Gate g = t.plans.nearestDenGate(x, z);
        return g != null && Math.hypot(x - g.ux, z - g.uz) <= GATE_REACH && Math.abs(pos.getY() - g.uFloor) <= GATE_DEPTH;
    }

    /**
     * Whether a den's family is the frost cousin. The den lies on the pack's old floor, often in a biome that says
     * nothing of the cold (dripstone caves, or the pack's own cave biome there), so the biome at the den alone would
     * miss most dens under snowy ground. The Frost Fern Grotto itself is chosen by the ground above it being cold
     * (the surface biome is one snow falls on), so the den asks that too: a family is frost where the grotto would be.
     */
    static boolean coldDen(ServerLevel level, BlockPos pos) {
        if (MossbackEntity.isColdPlace(level, pos)) return true;
        SurfaceOracle above = DeepWorld.oracle();
        return above != null && above.at(pos.getX(), pos.getZ()).cold();
    }

    // ------------------------------------------------------------------------------------------------ the family

    /** The lizard says it is still about: where it is, and what it should know (its role, a call home). */
    static void checkIn(MossbackEntity lizard) {
        if (!(lizard.level() instanceof ServerLevel level)) return;
        BlockPos den = lizard.den();
        if (den == null) return;
        DenRegistry reg = DenRegistry.get(level);
        DenRegistry.Den rec = reg.den(den);
        boolean denGone = level.isLoaded(den) && !(level.getBlockEntity(den) instanceof Tile);
        if (rec == null || denGone) {
            // its den is gone (broken, or one of the old plant-cave dens, retired): it roams on alone
            if (denGone) {
                if (rec != null) reg.remove(den);
                lizard.setDen(null);
            }
            return;
        }
        DenRegistry.Member m = rec.member(lizard.getUUID());
        if (m == null) {
            // not of the family (lost, then come back; or from an old world): taken back while the family has room
            if (rec.members.size() >= familySize(den)) { lizard.becomeStray(); return; }
            m = rec.join(lizard.getUUID(), lizard.blockPosition(), lizard.nearDen(8.0));
        }
        m.last = lizard.blockPosition();
        m.missing = 0;
        reg.setDirty();
        lizard.learn(m.role, rec.comeHome);
    }

    /** It reached the den: if its family was called home, it is the guard now. */
    static void arrivedHome(MossbackEntity lizard) {
        if (!(lizard.level() instanceof ServerLevel level) || lizard.den() == null) return;
        DenRegistry reg = DenRegistry.get(level);
        DenRegistry.Den rec = reg.den(lizard.den());
        if (rec == null) return;
        DenRegistry.Member m = rec.member(lizard.getUUID());
        if (m == null) return;
        if (rec.promote(lizard.getUUID())) reg.setDirty();
        m.last = lizard.blockPosition();
        m.missing = 0;
        lizard.learn(m.role, rec.comeHome);
    }

    /** Killed: it leaves its family, wherever it died (the record is the level's, so the den need not be loaded). */
    static void lizardDied(MossbackEntity lizard) {
        leave(lizard, DenRegistry.Loss.KILLED);
    }

    /** Taken out of the level without dying (Peaceful, the void, another dimension): it leaves its family too. */
    static void lizardGone(MossbackEntity lizard) {
        leave(lizard, DenRegistry.Loss.REMOVED);
    }

    /** It lost its way home and roams on alone: it leaves its family, and its place is filled a day later. */
    static void lizardStrayed(MossbackEntity lizard) {
        leave(lizard, DenRegistry.Loss.STRAYED);
    }

    private static void leave(MossbackEntity lizard, DenRegistry.Loss why) {
        if (!(lizard.level() instanceof ServerLevel level) || lizard.den() == null) return;
        DenRegistry reg = DenRegistry.get(level);
        DenRegistry.Den rec = reg.den(lizard.den());
        if (rec == null || rec.member(lizard.getUUID()) == null) return;
        rec.gone(lizard.getUUID(), level.getGameTime(), why);
        reg.setDirty();
    }

    /**
     * A meal at the den leaves a scrap of bone on open floor within {@link #SCRAPS_REACH} blocks of it, until there
     * are {@link #MAX_SCRAPS}. The scraps block is looked up by its id, so the den works (without scraps) without it.
     */
    static boolean leaveScraps(MossbackEntity lizard) {
        if (!(lizard.level() instanceof ServerLevel level) || lizard.den() == null) return false;
        DenRegistry reg = DenRegistry.get(level);
        DenRegistry.Den rec = reg.den(lizard.den());
        if (rec == null || rec.scraps >= MAX_SCRAPS || !ForgeRegistries.BLOCKS.containsKey(SCRAPS)) return false;
        Block block = ForgeRegistries.BLOCKS.getValue(SCRAPS);
        if (block == null || block == Blocks.AIR) return false;
        BlockState scraps = block.defaultBlockState();
        BlockPos d = rec.pos;
        RandomSource r = level.random;
        for (int i = 0; i < 32; i++) {
            int dx = r.nextInt(2 * SCRAPS_REACH + 1) - SCRAPS_REACH, dz = r.nextInt(2 * SCRAPS_REACH + 1) - SCRAPS_REACH;
            if (dx * dx + dz * dz > SCRAPS_REACH * SCRAPS_REACH) continue;
            if (dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1) continue;      // the den's own two by two stays clear
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos p = d.offset(dx, dy, dz);
                if (!level.isLoaded(p)) break;
                BlockState s = varied(block, scraps, r);
                if (!openFloor(level, p) || !s.canSurvive(level, p)) continue;
                level.setBlock(p, s, Block.UPDATE_ALL);
                rec.scraps++;
                reg.setDirty();
                return true;
            }
        }
        return false;
    }

    /** One of the scraps' looks, turned any way (its properties found by name, so the den needs no scraps class). */
    private static BlockState varied(Block block, BlockState s, RandomSource r) {
        if (block.getStateDefinition().getProperty("look") instanceof net.minecraft.world.level.block.state.properties.IntegerProperty look) {
            List<Integer> v = List.copyOf(look.getPossibleValues());
            s = s.setValue(look, v.get(r.nextInt(v.size())));
        }
        if (s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING))
            s = s.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r));
        return s;
    }

    /** Open air on top of solid ground (never over a plant, a carpet or another scrap). */
    private static boolean openFloor(Level level, BlockPos p) {
        BlockState at = level.getBlockState(p);
        if (!at.isAir() || !at.getFluidState().isEmpty()) return false;
        BlockPos below = p.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    /** One line for each member: its role, and where it is or was last seen. */
    static List<String> describeMembers(ServerLevel level, DenRegistry.Den rec) {
        List<String> out = new ArrayList<>();
        for (DenRegistry.Member m : rec.members) {
            String who = m.role.name().toLowerCase(Locale.ROOT) + " " + m.id.toString().substring(0, 8);
            Entity e = level.getEntity(m.id);
            if (e instanceof MossbackEntity lz) {
                out.add(String.format(Locale.ROOT, "%s, %.0f blocks from the den at %s: %s", who,
                        Math.sqrt(lz.blockPosition().distSqr(rec.pos)), lz.blockPosition().toShortString(), lz.describe()));
            } else {
                out.add(String.format(Locale.ROOT, "%s, away: last seen at %s, %.0f blocks from the den%s", who, m.last.toShortString(),
                        Math.sqrt(m.last.distSqr(rec.pos)), m.missing > 0 ? ", missing there " + m.missing / 20 + "s" : " (out of reach)"));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ the tile

    public static class Tile extends BlockEntity {
        /** False until its first tick after loading, when it asks whether it lies on a den gate's landing. */
        private boolean checked;
        /** A den saved by 0.6.0 (one lizard, kept in the den itself): taken into the registry on its first tick. */
        private boolean legacy;
        @Nullable private UUID legacyLizard;
        private long legacyLastSeen, legacyEmptyUntil;

        public Tile(BlockPos pos, BlockState state) {
            super(EREntities.DEN_TILE.get(), pos, state);
        }

        void tick(ServerLevel level) {
            if (!checked) {
                checked = true;
                if (!isGateDen(level, worldPosition)) {
                    // not on a den gate's landing: one of the old plant-cave dens. It goes; its lizard roams on as a stray
                    level.removeBlock(worldPosition, false);
                    return;
                }
                if (legacy) adoptLegacy(level);
            }
            long now = level.getGameTime();
            if (Math.floorMod(now + worldPosition.hashCode(), (long) PASS) != 0) return;
            DenRegistry reg = DenRegistry.get(level);
            DenRegistry.Den rec = reg.open(worldPosition);
            if (lookForFamily(level, rec, now)) reg.setDirty();
            if (bringOut(level, rec, now)) reg.setDirty();
        }

        /** Each member is seen where it is, or missed where it was last seen; missed long enough there, it is lost. */
        private boolean lookForFamily(ServerLevel level, DenRegistry.Den rec, long now) {
            boolean changed = false;
            for (DenRegistry.Member m : new ArrayList<>(rec.members)) {
                Entity e = level.getEntity(m.id);
                if (e instanceof MossbackEntity lz && lz.isAlive()) {
                    if (!worldPosition.equals(lz.den())) rec.gone(m.id, now, DenRegistry.Loss.STRAYED);    // it has left the family
                    else { m.last = lz.blockPosition(); m.missing = 0; }
                    changed = true;
                    continue;
                }
                // where it was is not loaded (or not ticking): it is frozen there, and never counts as lost
                if (!watched(level, m.last)) continue;
                m.missing += PASS;
                changed = true;
                // lost: most likely killed where the den could not hear of it, so the den lies empty as after a death
                if (m.missing >= LOST_TICKS) rec.gone(m.id, now, DenRegistry.Loss.KILLED);
            }
            return changed;
        }

        /** Whether entities round {@code p} are loaded and ticking: a lizard there would be found, or be walking. */
        private static boolean watched(ServerLevel level, BlockPos p) {
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (!level.isPositionEntityTicking(p.offset(dx * 16, 0, dz * 16))) return false;
            return true;
        }

        /**
         * Brings out the next of the family, one a pass, while a player is near enough to meet it and none close
         * enough to watch it appear (never in Peaceful). The first is the guard, the rest roamers.
         */
        private boolean bringOut(ServerLevel level, DenRegistry.Den rec, long now) {
            int size = familySize(worldPosition);
            if (rec.members.size() >= size || rec.spawned >= size) return false;
            if (now < rec.emptyUntil || now < rec.refillAt || level.getDifficulty() == Difficulty.PEACEFUL) return false;
            double cx = worldPosition.getX() + 1.0, cy = worldPosition.getY(), cz = worldPosition.getZ() + 1.0;
            Player near = level.getNearestPlayer(cx, cy, cz, SPAWN_FAR, p -> !p.isSpectator());
            if (near == null || near.distanceToSqr(cx, cy, cz) < SPAWN_NEAR * SPAWN_NEAR) return false;
            MossbackEntity m = EREntities.MOSSBACK.get().create(level);
            if (m == null || !place(level, m)) return false;
            m.setDen(worldPosition);
            m.finalizeSpawn(level, level.getCurrentDifficultyAt(worldPosition), MobSpawnType.STRUCTURE, null, null);
            m.setVariant(coldDen(level, worldPosition) ? MossbackEntity.FROST : MossbackEntity.MOSS);   // one family, one kind
            DenRegistry.Member mem = rec.join(m.getUUID(), m.blockPosition(), true);
            m.learn(mem.role, rec.comeHome);
            level.addFreshEntityWithPassengers(m);
            rec.spawned++;
            return true;
        }

        /** On the den's own two by two if it is free, else on the nearest free floor round it. */
        private boolean place(ServerLevel level, MossbackEntity m) {
            RandomSource r = level.random;
            for (int i = 0; i < 48; i++) {
                int reach = 1 + i / 8;
                BlockPos c = i == 0 ? worldPosition
                        : worldPosition.offset(r.nextInt(2 * reach + 1) - reach, r.nextInt(5) - 2, r.nextInt(2 * reach + 1) - reach);
                if (!level.hasChunksAt(c.getX() - 1, c.getZ() - 1, c.getX() + 2, c.getZ() + 2)) continue;
                if (ClimbNodeEvaluator.classify(level, c.getX(), c.getY(), c.getZ(), 2, 1, 2) != ClimbNodeEvaluator.FLOOR) continue;
                m.moveTo(c.getX() + 1.0, c.getY() + 0.1, c.getZ() + 1.0, r.nextFloat() * 360.0F, 0.0F);
                if (level.noCollision(m)) return true;
            }
            return false;
        }

        /** A den saved by 0.6.0 that does lie on a gate's landing: its lizard is the first of the family, its guard. */
        private void adoptLegacy(ServerLevel level) {
            DenRegistry reg = DenRegistry.get(level);
            if (reg.den(worldPosition) == null) {
                DenRegistry.Den rec = reg.open(worldPosition);
                rec.emptyUntil = legacyEmptyUntil;
                if (legacyLizard != null) {
                    rec.join(legacyLizard, worldPosition, true);
                    rec.spawned = 1;
                }
                reg.setDirty();
            }
            legacy = false;
            legacyLizard = null;
            setChanged();
        }

        public String describe(ServerLevel level, long now) {
            int size = familySize(worldPosition);
            DenRegistry.Den rec = DenRegistry.get(level).den(worldPosition);
            if (!checked && rec == null) return "not woken yet (a family of " + size + ")";
            if (rec == null || rec.members.isEmpty()) {
                if (rec != null && now < rec.emptyUntil) return "empty for " + (rec.emptyUntil - now) / 20 + "s more (then a family of " + size + ")";
                return "waiting for a player (a family of " + size + ")";
            }
            int guards = 0;
            for (DenRegistry.Member m : rec.members) if (m.role == DenRegistry.Role.GUARD) guards++;
            return String.format(Locale.ROOT, "a family of %d (%d brought out of %d): %s, %d roamer%s%s, scraps %d of %d",
                    rec.members.size(), rec.spawned, size, guards > 0 ? "guarded" : "no guard", rec.members.size() - guards,
                    rec.members.size() - guards == 1 ? "" : "s", rec.comeHome ? ", called home" : "", rec.scraps, MAX_SCRAPS);
        }

        /** One line for each member of the den's family (empty when it has none). */
        public List<String> describeMembers(ServerLevel level) {
            DenRegistry.Den rec = DenRegistry.get(level).den(worldPosition);
            return rec == null ? List.of() : MossbackDen.describeMembers(level, rec);
        }

        @Override
        protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag);
            // the family is kept in the level's registry; only an old den not yet taken into it keeps its own
            if (legacy) {
                if (legacyLizard != null) tag.putUUID("Lizard", legacyLizard);
                tag.putLong("LastSeen", legacyLastSeen);
                tag.putLong("EmptyUntil", legacyEmptyUntil);
            }
        }

        @Override
        public void load(CompoundTag tag) {
            super.load(tag);
            // 0.6.0 kept its one lizard here: Lizard, LastSeen and EmptyUntil
            legacy = tag.hasUUID("Lizard") || tag.contains("EmptyUntil");
            legacyLizard = tag.hasUUID("Lizard") ? tag.getUUID("Lizard") : null;
            legacyLastSeen = tag.getLong("LastSeen");
            legacyEmptyUntil = tag.getLong("EmptyUntil");
        }
    }
}
