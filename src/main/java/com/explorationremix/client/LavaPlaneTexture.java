package com.explorationremix.client;

import com.explorationremix.ExplorationRemix;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.Tickable;
import net.minecraft.client.resources.metadata.animation.AnimationMetadataSection;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The lava plane's texture (0.21.6, {@link LavaPlaneRenderer}): the game's still lava, {@code lava_still.png} from
 * whatever resource pack gives it, as a texture of its own that repeats, so one square of the plane can cover a whole
 * chunk with a block's worth of lava per block (the block atlas cannot repeat a sprite). It plays the lava's animation
 * from the texture's {@code .mcmeta} (its frames, in their order, each for its own ticks), one frame at a time with its
 * mipmaps made the way the atlas makes them, so far lava blurs as the game's own does. Ticked by the texture manager,
 * and loaded again on every resource reload.
 */
public final class LavaPlaneTexture extends AbstractTexture implements Tickable {
    /** Where the texture manager keeps it. */
    public static final ResourceLocation LOCATION = new ResourceLocation(ExplorationRemix.MOD_ID, "dynamic/lava_plane");
    /** The game's still lava. */
    static final ResourceLocation SOURCE = new ResourceLocation("minecraft", "textures/block/lava_still.png");

    /** Each frame's images, its mipmaps after it. */
    @Nullable private NativeImage[][] frames;
    /** The animation: the frame shown at each step, and for how many ticks. */
    private int[] order = new int[0], times = new int[0];
    private int step, left, shown = -1, levels;

    @Override
    public void load(ResourceManager resources) throws IOException {
        free();
        Resource res = resources.getResourceOrThrow(SOURCE);
        AnimationMetadataSection anim = res.metadata().getSection(AnimationMetadataSection.SERIALIZER).orElse(AnimationMetadataSection.EMPTY);
        NativeImage strip;
        try (InputStream in = res.open()) {
            strip = NativeImage.read(in);
        }
        try {
            FrameSize size = anim.calculateFrameSize(strip.getWidth(), strip.getHeight());
            int w = size.width(), h = size.height();
            if (w <= 0 || h <= 0 || strip.getWidth() % w != 0 || strip.getHeight() % h != 0) {
                throw new IOException("lava_still.png is " + strip.getWidth() + "x" + strip.getHeight() + ", not frames of " + w + "x" + h);
            }
            int columns = strip.getWidth() / w, count = columns * (strip.getHeight() / h);
            List<int[]> sequence = new ArrayList<>();
            anim.forEachFrame((index, time) -> {
                if (index >= 0 && index < count) sequence.add(new int[]{index, Math.max(1, time)});
            });
            if (sequence.isEmpty()) for (int i = 0; i < count; i++) sequence.add(new int[]{i, Math.max(1, anim.getDefaultFrameTime())});
            // as many mipmaps as the game's option asks, as far as the frame halves evenly (16 by 16: four)
            levels = Math.max(0, Math.min(Minecraft.getInstance().options.mipmapLevels().get(),
                    Math.min(Integer.numberOfTrailingZeros(w), Integer.numberOfTrailingZeros(h))));
            NativeImage[][] made = new NativeImage[count][];
            for (int f = 0; f < count; f++) {
                NativeImage frame = new NativeImage(w, h, false);
                int ox = (f % columns) * w, oy = (f / columns) * h;
                for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) frame.setPixelRGBA(x, y, strip.getPixelRGBA(ox + x, oy + y));
                made[f] = MipmapGenerator.generateMipLevels(new NativeImage[]{frame}, levels);
            }
            frames = made;
            order = new int[sequence.size()];
            times = new int[sequence.size()];
            for (int i = 0; i < order.length; i++) {
                order[i] = sequence.get(i)[0];
                times[i] = sequence.get(i)[1];
            }
            TextureUtil.prepareImage(getId(), levels, w, h);
            step = 0;
            left = times[0];
            shown = -1;
            show(order[0]);
        } finally {
            strip.close();
        }
    }

    @Override
    public void tick() {
        if (frames == null || order.length == 0) return;
        if (--left > 0) return;
        step = (step + 1) % order.length;
        left = times[step];
        if (order[step] != shown) show(order[step]);
    }

    /** Uploads frame {@code f} and its mipmaps, and has the texture repeat. */
    private void show(int f) {
        NativeImage[][] fs = frames;
        if (fs == null) return;
        bind();
        NativeImage[] mips = fs[f];
        for (int i = 0; i < mips.length && i <= levels; i++) {
            mips[i].upload(i, 0, 0, 0, 0, mips[i].getWidth(), mips[i].getHeight(), false, false, levels > 0, false);
        }
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        shown = f;
    }

    private void free() {
        NativeImage[][] fs = frames;
        frames = null;
        if (fs == null) return;
        for (NativeImage[] mips : fs) for (NativeImage m : mips) m.close();
    }

    @Override
    public void close() {
        free();
        super.close();
    }
}
