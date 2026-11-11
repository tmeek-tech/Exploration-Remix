package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.registry.ERSounds;
import com.explorationremix.rune.RunDepth;
import com.explorationremix.rune.RuneConfig;
import com.explorationremix.rune.RuneGear;
import com.explorationremix.rune.engine.CastMachine;
import com.explorationremix.rune.engine.CastReading;
import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Host;
import com.explorationremix.rune.engine.HostSpec;
import com.explorationremix.rune.engine.Reading;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * A rune sword's light when it comes back to enough energy for its next cast (Taran, 2026-11-11: pale blue-white on
 * every sword; 13:20 the same day: on the sword's own pixels, played once per recharge, and working under shaders;
 * 14:45: over the whole sword, and with a sound after all, subtler than 0.12.0's chime). A band of light runs up the
 * sword from the pommel to the tip, the tip flares, and an afterglow fades away, about a second and a third in all;
 * your own sword also makes a faint shimmer that ends in a soft note as the tip flares. It does not come back until the
 * sword has run short and refilled. BladeLight holds the shape and timing; this draws it.
 *
 * <p>The light lies on what is actually drawn for the item: the faces of its model (or, for an item with its own
 * renderer such as Create's cardboard sword, the faces that renderer sends through ItemRenderer.renderQuadList), cut
 * into the texels they show. In first person, and in third person under a shader pack, it is drawn once the world is
 * finished (after the shader pack's last pass, which would otherwise paint its reflections over it), against the depth
 * the world left behind, with the same projection the sword was drawn with, so whatever covered the sword still covers
 * the light. Without a shader pack, a sword seen from outside gets its light among the world's own buffers.
 */
@Mod.EventBusSubscriber(modid = ExplorationRemix.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RuneGlow {
    /** How ready a sword with the energy but waiting out a cast's delay counts (see BladeLight.Trigger). */
    static final float WAITING = 0.35F;
    /** The light's colour (pale blue-white); its hottest parts burn halfway to white. */
    static final float R = 0xD6 / 255.0F, G = 0xEC / 255.0F, B = 1.0F;
    /** Players' swords are followed this far from the camera. */
    private static final double SEEN = 64.0;

    private static final Map<Long, BladeLight.Trigger> TRIGGERS = new HashMap<>();
    private static final Set<Long> SEEN_NOW = new HashSet<>();
    private static int clock;
    /** Dev: the local player's main-hand sword held still this many ticks into its light (-1: off). */
    private static float devTick = -1.0F;

    private RuneGlow() {}

    private static long key(int entity, InteractionHand hand) { return ((long) entity << 1) | (hand == InteractionHand.OFF_HAND ? 1 : 0); }

    /** The local player's main hand: the sword and the slot it sits in (another slot is another sword). */
    private record Held(Item item, int slot) {}

    // ------------------------------------------------------------------------------------------------ when it plays

    /** How ready a sword is: 1 ready to cast, {@link #WAITING} with the energy but waiting out a delay, 0 short of energy. */
    static float readiness(ItemStack stack, long now) {
        if (RuneGear.host(stack) != Host.SWORD || !RuneGear.hasRunes(stack)) return 0.0F;
        HostSpec spec = RuneGear.spec(stack);
        Reading r = RuneGear.reading(stack, RunDepth.clientDepth());
        if (spec == null || r == null) return 0.0F;
        CastState s = RuneGear.state(stack, spec, now);
        int next = CastMachine.next(s, r, now, RuneConfig.limits());
        if (next < 0) return 0.0F;
        CastReading c = r.casts().get(next);
        if (CastMachine.energyAt(s, spec, now) + 1.0E-3F < c.cost()) return 0.0F;
        return now < s.readyAt ? WAITING : 1.0F;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        clock++;
        long now = mc.level.getGameTime();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        SEEN_NOW.clear();
        for (Player p : mc.level.players()) {
            if (p.distanceToSqr(eye) > SEEN * SEEN) continue;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = p.getItemInHand(hand);
                long key = key(p.getId(), hand);
                SEEN_NOW.add(key);
                Object held = p == mc.player && hand == InteractionHand.MAIN_HAND ? new Held(stack.getItem(), p.getInventory().selected) : stack.getItem();
                boolean starts = TRIGGERS.computeIfAbsent(key, k -> new BladeLight.Trigger()).update(held, readiness(stack, now), clock);
                // only your own swords are heard (runes.ready: a quiet shimmer, and a soft note as the tip flares)
                if (starts && p == mc.player) {
                    mc.level.playLocalSound(p.getX(), p.getEyeY(), p.getZ(), ERSounds.RUNES_READY.get(), SoundSource.PLAYERS, 0.5F,
                            0.97F + p.getRandom().nextFloat() * 0.06F, false);
                }
            }
        }
        TRIGGERS.keySet().retainAll(SEEN_NOW);
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut e) {
        TRIGGERS.clear();
        PENDING.clear();
        devTick = -1.0F;
    }

    /** Dev ("! glow tick" in DevShots): hold the main-hand sword's light still {@code tick} ticks in; below 0, let go. */
    public static void devHold(float tick) { devTick = tick; }

    /** How far into its light (ticks, this frame) what {@code p} holds in {@code hand} is, or -1: no light. */
    private static float age(Player p, InteractionHand hand, ItemStack stack, float partialTick) {
        if (RuneGear.host(stack) != Host.SWORD) return -1.0F;
        if (devTick >= 0 && p == Minecraft.getInstance().player && hand == InteractionHand.MAIN_HAND) return devTick;
        BladeLight.Trigger t = TRIGGERS.get(key(p.getId(), hand));
        if (t == null) return -1.0F;
        float age = t.age(clock + partialTick);
        return age >= 0 && age < BladeLight.END ? age : -1.0F;
    }

    // ------------------------------------------------------------------------------------------------ catching what is drawn

    /** A held sword being drawn with its light playing: the faces drawn for it, and where. */
    private static final class Capture {
        final int entity;
        final InteractionHand hand;
        final boolean firstPerson;
        final float age;
        /** Where the item was placed (the hand's space), and the projection and view it was drawn with. */
        final Matrix4f head, proj, view;
        final List<Matrix4f> poses = new ArrayList<>();
        final List<BakedQuad> quads = new ArrayList<>();

        Capture(int entity, InteractionHand hand, boolean firstPerson, float age, Matrix4f head, Matrix4f proj, Matrix4f view) {
            this.entity = entity;
            this.hand = hand;
            this.firstPerson = firstPerson;
            this.age = age;
            this.head = new Matrix4f(head);
            this.proj = new Matrix4f(proj);
            this.view = new Matrix4f(view);
        }

        void add(Matrix4f pose, List<BakedQuad> faces) {
            if (faces.isEmpty()) return;
            Matrix4f at = new Matrix4f(pose);
            for (BakedQuad q : faces) {
                poses.add(at);
                quads.add(q);
            }
        }
    }

    /** True while the game draws the world (the level and the hands); held items drawn elsewhere (screens) get no light. */
    private static boolean inLevel;
    /** The sword being drawn now; whether it is drawn by its own renderer (its faces then come through renderQuadList). */
    private static Capture current;
    private static boolean custom;

    /**
     * Before a held item is drawn ({@code pose} as it is handed to ItemRenderer): if it is a sword whose light is
     * playing, its faces are gathered: a plain model's here, as ItemRenderer will draw them; a custom renderer's as it
     * draws them.
     */
    public static void beginItem(LivingEntity holder, ItemStack stack, ItemDisplayContext ctx, boolean leftHand, PoseStack pose) {
        current = null;
        custom = false;
        if (!inLevel || (TRIGGERS.isEmpty() && devTick < 0) || !(holder instanceof Player p) || stack.isEmpty()) return;
        boolean first = ctx.firstPerson();
        if (!first && ctx != ItemDisplayContext.THIRD_PERSON_LEFT_HAND && ctx != ItemDisplayContext.THIRD_PERSON_RIGHT_HAND) return;
        Minecraft mc = Minecraft.getInstance();
        boolean right = ctx == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND || ctx == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        InteractionHand hand = (right ? HumanoidArm.RIGHT : HumanoidArm.LEFT) == p.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        float age = age(p, hand, stack, mc.getFrameTime());
        if (age < 0 || Shaders.shadowPass()) return;
        Capture c = new Capture(p.getId(), hand, first, age, pose.last().pose(), RenderSystem.getProjectionMatrix(), RenderSystem.getModelViewMatrix());
        BakedModel model = mc.getItemRenderer().getModel(stack, p.level(), p, p.getId() + ctx.ordinal());
        PoseStack at = new PoseStack();
        at.last().pose().set(pose.last().pose());
        at.last().normal().set(pose.last().normal());
        model = model.applyTransform(ctx, at, leftHand);
        at.translate(-0.5F, -0.5F, -0.5F);
        if (model.isCustomRenderer()) {
            custom = true;
        } else {
            RandomSource random = RandomSource.create();
            for (BakedModel part : model.getRenderPasses(stack, true)) {
                for (Direction d : Direction.values()) {
                    random.setSeed(42L);
                    c.add(at.last().pose(), part.getQuads(null, d, random));
                }
                random.setSeed(42L);
                c.add(at.last().pose(), part.getQuads(null, null, random));
            }
        }
        current = c;
    }

    /** ItemRenderer.renderQuadList, while a sword with its own renderer is drawn: these faces, drawn at {@code pose}. */
    public static void quads(PoseStack pose, List<BakedQuad> faces) {
        if (custom && current != null) current.add(pose.last().pose(), faces);
    }

    /** After a held item is drawn: its light, if it has one playing, drawn among the world's buffers or kept for later. */
    public static void endItem(MultiBufferSource buffers) {
        Capture c = current;
        current = null;
        custom = false;
        if (c == null || c.quads.isEmpty()) return;
        Lit lit = light(c);
        if (lit == null) return;
        if (c.firstPerson || Shaders.inUse()) PENDING.put(key(c.entity, c.hand) << 1 | (c.firstPerson ? 1 : 0), lit);
        else lit.emit(buffers.getBuffer(GlowRender.BLADE));
    }

    // ------------------------------------------------------------------------------------------------ the cells

    /** Each face's cells, cut once (faces live as long as the models they belong to). */
    private static final Map<BakedQuad, BladeLight.Cells> CELLS = new WeakHashMap<>();

    private static BladeLight.Cells cells(BakedQuad q) {
        BladeLight.Cells c = CELLS.get(q);
        if (c == null) {
            c = cut(q);
            CELLS.put(q, c);
        }
        return c;
    }

    /** A face cut into the texels it shows (its corners and their texture coordinates read from its vertices). */
    private static BladeLight.Cells cut(BakedQuad q) {
        int[] v = q.getVertices();
        int stride = v.length / 4;
        TextureAtlasSprite sprite = q.getSprite();
        if (stride < 6 || sprite == null) return BladeLight.Cells.NONE;
        float u0 = sprite.getU0(), u1 = sprite.getU1(), v0 = sprite.getV0(), v1 = sprite.getV1();
        int w = sprite.contents().width(), h = sprite.contents().height();
        if (u1 == u0 || v1 == v0 || w <= 0 || h <= 0) return BladeLight.Cells.NONE;
        float[] pos = new float[12], st = new float[8];
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            pos[i * 3] = Float.intBitsToFloat(v[o]);
            pos[i * 3 + 1] = Float.intBitsToFloat(v[o + 1]);
            pos[i * 3 + 2] = Float.intBitsToFloat(v[o + 2]);
            st[i * 2] = (Float.intBitsToFloat(v[o + 4]) - u0) / (u1 - u0) * w;
            st[i * 2 + 1] = (Float.intBitsToFloat(v[o + 5]) - v0) / (v1 - v0) * h;
        }
        return BladeLight.cut(pos, st, w, h, (x, y) -> pixel(sprite, x, y));
    }

    private static int pixel(TextureAtlasSprite sprite, int x, int y) {
        try {
            return sprite.getPixelRGBA(0, x, y);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------------------------------------ the light

    /** Squares of light to draw: four corners each where the sword was drawn, and a colour (alpha: strength). */
    private static final class Lit {
        final Matrix4f proj, view;
        float[] xyz = new float[256 * 12];
        float[] rgba = new float[256 * 4];
        int count;

        Lit(Matrix4f proj, Matrix4f view) {
            this.proj = proj;
            this.view = view;
        }

        void add(float[] corners, int k, float r, float g, float b, float a) {
            if (count * 12 == xyz.length) {
                xyz = Arrays.copyOf(xyz, xyz.length * 2);
                rgba = Arrays.copyOf(rgba, rgba.length * 2);
            }
            System.arraycopy(corners, k * 12, xyz, count * 12, 12);
            rgba[count * 4] = r;
            rgba[count * 4 + 1] = g;
            rgba[count * 4 + 2] = b;
            rgba[count * 4 + 3] = a;
            count++;
        }

        void emit(VertexConsumer vc) {
            for (int k = 0; k < count; k++) {
                float r = rgba[k * 4], g = rgba[k * 4 + 1], b = rgba[k * 4 + 2], a = rgba[k * 4 + 3];
                for (int q = 0; q < 4; q++) {
                    int o = k * 12 + q * 3;
                    vc.vertex(xyz[o], xyz[o + 1], xyz[o + 2]).color(r, g, b, a).endVertex();
                }
            }
        }
    }

    /** The light on a captured sword now: its cells, the way the light runs over them, and each one's brightness. */
    private static Lit light(Capture c) {
        BladeLight.Cells[] faces = new BladeLight.Cells[c.quads.size()];
        int n = 0;
        for (int i = 0; i < faces.length; i++) {
            faces[i] = cells(c.quads.get(i));
            n += faces[i].count;
        }
        if (n == 0) return null;
        Matrix4f toHand = new Matrix4f(c.head).invert();
        Matrix4f hand = new Matrix4f();
        float[] mid = new float[n * 3], corners = new float[n * 12], side = new float[n];
        Vector3f a = new Vector3f(), b = new Vector3f();
        int k = 0;
        for (int i = 0; i < faces.length; i++) {
            BladeLight.Cells f = faces[i];
            if (f.count == 0) continue;
            Matrix4f m = c.poses.get(i);
            toHand.mul(m, hand);
            for (int j = 0; j < f.count; j++, k++) {
                for (int q = 0; q < 4; q++) {
                    int o = j * 12 + q * 3;
                    m.transformPosition(f.corners[o], f.corners[o + 1], f.corners[o + 2], a);
                    corners[k * 12 + q * 3] = a.x;
                    corners[k * 12 + q * 3 + 1] = a.y;
                    corners[k * 12 + q * 3 + 2] = a.z;
                }
                hand.transformPosition(f.middles[j * 3], f.middles[j * 3 + 1], f.middles[j * 3 + 2], a);
                mid[k * 3] = a.x;
                mid[k * 3 + 1] = a.y;
                mid[k * 3 + 2] = a.z;
                // a texel's size where the hand is: the cell's shorter side
                int o = j * 12;
                hand.transformPosition(f.corners[o], f.corners[o + 1], f.corners[o + 2], a);
                hand.transformPosition(f.corners[o + 3], f.corners[o + 4], f.corners[o + 5], b);
                float s1 = a.distance(b);
                hand.transformPosition(f.corners[o + 9], f.corners[o + 10], f.corners[o + 11], b);
                side[k] = Math.min(s1, a.distance(b));
            }
        }
        float[] sorted = Arrays.copyOf(side, n);
        Arrays.sort(sorted);
        int firstReal = 0;
        while (firstReal < n && sorted[firstReal] <= 1.0E-6F) firstReal++;
        if (firstReal == n) return null;
        float texel = sorted[(firstReal + n) / 2];
        BladeLight.Run run = BladeLight.run(mid, n, texel);
        Lit lit = new Lit(c.proj, c.view);
        float[] white = new float[1];
        float len = run.texels();
        for (int j = 0; j < n; j++) {
            float along = run.along()[j];
            if (along < 0) continue;
            float strength = BladeLight.light(along * len, len, c.age, white);
            if (strength < 0.004F) continue;
            float w = 0.5F * white[0];
            lit.add(corners, j, R + (1.0F - R) * w, G + (1.0F - G) * w, B, Math.min(1.0F, strength));
        }
        return lit.count == 0 ? null : lit;
    }

    // ------------------------------------------------------------------------------------------------ drawing after the world

    /** Lights to draw once the world is finished (first person always, third person under a shader pack). */
    private static final Map<Long, Lit> PENDING = new LinkedHashMap<>();
    private static final BufferBuilder BUILDER = new BufferBuilder(8192);

    /** GameRenderer.renderLevel begins. */
    public static void beginLevel() {
        inLevel = true;
        PENDING.clear();
        current = null;
        custom = false;
    }

    /** The world is drawn (and, under a shader pack, finished: its last pass is on the screen; the hand is in it). */
    public static void afterWorld() { flush(); }

    /** GameRenderer.renderLevel ends (without a shader pack, the hand has just been drawn). */
    public static void endLevel() {
        flush();
        inLevel = false;
    }

    /**
     * The kept lights, added onto the screen against the depth left there, each with the projection and view its sword
     * was drawn with (a hand's own, under a shader pack squeezed in depth as it was), nudged towards the eye so it wins
     * against the sword it lies on.
     */
    private static void flush() {
        if (PENDING.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.getMainRenderTarget().bindWrite(true);
        Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-2.0F, -10.0F);
        BufferUploader.invalidate();
        try {
            for (Lit lit : PENDING.values()) {
                RenderSystem.setProjectionMatrix(lit.proj, VertexSorting.DISTANCE_TO_ORIGIN);
                view.setIdentity();
                view.mulPoseMatrix(lit.view);
                RenderSystem.applyModelViewMatrix();
                BUILDER.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                lit.emit(BUILDER);
                BufferUploader.drawWithShader(BUILDER.end());
            }
        } finally {
            PENDING.clear();
            RenderSystem.polygonOffset(0.0F, 0.0F);
            RenderSystem.disablePolygonOffset();
            RenderSystem.enableCull();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableBlend();
            RenderSystem.depthMask(true);
            view.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(proj, sorting);
        }
    }

    // ------------------------------------------------------------------------------------------------ shader packs

    /** Oculus (Iris), if it is there: whether a shader pack draws the world, and whether this is its shadow pass. */
    private static final class Shaders {
        private static boolean looked;
        private static Object api;
        private static Method inUse, shadow;

        static boolean inUse() { return ask(inUse); }

        static boolean shadowPass() { return ask(shadow); }

        private static boolean ask(Method m) {
            if (!looked) look();
            if (api == null || m == null) return false;
            try {
                return Boolean.TRUE.equals(m.invoke(api));
            } catch (ReflectiveOperationException | RuntimeException e) {
                return false;
            }
        }

        private static void look() {
            looked = true;
            try {
                Class<?> c = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object a = c.getMethod("getInstance").invoke(null);
                inUse = c.getMethod("isShaderPackInUse");
                shadow = c.getMethod("isRenderingShadowPass");
                api = a;
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                api = null;
            }
        }
    }
}
