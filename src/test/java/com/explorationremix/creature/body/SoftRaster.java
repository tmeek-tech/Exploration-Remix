package com.explorationremix.creature.body;

import org.joml.Vector3d;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** A small z-buffered software renderer for the body's clips: textured or flat triangles, lit from one side. */
final class SoftRaster {
    final int w, h, ss;
    private final int[] rgb;
    private final float[] depth;
    private final Vector3d eye = new Vector3d(), fwd = new Vector3d(), right = new Vector3d(), upv = new Vector3d();
    private double focal;
    private final Vector3d light = new Vector3d(-0.45, 0.8, -0.35).normalize();

    SoftRaster(int w, int h, int supersample) {
        this.w = w * supersample; this.h = h * supersample; this.ss = supersample;
        rgb = new int[this.w * this.h];
        depth = new float[this.w * this.h];
    }

    void camera(Vector3d eyeAt, Vector3d target, double fovDeg) {
        eye.set(eyeAt);
        fwd.set(target).sub(eyeAt).normalize();
        new Vector3d(fwd).cross(0, 1, 0, right);
        right.normalize();
        new Vector3d(right).cross(fwd, upv);
        upv.normalize();
        focal = (w / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);
    }

    void clear(int color) {
        java.util.Arrays.fill(rgb, color);
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
    }

    /** A texture: ARGB texels. */
    record Tex(int[] argb, int tw, int th) {
        static Tex load(File f) throws IOException {
            BufferedImage img = ImageIO.read(f);
            int[] a = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
            return new Tex(a, img.getWidth(), img.getHeight());
        }
    }

    private final double[] sx = new double[3], sy = new double[3], sz = new double[3];

    /** A triangle: world corners, texture coords (ignored without a texture), the face's normal, and a flat colour. */
    void tri(double[] p0, double[] p1, double[] p2, double[] uv0, double[] uv1, double[] uv2, Tex tex, int flat,
             double nx, double ny, double nz, double glow) {
        double[][] p = {p0, p1, p2};
        for (int i = 0; i < 3; i++) {
            double dx = p[i][0] - eye.x, dy = p[i][1] - eye.y, dz = p[i][2] - eye.z;
            double cz = dx * fwd.x + dy * fwd.y + dz * fwd.z;
            if (cz < 0.05) return;
            double cx = dx * right.x + dy * right.y + dz * right.z, cy = dx * upv.x + dy * upv.y + dz * upv.z;
            sx[i] = w / 2.0 + focal * cx / cz;
            sy[i] = h / 2.0 - focal * cy / cz;
            sz[i] = cz;
        }
        // two-sided: light the side facing the camera
        double vx = eye.x - p0[0], vy = eye.y - p0[1], vz = eye.z - p0[2];
        if (nx * vx + ny * vy + nz * vz < 0) { nx = -nx; ny = -ny; nz = -nz; }
        double shade = 0.5 + 0.5 * Math.max(0, nx * light.x + ny * light.y + nz * light.z);
        shade = Math.max(shade, glow);
        int minX = (int) Math.max(0, Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        int maxX = (int) Math.min(w - 1, Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        int minY = (int) Math.max(0, Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        int maxY = (int) Math.min(h - 1, Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        double area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0]);
        if (Math.abs(area) < 1.0E-9) return;
        double iz0 = 1 / sz[0], iz1 = 1 / sz[1], iz2 = 1 / sz[2];
        for (int y = minY; y <= maxY; y++) {
            double py = y + 0.5;
            for (int x = minX; x <= maxX; x++) {
                double px = x + 0.5;
                double w0 = ((sx[1] - px) * (sy[2] - py) - (sx[2] - px) * (sy[1] - py)) / area;
                double w1 = ((sx[2] - px) * (sy[0] - py) - (sx[0] - px) * (sy[2] - py)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < -1.0E-9 || w1 < -1.0E-9 || w2 < -1.0E-9) continue;
                double iz = w0 * iz0 + w1 * iz1 + w2 * iz2;
                double zz = 1 / iz;
                int idx = y * w + x;
                if (zz >= depth[idx]) continue;
                int c;
                if (tex != null) {
                    double u = (w0 * uv0[0] * iz0 + w1 * uv1[0] * iz1 + w2 * uv2[0] * iz2) * zz;
                    double v = (w0 * uv0[1] * iz0 + w1 * uv1[1] * iz1 + w2 * uv2[1] * iz2) * zz;
                    int tx = Math.min(tex.tw - 1, Math.max(0, (int) Math.floor(u * tex.tw)));
                    int ty = Math.min(tex.th - 1, Math.max(0, (int) Math.floor(v * tex.th)));
                    c = tex.argb[ty * tex.tw + tx];
                    if ((c >>> 24) < 128) continue;
                } else {
                    c = flat;
                }
                depth[idx] = (float) zz;
                int r = (int) Math.min(255, ((c >> 16) & 255) * shade), g = (int) Math.min(255, ((c >> 8) & 255) * shade),
                        b = (int) Math.min(255, (c & 255) * shade);
                rgb[idx] = (r << 16) | (g << 8) | b;
            }
        }
    }

    /** A dot drawn over everything (debug marks), in world space. */
    void dot(double[] p, int color, int radius) {
        double dx = p[0] - eye.x, dy = p[1] - eye.y, dz = p[2] - eye.z;
        double cz = dx * fwd.x + dy * fwd.y + dz * fwd.z;
        if (cz < 0.05) return;
        double cx = dx * right.x + dy * right.y + dz * right.z, cy = dx * upv.x + dy * upv.y + dz * upv.z;
        int x = (int) (w / 2.0 + focal * cx / cz), y = (int) (h / 2.0 - focal * cy / cz);
        int r = radius * ss;
        for (int yy = y - r; yy <= y + r; yy++)
            for (int xx = x - r; xx <= x + r; xx++)
                if (xx >= 0 && yy >= 0 && xx < w && yy < h && (xx - x) * (xx - x) + (yy - y) * (yy - y) <= r * r) rgb[yy * w + xx] = color;
    }

    void save(File f) throws IOException {
        int ow = w / ss, oh = h / ss;
        BufferedImage img = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < oh; y++)
            for (int x = 0; x < ow; x++) {
                int r = 0, g = 0, b = 0;
                for (int j = 0; j < ss; j++)
                    for (int i = 0; i < ss; i++) {
                        int c = rgb[(y * ss + j) * w + x * ss + i];
                        r += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255;
                    }
                int k = ss * ss;
                img.setRGB(x, y, ((r / k) << 16) | ((g / k) << 8) | (b / k));
            }
        ImageIO.write(img, "png", f);
    }
}
