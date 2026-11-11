package com.explorationremix.client.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.entity.PartEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What a swing finds as it lands, on the swinger's own game (what they see is what they hit): creatures within reach and
 * within the swing's cone round the line of sight, with nothing solid between (grass, ferns and vines don't stop a
 * blade), the one nearest the line of sight first. A big creature counts wherever any of it is in reach (the Skein's
 * body, a Mossback's flank). Something else the crosshair rests on (a boat, an item frame) is struck as vanilla would.
 * Your own animals, team-mates, other players and what you ride are struck only when the crosshair is on them.
 */
final class SwingAim {
    private SwingAim() {}

    record Target(Entity entity, double score) {}

    /** Everything a swing reaching {@code reach} blocks in a cone of {@code cone} degrees strikes now, best first. */
    static List<Target> find(LocalPlayer p, double reach, double cone) {
        Vec3 eye = p.getEyePosition();
        Vec3 look = p.getViewVector(1.0F);
        double cosCone = Math.cos(Math.toRadians(cone));
        AABB area = p.getBoundingBox().inflate(reach + 2.0);
        List<Target> out = new ArrayList<>();
        Entity aimed = Minecraft.getInstance().crosshairPickEntity;
        Entity aimedRoot = aimed instanceof PartEntity<?> part ? part.getParent() : aimed;
        for (Entity e : p.level().getEntities(p, area, SwingAim::strikable)) {
            Entity root = e instanceof PartEntity<?> part ? part.getParent() : e;
            if (root == p) continue;
            // a friend (your own wolf, a team-mate), another player or what you ride is struck only when you aim at it
            if (root != aimedRoot && (root.isAlliedTo(p) || p.isAlliedTo(root) || root instanceof Player || p.isPassengerOfSameVehicle(root))) continue;
            AABB box = e.getBoundingBox().inflate(Math.max(0.1, e.getPickRadius()));
            Vec3 at = nearest(box, eye, look, reach);
            double dist = at.distanceTo(eye);
            if (dist > reach) continue;
            Vec3 to = at.subtract(eye);
            double cos = to.lengthSqr() < 1.0E-6 ? 1.0 : to.normalize().dot(look);
            if (cos < cosCone) continue;
            if (!clear(p, eye, at) && !clear(p, eye, box.getCenter())) continue;
            // nearest the line of sight first, then the nearer
            double off = Math.toDegrees(Math.acos(Math.min(1.0, cos)));
            out.add(new Target(e, off + dist * 3.0));
        }
        // what the crosshair rests on, if it isn't a creature (those were all looked at above)
        HitResult hr = Minecraft.getInstance().hitResult;
        if (hr instanceof EntityHitResult ehr && !(ehr.getEntity() instanceof LivingEntity) && !(ehr.getEntity() instanceof PartEntity<?>)
                && ehr.getEntity().isAttackable() && !(ehr.getEntity() instanceof ItemEntity) && !(ehr.getEntity() instanceof ExperienceOrb)
                && !(ehr.getEntity() instanceof AbstractArrow) && ehr.getLocation().distanceTo(eye) <= reach) {
            out.add(new Target(ehr.getEntity(), 1.0E6));
        }
        out.sort(Comparator.comparingDouble(Target::score));
        // one entry per creature: the part of it nearest the line of sight
        List<Target> one = new ArrayList<>();
        List<Entity> roots = new ArrayList<>();
        for (Target t : out) {
            Entity root = t.entity instanceof PartEntity<?> part ? part.getParent() : t.entity;
            if (roots.contains(root)) continue;
            roots.add(root);
            one.add(t);
        }
        return one;
    }

    /** The best one, or null. */
    @Nullable
    static Entity best(LocalPlayer p, double reach, double cone) {
        List<Target> t = find(p, reach, cone);
        return t.isEmpty() ? null : t.get(0).entity;
    }

    private static boolean strikable(Entity e) {
        if (!e.isAlive() || e.isSpectator() || !e.isPickable()) return false;
        if (e instanceof LivingEntity) return true;
        return e instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity le && le.isAlive();
    }

    /**
     * The point of {@code box} a blade along the line of sight would meet: where the line enters it if it does within
     * reach, else the box's point nearest the line (at the depth the line passes it).
     */
    private static Vec3 nearest(AABB box, Vec3 eye, Vec3 look, double reach) {
        if (box.contains(eye)) return eye;
        var clip = box.clip(eye, eye.add(look.scale(reach)));
        if (clip.isPresent()) return clip.get();
        // the point on the line of sight closest to the box's middle, then the box's point closest to that
        Vec3 c = box.getCenter();
        double along = Math.max(0.0, Math.min(reach, c.subtract(eye).dot(look)));
        Vec3 onLine = eye.add(look.scale(along));
        return new Vec3(clamp(onLine.x, box.minX, box.maxX), clamp(onLine.y, box.minY, box.maxY), clamp(onLine.z, box.minZ, box.maxZ));
    }

    private static double clamp(double v, double lo, double hi) { return v < lo ? lo : Math.min(v, hi); }

    /** Nothing solid between: rock, a door, a pane; not plants, which have no collision. */
    private static boolean clear(LocalPlayer p, Vec3 from, Vec3 to) {
        return p.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p)).getType() == HitResult.Type.MISS;
    }
}
