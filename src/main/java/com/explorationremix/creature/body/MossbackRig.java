package com.explorationremix.creature.body;

import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Mossback's drawn shape, rigged to its body. Its trunk and tail (and the moss on them) are one skin laid along the
 * spine: every box is cut into slices a pixel long, and each slice's corners keep their place across the spine, so the
 * body bends smoothly wherever the spine does, with no gaps or creases between parts. Its head (with the jaw, throat
 * sac and frills), the ferns on its back and its legs are solid bones: the head and ferns ride the spine where they
 * grow from it, the legs reach from their shoulders to the feet (see {@link MossbackPose}).
 *
 * <p>Every face keeps the texture vanilla's model boxes give it (ModelPart.Cube's layout, sliced where the box is cut),
 * so the painted texture lands where it always did. Built once from the shape; {@link MossbackPose} poses it each frame.
 */
public final class MossbackRig {
    /** The parts the skin is made of; a part fixed to one of these (no offset, no turn of its own) is skin too. */
    static final Set<String> TRUNK = Set.of("chest", "hips", "neck");

    public final CreatureShape shape;
    public final MossbackSpec spec;

    // ------------------------------------------------------------------------------------------------ stations
    /** Places along the spine where a frame is worked out each frame (blocks from the head's hinge). */
    final double[] station;
    /** The spine's frame at each station at rest, in the model (its point, and the ways left, down and back). */
    final Vector3d[] restP, restL, restD, restB;

    // ------------------------------------------------------------------------------------------------ vertices
    /** Four to a quad. */
    final int vertexCount;
    /** Skin: the station and the place across the spine (blocks along left, down, back); bone: -1, and the place in the bone (pixels). */
    final int[] vStation, vBone;
    final float[] va, vb, vc;
    final float[] vu, vv;

    // ------------------------------------------------------------------------------------------------ bones
    final Bone[] bones;
    final Map<String, Integer> boneIndex = new HashMap<>();
    /** Per leg: its upper, lower and foot bones, and the station its shoulder rides. */
    final int[][] legBones = new int[4][3];
    final int[] legStation = new int[4];
    final Vector3d[] legPivot = new Vector3d[4];

    static final class Bone {
        final String name;
        /** Its parent bone, or -1 when it grows from the spine (at {@link #station}). */
        final int parent, station;
        /** From the spine: its pivot in the model, and the turn of the part it grows from; from a bone: its pivot in the parent's frame. */
        final Vector3d pivot;
        final Matrix3d parentTurn;
        /** Its own turn at rest (radians, as vanilla's: z, then y, then x). */
        final double rx, ry, rz;
        /** A leg bone: which leg, and which part of it (0 upper, 1 lower, 2 foot); -1 otherwise. */
        final int leg, legPart;

        Bone(String name, int parent, int station, Vector3d pivot, Matrix3d parentTurn, double rx, double ry, double rz, int leg, int legPart) {
            this.name = name; this.parent = parent; this.station = station; this.pivot = pivot; this.parentTurn = parentTurn;
            this.rx = rx; this.ry = ry; this.rz = rz; this.leg = leg; this.legPart = legPart;
        }
    }

    public static MossbackRig load() {
        CreatureShape shape = CreatureShape.load("mossback");
        return new MossbackRig(shape, MossbackSpec.of(shape));
    }

    private static volatile MossbackRig shared;

    /** The one rig every Mossback is drawn with (and whose measurements every body moves by), read the first time it is asked for. */
    public static MossbackRig shared() {
        MossbackRig r = shared;
        if (r == null) {
            synchronized (MossbackRig.class) {
                r = shared;
                if (r == null) shared = r = load();
            }
        }
        return r;
    }

