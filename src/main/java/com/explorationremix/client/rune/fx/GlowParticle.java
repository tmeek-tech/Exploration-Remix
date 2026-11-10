package com.explorationremix.client.rune.fx;

import com.explorationremix.ExplorationRemix;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * One point of rune light, drawn added onto the scene at full brightness ({@link GlowRender#SHEET}). Four kinds:
 * <ul>
 *   <li>a dot, facing the camera;</li>
 *   <li>a streak: a dot drawn long along the way it flies, so a fast spark leaves a line;</li>
 *   <li>a flash: a star of light that swells and is gone in a few ticks;</li>
 *   <li>a ring lying in a plane (a shockwave), widening as it fades.</li>
 * </ul>
 * Each changes from its first colour to its last and from its first size to its last over its life, and fades out.
 * Made with {@link #dot}, {@link #streak}, {@link #flash} or {@link #ring}, set up, then {@link Spawn#spawn}.
 */
public final class GlowParticle extends TextureSheetParticle {
    public enum Mode { DOT, STREAK, FLASH, RING }

    private static final ResourceLocation DOT = new ResourceLocation(ExplorationRemix.MOD_ID, "rune_dot"),
            SPARK = new ResourceLocation(ExplorationRemix.MOD_ID, "rune_spark"),
            FLASH = new ResourceLocation(ExplorationRemix.MOD_ID, "rune_flash"),
            RING = new ResourceLocation(ExplorationRemix.MOD_ID, "rune_ring"),
            STAR = new ResourceLocation(ExplorationRemix.MOD_ID, "rune_star");

    private final Mode mode;
    private final float r0, g0, b0, r1, g1, b1;
    private final float size0, size1, alpha0;
    /** How the size eases from first to last: 1 evenly, more for a fast start. */
    private final float ease;
    /** A streak's length, in ticks of its flight. */
    private final float stretch;
    /** A ring's plane. */
    private final Vector3f u, v;

    private GlowParticle(ClientLevel level, Spawn s) {
        super(level, s.x, s.y, s.z);
        this.mode = s.mode;
        this.xd = s.vx;
        this.yd = s.vy;
        this.zd = s.vz;
        this.r0 = (s.from >> 16 & 255) / 255.0F;
        this.g0 = (s.from >> 8 & 255) / 255.0F;
        this.b0 = (s.from & 255) / 255.0F;
        this.r1 = (s.to >> 16 & 255) / 255.0F;
        this.g1 = (s.to >> 8 & 255) / 255.0F;
        this.b1 = (s.to & 255) / 255.0F;
        this.size0 = s.size0;
        this.size1 = s.size1;
        this.alpha0 = s.alpha;
        this.ease = s.ease;
        this.stretch = s.stretch;
        this.lifetime = Math.max(1, s.life);
        this.gravity = s.gravity;
        this.friction = s.drag;
        this.hasPhysics = s.physics;
        this.quadSize = s.size0;
        this.roll = this.oRoll = s.roll;
        this.rCol = r0;
        this.gCol = g0;
        this.bCol = b0;
        this.alpha = alpha0;
        Vector3f n = s.normal == null ? new Vector3f(0, 1, 0) : s.normal;
        Vector3f a = Math.abs(n.y()) < 0.9F ? new Vector3f(0, 1, 0) : new Vector3f(1, 0, 0);
        this.u = new Vector3f(n).cross(a).normalize();
        this.v = new Vector3f(n).cross(u).normalize();
        setSprite(s.sprite);
    }

    /**
     * The sprite for a kind of glow, from the particle atlas (the particle engine's own: the game's model atlases don't
     * hold it), or null if there is none.
     */
    @javax.annotation.Nullable
    private static TextureAtlasSprite sprite(Spawn s) {
        AbstractTexture t = Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES);
        if (!(t instanceof TextureAtlas atlas)) return null;
        return atlas.getSprite(switch (s.mode) {
            case FLASH -> FLASH;
            case RING -> RING;
            default -> s.star ? STAR : s.hot ? SPARK : DOT;
        });
    }

    @Override
    public ParticleRenderType getRenderType() { return GlowRender.SHEET; }

    @Override
    protected int getLightColor(float partialTick) { return LightTexture.FULL_BRIGHT; }

    @Override
    public boolean shouldCull() { return mode != Mode.RING && mode != Mode.FLASH; }

    private float life(float partialTick) { return Mth.clamp((age + partialTick) / lifetime, 0.0F, 1.0F); }

    @Override
    public float getQuadSize(float partialTick) {
        float t = life(partialTick);
        float k = ease == 1.0F ? t : 1.0F - (float) Math.pow(1.0F - t, ease);
        return size0 + (size1 - size0) * k;
    }

    @Override
    public void tick() {
        super.tick();
        float t = life(0.0F);
        rCol = r0 + (r1 - r0) * t;
        gCol = g0 + (g1 - g0) * t;
        bCol = b0 + (b1 - b0) * t;
        float left = 1.0F - t;
        alpha = alpha0 * switch (mode) {
            case FLASH -> left * left;
            case RING -> (float) Math.pow(left, 1.4);
            default -> t < 0.6F ? 1.0F : left / 0.4F;
        };
    }

    @Override
    public void render(VertexConsumer vc, Camera camera, float partialTick) {
        if (mode == Mode.STREAK) {
            streak(vc, camera, partialTick);
        } else if (mode == Mode.RING) {
            ring(vc, camera, partialTick);
        } else {
            super.render(vc, camera, partialTick);
        }
    }

    /** Drawn long along its last tick's flight, its tail where it was {@link #stretch} ticks ago. */
    private void streak(VertexConsumer vc, Camera camera, float partialTick) {
        Vec3 cam = camera.getPosition();
        float hx = (float) (Mth.lerp(partialTick, xo, x) - cam.x), hy = (float) (Mth.lerp(partialTick, yo, y) - cam.y),
                hz = (float) (Mth.lerp(partialTick, zo, z) - cam.z);
        Vector3f m = new Vector3f((float) (x - xo), (float) (y - yo), (float) (z - zo));
        float len = m.length();
        float w = getQuadSize(partialTick);
        if (len * stretch < w * 0.5F) {
            super.render(vc, camera, partialTick);
            return;
        }
        Vector3f dir = new Vector3f(m).div(len);
        Vector3f head = new Vector3f(hx, hy, hz).add(new Vector3f(dir).mul(w));
        Vector3f tail = new Vector3f(hx, hy, hz).sub(new Vector3f(dir).mul(len * stretch + w));
        // across: square to its way and to the line of sight
        Vector3f side = new Vector3f(dir).cross(hx, hy, hz);
        if (side.lengthSquared() < 1.0E-8F) {
            super.render(vc, camera, partialTick);
            return;
        }
        side.normalize().mul(w);
        quad(vc, new Vector3f(head).add(side), new Vector3f(head).sub(side), new Vector3f(tail).sub(side), new Vector3f(tail).add(side));
    }

    /** A ring in its plane, its radius its size. */
    private void ring(VertexConsumer vc, Camera camera, float partialTick) {
        Vec3 cam = camera.getPosition();
        Vector3f c = new Vector3f((float) (Mth.lerp(partialTick, xo, x) - cam.x), (float) (Mth.lerp(partialTick, yo, y) - cam.y),
                (float) (Mth.lerp(partialTick, zo, z) - cam.z));
        float r = getQuadSize(partialTick);
        Vector3f a = new Vector3f(u).mul(r), b = new Vector3f(v).mul(r);
        quad(vc, new Vector3f(c).add(a).add(b), new Vector3f(c).add(a).sub(b), new Vector3f(c).sub(a).sub(b), new Vector3f(c).sub(a).add(b));
    }

    private void quad(VertexConsumer vc, Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3) {
        float u0 = getU0(), u1 = getU1(), v0 = getV0(), v1 = getV1();
        int light = getLightColor(0.0F);
        vc.vertex(p0.x(), p0.y(), p0.z()).uv(u1, v1).color(rCol, gCol, bCol, alpha).uv2(light).endVertex();
        vc.vertex(p1.x(), p1.y(), p1.z()).uv(u1, v0).color(rCol, gCol, bCol, alpha).uv2(light).endVertex();
        vc.vertex(p2.x(), p2.y(), p2.z()).uv(u0, v0).color(rCol, gCol, bCol, alpha).uv2(light).endVertex();
        vc.vertex(p3.x(), p3.y(), p3.z()).uv(u0, v1).color(rCol, gCol, bCol, alpha).uv2(light).endVertex();
    }

    // ------------------------------------------------------------------------------------------------ making them

    public static Spawn dot(Vec3 at) { return new Spawn(Mode.DOT, at); }

    public static Spawn streak(Vec3 at) { return new Spawn(Mode.STREAK, at).stretch(2.0F); }

    public static Spawn flash(Vec3 at) { return new Spawn(Mode.FLASH, at).ease(3.0F).roll((float) (Math.random() * Math.PI * 2)); }

    public static Spawn ring(Vec3 at, Vec3 normal) { return new Spawn(Mode.RING, at).normal(normal).ease(2.5F); }

    /** One glow particle being set up. */
    public static final class Spawn {
        final Mode mode;
        final double x, y, z;
        double vx, vy, vz;
        int from = 0xFFFFFF, to = -1, life = 10;
        float size0 = 0.1F, size1 = -1.0F, alpha = 1.0F, ease = 1.0F, stretch = 2.0F, gravity, drag = 0.9F, roll;
        boolean physics, hot, star;
        Vector3f normal;
        TextureAtlasSprite sprite;

        Spawn(Mode mode, Vec3 at) {
            this.mode = mode;
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
        }

        public Spawn vel(Vec3 v) {
            vx = v.x;
            vy = v.y;
            vz = v.z;
            return this;
        }

        /** Its colour throughout. */
        public Spawn colour(int rgb) {
            from = rgb;
            return this;
        }

        /** Its colour at the end (it starts as {@link #colour}). */
        public Spawn fadeTo(int rgb) {
            to = rgb;
            return this;
        }

        /** Its size (half its width; a ring's radius) at the start and at the end. */
        public Spawn size(float start, float end) {
            size0 = start;
            size1 = end;
            return this;
        }

        public Spawn size(float s) { return size(s, s); }

        public Spawn alpha(float a) {
            alpha = Mth.clamp(a, 0.0F, 1.0F);
            return this;
        }

        public Spawn life(int ticks) {
            life = ticks;
            return this;
        }

        /** How fast it falls (1: about as an item does). */
        public Spawn gravity(float g) {
            gravity = g;
            return this;
        }

        /** What is left of its speed each tick. */
        public Spawn drag(float d) {
            drag = d;
            return this;
        }

        /** It stops against blocks. */
        public Spawn physics() {
            physics = true;
            return this;
        }

        /** A tighter, hotter sprite. */
        public Spawn hot() {
            hot = true;
            return this;
        }

        /** A four-pointed star. */
        public Spawn star() {
            star = true;
            return this;
        }

        public Spawn ease(float e) {
            ease = e;
            return this;
        }

        public Spawn stretch(float s) {
            stretch = s;
            return this;
        }

        public Spawn roll(float r) {
            roll = r;
            return this;
        }

        Spawn normal(Vec3 n) {
            double l = n.length();
            normal = l < 1.0E-6 ? new Vector3f(0, 1, 0) : new Vector3f((float) (n.x / l), (float) (n.y / l), (float) (n.z / l));
            return this;
        }

        public void spawn() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return;
            if (to < 0) to = from;
            if (size1 < 0) size1 = size0;
            sprite = sprite(this);
            if (sprite == null) return;
            mc.particleEngine.add(new GlowParticle(mc.level, this));
        }
    }
}
