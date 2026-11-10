package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads a rune row the way it is said, left to right ("relay rows"):
 * <ol>
 *   <li>Cast breaks split the row into casts. Asleep and empty slots are skipped.</li>
 *   <li>Relays split a cast into parts. A relay with no action before it or none after it does nothing (grey), and
 *   the parts on either side of it join. A bow's own arrow is the action at the very front, after the modifiers that
 *   shape it; a relay right after those hooks the arrow, otherwise an implicit "on hit" does.</li>
 *   <li>Parts past the limit are grey ("too deep").</li>
 *   <li>In a part, each action takes the modifiers to its left in its part (nearest first, up to each kind's cap), the
 *   nearest element, and the copies of up to two Fans. A modifier, element or Fan that changes no action is grey.</li>
 * </ol>
 * The reading is pure: the same row, gear and depth always read the same.
 */
public final class Reader {
    /** In a part's list of items, the bow's own arrow. */
    private static final int ARROW_ITEM = -1;

    private Reader() {}

    public static Reading read(Row row, HostSpec spec, int depth) { return read(row, spec, depth, Limits.DEFAULT); }

    public static Reading read(Row row, HostSpec spec, int depth, Limits lim) {
        int n = row.size();
        int awake = Math.min(n, spec.awakeSlots(depth));
        Note[] notes = new Note[n];
        List<List<Integer>> castSlots = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RuneId r = row.get(i);
            if (r == null) notes[i] = Note.EMPTY;
            else if (i >= awake) notes[i] = Note.asleep(spec.host().wakesAt(i, spec.bonusSlots()));
            else cur.add(i);
            if (row.breakAfter(i)) {
                if (!cur.isEmpty()) castSlots.add(cur);
                cur = new ArrayList<>();
            }
        }
        if (!cur.isEmpty()) castSlots.add(cur);

        List<CastReading> casts = new ArrayList<>();
        for (List<Integer> slots : castSlots) {
            CastReading c = readCast(row, slots, spec, lim, notes, casts.size());
            if (c != null) casts.add(c);
        }
        int recovery = spec.host().baseRecovery;
        for (int i = 0; i < n; i++) if (notes[i].live()) recovery += row.get(i).recovery;
        recovery = Math.max(6, Math.min(80, recovery));
        return new Reading(row, spec, awake, Collections.unmodifiableList(casts), notes, recovery);
    }

    private static CastReading readCast(Row row, List<Integer> slots, HostSpec spec, Limits lim, Note[] notes, int castIndex) {
        boolean bow = spec.host().arrowCarrier;
        // ---- parts and the relays between them. A relay with nothing to ride on, or nothing to carry, does nothing:
        // the cast is read again as if it were not there (so the parts on each side join, and on a bow the runes after
        // a dead hook can still shape the arrow), one relay at a time until every relay left has work.
        List<Integer> live = new ArrayList<>(slots);
        List<List<Integer>> parts = new ArrayList<>();
        List<Integer> relays = new ArrayList<>();       // relays.get(j) releases parts.get(j + 1); -1: the bow's implicit on hit
        while (true) {
            split(row, live, bow, parts, relays);
            int bad = -1;
            boolean after = true;
            for (int j = 0; j < relays.size() && bad < 0; j++) {
                boolean b = hasAction(row, parts.get(j)), a = hasAction(row, parts.get(j + 1));
                if (!b || !a) {
                    bad = j;
                    after = a;
                }
            }
            if (bad < 0) break;
            int rs = relays.get(bad);
            if (rs < 0) break;                          // the bow's implicit relay always has its arrow and an action
            notes[rs] = Note.grey(after ? Note.Reason.NOTHING_BEFORE : Note.Reason.NOTHING_AFTER, row.get(rs).title);
            live.remove(Integer.valueOf(rs));
        }

        // ---- too deep
        if (parts.size() > lim.maxParts()) {
            String most = Integer.toString(lim.maxParts());
            for (int j = lim.maxParts() - 1; j < relays.size(); j++) {
                int rs = relays.get(j);
                if (rs >= 0) notes[rs] = Note.grey(Note.Reason.TOO_DEEP, most);
            }
            for (int p = lim.maxParts(); p < parts.size(); p++)
                for (int it : parts.get(p)) if (it >= 0) notes[it] = Note.grey(Note.Reason.TOO_DEEP, most);
            while (parts.size() > lim.maxParts()) {
                parts.remove(parts.size() - 1);
                relays.remove(relays.size() - 1);
            }
        }

        // ---- each part's actions, and which modifiers count
        boolean[] used = new boolean[row.size()];
        List<Part> out = new ArrayList<>();
        for (int p = 0; p < parts.size(); p++) {
            List<Integer> items = parts.get(p);
            List<Act> acts = new ArrayList<>();
            for (int idx = 0; idx < items.size(); idx++) {
                int it = items.get(idx);
                RuneId a = it == ARROW_ITEM ? RuneId.ARROW : row.get(it);
                if (!a.isAction()) continue;
                int[] counts = new int[Act.MODS.length];
                Element el = Element.NONE;
                int fans = 0;
                for (int k = idx - 1; k >= 0; k--) {
                    int mi = items.get(k);
                    if (mi < 0) continue;
                    RuneId m = row.get(mi);
                    if (!m.changes(a)) continue;
                    switch (m.kind) {
                        case MODIFIER -> {
                            int x = Act.modIndex(m);
                            if (counts[x] < m.cap) {
                                counts[x]++;
                                used[mi] = true;
                            }
                        }
                        case ELEMENT -> {
                            if (el == Element.NONE) {
                                el = m.element;
                                used[mi] = true;
                            }
                        }
                        case FAN -> {
                            if (fans < m.cap) {
                                fans++;
                                used[mi] = true;
                            }
                        }
                        default -> { }
                    }
                }
                acts.add(new Act(a, it, p, counts, el, 1 + 2 * fans));
            }
            RuneId relay = p == 0 ? null : relays.get(p - 1) >= 0 ? row.get(relays.get(p - 1)) : RuneId.ON_HIT;
            int relaySlot = p == 0 ? -1 : relays.get(p - 1);
            out.add(new Part(relay, relaySlot, Collections.unmodifiableList(acts)));
        }

        // ---- notes: live runes, and why the rest are grey
        boolean anyLive = false;
        for (int p = 0; p < parts.size(); p++) {
            List<Integer> items = parts.get(p);
            for (int idx = 0; idx < items.size(); idx++) {
                int it = items.get(idx);
                if (it < 0) continue;
                RuneId r = row.get(it);
                if (r.isAction() || used[it]) {
                    notes[it] = Note.live(castIndex, p);
                    anyLive = true;
                } else if (r.kind != RuneKind.RELAY) {
                    notes[it] = whyGrey(row, items, idx, r);
                }
            }
        }
        for (int j = 0; j < relays.size(); j++) {
            int rs = relays.get(j);
            if (rs >= 0) {
                notes[rs] = Note.live(castIndex, j + 1);
                anyLive = true;
            }
        }
        if (!anyLive) {
            // nothing in it does anything (a bow's bare arrow is a plain shot, not a cast): its runes keep their notes
            for (int s : slots) if (notes[s] != null && notes[s].live()) notes[s] = Note.grey(Note.Reason.NOTHING_RIGHT, row.get(s).title);
            return null;
        }

        // ---- cost and the limit on actions
        Plan.Counts counts = Plan.count(out, lim);
        int delay = spec.host().baseDelay;
        for (int s : slots) if (notes[s].live()) delay += row.get(s).delay;
        delay = Math.max(spec.host().arrowCarrier ? 0 : 2, delay);
        boolean red = counts.cost() > spec.maxEnergy();
        return new CastReading(castIndex, slots.get(0), slots.get(slots.size() - 1), Collections.unmodifiableList(out),
                counts.cost(), counts.planned(), counts.wanted(), delay, red);
    }

    /**
     * Splits a cast's slots into parts at its relays. On a bow, part 0 is the modifiers at the very front and then the
     * arrow; a relay right after them hooks the arrow, otherwise (when more follows) an implicit "on hit" does.
     */
    private static void split(Row row, List<Integer> slots, boolean bow, List<List<Integer>> parts, List<Integer> relays) {
        parts.clear();
        relays.clear();
        int start = 0;
        if (bow) {
            List<Integer> front = new ArrayList<>();
            while (start < slots.size()) {
                RuneId r = row.get(slots.get(start));
                if (r.isAction() || r.kind == RuneKind.RELAY) break;
                front.add(slots.get(start));
                start++;
            }
            front.add(ARROW_ITEM);
            parts.add(front);
            if (start >= slots.size()) return;
            if (row.get(slots.get(start)).kind == RuneKind.RELAY) {
                relays.add(slots.get(start));
                start++;
            } else {
                relays.add(-1);
            }
        }
        List<Integer> seg = new ArrayList<>();
        for (int k = start; k < slots.size(); k++) {
            int s = slots.get(k);
            if (row.get(s).kind == RuneKind.RELAY) {
                parts.add(seg);
                relays.add(s);
                seg = new ArrayList<>();
            } else {
                seg.add(s);
            }
        }
        parts.add(seg);
    }

    private static boolean hasAction(Row row, List<Integer> items) {
        for (int it : items) if (it == ARROW_ITEM || row.get(it).isAction()) return true;
        return false;
    }

    /** Why the modifier, element or Fan at {@code items[idx]} changes nothing. */
    private static Note whyGrey(Row row, List<Integer> items, int idx, RuneId r) {
        List<RuneId> right = new ArrayList<>();
        for (int k = idx + 1; k < items.size(); k++) {
            int it = items.get(k);
            RuneId a = it < 0 ? RuneId.ARROW : row.get(it);
            if (a.isAction()) right.add(a);
        }
        if (right.isEmpty()) return Note.grey(Note.Reason.NOTHING_RIGHT, r.title);
        boolean any = false;
        for (RuneId a : right) any |= r.changes(a);
        if (!any) {
            RuneId first = right.get(0);
            if (first == RuneId.ARROW && right.size() == 1) return Note.grey(Note.Reason.ARROW_CANT, r.title);
            return Note.grey(Note.Reason.CANT_CHANGE, first.title);
        }
        if (r.kind == RuneKind.ELEMENT) {
            // a nearer element wins every action it could change
            for (int k = idx + 1; k < items.size(); k++) {
                int it = items.get(k);
                if (it >= 0 && row.get(it).kind == RuneKind.ELEMENT) return Note.grey(Note.Reason.OVERRULED, row.get(it).title);
            }
        }
        return Note.grey(Note.Reason.AT_CAP, Integer.toString(r.cap));
    }
}
