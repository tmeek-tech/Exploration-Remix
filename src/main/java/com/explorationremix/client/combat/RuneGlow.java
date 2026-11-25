package com.explorationremix.client.combat;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.registry.ERSounds;
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
 * 14:45: over the whole sword, and with a sound after all, subtler than 0.12.0's chime). Since 0.22.7 (Taran,
 * 2026-11-25: a sparkly glow) the whole sword swells with light at once, a burst of star glints flashes over it, and a
 * soft halo spills past its edges, about a second in all; your own sword also makes a faint shimmer with a soft note as
 * it swells. It does not come back until the sword has run short and refilled. And for as long as the sword has the
 * energy to cast (Taran, 15:00), it sparkles: texels picked at random flash and fade, ten a second, the brighter ones
 * with a small star that crosses the sword's edges. BladeLight holds the shapes and timing; this draws them. The halo
 * and the stars face the eye, so they read the same however the sword is turned.
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
    /** How near white a twinkle's flash burns at its brightest. */
    static final float SPARK_WHITE = 0.8F;
    /** Players' swords are followed this far from the camera. */
    private static final double SEEN = 64.0;

    private static final Map<Long, BladeLight.Trigger> TRIGGERS = new HashMap<>();
    private static final Set<Long> SEEN_NOW = new HashSet<>();
    /** The hands whose sword has the energy for its next cast this tick: they sparkle. */
    private static final Set<Long> CHARGED = new HashSet<>();
    private static int clock;
    /** Dev: the local player's main-hand sword held still this many ticks into its light (-1: off). */
    private static float devTick = -1.0F;
    /** Dev: the sparkle's clock held still at this tick (-1: off). */
    private static float devSpark = -1.0F;

    private RuneGlow() {}

    private static long key(int entity, InteractionHand hand) { return ((long) entity << 1) | (hand == InteractionHand.OFF_HAND ? 1 : 0); }

    /** The local player's main hand: the sword and the slot it sits in (another slot is another sword). */
    private record Held(Item item, int slot) {}

    // ------------------------------------------------------------------------------------------------ when it plays

    /**
     * How ready a sword is: 1 ready to cast, {@link #WAITING} with the energy but waiting out a delay, 0 short of energy.
     * Read at its holder's XP level (another player's is not sent to this game, but the level only wakes the second
     * phase, which changes neither the cost nor the wait).
     */
    static float readiness(Player holder, ItemStack stack, long now) {
        if (RuneGear.host(stack) != Host.SWORD || !RuneGear.hasRunes(stack)) return 0.0F;
        HostSpec spec = RuneGear.spec(stack);
        Reading r = RuneGear.reading(stack, holder);
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
        CHARGED.clear();
        for (Player p : mc.level.players()) {
            if (p.distanceToSqr(eye) > SEEN * SEEN) continue;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = p.getItemInHand(hand);
                long key = key(p.getId(), hand);
                SEEN_NOW.add(key);
                Object held = p == mc.player && hand == InteractionHand.MAIN_HAND ? new Held(stack.getItem(), p.getInventory().selected) : stack.getItem();
                float readiness = readiness(p, stack, now);
                if (readiness > 0) CHARGED.add(key);
                boolean starts = TRIGGERS.computeIfAbsent(key, k -> new BladeLight.Trigger()).update(held, readiness, clock);
                // only your own swords are heard (runes.ready: a quiet shimmer, and a soft note as the sword swells)
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
        CHARGED.clear();
        PENDING.clear();
        devTick = -1.0F;
        devSpark = -1.0F;
    }

    /** Dev ("! glow tick" in DevShots): hold the main-hand sword's light still {@code tick} ticks in; below 0, let go. */
    public static void devHold(float tick) { devTick = tick; }

    /** Dev ("! sparkle tick" in DevShots): hold the sparkle's clock still at {@code tick}; below 0, let go. */
    public static void devSparkle(float tick) { devSpark = tick; }

    /** Whether what {@code p} holds in {@code hand} sparkles: a sword with the energy for its next cast. */
    private static boolean charged(Player p, InteractionHand hand, ItemStack stack) {
        return RuneGear.host(stack) == Host.SWORD && CHARGED.contains(key(p.getId(), hand));
    }

    /** The sparkle's clock this frame. */
    private static float sparkAt(float partialTick) { return devSpark >= 0 ? devSpark : clock + partialTick; }

    /** How far into its light (ticks, this frame) what {@code p} holds in {@code hand} is, or -1: no light. */
    private static float age(Player p, InteractionHand hand, ItemStack stack, float partialTick) {
        if (RuneGear.host(stack) != Host.SWORD) return -1.0F;
        if (devTick >= 0 && p == Minecraft.getInstance().player && hand == InteractionHand.MAIN_HAND) return devTick;
        BladeLight.Trigger t = TRIGGERS.get(key(p.getId(), hand));
        if (t == null) return -1.0F;
        float age = t.age(clock + partialTick);
        return age >= 0 && age < BladeLight.END ? age : -1.0F;
    }

    /** What tells one play of a sword's light from another: its holder, its hand, and when it started (held still: none). */
    private static int seed(Player p, InteractionHand hand) {
        BladeLight.Trigger t = TRIGGERS.get(key(p.getId(), hand));
        boolean held = devTick >= 0 && p == Minecraft.getInstance().player && hand == InteractionHand.MAIN_HAND;
        int start = t == null || held ? 0 : Math.round(t.startedAt());
        return (int) key(p.getId(), hand) * 0x01000193 + start * 0x9E3779B1;
    }

    // ------------------------------------------------------------------------------------------------ catching what is drawn

    /** A held sword being drawn with its light playing or sparkling: the faces drawn for it, and where. */
    private static final class Capture {
        final int entity;
        final InteractionHand hand;
        final boolean firstPerson;
        /** Ticks into its light (-1: none); the sparkle's clock (-1: it isn't charged). */
        final float age, sparkAt;
        /** What tells this play of its light from another (where its burst's glints fall). */
        final int seed;
        /** Where the item was placed (the hand's space), and the projection and view it was drawn with. */
        final Matrix4f head, proj, view;
        final List<Matrix4f> poses = new ArrayList<>();
        final List<BakedQuad> quads = new ArrayList<>();

        Capture(int entity, InteractionHand hand, boolean firstPerson, float age, float sparkAt, int seed, Matrix4f head, Matrix4f proj,
                Matrix4f view) {
            this.entity = entity;
            this.hand = hand;
            this.firstPerson = firstPerson;
            this.age = age;
            this.sparkAt = sparkAt;
            this.seed = seed;
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
     * playing or that sparkles, its faces are gathered: a plain model's here, as ItemRenderer will draw them; a custom
     * renderer's as it draws them.
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
        float sparkAt = charged(p, hand, stack) ? sparkAt(mc.getFrameTime()) : -1.0F;
        if ((age < 0 && sparkAt < 0) || Shaders.shadowPass()) return;
        Capture c = new Capture(p.getId(), hand, first, age, sparkAt, seed(p, hand), pose.last().pose(), RenderSystem.getProjectionMatrix(),
                RenderSystem.getModelViewMatrix());
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

    /** After a held item is drawn: its light (or sparkle), drawn among the world's buffers or kept for later. */
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

    /** Light to draw, as four-cornered pieces where the sword was drawn, with a colour at each corner (alpha: strength). */
    private static final class Lit {
        final Matrix4f proj, view;
        float[] xyz = new float[1024 * 3];
        float[] rgba = new float[1024 * 4];
        int verts;

        Lit(Matrix4f proj, Matrix4f view) {
            this.proj = proj;
            this.view = view;
        }

        void vertex(float x, float y, float z, float r, float g, float b, float a) {
            if (verts * 3 == xyz.length) {
                xyz = Arrays.copyOf(xyz, xyz.length * 2);
                rgba = Arrays.copyOf(rgba, rgba.length * 2);
            }
            xyz[verts * 3] = x;
            xyz[verts * 3 + 1] = y;
            xyz[verts * 3 + 2] = z;
            rgba[verts * 4] = r;
            rgba[verts * 4 + 1] = g;
            rgba[verts * 4 + 2] = b;
            rgba[verts * 4 + 3] = a;
            verts++;
        }

        void vertex(Vector3f p, float r, float g, float b, float a) { vertex(p.x, p.y, p.z, r, g, b, a); }

        /** Cell {@code k} of {@code corners}, lit evenly. */
        void cell(float[] corners, int k, float r, float g, float b, float a) {
            for (int q = 0; q < 4; q++) {
                int o = k * 12 + q * 3;
                vertex(corners[o], corners[o + 1], corners[o + 2], r, g, b, a);
            }
        }

        void emit(VertexConsumer vc) {
            for (int v = 0; v < verts; v++) {
                vc.vertex(xyz[v * 3], xyz[v * 3 + 1], xyz[v * 3 + 2]).color(rgba[v * 4], rgba[v * 4 + 1], rgba[v * 4 + 2], rgba[v * 4 + 3]).endVertex();
            }
        }
    }

    /** The eye, and the screen's right and up, in the space a sword was drawn in: what the halo and the stars face. */
    private record Facing(Vector3f eye, Vector3f right, Vector3f up) {
        static Facing of(Matrix4f view) {
            Matrix4f back = new Matrix4f(view).invert();
            return new Facing(back.transformPosition(new Vector3f()), back.transformDirection(new Vector3f(1, 0, 0)).normalize(),
                    back.transformDirection(new Vector3f(0, 1, 0)).normalize());
        }

        /** {@code p} brought {@code by} nearer the eye (so it is drawn over the sword it lies on). */
        Vector3f toward(Vector3f p, float by) {
            Vector3f d = new Vector3f(eye).sub(p);
            float len = d.length();
            return len < 1.0E-6F ? p : p.add(d.mul(Math.min(by, len * 0.5F) / len));
        }

        /** The way at {@code angle} (radians, from the screen's right towards its up), {@code len} long. */
        Vector3f way(double angle, float len) {
            float c = (float) Math.cos(angle) * len, s = (float) Math.sin(angle) * len;
            return new Vector3f(right).mul(c).add(up.x * s, up.y * s, up.z * s);
        }
    }

    /** A halo glow's colour (the light's own pale blue); a star's, nearly white. */
    static final float STAR_R = 0.93F, STAR_G = 0.97F, STAR_B = 1.0F;
    /** Pieces round a soft glow. */
    private static final int ROUND = 12;
    /** How far a soft glow is brought nearer the eye than what it lies on, and a star (in the sword's texels). */
    static final float GLOW_LIFT = 1.5F, STAR_LIFT = 2.0F;

    /**
     * A soft round glow facing the eye at {@code at}, {@code radius} to its rim: {@code a} strong in its middle, about
     * half that two fifths of the way out, nothing at the rim (near enough a bell).
     */
    private static void glow(Lit lit, Facing f, Vector3f at, float radius, float r, float g, float b, float a) {
        float midA = 0.53F * a;
        Vector3f c0 = null, c1 = null, m0 = null, m1;
        for (int i = 0; i <= ROUND; i++) {
            double angle = 2.0 * Math.PI * i / ROUND;
            Vector3f m = new Vector3f(at).add(f.way(angle, radius * 0.4F));
            Vector3f c = new Vector3f(at).add(f.way(angle, radius));
            if (i > 0) {
                m1 = m;
                // the middle out to the ring (a triangle: its last corner the middle again), then the ring out to the rim
                lit.vertex(at, r, g, b, a);
                lit.vertex(m0, r, g, b, midA);
                lit.vertex(m1, r, g, b, midA);
                lit.vertex(at, r, g, b, a);
                lit.vertex(m0, r, g, b, midA);
                lit.vertex(c0, r, g, b, 0.0F);
                lit.vertex(c, r, g, b, 0.0F);
                lit.vertex(m1, r, g, b, midA);
            }
            m0 = m;
            c0 = c;
        }
    }

    /**
     * A star glint facing the eye at {@code at}: four long thin points {@code size} from its middle to their tips, turned
     * {@code tilt} (radians) from upright, four short ones between them, and a small glow at its heart; {@code a} strong.
     */
    private static void star(Lit lit, Facing f, Vector3f at, float size, float tilt, float a) {
        points(lit, f, at, size, tilt, 0.13F, a);
        points(lit, f, at, size * 0.42F, tilt + Math.PI / 4.0, 0.18F, 0.7F * a);
        glow(lit, f, at, size * 0.38F, STAR_R, STAR_G, STAR_B, 0.6F * a);
    }

    /** Four points of a star, each a thin kite from the middle (strongest) to its tip (nothing). */
    private static void points(Lit lit, Facing f, Vector3f at, float len, double tilt, float width, float a) {
        for (int i = 0; i < 4; i++) {
            double angle = tilt + Math.PI / 2.0 + i * Math.PI / 2.0;
            Vector3f out = f.way(angle, 1.0F), side = f.way(angle + Math.PI / 2.0, len * width);
            Vector3f base = new Vector3f(at).add(out.x * len * 0.14F, out.y * len * 0.14F, out.z * len * 0.14F);
            lit.vertex(at, STAR_R, STAR_G, STAR_B, a);
            lit.vertex(new Vector3f(base).add(side), STAR_R, STAR_G, STAR_B, 0.55F * a);
            lit.vertex(new Vector3f(at).add(out.mul(len)), STAR_R, STAR_G, STAR_B, 0.0F);
            lit.vertex(new Vector3f(base).sub(side), STAR_R, STAR_G, STAR_B, 0.55F * a);
        }
    }

    /**
     * The light on a captured sword now: its cells, the way the light runs over them, and each one's brightness, from the
     * swell, the burst's glints and the sparkle; and over them, the halo and the stars.
     */
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
        float[] mid = new float[n * 3], drawn = new float[n * 3], corners = new float[n * 12], side = new float[n], drawnSide = new float[n];
        long[] texels = new long[n];
        Vector3f a = new Vector3f(), b = new Vector3f();
        int k = 0;
        for (int i = 0; i < faces.length; i++) {
            BladeLight.Cells f = faces[i];
            if (f.count == 0) continue;
            Matrix4f m = c.poses.get(i);
            toHand.mul(m, hand);
            TextureAtlasSprite sprite = c.quads.get(i).getSprite();
            int salt = sprite == null ? 0 : sprite.contents().name().hashCode();
            for (int j = 0; j < f.count; j++, k++) {
                texels[k] = BladeLight.key(salt, f.texels[j]);
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
                m.transformPosition(f.middles[j * 3], f.middles[j * 3 + 1], f.middles[j * 3 + 2], a);
                drawn[k * 3] = a.x;
                drawn[k * 3 + 1] = a.y;
                drawn[k * 3 + 2] = a.z;
                // a texel's size where the hand is, and where it is drawn: the cell's shorter side
                int o = j * 12;
                hand.transformPosition(f.corners[o], f.corners[o + 1], f.corners[o + 2], a);
                hand.transformPosition(f.corners[o + 3], f.corners[o + 4], f.corners[o + 5], b);
                float s1 = a.distance(b);
                hand.transformPosition(f.corners[o + 9], f.corners[o + 10], f.corners[o + 11], b);
                side[k] = Math.min(s1, a.distance(b));
                int ko = k * 12;
                float d1 = dist(corners, ko, ko + 3), d2 = dist(corners, ko, ko + 9);
                drawnSide[k] = Math.min(d1, d2);
            }
        }
        float texel = median(side, n);
        if (texel <= 0) return null;
        BladeLight.Run run = BladeLight.run(mid, n, texel);
        float len = run.texels();
        if (len <= 0) return null;
        // the sword's own texel where it is drawn, and the size the halo and the stars are measured in: a vanilla sword's
        // texel on a sword its size (so a sword drawn from a finer texture gets them as big, not smaller)
        float px = median(drawnSide, n);
        float unit = px * Math.max(0.75F, Math.min(3.0F, len / 20.0F));
        Facing facing = Facing.of(c.view);
        Lit lit = new Lit(c.proj, c.view);
        float[] white = new float[1];
        long[] distinct = BladeLight.distinct(texels, n);
        int[] of = new int[n];
        int[] front = new int[distinct.length];
        float[] nearest = new float[distinct.length];
        Arrays.fill(front, -1);
        for (int j = 0; j < n; j++) {
            int d = Arrays.binarySearch(distinct, texels[j]);
            of[j] = d;
            float dx = drawn[j * 3] - facing.eye().x, dy = drawn[j * 3 + 1] - facing.eye().y, dz = drawn[j * 3 + 2] - facing.eye().z;
            float e = dx * dx + dy * dy + dz * dz;
            if (front[d] < 0 || e < nearest[d]) {
                front[d] = j;
                nearest[d] = e;
            }
        }
        // the burst's glints, each lighting its texel white as its star flashes
        BladeLight.Glint[] burst = c.age >= 0 ? BladeLight.burst(distinct.length, c.seed) : new BladeLight.Glint[0];
        float[] flash = new float[distinct.length];
        for (BladeLight.Glint g : burst) flash[g.texel()] = Math.max(flash[g.texel()], BladeLight.glint(c.age - g.at()));
        // the sparkle, coming in as the swell dies down
        float sparkIn = c.sparkAt < 0 ? 0.0F : c.age < 0 ? 1.0F
                : BladeLight.smooth(BladeLight.SWELL_UP + BladeLight.SWELL_HOLD, BladeLight.END - 4.0F, c.age);
        float[] own = null, spark = null, sparkWhite = null;
        if (sparkIn > 0) {
            own = BladeLight.twinkles(distinct.length, c.sparkAt, (int) key(c.entity, c.hand));
            spark = new float[n];
            sparkWhite = new float[n];
            BladeLight.sparkle(texels, n, distinct, own, spark, sparkWhite);
        }
        for (int j = 0; j < n; j++) {
            float along = run.along()[j];
            if (along < 0) continue;
            float strength = BladeLight.light(along * len, len, c.age, white);
            float w = 0.5F * white[0];
            float fl = flash[of[j]];
            if (fl > 0) {
                strength = 1.0F - (1.0F - strength) * (1.0F - fl);
                w = Math.max(w, fl);
            }
            if (spark != null && spark[j] > 0) {
                float s = sparkIn * spark[j];
                strength = 1.0F - (1.0F - strength) * (1.0F - s);
                w = Math.max(w, SPARK_WHITE * sparkIn * sparkWhite[j]);
            }
            if (strength < 0.004F) continue;
            lit.cell(corners, j, R + (1.0F - R) * w, G + (1.0F - G) * w, B, Math.min(1.0F, strength));
        }
        // the halo: soft glows strung along the sword, swelling with it and spilling past its edges
        if (c.age >= 0) {
            float[] glows = BladeLight.haloGlows(run.along(), drawn, n, BladeLight.HALO_GLOWS);
            for (int g = 0; g < BladeLight.HALO_GLOWS; g++) {
                float along = glows[g * 4 + 3];
                if (along < 0) continue;
                float strength = BladeLight.HALO * BladeLight.swell(c.age - BladeLight.WASH * along);
                if (strength < 0.004F) continue;
                Vector3f at = facing.toward(new Vector3f(glows[g * 4], glows[g * 4 + 1], glows[g * 4 + 2]), GLOW_LIFT * unit);
                glow(lit, facing, at, BladeLight.HALO_RADIUS * unit, R, G, B, strength);
            }
        }
        // the burst's stars, flashing over the swell and shrinking as they fade
        for (BladeLight.Glint g : burst) {
            float age = c.age - g.at();
            float s = BladeLight.glint(age);
            if (s <= 0.004F || front[g.texel()] < 0) continue;
            int j = front[g.texel()];
            Vector3f at = facing.toward(new Vector3f(drawn[j * 3], drawn[j * 3 + 1], drawn[j * 3 + 2]), STAR_LIFT * unit);
            star(lit, facing, at, g.size() * unit * (0.45F + 0.55F * s), g.tilt() + 0.3F * age / BladeLight.GLINT, s);
        }
        // and the resting sparkle's brighter twinkles: a small star each
        if (own != null) {
            for (int d = 0; d < own.length; d++) {
                float s = sparkIn * BladeLight.twinkleStar(own[d]);
                if (s <= 0.004F || front[d] < 0) continue;
                int j = front[d];
                Vector3f at = facing.toward(new Vector3f(drawn[j * 3], drawn[j * 3 + 1], drawn[j * 3 + 2]), STAR_LIFT * unit);
                star(lit, facing, at, BladeLight.TWINKLE_STAR * unit * own[d], BladeLight.twinkleTilt(d, (int) key(c.entity, c.hand)),
                        TWINKLE_STAR_A * s);
            }
        }
        return lit.verts == 0 ? null : lit;
    }

    /** How strong a resting twinkle's star is at its brightest. */
    static final float TWINKLE_STAR_A = 0.75F;

    private static float dist(float[] v, int i, int j) {
        float dx = v[i] - v[j], dy = v[i + 1] - v[j + 1], dz = v[i + 2] - v[j + 2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The middle of the first {@code n} sizes that aren't nothing (0 if all are). */
    private static float median(float[] v, int n) {
        float[] sorted = Arrays.copyOf(v, n);
        Arrays.sort(sorted);
        int first = 0;
        while (first < n && sorted[first] <= 1.0E-6F) first++;
        return first == n ? 0.0F : sorted[(first + n) / 2];
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
