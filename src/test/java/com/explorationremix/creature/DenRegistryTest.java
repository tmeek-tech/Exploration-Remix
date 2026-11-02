package com.explorationremix.creature;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A den's family record: the first is the guard, a dead guard calls the roamers home, an empty den waits, and it all saves. */
class DenRegistryTest {
    private static final BlockPos DEN = new BlockPos(310, -64, -1270);
    private static final BlockPos FAR = new BlockPos(450, -50, -1100);

    @Test
    void theFirstIsTheGuardTheRestRoam() {
        DenRegistry.Den d = new DenRegistry().open(DEN);
        DenRegistry.Member a = d.join(UUID.randomUUID(), DEN, true);
        DenRegistry.Member b = d.join(UUID.randomUUID(), DEN, true);
        DenRegistry.Member c = d.join(UUID.randomUUID(), DEN, true);
        assertEquals(DenRegistry.Role.GUARD, a.role);
        assertEquals(DenRegistry.Role.ROAMER, b.role);
        assertEquals(DenRegistry.Role.ROAMER, c.role);
        assertTrue(d.hasGuard());
        assertFalse(d.comeHome);
    }

    @Test
    void aDeadGuardCallsTheRoamersHomeAndTheFirstBackTakesOver() {
        DenRegistry.Den d = new DenRegistry().open(DEN);
        UUID guard = UUID.randomUUID(), r1 = UUID.randomUUID(), r2 = UUID.randomUUID();
        d.join(guard, DEN, true);
        d.join(r1, FAR, true);
        d.join(r2, FAR, true);
        d.gone(guard, 1000, DenRegistry.Loss.KILLED);
        assertTrue(d.comeHome, "no guard: the roamers are called home");
        assertFalse(d.hasGuard());
        assertEquals(0, d.emptyUntil, "the family lives on: the den is not empty");
        // one taken in far away while they are called home is a roamer, not the guard
        UUID stray = UUID.randomUUID();
        assertEquals(DenRegistry.Role.ROAMER, d.join(stray, FAR, false).role);
        assertTrue(d.promote(r2), "the first back is the guard");
        assertEquals(DenRegistry.Role.GUARD, d.member(r2).role);
        assertFalse(d.comeHome);
        assertFalse(d.promote(r1), "the second back stays a roamer");
        assertEquals(DenRegistry.Role.ROAMER, d.member(r1).role);
        // a roamer's death calls nobody home
        d.gone(r1, 2000, DenRegistry.Loss.KILLED);
        assertFalse(d.comeHome);
    }

    @Test
    void anEmptyDenWaitsOnlyAfterADeath() {
        DenRegistry.Den d = new DenRegistry().open(DEN);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        d.join(a, DEN, true);
        d.join(b, DEN, true);
        d.spawned = 2;
        d.gone(a, 5000, DenRegistry.Loss.KILLED);
        d.gone(b, 6000, DenRegistry.Loss.KILLED);
        assertTrue(d.members.isEmpty());
        assertEquals(6000 + MossbackDen.EMPTY_TICKS, d.emptyUntil);
        assertEquals(0, d.spawned, "a new family is brought out whole");
        assertFalse(d.comeHome);
        // a family taken out of the level (Peaceful) leaves no wait behind it
        DenRegistry.Den e = new DenRegistry().open(DEN);
        UUID c = UUID.randomUUID();
        e.join(c, DEN, true);
        e.gone(c, 7000, DenRegistry.Loss.REMOVED);
        assertEquals(0, e.emptyUntil);
    }

    @Test
    void aLossIsReplacedADayLaterAndNeverSooner() {
        DenRegistry.Den d = new DenRegistry().open(DEN);
        UUID g = UUID.randomUUID(), r = UUID.randomUUID(), s = UUID.randomUUID();
        d.join(g, DEN, true);
        d.join(r, FAR, true);
        d.join(s, FAR, true);
        d.spawned = 3;
        // a killed roamer while the rest live: the den brings out another a day later
        d.gone(s, 1000, DenRegistry.Loss.KILLED);
        assertEquals(2, d.spawned);
        assertEquals(1000 + MossbackDen.REFILL_TICKS, d.refillAt);
        // the guard killed: the last roamer loses its way home and strays; the den is empty, but the day owed for the
        // deaths still holds the new family back (and a stray holds it back a day of its own)
        d.gone(g, 2000, DenRegistry.Loss.KILLED);
        assertEquals(2000 + MossbackDen.REFILL_TICKS, d.refillAt);
        d.gone(r, 3000, DenRegistry.Loss.STRAYED);
        assertTrue(d.members.isEmpty());
        assertEquals(0, d.spawned);
        assertEquals(2000 + MossbackDen.REFILL_TICKS, d.refillAt, "the day owed is still owed");
        assertEquals(3000 + MossbackDen.REFILL_TICKS, d.emptyUntil, "a stray holds the den back a day, not three");
        // one only taken out of the level is replaced at once
        DenRegistry.Den e = new DenRegistry().open(DEN);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        e.join(a, DEN, true);
        e.join(b, FAR, true);
        e.spawned = 2;
        e.gone(b, 4000, DenRegistry.Loss.REMOVED);
        assertEquals(1, e.spawned);
        assertEquals(0, e.refillAt);
    }

    @Test
    void savedAndReadBack() {
        DenRegistry r = new DenRegistry();
        DenRegistry.Den d = r.open(DEN);
        UUID g = UUID.randomUUID(), m = UUID.randomUUID();
        d.join(g, DEN, true);
        d.join(m, FAR, false).missing = 120;
        d.scraps = 7;
        d.spawned = 2;
        d.emptyUntil = 99;
        d.refillAt = 55;
        d.comeHome = true;
        r.open(FAR);
        DenRegistry back = DenRegistry.load(r.save(new CompoundTag()));
        assertEquals(2, back.all().size());
        DenRegistry.Den e = back.den(DEN);
        assertNotNull(e);
        assertEquals(2, e.members.size());
        assertEquals(DenRegistry.Role.GUARD, e.member(g).role);
        assertEquals(DenRegistry.Role.ROAMER, e.member(m).role);
        assertEquals(FAR, e.member(m).last);
        assertEquals(120, e.member(m).missing);
        assertEquals(7, e.scraps);
        assertEquals(55, e.refillAt);
        assertEquals(2, e.spawned);
        assertEquals(99, e.emptyUntil);
        assertTrue(e.comeHome);
        back.remove(DEN);
        assertNull(back.den(DEN));
    }

    @Test
    void familySizeIsTwoOrThreeAndFixed() {
        int twos = 0, threes = 0;
        for (int i = 0; i < 200; i++) {
            BlockPos p = new BlockPos(i * 37 - 4000, -64 + (i % 5), i * -53 + 900);
            int n = DenRegistry.familySize(p);
            assertEquals(n, DenRegistry.familySize(new BlockPos(p.getX(), p.getY(), p.getZ())));
            if (n == 2) twos++;
            else if (n == 3) threes++;
            else fail("a family of " + n);
        }
        assertTrue(twos > 40 && threes > 40, twos + " twos, " + threes + " threes");
    }
}