    public MossbackRig(CreatureShape shape, MossbackSpec spec) {
        this.shape = shape;
        this.spec = spec;
        Builder b = new Builder();
        // which parts are skin, and how each maps its z onto the spine
        Map<String, Skin> skins = new HashMap<>();
        for (CreatureShape.Part p : shape.parts) {
            Skin s = skin(p, skins);
            if (s != null) skins.put(p.name, s);
        }
        // bones: the legs, then every other part that is not skin
        List<Bone> boneList = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            String leg = MossbackSpec.LEGS[k];
            CreatureShape.Part up = shape.part(leg + "_upper");
            legPivot[k] = new Vector3d(up.restPivot);
            legStation[k] = b.station(spec.trunkArc(up.restPivot.z));
            String[] names = {leg + "_upper", leg + "_lower", leg + "_foot"};
            for (int j = 0; j < 3; j++) {
                CreatureShape.Part p = shape.part(names[j]);
                legBones[k][j] = boneList.size();
                boneIndex.put(p.name, boneList.size());
                boneList.add(new Bone(p.name, -1, legStation[k], new Vector3d(), new Matrix3d(), p.rx, p.ry, p.rz, k, j));
            }
        }
        for (CreatureShape.Part p : shape.parts) {
            if (skins.containsKey(p.name) || boneIndex.containsKey(p.name)) continue;
            Integer parentBone = boneIndex.get(p.parent);
            Bone bone;
            if (parentBone != null) {
                bone = new Bone(p.name, parentBone, -1, new Vector3d(p.px, p.py, p.pz), new Matrix3d(), p.rx, p.ry, p.rz, -1, -1);
            } else {
                // grows from the skin (or the root): it rides the spine where its pivot lies
                Matrix3d turn = p.parentIndex < 0 ? new Matrix3d() : new Matrix3d(shape.parts.get(p.parentIndex).restTurn);
                int st = b.station(arcOf(p.restPivot, p, skins));
                bone = new Bone(p.name, -1, st, new Vector3d(p.restPivot), turn, p.rx, p.ry, p.rz, -1, -1);
            }
            boneIndex.put(p.name, boneList.size());
            boneList.add(bone);
        }
        bones = boneList.toArray(new Bone[0]);

        // the faces: skin sliced along its length, bones whole
        for (CreatureShape.Part p : shape.parts) {
            Skin skin = skins.get(p.name);
            Integer bone = boneIndex.get(p.name);
            for (CreatureShape.Box box : p.boxes) {
                double[][][] faces = faces(box);
                for (int f = 0; f < 6; f++) {
                    double[][] q = faces[f];
                    if (area(q) < 1.0E-6) continue;
                    if (skin == null) {
                        b.quad(q, bone, null, p, shape);
                    } else if (f == NORTH || f == SOUTH) {
                        b.quad(q, -1, skin, p, shape);
                    } else {
                        double z0 = box.z() - box.inflate(), z1 = box.z() + box.d() + box.inflate();
                        double za = z0;
                        while (za < z1 - 1.0E-4) {
                            double zb = Math.min(z1, Math.floor(za + 1.0E-4) + 1.0);
                            if (z1 - zb < 1.0E-4) zb = z1;
                            b.quad(slice(q, z0, z1, za, zb), -1, skin, p, shape);
                            za = zb;
                        }
                    }
                }
            }
        }

        // the stations' frames at rest
        int ns = b.stations.size();
        station = new double[ns];
        restP = new Vector3d[ns]; restL = new Vector3d[ns]; restD = new Vector3d[ns]; restB = new Vector3d[ns];
        Vector3d[] ups = new Vector3d[spec.rest.length];
        for (int i = 0; i < ups.length; i++) ups[i] = new Vector3d(0, -1, 0);
        SpineCurve curve = new SpineCurve(spec.arc);
        curve.set(spec.rest, ups);
        Vector3d up = new Vector3d();
        for (int i = 0; i < ns; i++) {
            station[i] = b.stations.get(i);
            restP[i] = new Vector3d(); restB[i] = new Vector3d();
            curve.eval(station[i], restP[i], restB[i], up);
            restL[i] = new Vector3d(restB[i]).cross(up);
            restD[i] = new Vector3d(up).negate();
        }

