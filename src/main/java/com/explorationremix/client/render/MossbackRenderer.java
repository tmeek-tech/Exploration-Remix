package com.explorationremix.client.render;

import com.explorationremix.ExplorationRemix;
import com.explorationremix.client.model.MossbackModel;
import com.explorationremix.creature.MossbackEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Draws the Mossback as its body lies on the rock (0.15.0: see MossbackModel and creature.body): bent along floors,
 * walls, ceilings and the edges between them, its feet on the blocks under them. Two glowing layers go over the
 * texture: its eyes, always faintly lit, and its markings (flank spots, frill rims, throat), dark while it keeps still
 * and bright green (pale blue for the frost cousin) once it moves. Both are drawn as eyes are, so shader packs light
 * them. A third layer draws the catch a roamer carries home, limp in its jaws.
 */
public class MossbackRenderer extends MobRenderer<MossbackEntity, MossbackModel> {
    private static final ResourceLocation[] TEXTURES = textures("");
    private static final ResourceLocation[] EYES = textures("_eyes");
    private static final ResourceLocation[] MARKINGS = textures("_flare");

    private static ResourceLocation[] textures(String suffix) {
        return new ResourceLocation[]{
                new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/mossback/mossback" + suffix + ".png"),
                new ResourceLocation(ExplorationRemix.MOD_ID, "textures/entity/mossback/mossback_frost" + suffix + ".png")};
    }

    public MossbackRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new MossbackModel(), 0.9F);
        addLayer(new Glow(this, EYES, true));
        addLayer(new Glow(this, MARKINGS, false));
        addLayer(new Catch(this, ctx.getEntityRenderDispatcher()));
    }

    @Override
    public ResourceLocation getTextureLocation(MossbackEntity e) {
        return TEXTURES[Mth.clamp(e.variant(), 0, 1)];
    }

    /** Its body lies in the world as it is, turned, bent and (dying) rolled over already: nothing to turn here. */
    @Override
    protected void setupRotations(MossbackEntity e, PoseStack pose, float bob, float bodyYaw, float partialTick) {
    }

    /** One glowing layer. Eyes keep a faint light while still; markings go fully dark. */
    private static final class Glow extends RenderLayer<MossbackEntity, MossbackModel> {
        private final ResourceLocation[] textures;
        private final boolean eyes;

        Glow(RenderLayerParent<MossbackEntity, MossbackModel> parent, ResourceLocation[] textures, boolean eyes) {
            super(parent);
            this.textures = textures;
            this.eyes = eyes;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, MossbackEntity e, float limbSwing, float amount,
                           float partialTick, float age, float headYaw, float headPitch) {
            float g = e.glow(partialTick);
            float level = eyes ? 0.3F + 0.7F * g : g;
            if (e.isDeadOrDying()) level *= Math.max(0.0F, 1.0F - e.deathTime / 20.0F);
            if (level < 0.02F) return;
            VertexConsumer vc = buffers.getBuffer(RenderType.eyes(textures[Mth.clamp(e.variant(), 0, 1)]));
            getParentModel().renderBright(pose, vc, level, level, level, 1.0F);
            // winding up a strike: the eyes blaze and the markings flare, drawn again over themselves (it adds up)
            float wind = e.windup(partialTick);
            float surge = wind * (eyes ? 1.0F : 0.6F);
            if (surge > 0.02F && !e.isDeadOrDying()) {
                getParentModel().renderBright(pose, vc, surge, surge, surge, 1.0F);
            }
        }
    }

    /**
     * The catch in its jaws: a copy of the animal made only to be drawn (one for each kind, never put in the level),
     * drawn by its own renderer, crosswise between the jaws and rolled limp onto its side by hand (not with its death
     * time, which would tint it red), shrunk to fit if it is big (a sheep or a goat).
     */
    private static final class Catch extends RenderLayer<MossbackEntity, MossbackModel> {
        private static final Map<String, Entity> DUMMIES = new HashMap<>();
        private static final Set<String> UNDRAWABLE = new HashSet<>();
        @Nullable private static Level dummyLevel;
        private static boolean listening;
        private final EntityRenderDispatcher dispatcher;

        Catch(RenderLayerParent<MossbackEntity, MossbackModel> parent, EntityRenderDispatcher dispatcher) {
            super(parent);
            this.dispatcher = dispatcher;
            if (!listening) {
                // the copies belong to the level they were made in: let them go with it
                listening = true;
                MinecraftForge.EVENT_BUS.addListener(Catch::onUnload);
            }
        }

        private static void onUnload(LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) forget();
        }

        private static void forget() {
            DUMMIES.clear();
            UNDRAWABLE.clear();
            dummyLevel = null;
        }

        @Nullable
        private static Entity dummy(String id, Level level) {
            if (level != dummyLevel) { forget(); dummyLevel = level; }
            Entity d = DUMMIES.get(id);
            if (d != null || UNDRAWABLE.contains(id)) return d;
            try {
                d = EntityType.byString(id).<Entity>map(t -> t.create(level)).orElse(null);
            } catch (RuntimeException ex) {
                d = null;                                   // a kind that cannot be made outside its own spawning
            }
            if (d == null) UNDRAWABLE.add(id);
            else DUMMIES.put(id, d);
            return d;
        }

        @Override
        public void render(PoseStack pose, MultiBufferSource buffers, int light, MossbackEntity e, float limbSwing, float amount,
                           float partialTick, float age, float headYaw, float headPitch) {
            String id = e.carried();
            if (id.isEmpty() || e.isInvisible()) return;
            Entity d = dummy(id, e.level());
            if (d == null) return;
            d.setPos(e.getX(), e.getY(), e.getZ());
            float h = d.getBbHeight();
            float s = Math.min(1.0F, 0.7F / Math.max(d.getBbWidth(), h));
            pose.pushPose();
            getParentModel().translateToJaw(pose);
            // undo LivingEntityRenderer's flip (a scale of -1, -1, 1): up is up again, the snout still towards -z
            pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
            // crosswise in the jaws (its head to one side), limp on its side, swinging a little as the lizard walks
            pose.mulPose(Axis.YP.rotationDegrees(90.0F));
            pose.mulPose(Axis.ZP.rotationDegrees(90.0F + Mth.sin(limbSwing * 0.55F) * 10.0F * Math.min(1.0F, amount)));
            pose.scale(s, s, s);
            pose.translate(0.0F, -h * 0.5F, 0.0F);          // its middle between the jaws
            dispatcher.getRenderer(d).render(d, 0.0F, partialTick, pose, buffers, light);
            pose.popPose();
        }
    }
}
