package com.explorationremix.client.rune.stone;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.GlowSprite;
import com.explorationremix.client.rune.fx.GlowParticle;
import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.mixin.client.LevelRendererAccessor;
import com.explorationremix.mixin.client.MultiPlayerGameModeAccessor;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.stone.RuneStoneBlock;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Each client's half of a rune stone's breaking (0.23.1; the server's half is rune.stone.RuneStones). While a stone is
 * dug its carving lights from inside, brighter the further the pick has got: the light bleeds out into the stone round
 * the strokes (each face's {@code _lit} texture, laid over it additively) and leaks out of it in motes, sparks too near
 * the end, and a hum rises from it, louder and higher as the pick works, that dies away if the digging stops. Everyone
 * digging is seen and heard: this player's progress is the game's own smooth one, another player's the stage the server
 * sends.
 *
 * <p>When a stone is broken, every other stone within RuneStones.ECHO blocks answers: a spark of light shoots to it
 * from the broken one, seen through the rock, and as it arrives the stone chimes from where it lies, its carving flares
 * and a glow shows where it is, through the rock too, for a moment ({@link #answer}).
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneStoneClient {
    /** The carving's light blue, its near-white core and its dim end (tools/rune_stones.py). */
    static final int MAIN = 0x8EC2FF, CORE = 0xE6F2FF, DIM = 0x4F7FC4, WHITE = 0xFFFFFF;
    /** A flare's light falls by e every this many ticks. */
    static final float FLARE_FADE = 10.0F;
    /** How long the glow seen through the rock shows at an answering stone, ticks. */
    static final int MARK = 36;
    /** A baked quad's vertex, in ints: its place (0 to 2), colour, texture place (4, 5), light and normal. */
    private static final int STRIDE = DefaultVertexFormat.BLOCK.getIntegerSize();
    private static final ResourceLocation HALO = GlowSprite.texture("halo");

    /** Stones lit now (being dug, or flaring), by where they are. */
    private static final Map<BlockPos, Lit> LIT = new HashMap<>();
    /** Answers on their way from a broken stone. */
    private static final List<Answer> ANSWERS = new ArrayList<>();
    /**
     * Each carving's light texture, by its glow texture. A resource reload makes new sprites, so the old keys are simply
     * never asked for again (and the map is emptied if it grows past a few reloads' worth).
     */
    private static final Map<TextureAtlasSprite, TextureAtlasSprite> LIT_SPRITES = new HashMap<>();
    private static long ticks;

    private RuneStoneClient() {}

    /** One stone lit. */
    static final class Lit {
        final BlockPos pos;
        BlockState state;
        /** The light of its digging, 0 to 1 (eased towards {@link #target}), now and a tick ago. */
        float dig, digBefore;
        /** How far the digging has got, as last reported. */
        float target;
        long dugAt = Long.MIN_VALUE;
        /** A flare's light, 0 to 1, now and a tick ago. */
        float flare, flareBefore;
        /** When its glow through the rock began. */
        long markAt = Long.MIN_VALUE;
        boolean gone;
        @Nullable
        Hum hum;
        /** Its carving on each face: the glow quads, and where on the face the glyph's lit texels lie. */
        @Nullable
        Map<Direction, Face> faces;

        Lit(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }

        float light(float pt) { return Math.max(Mth.lerp(pt, digBefore, dig), Mth.lerp(pt, flareBefore, flare)); }
    }

    /** A face's carving: the quads of its glow (drawn again with its light texture) and its lit texels' places. */
    record Face(List<BakedQuad> quads, List<Vec3> points) {}

    /** A broken stone's answer: the spark from {@code from} that reaches the stone at {@code to} {@code delay} ticks on. */
    record Answer(Vec3 from, Vec3 to, long start, int delay, int order) {}

    // ------------------------------------------------------------------------------------------------ the answers

    /**
     * A stone at {@code at} answers one broken {@code way} back from it: its spark sets off now and arrives in
     * {@code delay} ticks (RuneFx.stoneFlare).
     */
    public static void answer(Vec3 at, Vec3 way, int delay) {
        int order = 0;
        for (Answer a : ANSWERS) if (a.start() == ticks) order++;
        ANSWERS.add(new Answer(at.subtract(way), at, ticks, Math.max(1, delay), order));
        // where it sets off from: a quick bright point
        RandomSource r = rand();
        if (r == null) return;
        Vec3 from = at.subtract(way);
        GlowParticle.streak(from).vel(way.scale(1.0 / Math.max(1, delay))).colour(CORE).fadeTo(MAIN)
                .size(0.07F, 0.04F).life(Math.max(1, delay)).drag(1.0F).stretch(2.5F).hot().spawn();
    }

    @Nullable
    private static RandomSource rand() {
        ClientLevel l = Minecraft.getInstance().level;
        return l == null ? null : l.random;
    }

    private static void arrive(ClientLevel level, Answer a) {
        BlockPos pos = BlockPos.containing(a.to());
        BlockState state = level.getBlockState(pos);
        RandomSource r = level.random;
        // the chime, from where it lies: each a shade apart, so several answering make a chord
        float pitch = (float) Math.pow(2.0, ((a.order() * 5) % 12) / 12.0) * (0.94F + r.nextFloat() * 0.06F);
        level.playLocalSound(a.to().x, a.to().y, a.to().z, ERSounds.RUNE_STONE_FLARE.get(), SoundSource.BLOCKS, 0.85F, Math.min(1.9F, pitch), false);
        if (!(state.getBlock() instanceof RuneStoneBlock)) return;
        Lit lit = LIT.computeIfAbsent(pos.immutable(), p -> new Lit(p, state));
        lit.state = state;
        lit.flare = 1.0F;
        lit.markAt = ticks;
        RuneStoneFx.flare(level, r, lit);
    }

    // ------------------------------------------------------------------------------------------------ each tick

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            clear();
            return;
        }
        if (mc.isPaused()) return;
        ticks++;
        // who is digging what: every breaker the game knows of, this player with their own smooth progress
        Map<Integer, BlockDestructionProgress> digging = ((LevelRendererAccessor) mc.levelRenderer).explorationremix$digging();
        MultiPlayerGameMode mode = mc.gameMode;
        for (BlockDestructionProgress d : digging.values()) {
            BlockPos pos = d.getPos();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof RuneStoneBlock)) continue;
            float progress = Mth.clamp((d.getProgress() + 1) / 10.0F, 0.0F, 1.0F);
            if (mc.player != null && d.getId() == mc.player.getId() && mode != null && mode.isDestroying()) {
                MultiPlayerGameModeAccessor own = (MultiPlayerGameModeAccessor) mode;
                if (pos.equals(own.explorationremix$digging())) progress = Mth.clamp(own.explorationremix$progress(), 0.0F, 1.0F);
            }
            Lit lit = LIT.computeIfAbsent(pos.immutable(), p -> new Lit(p, state));
            lit.state = state;
            lit.target = Math.max(lit.dugAt == ticks ? lit.target : 0.0F, progress);
            lit.dugAt = ticks;
        }
        for (Iterator<Lit> it = LIT.values().iterator(); it.hasNext(); ) {
            Lit lit = it.next();
            BlockState now = level.getBlockState(lit.pos);
            lit.digBefore = lit.dig;
            lit.flareBefore = lit.flare;
            if (!(now.getBlock() instanceof RuneStoneBlock)) {
                // broken (its burst comes from the server) or gone some other way
                lit.gone = true;
                if (lit.hum != null) lit.hum.end();
                it.remove();
                continue;
            }
            if (now != lit.state) {
                lit.state = now;
                lit.faces = null;
            }
            float target = lit.dugAt == ticks ? lit.target : 0.0F;
            // up quickly as the pick bites, down more slowly once it stops
            lit.dig += (target - lit.dig) * (target > lit.dig ? 0.55F : 0.22F);
            if (lit.dig < 0.004F) lit.dig = 0.0F;
            lit.flare *= (float) Math.exp(-1.0 / FLARE_FADE);
            if (lit.flare < 0.004F) lit.flare = 0.0F;
            if (lit.dig > 0.02F && (lit.hum == null || lit.hum.isStopped())) {
                lit.hum = new Hum(lit);
                mc.getSoundManager().play(lit.hum);
            }
            if (lit.dig > 0.0F) RuneStoneFx.leak(level, level.random, lit, lit.dig);
            boolean marked = ticks - lit.markAt <= MARK;
            if (lit.dig == 0.0F && lit.flare == 0.0F && !marked && (lit.hum == null || lit.hum.isStopped())) it.remove();
        }
        for (Iterator<Answer> it = ANSWERS.iterator(); it.hasNext(); ) {
            Answer a = it.next();
            if (ticks - a.start() >= a.delay()) {
                arrive(level, a);
                it.remove();
            }
        }
    }

    private static void clear() {
        for (Lit lit : LIT.values()) {
            lit.gone = true;
            if (lit.hum != null) lit.hum.end();
        }
        LIT.clear();
        ANSWERS.clear();
        LIT_SPRITES.clear();
    }

    /** The faces of {@code lit}'s stone that show (not against a full block), with their carvings. */
    static List<Map.Entry<Direction, Face>> open(ClientLevel level, Lit lit) {
        Map<Direction, Face> faces = faces(lit);
        List<Map.Entry<Direction, Face>> out = new ArrayList<>(faces.size());
        for (Map.Entry<Direction, Face> f : faces.entrySet()) {
            Direction d = f.getKey();
            if (Block.shouldRenderFace(lit.state, level, lit.pos, d, lit.pos.relative(d))) out.add(f);
        }
        return out;
    }

    /**
     * The carving on each face of the stone as the game draws it here: the model its position picks (as the chunk's own
     * drawing picks it), its quads whose texture is a carving's glow, and the middles of their lit texels, set on the
     * face (in the block's own space, 0 to 1).
     */
    static Map<Direction, Face> faces(Lit lit) {
        if (lit.faces != null) return lit.faces;
        Map<Direction, Face> out = new EnumMap<>(Direction.class);
        BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(lit.state);
        RandomSource rand = RandomSource.create();
        long seed = lit.state.getSeed(lit.pos);
        for (Direction d : Direction.values()) {
            rand.setSeed(seed);
            List<BakedQuad> glow = new ArrayList<>();
            List<Vec3> points = new ArrayList<>();
            for (BakedQuad q : model.getQuads(lit.state, d, rand, ModelData.EMPTY, null)) {
                if (!isGlow(q.getSprite())) continue;
                glow.add(q);
                texels(q, points);
            }
            if (!glow.isEmpty()) out.put(d, new Face(glow, points));
        }
        lit.faces = out;
        return out;
    }

    private static boolean isGlow(TextureAtlasSprite s) {
        ResourceLocation n = s.contents().name();
        return n.getNamespace().equals(ExplorationRemix.MOD_ID) && n.getPath().startsWith("block/rune_stone/") && n.getPath().endsWith("_glow");
    }

    /** The middles of a glow quad's lit texels, on the quad: its corners solved for where each texel of its texture lies. */
    private static void texels(BakedQuad q, List<Vec3> out) {
        TextureAtlasSprite s = q.getSprite();
        int[] v = q.getVertices();
        double[][] p = new double[3][3];
        double[][] t = new double[3][2];
        for (int i = 0; i < 3; i++) {
            int o = i * STRIDE;
            p[i][0] = Float.intBitsToFloat(v[o]);
            p[i][1] = Float.intBitsToFloat(v[o + 1]);
            p[i][2] = Float.intBitsToFloat(v[o + 2]);
            t[i][0] = s.getUOffset(Float.intBitsToFloat(v[o + 4]));
            t[i][1] = s.getVOffset(Float.intBitsToFloat(v[o + 5]));
        }
        double d1u = t[1][0] - t[0][0], d1v = t[1][1] - t[0][1], d2u = t[2][0] - t[0][0], d2v = t[2][1] - t[0][1];
        double det = d1u * d2v - d1v * d2u;
        if (Math.abs(det) < 1.0E-6) return;
        double[] a = new double[3], b = new double[3];
        for (int k = 0; k < 3; k++) {
            double e1 = p[1][k] - p[0][k], e2 = p[2][k] - p[0][k];
            a[k] = (e1 * d2v - e2 * d1v) / det;
            b[k] = (e2 * d1u - e1 * d2u) / det;
        }
        int w = s.contents().width(), h = s.contents().height();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (s.contents().isTransparent(0, x, y)) continue;
                // the texel's middle in the 0 to 16 the quad's coordinates are given in
                double tu = (x + 0.5) * 16.0 / w - t[0][0], tv = (y + 0.5) * 16.0 / h - t[0][1];
                out.add(new Vec3(p[0][0] + tu * a[0] + tv * b[0], p[0][1] + tu * a[1] + tv * b[1], p[0][2] + tu * a[2] + tv * b[2]));
            }
        }
    }

    /** A carving's light texture: its glow texture's name with _lit for _glow, from the same atlas. */
    private static TextureAtlasSprite litSprite(TextureAtlasSprite glow) {
        if (LIT_SPRITES.size() > 512) LIT_SPRITES.clear();
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        return LIT_SPRITES.computeIfAbsent(glow, g -> {
            ResourceLocation n = g.contents().name();
            String path = n.getPath();
            return atlas.getSprite(new ResourceLocation(n.getNamespace(), path.substring(0, path.length() - "_glow".length()) + "_lit"));
        });
    }

    // ------------------------------------------------------------------------------------------------ drawing

    /**
     * After the solid world, the entities and the block entities and before anything see-through: each lit stone's
     * light over its faces that show, and the answers' sparks and marks, seen through the rock.
     */
    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) return;
        if (LIT.isEmpty() && ANSWERS.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) return;
        float pt = event.getPartialTick();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        float time = ticks + pt;

        VertexConsumer vc = null;
        for (Lit lit : LIT.values()) {
            float k = lit.light(pt);
            if (k < 0.004F || lit.gone) continue;
            // a slow shimmer, quicker and deeper as the stone nears breaking
            float shimmer = 0.9F + 0.1F * Mth.sin(time * (0.35F + 0.5F * lit.dig) + lit.pos.hashCode());
            if (lit.dig > 0.7F) shimmer *= 0.92F + 0.16F * Mth.sin(time * 1.9F + lit.pos.getY());
            float c = Mth.clamp(k * shimmer, 0.0F, 1.0F);
            if (vc == null) vc = buffers.getBuffer(GlowRender.STONE);
            pose.pushPose();
            pose.translate(lit.pos.getX() - cam.x, lit.pos.getY() - cam.y, lit.pos.getZ() - cam.z);
            Matrix4f m = pose.last().pose();
            Matrix3f n = pose.last().normal();
            for (Map.Entry<Direction, Face> f : open(level, lit)) {
                for (BakedQuad q : f.getValue().quads()) lightQuad(vc, m, n, q, litSprite(q.getSprite()), c);
            }
            pose.popPose();
        }
        if (vc != null) buffers.endBatch(GlowRender.STONE);

        // through the rock: the answers' sparks on their way, and the stones they reached
        VertexConsumer through = null;
        for (Answer a : ANSWERS) {
            if (through == null) through = buffers.getBuffer(GlowRender.through(HALO));
            float t = Mth.clamp((float) ((time - a.start()) / a.delay()), 0.0F, 1.0F);
            Vec3 way = a.to().subtract(a.from());
            double len = way.length();
            Vec3 head = a.from().add(way.scale(t));
            // a head and a tail of fading points behind it, back to where it set off
            for (int i = 0; i < 7; i++) {
                double back = i * 0.32;
                if (back > len * t) break;
                Vec3 at = head.subtract(way.scale(back / Math.max(1.0E-3, len)));
                float fade = 1.0F - i / 7.0F;
                mark(pose, through, camera, cam, at, (i == 0 ? 0.22F : 0.14F) * (0.6F + 0.4F * fade), i == 0 ? CORE : MAIN, 0.55F * fade * fade);
            }
        }
        for (Lit lit : LIT.values()) {
            long since = ticks - lit.markAt;
            if (since > MARK || lit.gone) continue;
            if (through == null) through = buffers.getBuffer(GlowRender.through(HALO));
            float s = Mth.clamp((since + pt) / MARK, 0.0F, 1.0F);
            float swell = s < 0.12F ? s / 0.12F : 1.0F;
            float fade = (1.0F - s) * (1.0F - s);
            Vec3 at = Vec3.atCenterOf(lit.pos);
            mark(pose, through, camera, cam, at, (0.5F + 0.55F * swell) * (1.0F - 0.25F * s), MAIN, 0.42F * fade * swell);
            mark(pose, through, camera, cam, at, 0.2F + 0.12F * swell, CORE, 0.5F * fade * swell);
        }
        if (through != null) buffers.endBatch(GlowRender.through(HALO));
    }

    /** One quad of a carving's glow drawn again with the carving's light texture, the light scaled by {@code k}. */
    private static void lightQuad(VertexConsumer vc, Matrix4f m, Matrix3f n, BakedQuad q, TextureAtlasSprite lit, float k) {
        TextureAtlasSprite glow = q.getSprite();
        int[] v = q.getVertices();
        Direction d = q.getDirection();
        for (int i = 0; i < 4; i++) {
            int o = i * STRIDE;
            float x = Float.intBitsToFloat(v[o]), y = Float.intBitsToFloat(v[o + 1]), z = Float.intBitsToFloat(v[o + 2]);
            float u = lit.getU(glow.getUOffset(Float.intBitsToFloat(v[o + 4]))), w = lit.getV(glow.getVOffset(Float.intBitsToFloat(v[o + 5])));
            vc.vertex(m, x, y, z).color(k, k, k, 1.0F).uv(u, w).overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                    .normal(n, d.getStepX(), d.getStepY(), d.getStepZ()).endVertex();
        }
    }

    /** A soft point of light at {@code at}, facing the camera, {@code half} blocks to each side, seen through the rock. */
    private static void mark(PoseStack pose, VertexConsumer vc, Camera camera, Vec3 cam, Vec3 at, float half, int rgb, float alpha) {
        if (alpha <= 0.004F) return;
        pose.pushPose();
        pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
        GlowSprite.facing(pose, vc, camera.rotation(), half, 0.0F, 0.0F, scale(rgb, alpha), 1.0F);
        pose.popPose();
    }

    /** A colour darkened by {@code k}: how bright a premultiplied glow is drawn. */
    static int scale(int rgb, float k) {
        k = Mth.clamp(k, 0.0F, 1.0F);
        return Math.round((rgb >> 16 & 255) * k) << 16 | Math.round((rgb >> 8 & 255) * k) << 8 | Math.round((rgb & 255) * k);
    }

    /** 1 with all particles, less when the game's particle setting is turned down. */
    static float amount() {
        ParticleStatus s = Minecraft.getInstance().options.particles().get();
        return s == ParticleStatus.ALL ? 1.0F : s == ParticleStatus.DECREASED ? 0.5F : 0.2F;
    }

    // ------------------------------------------------------------------------------------------------ the hum

    /** The hum of a stone being dug: louder and higher as the digging goes on, dying away when it stops. */
    static final class Hum extends AbstractTickableSoundInstance {
        private final Lit lit;

        Hum(Lit lit) {
            super(ERSounds.RUNE_STONE_HUM.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
            this.lit = lit;
            this.looping = true;
            this.delay = 0;
            this.x = lit.pos.getX() + 0.5;
            this.y = lit.pos.getY() + 0.5;
            this.z = lit.pos.getZ() + 0.5;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
            set();
        }

        private void set() {
            float d = lit.dig;
            this.volume = 0.06F + 0.74F * d;
            this.pitch = 0.7F + 0.55F * d * d + 0.1F * d;
        }

        @Override
        public void tick() {
            if (lit.gone || lit.dig <= 0.01F) {
                stop();
                return;
            }
            set();
        }

        void end() { stop(); }

        @Override
        public boolean canStartSilent() { return true; }
    }
}
