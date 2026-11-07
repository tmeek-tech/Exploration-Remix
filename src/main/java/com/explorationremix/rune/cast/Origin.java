package com.explorationremix.rune.cast;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * Where a part of a cast is let go: a point, the way it faces, and what was just hit there (a creature the payload
 * carries on through, so flying runes let go there do not strike it again at once).
 *
 * @param pos  the point
 * @param dir  the way it faces (unit length)
 * @param hit  the creature just hit there, or null
 */
public record Origin(Vec3 pos, Vec3 dir, @Nullable Entity hit) {
    /** A player's eyes, facing where they look; flying runes start a little in front. */
    public static Origin eyes(ServerPlayer p) {
        return new Origin(p.getEyePosition(), p.getLookAngle().normalize(), null);
    }

    public Origin at(Vec3 p) { return new Origin(p, dir, hit); }

    public Origin facing(Vec3 d) { return new Origin(pos, d.lengthSqr() < 1.0E-8 ? dir : d.normalize(), hit); }

    /** {@code dir} turned {@code degrees} about the up axis (Fan's spread). */
    public static Vec3 turn(Vec3 dir, float degrees) {
        if (degrees == 0.0F) return dir;
        double a = Math.toRadians(degrees), c = Math.cos(a), s = Math.sin(a);
        return new Vec3(dir.x * c - dir.z * s, dir.y, dir.x * s + dir.z * c).normalize();
    }

    /** {@code dir} mirrored off a surface with this normal (a payload let go at a wall reflects). */
    public static Vec3 reflect(Vec3 dir, Vec3 normal) {
        return dir.subtract(normal.scale(2 * dir.dot(normal))).normalize();
    }

    /** The flat part of a direction, for pushes and walls (straight down or up: no push). */
    public static Vec3 flat(Vec3 d) {
        Vec3 f = new Vec3(d.x, 0, d.z);
        return f.lengthSqr() < 1.0E-6 ? Vec3.ZERO : f.normalize();
    }
}
