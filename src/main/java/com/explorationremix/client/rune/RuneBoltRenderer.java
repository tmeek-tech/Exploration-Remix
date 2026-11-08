package com.explorationremix.client.rune;

import com.explorationremix.client.rune.fx.GlowRender;
import com.explorationremix.rune.effect.RuneBolt;
import com.explorationremix.rune.effect.RuneFx;
import com.explorationremix.rune.engine.RuneId;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

/**
 * A flying rune: its own sprite (Spark, Shard, Orb or Glow) facing the camera and turning slowly, in its element's
 * colour, with a hot white middle; round it a halo of the same colour that adds its light to what is behind it; and
 * behind it a ribbon of light through the places it has just been, wide at the bolt and thinning to nothing, a few
 * blocks long. Everything full bright, so it reads in a dark cave. Sparks it sheds come from the client's effects.
 */
final class RuneBoltRenderer extends EntityRenderer<RuneBolt> {
    private static final ResourceLocation SPARK = GlowSprite.texture("spark"), SHARD = GlowSprite.texture("shard"),
            ORB = GlowSprite.texture("orb"), GLOW = GlowSprite.texture("glow"), HALO = GlowSprite.texture("halo"),
            TRAIL = GlowSprite.texture("trail");
    /** The halo: this many times the sprite's size, at this strength (it adds light, so this is how bright). */
    private static final float HALO_SIZE = 2.6F, HALO_LIGHT = 0.55F;
    /** How far the sprite's colour is taken towards white, so its middle reads hotter than the halo. */
    private static final float CORE_WHITE = 0.35F;
    /**
     * Near the camera a bolt fades out (one leaving the hand, or a shard sprayed back off a wall past your face, would
     * fill the view): gone nearer than the first, whole beyond the second, in blocks.
     */
    private static final double FADE_NEAR = 0.9, FADE_FAR = 2.4;

    RuneBoltRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
        shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(RuneBolt b) {
        return switch (b.rune()) {
            case SHARD -> SHARD;
            case ORB -> ORB;
            case GLOW -> GLOW;
            default -> SPARK;
        };
    }

    /** The sprite's side in blocks at size 1: about half a block, an orb larger. */
    private static float side(RuneId rune) {
        return switch (rune) {
            case SHARD -> 0.45F;
            case ORB -> 0.8F;
            default -> 0.5F;
        };
    }

    /** Degrees it turns each tick. */
    private static float spin(RuneId rune) {
        return switch (rune) {
            case SPARK -> 4.0F;
            case SHARD -> 3.0F;
            case ORB -> 1.5F;
            default -> 2.0F;
        };
    }

    /** How long its ribbon is at size 1, in blocks. */
    private static double trailLength(RuneId rune) {
        return switch (rune) {
            case SHARD -> 3.2;
            case ORB -> 2.2;
            case GLOW -> 1.4;
            default -> 2.8;
        };
    }

    /** Half the sprite's side, in blocks. */
    private static float half(RuneBolt b) { return 0.5F * side(b.rune()) * Mth.clamp(b.size(), 0.25F, 4.0F); }

    @Override
    public boolean shouldRender(RuneBolt b, Frustum frustum, double camX, double camY, double camZ) {
        // the halo is wider than the bolt's box, and the ribbon reaches back behind it
        if (!b.shouldRender(camX, camY, camZ)) return false;
        AABB box = b.getBoundingBox().inflate(half(b) * HALO_SIZE + 0.25F);
        for (int i = 1; i < b.trailCount && i < 4; i++) box = box.minmax(new AABB(b.trail[i], b.trail[i]).inflate(0.3));
        return frustum.isVisible(box);
    }

    /** 0 (not drawn) to 1 (whole): how much of the bolt is drawn, by how near the camera its middle is. */
    private float fade(RuneBolt b, float partialTick) {
        Vec3 cam = entityRenderDispatcher.camera.getPosition();
        double x = Mth.lerp(partialTick, b.xo, b.getX()) - cam.x;
        double y = Mth.lerp(partialTick, b.yo, b.getY()) + b.getBbHeight() * 0.5 - cam.y;
        double z = Mth.lerp(partialTick, b.zo, b.getZ()) - cam.z;
        double d = Math.sqrt(x * x + y * y + z * z);
        return (float) Mth.clamp((d - FADE_NEAR) / (FADE_FAR - FADE_NEAR), 0.0, 1.0);
    }

    @Override
    public void render(RuneBolt b, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        float fade = fade(b, partialTick);
        if (!b.isInvisible() && fade > 0.0F) {
            RuneId rune = b.rune();
            int rgb = RuneFx.colour(rune, b.element());
            float half = half(b);
            float turn = Math.floorMod(b.getId() * 47, 360) + (b.tickCount + partialTick) * spin(rune);
            Quaternionf camera = entityRenderDispatcher.cameraOrientation();
            pose.pushPose();
            // its middle, where the ribbon comes from
            pose.translate(0.0F, b.getBbHeight() * 0.5F, 0.0F);
            ribbon(b, partialTick, pose, buffers, rgb, half, fade);
            float flicker = 0.9F + 0.1F * Mth.sin((b.tickCount + partialTick) * 1.7F + b.getId());
            GlowSprite.facing(pose, buffers.getBuffer(GlowRender.glow(HALO)), camera, half * HALO_SIZE * flicker, 0.0F, 0.02F,
                    scale(rgb, HALO_LIGHT * fade), 1.0F);
            GlowSprite.facing(pose, buffers.getBuffer(RenderType.entityTranslucentEmissive(getTextureLocation(b))), camera, half, turn,
                    0.0F, RuneLook.mix(0xFF000000 | rgb, 0xFFFFFFFF, CORE_WHITE) & 0xFFFFFF, fade);
            pose.popPose();
        }
        super.render(b, yaw, partialTick, pose, buffers, light);
    }

