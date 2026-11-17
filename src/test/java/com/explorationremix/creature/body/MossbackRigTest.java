package com.explorationremix.creature.body;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mossback's rig: its faces are vanilla's (so the painted texture lands where it always did), its slices keep the
 * texture running smoothly along a face, and on flat rock its bending trunk lies just where the model has it at rest.
 */
class MossbackRigTest {
    private static final MossbackRig RIG = MossbackRig.load();

    @Test
    void everyBoxHasVanillasFacesCornersAndTexture() {
        CreatureShape shape = RIG.shape;
        PoseStack.Pose identity = new PoseStack().last();
        int boxes = 0;
        for (CreatureShape.Part p : shape.parts) {
            for (CreatureShape.Box b : p.boxes) {
                boxes++;
                double g = b.inflate();
                ModelPart.Cube cube = new ModelPart.Cube((int) b.u(), (int) b.v(), (float) b.x(), (float) b.y(), (float) b.z(),
                        (float) b.w(), (float) b.h(), (float) b.d(), (float) g, (float) g, (float) g, false, shape.texWidth, shape.texHeight,
                        EnumSet.allOf(Direction.class));
                // vanilla's corners as it draws them: position (blocks), texture, facing
                List<float[]> drawn = new ArrayList<>();
                cube.compile(identity, new Capture(drawn), 0, 0, 1, 1, 1, 1);
                double[][][] ours = MossbackRig.faces(b);
                assertEquals(24, drawn.size(), p.name);
                for (int f = 0; f < 6; f++) {
                    double[][] q = ours[f];
                    Vector3d n = new Vector3d(q[2][0] - q[0][0], q[2][1] - q[0][1], q[2][2] - q[0][2])
                            .cross(q[3][0] - q[1][0], q[3][1] - q[1][1], q[3][2] - q[1][2]);
                    for (int k = 0; k < 4; k++) {
                        float[] v = drawn.get(f * 4 + k);
                        double[] c = q[k];
                        String at = p.name + " face " + f + " corner " + k;
                        assertEquals(v[0] * 16.0, c[0], 1.0E-4, at);
                        assertEquals(v[1] * 16.0, c[1], 1.0E-4, at);
                        assertEquals(v[2] * 16.0, c[2], 1.0E-4, at);
                        assertEquals(v[3], c[3] / shape.texWidth, 1.0E-5, at);
                        assertEquals(v[4], c[4] / shape.texHeight, 1.0E-5, at);
                        // and it faces the way vanilla's does
                        if (k == 0 && n.lengthSquared() > 1.0E-12) {
                            n.normalize();
                            assertTrue(n.x * v[5] + n.y * v[6] + n.z * v[7] > 0.999, at + " faces " + n);
                        }
                    }
                }
            }
        }
        assertTrue(boxes > 30, "only " + boxes + " boxes");
    }

    /** Keeps each corner vanilla draws: x, y, z, u, v and its facing. */
    private record Capture(List<float[]> out) implements VertexConsumer {
        @Override
        public void vertex(float x, float y, float z, float r, float g, float b, float a, float u, float v, int overlay, int light,
                           float nx, float ny, float nz) {
            out.add(new float[]{x, y, z, u, v, nx, ny, nz});
        }

        @Override public VertexConsumer vertex(double x, double y, double z) { throw new UnsupportedOperationException(); }
        @Override public VertexConsumer color(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer uv(float u, float v) { return this; }
        @Override public VertexConsumer overlayCoords(int u, int v) { return this; }
        @Override public VertexConsumer uv2(int u, int v) { return this; }
        @Override public VertexConsumer normal(float x, float y, float z) { return this; }
        @Override public void endVertex() { }
        @Override public void defaultColor(int r, int g, int b, int a) { }
        @Override public void unsetDefaultColor() { }
    }

    @Test
    void aSliceKeepsTheFacesTextureRunningAlongIt() {
        CreatureShape.Box b = new CreatureShape.Box(-3, -2, -4, 6, 4, 9, 10, 20, 0.25);
        double[][][] faces = MossbackRig.faces(b);
        double z0 = b.z() - b.inflate(), z1 = b.z() + b.d() + b.inflate();
        for (int f : new int[]{MossbackRig.DOWN, MossbackRig.UP, MossbackRig.WEST, MossbackRig.EAST}) {
            double[][] q = faces[f];
            for (double za = z0; za < z1 - 0.5; za += 1.0) {
                double zb = Math.min(z1, za + 1.0);
                double[][] s = MossbackRig.slice(q, z0, z1, za, zb);
                for (double[] c : s) {
                    assertTrue(Math.abs(c[2] - za) < 1.0E-9 || Math.abs(c[2] - zb) < 1.0E-9, "a slice's corner lies at one of its ends");
                    // the face's own texture at that point: the texture runs linearly along the face
                    double[] want = uvAt(q, c);
                    assertEquals(want[0], c[3], 1.0E-9, "u on face " + f);
                    assertEquals(want[1], c[4], 1.0E-9, "v on face " + f);
                }
                // the slice faces the way its face does
                Vector3d nq = normal(q), ns = normal(s);
                assertTrue(nq.dot(ns) > 0.999, "face " + f + " slice turned over");
            }
        }
    }

