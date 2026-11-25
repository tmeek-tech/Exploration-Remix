package com.explorationremix.devtools;

import com.explorationremix.world.gen.DeepMaterials;
import com.explorationremix.world.terrain.Rock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.material.MapColor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.IdentityHashMap;
import java.util.Map;

/** Pictures of real generated blocks, for checking world generation without a client. */
public final class BlockImages {
    private static final Map<Block, Integer> COLORS = new IdentityHashMap<>();

    private BlockImages() {}

    static int color(ServerLevel level, BlockPos pos, BlockState s) {
        if (s.isAir()) return 0x0b0b10;
        if (s.is(Blocks.WATER)) return 0x2f6fbf;
        if (s.is(Blocks.LAVA)) return 0xff7a10;
        if (COLORS.isEmpty()) {
            synchronized (COLORS) {
                if (COLORS.isEmpty()) for (Rock r : Rock.values()) {
                    BlockState rs = DeepMaterials.state(r);
                    if (rs != null) COLORS.putIfAbsent(rs.getBlock(), r.color);
                }
            }
        }
        Integer c = COLORS.get(s.getBlock());
        if (c != null) return c;
        if (!s.getFluidState().isEmpty()) return 0x2f6fbf;
        MapColor mc = s.getMapColor(level, pos);
        return mc == MapColor.NONE ? 0xff00ff : mc.col;
    }

    /** A vertical slice of real blocks: along x (axis 'x') or z, from y0 to y1, centred on (cx, cz). Generates chunks as needed. */
    public static BufferedImage slice(ServerLevel level, String axis, int cx, int cz, int halfWidth, int y0, int y1) {
        int w = halfWidth * 2 + 1, h = y1 - y0 + 1;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < w; i++) {
            int x = axis.equals("x") ? cx - halfWidth + i : cx;
            int z = axis.equals("x") ? cz : cz - halfWidth + i;
            ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
            for (int y = y0; y <= y1; y++) {
                pos.set(x, y, z);
                img.setRGB(i, y1 - y, color(level, pos, chunk.getBlockState(pos)));
            }
        }
        return img;
    }

    /** A horizontal map at height y: the top open-to-solid surface within dy below y, shaded by depth. */
    public static BufferedImage plan(ServerLevel level, int cx, int cz, int half, int y, int dy) {
        int w = half * 2 + 1;
        BufferedImage img = new BufferedImage(w, w, BufferedImage.TYPE_INT_RGB);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < w; i++) for (int j = 0; j < w; j++) {
            int x = cx - half + i, z = cz - half + j;
            ChunkAccess chunk = level.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, true);
            int col = 0x000000;
            boolean open = false;
            for (int yy = y; yy >= y - dy; yy--) {
                BlockState s = chunk.getBlockState(pos.set(x, yy, z));
                if (s.isAir()) { open = true; continue; }
                if (!open) { col = 0x202020; break; }
                int c = color(level, pos, s);
                double shade = 1.0 - 0.6 * (y - yy) / (double) dy;
                col = (((int) (((c >> 16) & 255) * shade)) << 16) | (((int) (((c >> 8) & 255) * shade)) << 8) | (int) ((c & 255) * shade);
                break;
            }
            img.setRGB(i, j, col);
        }
        return img;
    }

    public static void write(BufferedImage img, File file, int scale) throws IOException {
        file.getParentFile().mkdirs();
        if (scale > 1) {
            BufferedImage big = new BufferedImage(img.getWidth() * scale, img.getHeight() * scale, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < big.getWidth(); x++) for (int y = 0; y < big.getHeight(); y++) big.setRGB(x, y, img.getRGB(x / scale, y / scale));
            img = big;
        }
        ImageIO.write(img, "png", file);
    }
}
