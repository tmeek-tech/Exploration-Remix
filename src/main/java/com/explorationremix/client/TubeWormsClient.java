package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.block.TubeWormsBlock;
import com.explorationremix.block.TubeWormsBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The tube worms' plumes on the client (0.20.6): which clumps are drawn moving by client.render.TubeWormsRenderer and
 * which are left still in their chunk's model, where moving costs nothing.
 *
 * <p>A clump is live (drawn moving) while the eye is within {@link #NEAR_IN} blocks of it (until it is past
 * {@link #NEAR_OUT}), and while it comes out or goes in wherever it is, so a scare seen from afar still ripples. Turning
 * live or still, its wiggle eases in or out over {@link #SETTLE} ticks, so the still model and the moving plumes meet
 * in the same pose; its chunk's model is then built again, with or without the plumes (client.model.TubeWormsModel).
 * A clump pulled in has no plumes in its model either way, so it turns live or still without one.
 *
 * <p>The worms' block entities are made here as their chunk comes (the world's generator sets the worms down without
 * them), before the chunk is first drawn, and the server never has them.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, value = Dist.CLIENT)
public final class TubeWormsClient implements TubeWormsBlockEntity.Watcher {
    /** Blocks from the eye: nearer the worms are drawn moving, beyond the second still again. */
    public static final double NEAR_IN = 20.0, NEAR_OUT = 24.0;
    /**
     * Blocks from the eye past which a clump coming out or going in is not drawn moving (its chunk's model just shows
     * it so): well inside the renderer's reach (TubeWormsRenderer's view distance), so it never goes unseen.
     */
    public static final double FAR = 48.0;
    /** Ticks a clump's wiggle takes to ease in or out as it turns live or still. */
    public static final float SETTLE = 10.0F;
    /** How long a clump moves of its own coming out and going in, all its plumes (ticks; see TubeWormsRenderer). */
    public static final int OUT_TICKS = 54, IN_TICKS = 8;
    /** Frames drawn, counted: the chunk models are stamped with it as they are built. */
    public static volatile long frame;

    private static final TubeWormsClient INSTANCE = new TubeWormsClient();
    private static final Long2ObjectOpenHashMap<ReferenceOpenHashSet<TubeWormsBlockEntity>> BY_SECTION = new Long2ObjectOpenHashMap<>();
    /** The clumps near, live or moving: looked at every update. */
    private static final ReferenceOpenHashSet<TubeWormsBlockEntity> ACTIVE = new ReferenceOpenHashSet<>();
    private static final LongOpenHashSet DIRTY = new LongOpenHashSet();

    private TubeWormsClient() {}

    /** From the client's setup: the worms' block entities are made on this thread and report here. */
    public static void init() {
        TubeWormsBlock.clientThread = Thread.currentThread();
        TubeWormsBlockEntity.watcher = INSTANCE;
    }

    @Override
    public void loaded(TubeWormsBlockEntity be) {
        BY_SECTION.computeIfAbsent(SectionPos.asLong(be.getBlockPos()), k -> new ReferenceOpenHashSet<>()).add(be);
    }

    @Override
    public void removed(TubeWormsBlockEntity be) {
        long k = SectionPos.asLong(be.getBlockPos());
        ReferenceOpenHashSet<TubeWormsBlockEntity> set = BY_SECTION.get(k);
        if (set != null && set.remove(be) && set.isEmpty()) BY_SECTION.remove(k);
        ACTIVE.remove(be);
    }

    /**
     * They came out or went in: live at once, so the chunk's model built for the change leaves the plumes out (unless
     * they are too far off to be seen move).
     */
    @Override
    public void turned(TubeWormsBlockEntity be, boolean out) {
        long now = be.getLevel().getGameTime();
        if (out) {
            be.outAt = now;
            be.movingUntil = now + OUT_TICKS;
        } else {
            be.inAt = now;
            be.movingUntil = now + IN_TICKS;
        }
        if (!be.live && dist2(be, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition()) > FAR * FAR) return;
        wake(be, now);
        ACTIVE.add(be);
    }

    /** The clump turns live (or stops settling), its wiggle easing in from where it is. */
    private static void wake(TubeWormsBlockEntity be, long now) {
        if (!be.live) {
            be.live = true;
            be.liveAt = now;
            be.settleAt = TubeWormsBlockEntity.LONG_AGO;
        } else if (be.settleAt != TubeWormsBlockEntity.LONG_AGO) {
            float w = Mth.clamp(1.0F - (now - be.settleAt) / SETTLE, 0.0F, 1.0F);
            be.liveAt = now - (long) Math.floor(w * SETTLE);
            be.settleAt = TubeWormsBlockEntity.LONG_AGO;
        }
    }

    /** How much of its wiggle a clump has at tick {@code now} and partial tick {@code pt}: 0 still, 1 all of it. */
    public static float wiggle(TubeWormsBlockEntity be, long now, float pt) {
        if (!be.live) return 0.0F;
        if (be.settleAt != TubeWormsBlockEntity.LONG_AGO) return Mth.clamp(1.0F - ((now - be.settleAt) + pt) / SETTLE, 0.0F, 1.0F);
        return Mth.clamp(((now - be.liveAt) + pt) / SETTLE, 0.0F, 1.0F);
    }

    /**
     * Whether the renderer draws the clump's plumes this frame: while its chunk's model shows none, from the frame that
     * model was built in (the frame it first shows, or one before) to the frame after one with them was.
     */
    public static boolean drawn(TubeWormsBlockEntity be) {
        long f = frame;
        if (be.meshHides) return be.meshHidBefore || f >= be.meshFrame;
        return be.meshHidBefore && f <= be.meshFrame + 1;
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent e) {
        if (e.phase == TickEvent.Phase.START) frame++;
    }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load e) {
        if (!(e.getChunk() instanceof LevelChunk chunk) || !(chunk.getLevel() instanceof ClientLevel)) return;
        if (Thread.currentThread() != TubeWormsBlock.clientThread) return;
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
        for (int s = 0; s < sections.length; s++) {
            LevelChunkSection sec = sections[s];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> st.getBlock() instanceof TubeWormsBlock)) continue;
            int y0 = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(s));
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++)
                        if (sec.getBlockState(x, y, z).getBlock() instanceof TubeWormsBlock)
                            chunk.getBlockEntity(m.set(x0 + x, y0 + y, z0 + z), LevelChunk.EntityCreationType.IMMEDIATE);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload e) {
        if (!e.getLevel().isClientSide()) return;
        BY_SECTION.clear();
        ACTIVE.clear();
        DIRTY.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused() || (BY_SECTION.isEmpty() && ACTIVE.isEmpty())) return;
        long now = level.getGameTime();
        if ((now & 1L) != 0) return;
        update(mc, now, mc.gameRenderer.getMainCamera().getPosition());
    }

    private static void update(Minecraft mc, long now, Vec3 eye) {
        // the clumps round the eye: who comes near
        int ex = SectionPos.blockToSectionCoord(Mth.floor(eye.x)), ey = SectionPos.blockToSectionCoord(Mth.floor(eye.y)),
                ez = SectionPos.blockToSectionCoord(Mth.floor(eye.z));
        int r = Mth.ceil(NEAR_OUT / 16.0);
        double in2 = NEAR_IN * NEAR_IN, out2 = NEAR_OUT * NEAR_OUT;
        for (int sx = ex - r; sx <= ex + r; sx++)
            for (int sy = ey - r; sy <= ey + r; sy++)
                for (int sz = ez - r; sz <= ez + r; sz++) {
                    ReferenceOpenHashSet<TubeWormsBlockEntity> set = BY_SECTION.get(SectionPos.asLong(sx, sy, sz));
                    if (set == null || boxDist2(sx, sy, sz, eye) > in2) continue;
                    for (TubeWormsBlockEntity be : set)
                        if (!be.near && dist2(be, eye) < in2) {
                            be.near = true;
                            ACTIVE.add(be);
                        }
                }
        // the active ones: still near or moving, or settling, or let go
        for (ObjectIterator<TubeWormsBlockEntity> it = ACTIVE.iterator(); it.hasNext(); ) {
            TubeWormsBlockEntity be = it.next();
            double d2 = dist2(be, eye);
            be.near = d2 < (be.near ? out2 : in2);
            boolean want = be.near || (now < be.movingUntil && d2 < FAR * FAR);
            if (want) {
                if (!be.live && be.out()) dirty(be);
                wake(be, now);
            } else if (be.live) {
                if (be.settleAt == TubeWormsBlockEntity.LONG_AGO) {
                    float w = wiggle(be, now, 0.0F);
                    be.settleAt = now - (long) Math.floor((1.0F - w) * SETTLE);
                } else if (now - be.settleAt >= SETTLE) {
                    be.live = false;
                    be.settleAt = TubeWormsBlockEntity.LONG_AGO;
                    if (be.out()) dirty(be);
                    it.remove();
                }
            } else {
                it.remove();
            }
        }
        if (DIRTY.isEmpty()) return;
        for (LongIterator it = DIRTY.iterator(); it.hasNext(); ) {
            long k = it.nextLong();
            mc.levelRenderer.setSectionDirty(SectionPos.x(k), SectionPos.y(k), SectionPos.z(k));
        }
        DIRTY.clear();
    }

    private static void dirty(TubeWormsBlockEntity be) { DIRTY.add(SectionPos.asLong(be.getBlockPos())); }

    private static double dist2(TubeWormsBlockEntity be, Vec3 eye) {
        BlockPos p = be.getBlockPos();
        double dx = p.getX() + 0.5 - eye.x, dy = p.getY() + 0.5 - eye.y, dz = p.getZ() + 0.5 - eye.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** The square of the distance from the eye to the nearest point of a section. */
    private static double boxDist2(int sx, int sy, int sz, Vec3 eye) {
        double dx = axis(eye.x, sx), dy = axis(eye.y, sy), dz = axis(eye.z, sz);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double axis(double v, int s) {
        double lo = s * 16.0, hi = lo + 16.0;
        return v < lo ? lo - v : (v > hi ? v - hi : 0.0);
    }
}
