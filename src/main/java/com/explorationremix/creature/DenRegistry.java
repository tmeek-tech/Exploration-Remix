package com.explorationremix.creature;

import com.explorationremix.world.terrain.Hashing;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who lives in which Mossback den, for one level: each den's family (its members, their roles and where each was last
 * seen), how long the den lies empty after its family is gone, whether the family is called home, and how many scraps
 * its meals have left. The den's tile ticks and spawns, but the record lives here, so a death or a check-in counts
 * even while the den's chunk is unloaded: a roamer often walks and dies a hundred blocks or more from home.
 */
public final class DenRegistry extends SavedData {
    private static final String NAME = "explorationremix_mossback_dens";

    public enum Role {
        /** Stays at the den: roams and lies in wait round it, so the den is always a fight. */
        GUARD,
        /** Goes out on long forays, and brings what it catches home. */
        ROAMER
    }

    /** One lizard of a family. */
    public static final class Member {
        public final UUID id;
        public Role role;
        /** Where it was last seen (its own check-ins, or the den finding it): where to look before calling it lost. */
        public BlockPos last;
        /** Ticks the den has looked for it where it was last seen, with that place loaded, and not found it. */
        public int missing;

        Member(UUID id, Role role, BlockPos last) {
            this.id = id;
            this.role = role;
            this.last = last;
        }
    }

    /** One den's record. */
    public static final class Den {
        public final BlockPos pos;
        public final List<Member> members = new ArrayList<>();
        /** No new family moves in before this game time. */
        public long emptyUntil;
        /** Its guard is gone: the roamers head home, and the first to arrive becomes the guard. */
        public boolean comeHome;
        /** Scraps of bone its meals have left round it (never more than {@link MossbackDen#MAX_SCRAPS}). */
        public int scraps;
        /** How many of this family the den has brought out so far (a family is brought out once, not refilled). */
        public int spawned;

        Den(BlockPos pos) { this.pos = pos; }

        @Nullable
        public Member member(UUID id) {
            for (Member m : members) if (m.id.equals(id)) return m;
            return null;
        }

        public boolean hasGuard() {
            for (Member m : members) if (m.role == Role.GUARD) return true;
            return false;
        }

        /**
         * Joins the family: the guard if it has none, else a roamer. One that joins away from the den while the roamers
         * are called home is a roamer too: the first of them to reach the den takes the guard's place.
         */
        Member join(UUID id, BlockPos at, boolean atDen) {
            boolean guard = !hasGuard() && (atDen || !comeHome);
            Member m = new Member(id, guard ? Role.GUARD : Role.ROAMER, at);
            members.add(m);
            if (guard) comeHome = false;
            return m;
        }

        /**
         * A member is gone. A lost guard calls the roamers home. A family killed off (or lost, which is most often
         * killed out of the den's hearing) leaves the den empty for {@link MossbackDen#EMPTY_TICKS}, after which a new
         * family moves in; one only taken out of the level (Peaceful) is brought out again as soon as it may be.
         */
        void gone(UUID id, long now, boolean killed) {
            Member m = member(id);
            if (m == null) return;
            members.remove(m);
            if (members.isEmpty()) {
                if (killed) emptyUntil = Math.max(emptyUntil, now + MossbackDen.EMPTY_TICKS);
                spawned = 0;
                comeHome = false;
            } else if (m.role == Role.GUARD && !hasGuard()) {
                comeHome = true;
            }
        }

        /** A roamer reached the den while its family was called home: it is the guard now. */
        boolean promote(UUID id) {
            Member m = member(id);
            if (m == null || !comeHome) return false;
            m.role = Role.GUARD;
            comeHome = false;
            return true;
        }
    }

    private final Map<Long, Den> dens = new HashMap<>();

    /** Two or three to a family, by the den's position (so a den always keeps the same size of family). */
    public static int familySize(BlockPos pos) {
        return 2 + (int) ((Hashing.mix(0x46616D696C79L, pos.getX(), pos.getY(), pos.getZ()) >>> 21) & 1L);
    }

    public static DenRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(DenRegistry::load, DenRegistry::new, NAME);
    }

    @Nullable
    public Den den(@Nullable BlockPos pos) {
        return pos == null ? null : dens.get(pos.asLong());
    }

    /** The den's record, made empty if it has none yet. */
    public Den open(BlockPos pos) {
        Den d = dens.get(pos.asLong());
        if (d == null) {
            d = new Den(pos.immutable());
            dens.put(pos.asLong(), d);
            setDirty();
        }
        return d;
    }

    /** The den itself is gone (broken or replaced): its family become strays as they next check in. */
    public void remove(BlockPos pos) {
        if (dens.remove(pos.asLong()) != null) setDirty();
    }

    public Collection<Den> all() {
        return Collections.unmodifiableCollection(dens.values());
    }

    // ------------------------------------------------------------------------------------------------ saving

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Den d : dens.values()) {
            CompoundTag t = new CompoundTag();
            t.putLong("Pos", d.pos.asLong());
            t.putLong("EmptyUntil", d.emptyUntil);
            t.putBoolean("ComeHome", d.comeHome);
            t.putInt("Scraps", d.scraps);
            t.putInt("Spawned", d.spawned);
            ListTag ms = new ListTag();
            for (Member m : d.members) {
                CompoundTag mt = new CompoundTag();
                mt.putUUID("Id", m.id);
                mt.putString("Role", m.role.name());
                mt.putLong("Last", m.last.asLong());
                mt.putInt("Missing", m.missing);
                ms.add(mt);
            }
            t.put("Members", ms);
            list.add(t);
        }
        tag.put("Dens", list);
        return tag;
    }

    static DenRegistry load(CompoundTag tag) {
        DenRegistry r = new DenRegistry();
        ListTag list = tag.getList("Dens", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            Den d = new Den(BlockPos.of(t.getLong("Pos")));
            d.emptyUntil = t.getLong("EmptyUntil");
            d.comeHome = t.getBoolean("ComeHome");
            d.scraps = t.getInt("Scraps");
            d.spawned = t.getInt("Spawned");
            ListTag ms = t.getList("Members", Tag.TAG_COMPOUND);
            for (int j = 0; j < ms.size(); j++) {
                CompoundTag mt = ms.getCompound(j);
                if (!mt.hasUUID("Id")) continue;
                Role role;
                try { role = Role.valueOf(mt.getString("Role")); } catch (IllegalArgumentException e) { role = Role.ROAMER; }
                Member m = new Member(mt.getUUID("Id"), role, BlockPos.of(mt.getLong("Last")));
                m.missing = mt.getInt("Missing");
                d.members.add(m);
            }
            r.dens.put(d.pos.asLong(), d);
        }
        return r;
    }
}
