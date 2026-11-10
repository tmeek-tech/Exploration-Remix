package com.explorationremix.rune;

import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Row;
import com.explorationremix.rune.engine.RuneId;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What gear saves: its row and its casting state survive being written, saved to bytes and read back. */
class RuneTagsTest {
    /** A gear tag holding this row, as a stack's tag would. */
    static CompoundTag gearWith(Row row) {
        CompoundTag gear = new CompoundTag();
        CompoundTag r = RuneTags.writeRow(row);
        if (r != null) gear.put(RuneTags.ROW, r);
        return gear;
    }

    /** Through bytes and back, as a world save or a network packet carries it. */
    static CompoundTag saved(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    void aRowWithEmptySlotsAndBreaksComesBackTheSame() throws IOException {
        Row row = Row.parse("fan spark on_hit _ | orb");
        CompoundTag gear = saved(gearWith(row));
        assertEquals(row, RuneTags.readRow(gear, row.size()));
        assertTrue(RuneTags.hasRunes(gear));
        assertEquals(List.of(RuneId.FAN, RuneId.SPARK, RuneId.ON_HIT, RuneId.ORB), RuneTags.storedRunes(gear));
    }

    @Test
    void anEmptyRowKeepsNoTagAndReadsEmpty() {
        assertNull(RuneTags.writeRow(Row.empty(5)));
        CompoundTag gear = gearWith(Row.empty(5));
        assertFalse(gear.contains(RuneTags.ROW));
        assertEquals(Row.empty(5), RuneTags.readRow(gear, 5));
        assertEquals(Row.empty(3), RuneTags.readRow(null, 3));
        assertFalse(RuneTags.hasRunes(null));
    }

    @Test
    void anEmptyRowWithABreakIsStillKept() {
        Row row = Row.empty(4).withBreak(1, true);
        assertNotNull(RuneTags.writeRow(row));
        assertEquals(row, RuneTags.readRow(gearWith(row), 4));
        assertFalse(RuneTags.hasRunes(gearWith(row)));
    }

    @Test
    void gearWithFewerSlotsReadsTheFrontAndTheRestIsOverflow() {
        CompoundTag gear = gearWith(Row.parse("swift spark heavy orb burst"));
        assertEquals(Row.parse("swift spark heavy"), RuneTags.readRow(gear, 3));
        assertEquals(List.of(RuneId.ORB, RuneId.BURST), RuneTags.readOverflow(gear, 3));
        assertEquals(List.of(), RuneTags.readOverflow(gear, 5));
        assertEquals(5, RuneTags.storedRunes(gear).size(), "a broken tool gives back every rune it holds");
    }

    @Test
    void gearWithMoreSlotsReadsTheRowPaddedWithEmptySlots() {
        CompoundTag gear = gearWith(Row.parse("spark | orb"));
        Row read = RuneTags.readRow(gear, 5);
        assertEquals(5, read.size());
        assertEquals(RuneId.SPARK, read.get(0));
        assertEquals(RuneId.ORB, read.get(1));
        assertTrue(read.breakAfter(0));
        assertNull(read.get(4));
    }

    @Test
    void aRuneTheGameDoesNotKnowReadsAsAnEmptySlot() {
        CompoundTag row = new CompoundTag();
        ListTag list = new ListTag();
        list.add(StringTag.valueOf("spark"));
        list.add(StringTag.valueOf("moonbeam"));
        list.add(StringTag.valueOf("orb"));
        row.put("s", list);
        CompoundTag gear = new CompoundTag();
        gear.put(RuneTags.ROW, row);
        assertEquals(Row.parse("spark _ orb"), RuneTags.readRow(gear, 3));
        assertEquals(List.of(RuneId.SPARK, RuneId.ORB), RuneTags.storedRunes(gear));
        CompoundTag onlyUnknown = new CompoundTag();
        ListTag l2 = new ListTag();
        l2.add(StringTag.valueOf("moonbeam"));
        CompoundTag r2 = new CompoundTag();
        r2.put("s", l2);
        onlyUnknown.put(RuneTags.ROW, r2);
        assertFalse(RuneTags.hasRunes(onlyUnknown));
    }

    @Test
    void theCastingStateComesBackTheSame() throws IOException {
        CastState s = new CastState(41.25F, 123456789L, 123456000L, 2, 123456799L, 123456700L);
        CompoundTag gear = new CompoundTag();
        gear.put(RuneTags.CAST, RuneTags.writeState(s, 77));
        gear = saved(gear);
        assertEquals(s, RuneTags.readState(gear));
        assertEquals(77, RuneTags.gearId(gear));
    }

    @Test
    void aBowThatIsNotRegeneratingAndNeverCastComesBackTheSame() throws IOException {
        CastState s = new CastState(60.0F, 10L, -1L, 0, 0L, Long.MIN_VALUE / 4);
        CompoundTag gear = new CompoundTag();
        gear.put(RuneTags.CAST, RuneTags.writeState(s, 0));
        assertEquals(s, RuneTags.readState(saved(gear)));
        assertEquals(0, RuneTags.gearId(gear));
    }

    @Test
    void anIdAloneIsNoStateSoTheGearStaysFresh() {
        // the gear is given an id the moment it is taken in hand, before anything happens to it
        CompoundTag gear = new CompoundTag();
        gear.put(RuneTags.CAST, RuneTags.withGearId(gear, 99));
        assertEquals(99, RuneTags.gearId(gear));
        assertNull(RuneTags.readState(gear), "read as fresh, keeping its starting energy");
        assertNull(RuneTags.readState(null));
        assertNull(RuneTags.readState(new CompoundTag()));
    }

    @Test
    void givingAnIdKeepsTheStateAndWritingAStateKeepsTheId() {
        CastState s = new CastState(12.5F, 50L, -1L, 1, 60L, 40L);
        CompoundTag gear = new CompoundTag();
        gear.put(RuneTags.CAST, RuneTags.writeState(s, 0));
        gear.put(RuneTags.CAST, RuneTags.withGearId(gear, 5));
        assertEquals(s, RuneTags.readState(gear));
        assertEquals(5, RuneTags.gearId(gear));
        gear.put(RuneTags.CAST, RuneTags.writeState(new CastState(1.0F, 70L, -1L, 0, 80L, 70L), RuneTags.gearId(gear)));
        assertEquals(5, RuneTags.gearId(gear));
    }

    @Test
    void theRowAndTheStateAreSeparateTags() {
        CompoundTag gear = gearWith(Row.parse("spark"));
        gear.put(RuneTags.CAST, RuneTags.writeState(new CastState(3.0F, 1L, -1L, 0, 0L, 0L), 8));
        CompoundTag r = RuneTags.writeRow(Row.parse("orb"));
        gear.put(RuneTags.ROW, r);
        assertEquals(Row.parse("orb"), RuneTags.readRow(gear, 1));
        assertEquals(8, RuneTags.gearId(gear), "editing the row keeps the gear's id");
    }
}
