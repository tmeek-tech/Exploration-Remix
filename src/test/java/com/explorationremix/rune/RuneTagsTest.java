package com.explorationremix.rune;

import com.explorationremix.rune.engine.CastState;
import com.explorationremix.rune.engine.Loadout;
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

/** What a weapon saves: its sockets and its casting state survive being written, saved to bytes and read back. */
class RuneTagsTest {
    /** A weapon's tag holding these runes, as a stack's tag would. */
    static CompoundTag gearWith(Loadout l) {
        CompoundTag gear = new CompoundTag();
        CompoundTag t = RuneTags.writeLoadout(l);
        if (t != null) gear.put(RuneTags.LOADOUT, t);
        return gear;
    }

    /** A weapon's tag from before 0.20.3, holding a row of runes by id ("_" an empty slot). */
    static CompoundTag gearWithRow(String... ids) {
        ListTag list = new ListTag();
        for (String id : ids) list.add(StringTag.valueOf(id.equals("_") ? "" : id));
        CompoundTag row = new CompoundTag();
        row.put("s", list);
        CompoundTag gear = new CompoundTag();
        gear.put(RuneTags.OLD_ROW, row);
        return gear;
    }

    /** Through bytes and back, as a world save or a network packet carries it. */
    static CompoundTag saved(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
    }

    @Test
    void theSocketsComeBackTheSame() throws IOException {
        Loadout l = Loadout.parse("spark fan echo");
        CompoundTag gear = saved(gearWith(l));
        assertEquals(l, RuneTags.readLoadout(gear));
        assertTrue(RuneTags.hasRunes(gear));
        assertEquals(List.of(RuneId.SPARK, RuneId.FAN, RuneId.AFTER), RuneTags.storedRunes(gear));
        Loadout some = Loadout.parse("_ heavy");
        assertEquals(some, RuneTags.readLoadout(saved(gearWith(some))));
    }

    @Test
    void noRunesKeepNoTagAndReadEmpty() {
        assertNull(RuneTags.writeLoadout(Loadout.EMPTY));
        CompoundTag gear = gearWith(Loadout.EMPTY);
        assertFalse(gear.contains(RuneTags.LOADOUT));
        assertEquals(Loadout.EMPTY, RuneTags.readLoadout(gear));
        assertEquals(Loadout.EMPTY, RuneTags.readLoadout(null));
        assertFalse(RuneTags.hasRunes(null));
        assertTrue(RuneTags.storedRunes(null).isEmpty());
    }

    @Test
    void aRuneTheGameDoesNotKnowOrOfTheWrongKindReadsAsAnEmptySocket() {
        CompoundTag t = new CompoundTag();
        t.putString("a", "moonbeam");
        t.putString("m", "spark");
        t.putString("p", "ember");
        CompoundTag gear = new CompoundTag();
        gear.put(RuneTags.LOADOUT, t);
        assertEquals(new Loadout(null, null, RuneId.EMBER), RuneTags.readLoadout(gear));
        assertEquals(List.of(RuneId.EMBER), RuneTags.storedRunes(gear));
    }

    @Test
    void anOldRowIsSetInTheSocketsAndTheRestGoBack() {
        CompoundTag gear = gearWithRow("fan", "spark", "on_hit", "_", "orb", "swift", "moonbeam", "frost");
        assertTrue(RuneTags.hasOldRow(gear));
        assertEquals(new Loadout(RuneId.SPARK, RuneId.FAN, RuneId.ON_HIT), RuneTags.readLoadout(gear), "read as it will be set");
        RuneTags.Converted c = RuneTags.convertGear(gear);
        assertNotNull(c);
        assertEquals(new Loadout(RuneId.SPARK, RuneId.FAN, RuneId.ON_HIT), c.kept());
        assertEquals(List.of(RuneId.ORB, RuneId.SWIFT, RuneId.FROST), c.extras(), "the unknown rune is lost, as it always was");
        assertEquals(6, RuneTags.storedRunes(gear).size(), "a weapon broken before it is converted gives back every rune");
        assertNull(RuneTags.convertGear(gearWith(Loadout.parse("spark"))), "nothing to convert");
    }

    @Test
    void socketsAlreadySetWinOverAnOldRow() {
        CompoundTag gear = gearWith(Loadout.parse("orb heavy"));
        gear.put(RuneTags.OLD_ROW, gearWithRow("spark", "swift").getCompound(RuneTags.OLD_ROW));
        RuneTags.Converted c = RuneTags.convertGear(gear);
        assertNotNull(c);
        assertEquals(Loadout.parse("orb heavy"), c.kept());
        assertEquals(List.of(RuneId.SPARK, RuneId.SWIFT), c.extras());
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
    void theSocketsAndTheStateAreSeparateTags() {
        CompoundTag gear = gearWith(Loadout.parse("spark"));
        gear.put(RuneTags.CAST, RuneTags.writeState(new CastState(3.0F, 1L, -1L, 0, 0L, 0L), 8));
        gear.put(RuneTags.LOADOUT, RuneTags.writeLoadout(Loadout.parse("orb")));
        assertEquals(Loadout.parse("orb"), RuneTags.readLoadout(gear));
        assertEquals(8, RuneTags.gearId(gear), "changing the runes keeps the weapon's id");
    }
}