    /** A colour darkened by {@code k} (0 to 1): how light that adds is faded. */
    static int scale(int rgb, float k) {
        k = Mth.clamp(k, 0.0F, 1.0F);
        return Math.round((rgb >> 16 & 255) * k) << 16 | Math.round((rgb >> 8 & 255) * k) << 8 | Math.round((rgb & 255) * k);
    }

    /**
     * The ribbon behind it: from where it is drawn now back through where it was at the end of each of its last ticks,
     * cut to its length; wide and bright at the bolt, thinning and fading along it. Two strips: a wide one of its
     * colour, and a narrow hot one down its middle.
     */
    private void ribbon(RuneBolt b, float partialTick, PoseStack pose, MultiBufferSource buffers, int rgb, float half, float fade) {
        if (b.trailCount < 2) return;
        // in the pose's frame: the bolt's middle, now, is the origin
        double hx = Mth.lerp(partialTick, b.xo, b.getX()), hy = Mth.lerp(partialTick, b.yo, b.getY()), hz = Mth.lerp(partialTick, b.zo, b.getZ());
        double lift = b.getBbHeight() * 0.5;
        Vec3 cam = entityRenderDispatcher.camera.getPosition().subtract(hx, hy + lift, hz);
        double want = trailLength(b.rune()) * Mth.clamp(b.size(), 0.5F, 3.0F);
        Vec3[] pts = new Vec3[RuneBolt.TRAIL + 1];
        int n = 0;
        pts[n++] = Vec3.ZERO;
        double got = 0.0;
        // trail[0] is where it is at the end of this tick, ahead of where it is drawn: the ribbon starts from trail[1]
        for (int i = 1; i < b.trailCount && got < want; i++) {
            Vec3 p = b.trail[i].subtract(hx, hy, hz);
            double step = p.distanceTo(pts[n - 1]);
            if (step < 1.0E-4) continue;
            if (got + step > want) {
                p = pts[n - 1].add(p.subtract(pts[n - 1]).scale((want - got) / step));
                step = want - got;
            }
            pts[n++] = p;
            got += step;
        }
        if (n < 2 || got < 0.05) return;
        Matrix4f m = pose.last().pose();
        Matrix3f normal = pose.last().normal();
        VertexConsumer vc = buffers.getBuffer(GlowRender.glow(TRAIL));
        strip(vc, m, normal, pts, n, cam, half * 0.9F, scale(rgb, 0.75F * fade));
        strip(vc, m, normal, pts, n, cam, half * 0.3F, scale(RuneLook.mix(0xFF000000 | rgb, 0xFFFFFFFF, 0.6F) & 0xFFFFFF, 0.9F * fade));
    }

    /** One strip along the points, {@code width} wide at the first and thinning to nothing at the last. */
    private static void strip(VertexConsumer vc, Matrix4f m, Matrix3f normal, Vec3[] pts, int n, Vec3 cam, float width, int rgb) {
        Vec3 prevA = null, prevB = null;
        int prevC = 0;
        for (int i = 0; i < n; i++) {
            Vec3 along = i == 0 ? pts[0].subtract(pts[1]) : i == n - 1 ? pts[n - 2].subtract(pts[n - 1]) : pts[i - 1].subtract(pts[i + 1]);
            Vec3 view = pts[i].subtract(cam);
            Vec3 side = along.cross(view);
            double sl = side.length();
            if (sl < 1.0E-6) continue;
            float t = (float) i / (n - 1);
            float w = width * (1.0F - t) * (1.0F - t * 0.25F);
            side = side.scale(w / sl);
            Vec3 a = pts[i].add(side), c = pts[i].subtract(side);
            int col = scale(rgb, (1.0F - t) * (1.0F - t));
            if (prevA != null) {
                vertex(vc, m, normal, prevA, 0.0F, 0.0F, prevC);
                vertex(vc, m, normal, prevB, 0.0F, 1.0F, prevC);
                vertex(vc, m, normal, c, 1.0F, 1.0F, col);
                vertex(vc, m, normal, a, 1.0F, 0.0F, col);
            }
            prevA = a;
            prevB = c;
            prevC = col;
        }
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Matrix3f n, Vec3 p, float u, float v, int rgb) {
        vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).color(rgb >> 16 & 255, rgb >> 8 & 255, rgb & 255, 255).uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT).normal(n, 0.0F, 1.0F, 0.0F).endVertex();
    }
}