    /** The texel a face of four corners shows at point c (its texture is laid on it linearly). */
    private static double[] uvAt(double[][] q, double[] c) {
        Vector3d p0 = new Vector3d(q[0][0], q[0][1], q[0][2]);
        Vector3d e1 = new Vector3d(q[1][0], q[1][1], q[1][2]).sub(p0), e2 = new Vector3d(q[3][0], q[3][1], q[3][2]).sub(p0);
        Vector3d d = new Vector3d(c[0], c[1], c[2]).sub(p0);
        double s = d.dot(e1) / e1.lengthSquared(), t = d.dot(e2) / e2.lengthSquared();
        return new double[]{q[0][3] + s * (q[1][3] - q[0][3]) + t * (q[3][3] - q[0][3]), q[0][4] + s * (q[1][4] - q[0][4]) + t * (q[3][4] - q[0][4])};
    }

    private static Vector3d normal(double[][] q) {
        return new Vector3d(q[2][0] - q[0][0], q[2][1] - q[0][1], q[2][2] - q[0][2]).cross(q[3][0] - q[1][0], q[3][1] - q[1][1], q[3][2] - q[1][2]).normalize();
    }

    /**
     * Laid out on flat rock facing south, standing still, its trunk (chest, hips and neck, with their moss) lies where
     * the model has it at rest: the model's left on the world's west... east (+x), its back up, its tail to the north.
     * The whole chain is in this: the stations' frames, the skin's places across them and the way they are posed.
     */
    @Test
    void onFlatRockItsTrunkLiesWhereTheModelHasIt() {
        MossbackSpec spec = RIG.spec;
        BlockWorld world = new BlockWorld().fill(-8, -1, -8, 8, -1, 8);
        MossbackBody body = new MossbackBody(spec);
        MossbackBody.Drive drive = new MossbackBody.Drive();
        drive.x = 0.5; drive.y = 0.45; drive.z = 0.5;
        TerrainPatch rock = new TerrainPatch();
        int[] bounds = new int[6];
        for (int t = 0; t < 3; t++) {
            body.bounds(drive, bounds);
            rock.build(world, bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
            body.tick(rock, drive);
        }
        MossbackPose pose = new MossbackPose(RIG);
        pose.compute(body, 1.0, new MossbackPose.Inputs(), 0, 0, 0, null, 0);
        List<double[]> drawn = new ArrayList<>();
        pose.emit((x, y, z, u, v, nx, ny, nz, light) -> drawn.add(new double[]{x, y, z}), 0, false);
        assertEquals(RIG.vertexCount, drawn.size());

        Vector3d mid = body.jointNow(MossbackSpec.MIDDLE), restMid = spec.rest[MossbackSpec.MIDDLE];
        double trunkEnd = spec.arc[MossbackSpec.HIPS];
        double worst = 0;
        int checked = 0;
        for (int i = 0; i < RIG.vertexCount; i++) {
            int s = RIG.vStation[i];
            if (s < 0 || RIG.station[s] > trunkEnd) continue;
            // where the model has this corner at rest (pixels), and so where it should lie in the world
            Vector3d m = new Vector3d(RIG.restP[s])
                    .fma(16.0 * RIG.va[i], RIG.restL[s]).fma(16.0 * RIG.vb[i], RIG.restD[s]).fma(16.0 * RIG.vc[i], RIG.restB[s]);
            double wx = mid.x + (m.x - restMid.x) / 16.0, wy = mid.y - (m.y - restMid.y) / 16.0, wz = mid.z - (m.z - restMid.z) / 16.0;
            double[] d = drawn.get(i);
            double off = Math.sqrt((d[0] - wx) * (d[0] - wx) + (d[1] - wy) * (d[1] - wy) + (d[2] - wz) * (d[2] - wz));
            worst = Math.max(worst, off);
            checked++;
        }
        assertTrue(checked > 200, "only " + checked + " corners of the trunk");
        assertTrue(worst < 0.04, "a corner of the trunk lies " + worst + " blocks from where the model has it");
        // and its feet stand on the rock
        for (MossbackBody.Foot f : body.feet) {
            assertTrue(f.planted, f.leg.name + " is not planted");
            assertEquals(0.0, f.at.y, 1.0E-6, f.leg.name + " is not on the floor");
        }
    }
}
