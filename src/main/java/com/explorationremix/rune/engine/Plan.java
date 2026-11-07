package com.explorationremix.rune.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A cast spelt out copy by copy: part 0's copies are the roots; every copy in part {@code k} carries its own copy of
 * part {@code k + 1}. Copies are counted part by part, carrier by carrier, middle copy first; once a cast holds
 * {@link Limits#maxActions()} actions, the rest are cut whole (with all they would carry) and not paid for.
 */
public final class Plan {
    /** One copy of an action and what it carries. */
    public static final class Node {
        public final Act act;
        /** Which copy (0 the middle one). */
        public final int copy;
        /** Its turn off the aim, in degrees (Fan's spread; 0 for the middle copy). */
        public final float yaw;
        /** The relay that releases {@link #children} (null when nothing rides on it). */
        public RuneId relay;
        public final List<Node> children = new ArrayList<>();

        Node(Act act, int copy, float yaw) {
            this.act = act;
            this.copy = copy;
            this.yaw = yaw;
        }

        /** Actions in this copy and everything it carries. */
        public int size() {
            int n = 1;
            for (Node c : children) n += c.size();
            return n;
        }
    }

    public record Counts(int planned, int wanted, int cost) {}

    public final List<Node> roots;
    public final int planned, wanted, cost;

    private Plan(List<Node> roots, int planned, int wanted, int cost) {
        this.roots = Collections.unmodifiableList(roots);
        this.planned = planned;
        this.wanted = wanted;
        this.cost = cost;
    }

    /** Spells out a cast. */
    public static Plan expand(CastReading cast, Limits lim) { return build(cast.parts(), lim); }

    /** Counts a cast's actions and cost without keeping the copies. */
    static Counts count(List<Part> parts, Limits lim) {
        Plan p = build(parts, lim);
        return new Counts(p.planned, p.wanted, p.cost);
    }

    private static Plan build(List<Part> parts, Limits lim) {
        int budget = lim.maxActions();
        int planned = 0, cost = 0;
        List<Node> roots = new ArrayList<>();
        List<Node> level = new ArrayList<>();
        if (!parts.isEmpty()) {
            for (Act a : parts.get(0).acts()) {
                for (int c = 0; c < a.copies; c++) {
                    if (planned >= budget) break;
                    Node node = new Node(a, c, spread(a, c));
                    roots.add(node);
                    level.add(node);
                    planned++;
                    cost += a.unitCost;
                }
            }
        }
        for (int k = 1; k < parts.size(); k++) {
            Part part = parts.get(k);
            List<Node> next = new ArrayList<>();
            for (Node carrier : level) {
                carrier.relay = part.relay();
                for (Act a : part.acts()) {
                    for (int c = 0; c < a.copies; c++) {
                        if (planned >= budget) break;
                        Node node = new Node(a, c, spread(a, c));
                        carrier.children.add(node);
                        next.add(node);
                        planned++;
                        cost += a.unitCost;
                    }
                }
            }
            level = next;
        }
        // what the cast would make with no limit
        long wanted = 0, carriers = 1;
        for (Part part : parts) {
            carriers *= Math.max(0, part.instances());
            wanted += carriers;
            if (wanted > 1_000_000) { wanted = 1_000_000; break; }
        }
        return new Plan(roots, planned, (int) wanted, cost);
    }

    /** The middle copy first, then pairs further out: 0, -s, +s, -2s, +2s. */
    static float spread(Act a, int copy) {
        if (a.copies <= 1 || copy == 0) return 0.0F;
        float step = a.rune == RuneId.CLEAVE ? 35.0F : a.rune == RuneId.ARROW ? 10.0F : 12.0F;
        int ring = (copy + 1) / 2;
        return (copy % 2 == 1 ? -1 : 1) * ring * step;
    }
}