        // the skin's places across the spine
        vertexCount = b.count;
        vStation = new int[vertexCount]; vBone = new int[vertexCount];
        va = new float[vertexCount]; vb = new float[vertexCount]; vc = new float[vertexCount];
        vu = new float[vertexCount]; vv = new float[vertexCount];
        Vector3d d = new Vector3d();
        for (int i = 0; i < vertexCount; i++) {
            vStation[i] = b.vStation.get(i);
            vBone[i] = b.vBone.get(i);
            double[] v = b.vPos.get(i);
            vu[i] = (float) (b.vUv.get(i)[0] / shape.texWidth);
            vv[i] = (float) (b.vUv.get(i)[1] / shape.texHeight);
            if (vStation[i] >= 0) {
                int s = vStation[i];
                d.set(v[0], v[1], v[2]).sub(restP[s]);
                va[i] = (float) (d.dot(restL[s]) / 16.0);
                vb[i] = (float) (d.dot(restD[s]) / 16.0);
                vc[i] = (float) (d.dot(restB[s]) / 16.0);
            } else {
                va[i] = (float) v[0]; vb[i] = (float) v[1]; vc[i] = (float) v[2];
            }
        }
    }

    public int quadCount() { return vertexCount / 4; }
    public int stationCount() { return station.length; }
    public int boneCount() { return bones.length; }

    int bone(String name) {
        Integer i = boneIndex.get(name);
        if (i == null) throw new IllegalArgumentException("no bone " + name);
        return i;
    }

    // ------------------------------------------------------------------------------------------------ skin

    /** How a skin part lays its z along the spine: the trunk by its place in the model, a tail part along its own segment. */
    private record Skin(boolean trunk, int joint, double zNext) {}

    private Skin skin(CreatureShape.Part p, Map<String, Skin> known) {
        if (TRUNK.contains(p.name)) return new Skin(true, -1, 0);
        for (int i = 0; i < MossbackSpec.TAIL.length; i++) {
            if (!MossbackSpec.TAIL[i].equals(p.name)) continue;
            int j = MossbackSpec.TAIL_BASE + i;
            // the next joint in this part's own frame
            Vector3d next = new Vector3d(spec.rest[j + 1]).sub(p.restPivot);
            new Matrix3d(p.restTurn).transpose().transform(next);
            return new Skin(false, j, next.z);
        }
        Skin parent = known.get(p.parent);
        boolean fixed = Math.abs(p.px) + Math.abs(p.py) + Math.abs(p.pz) < 1.0E-9 && Math.abs(p.rx) + Math.abs(p.ry) + Math.abs(p.rz) < 1.0E-9;
        return parent != null && fixed ? parent : null;
    }

    /** Where along the spine a point of a skin part lies: {@code local} in the part's own frame. */
    private double skinArc(Skin s, CreatureShape.Part p, double[] local) {
        if (s.trunk) {
            Vector3d m = p.restTurn.transform(new Vector3d(local[0], local[1], local[2])).add(p.restPivot);
            return spec.trunkArc(m.z);
        }
        int j = s.joint;
        return spec.arc[j] + local[2] * (spec.arc[j + 1] - spec.arc[j]) / s.zNext;
    }

    /** Where along the spine a bone that grows from the skin rides: its pivot's place. */
    private double arcOf(Vector3d modelPivot, CreatureShape.Part p, Map<String, Skin> skins) {
        CreatureShape.Part parent = p.parentIndex < 0 ? null : shape.parts.get(p.parentIndex);
        Skin s = parent == null ? null : skins.get(parent.name);
        if (s != null && !s.trunk) {
            Vector3d local = new Vector3d(modelPivot).sub(parent.restPivot);
            new Matrix3d(parent.restTurn).transpose().transform(local);
            return skinArc(s, parent, new double[]{local.x, local.y, local.z});
        }
        return spec.trunkArc(modelPivot.z);
    }

    // ------------------------------------------------------------------------------------------------ vanilla's box layout

    static final int DOWN = 0, UP = 1, WEST = 2, NORTH = 3, EAST = 4, SOUTH = 5;

    /**
     * A box's six faces as ModelPart.Cube makes them: per face four corners, each (x, y, z, u, v) in pixels and texels,
     * in vanilla's order (their outward normal is (c2 - c0) x (c3 - c1)). DOWN is the box's top as drawn (the model's
     * y grows downward).
     */
    static double[][][] faces(CreatureShape.Box b) {
        double g = b.inflate();
        double x0 = b.x() - g, y0 = b.y() - g, z0 = b.z() - g;
        double x1 = b.x() + b.w() + g, y1 = b.y() + b.h() + g, z1 = b.z() + b.d() + g;
        double w = b.w(), h = b.h(), d = b.d();
        double u = b.u(), v = b.v();
        double f4 = u, f5 = u + d, f6 = u + d + w, f7 = u + d + w + w, f8 = u + d + w + d, f9 = u + d + w + d + w;
        double f10 = v, f11 = v + d, f12 = v + d + h;
        double[] c7 = {x0, y0, z0}, c0 = {x1, y0, z0}, c1 = {x1, y1, z0}, c2 = {x0, y1, z0};
        double[] c3 = {x0, y0, z1}, c4 = {x1, y0, z1}, c5 = {x1, y1, z1}, c6 = {x0, y1, z1};
        return new double[][][]{
                poly(c4, c3, c7, c0, f5, f10, f6, f11),
                poly(c1, c2, c6, c5, f6, f11, f7, f10),
                poly(c7, c3, c6, c2, f4, f11, f5, f12),
                poly(c0, c7, c2, c1, f5, f11, f6, f12),
                poly(c4, c0, c1, c5, f6, f11, f8, f12),
                poly(c3, c4, c5, c6, f8, f11, f9, f12),
        };
    }

    /** ModelPart.Polygon: the corners take (u2, v1), (u1, v1), (u1, v2), (u2, v2). */
    private static double[][] poly(double[] a, double[] b, double[] c, double[] d, double u1, double v1, double u2, double v2) {
        return new double[][]{
                {a[0], a[1], a[2], u2, v1}, {b[0], b[1], b[2], u1, v1}, {c[0], c[1], c[2], u1, v2}, {d[0], d[1], d[2], u2, v2}};
    }

    private static double area(double[][] q) {
        double ax = q[2][0] - q[0][0], ay = q[2][1] - q[0][1], az = q[2][2] - q[0][2];
        double bx = q[3][0] - q[1][0], by = q[3][1] - q[1][1], bz = q[3][2] - q[1][2];
        double cx = ay * bz - az * by, cy = az * bx - ax * bz, cz = ax * by - ay * bx;
        return Math.sqrt(cx * cx + cy * cy + cz * cz) * 0.5;
    }

    /** The part of a face running along z between za and zb (the face spans z0 to z1), its texture cut to match. */
    static double[][] slice(double[][] q, double z0, double z1, double za, double zb) {
        double[][] out = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] c = q[i];
            // its partner: the corner across the face's length (same x and y, the other end)
            double[] o = null;
            for (int j = 0; j < 4; j++) {
                double[] e = q[j];
                if (j != i && Math.abs(e[0] - c[0]) < 1.0E-9 && Math.abs(e[1] - c[1]) < 1.0E-9) { o = e; break; }
            }
            boolean front = Math.abs(c[2] - z0) < 1.0E-9;
            double[] a = front ? c : o, b = front ? o : c;          // a at z0, b at z1
            double z = front ? za : zb;
            double t = (z - z0) / (z1 - z0);
            out[i] = new double[]{c[0], c[1], z, a[3] + (b[3] - a[3]) * t, a[4] + (b[4] - a[4]) * t};
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ building

    private final class Builder {
        final List<Double> stations = new ArrayList<>();
        final Map<Long, Integer> stationByArc = new HashMap<>();
        final List<Integer> vStation = new ArrayList<>(), vBone = new ArrayList<>();
        final List<double[]> vPos = new ArrayList<>(), vUv = new ArrayList<>();
        int count;

        int station(double s) {
            long key = Math.round(s * 1.0E6);
            Integer i = stationByArc.get(key);
            if (i != null) return i;
            stations.add(s);
            stationByArc.put(key, stations.size() - 1);
            return stations.size() - 1;
        }

        /** One quad: a bone's (corners kept in its own frame), or skin's (corners placed in the model, each on its station). */
        void quad(double[][] q, Integer bone, Skin skin, CreatureShape.Part p, CreatureShape shape) {
            for (double[] c : q) {
                if (skin == null) {
                    vStation.add(-1);
                    vBone.add(bone);
                    vPos.add(new double[]{c[0], c[1], c[2]});
                } else {
                    double s = skinArc(skin, p, c);
                    Vector3d m = p.restTurn.transform(new Vector3d(c[0], c[1], c[2])).add(p.restPivot);
                    vStation.add(station(s));
                    vBone.add(-1);
                    vPos.add(new double[]{m.x, m.y, m.z});
                }
                vUv.add(new double[]{c[3], c[4]});
                count++;
            }
        }
    }
}
