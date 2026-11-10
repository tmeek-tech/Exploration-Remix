package com.explorationremix.rune.effect;

import com.explorationremix.rune.engine.Element;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/**
 * One rune or combat effect for each nearby client to draw: what it is, where, which way, its colour, element and size,
 * one more number some kinds need ({@code a}), and the creature it belongs to, if any ({@code entity}, -1 if none). The
 * server sends only this; each client makes the flashes, sparks, rings and shake itself.
 */
public record Fx(Kind kind, double x, double y, double z, float dx, float dy, float dz, int rgb, Element element, float size, float a, int entity) {
    public enum Kind {
        /** A cast leaves the hand: {@code d} the way it goes, {@code entity} the caster, {@code a} 1 for the off hand. */
        CAST,
        /** A bolt stops: {@code d} the way out of what it hit, {@code a} 1 when it hit a creature. */
        IMPACT,
        /** A Burst: {@code size} its radius. */
        BURST,
        /** A Cleave: {@code d} its way (flat), {@code size} its reach, {@code a} half its arc in radians. */
        CLEAVE,
        /** A Field opens ({@code a} 0) or pulses ({@code a} 1): {@code size} its radius. */
        FIELD,
        /** A Barrier stands: {@code d} along the wall, {@code size} its width, {@code a} its height. */
        BARRIER,
        /** A Dash: {@code d} the way; {@code entity} who dashed. */
        DASH,
        /** A beam from here to here plus {@code d} (Reel's line, Storm's leap): {@code size} its dots' size, {@code a} their spacing (0: close). */
        BEAM,
        /** A bolt pierces or passes a creature: {@code d} the way it flies. */
        PIERCE,
        /** A bolt glances off a wall: {@code d} the way it flies off. */
        BOUNCE,
        /** An element reaction on a creature: {@code a} the reaction's number ({@code Reactions.Reaction}). */
        REACTION,
        /** A melee hit lands: {@code d} from the attacker to the creature, {@code size} how hard (1 a full hit). */
        HIT,
        /** A momentum hit: {@code d} the way of the hit, {@code size} how much momentum (0 to 1). */
        MOMENTUM,
        /** A creature's poise breaks: it reels for {@code a} ticks; {@code entity} the creature. */
        STAGGER,
        /** A creature slams into a wall: {@code d} out of the wall, {@code size} how hard (0 to 1). */
        SLAM,
        /** Motes land in the player's gear: {@code size} how full it is now (0 to 1). */
        MOTE_IN
    }

    public Vec3 pos() { return new Vec3(x, y, z); }

    public Vec3 dir() { return new Vec3(dx, dy, dz); }

    public static Fx of(Kind kind, Vec3 at, Vec3 dir, int rgb, Element element, float size, float a, int entity) {
        return new Fx(kind, at.x, at.y, at.z, (float) dir.x, (float) dir.y, (float) dir.z, rgb, element, size, a, entity);
    }

    public void write(FriendlyByteBuf b) {
        b.writeByte(kind.ordinal());
        b.writeDouble(x);
        b.writeDouble(y);
        b.writeDouble(z);
        b.writeFloat(dx);
        b.writeFloat(dy);
        b.writeFloat(dz);
        b.writeInt(rgb);
        b.writeByte(element.ordinal());
        b.writeFloat(size);
        b.writeFloat(a);
        b.writeVarInt(entity + 1);
    }

    /** Reads one; a kind or element this game doesn't know comes back null (and is not drawn). */
    public static Fx read(FriendlyByteBuf b) {
        int k = b.readByte();
        double x = b.readDouble(), y = b.readDouble(), z = b.readDouble();
        float dx = b.readFloat(), dy = b.readFloat(), dz = b.readFloat();
        int rgb = b.readInt();
        int e = b.readByte();
        float size = b.readFloat(), a = b.readFloat();
        int entity = b.readVarInt() - 1;
        if (k < 0 || k >= Kind.values().length || e < 0 || e >= Element.values().length) return null;
        return new Fx(Kind.values()[k], x, y, z, dx, dy, dz, rgb, Element.values()[e], size, a, entity);
    }

    /** Whether every number is one a client can draw (nothing infinite, nothing absurdly large). */
    public boolean sane() {
        return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) && Float.isFinite(dx) && Float.isFinite(dy) && Float.isFinite(dz)
                && Float.isFinite(size) && Float.isFinite(a) && Math.abs(size) <= 64.0F && Math.abs(a) <= 4000.0F
                && Math.abs(dx) <= 128.0F && Math.abs(dy) <= 128.0F && Math.abs(dz) <= 128.0F;
    }
}
