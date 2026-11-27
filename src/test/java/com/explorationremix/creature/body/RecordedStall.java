package com.explorationremix.creature.body;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Mossback stall as /remix mossback watch logged it in a test world ("[mossback stall] ..."): where its box was, the
 * path it was on, and every block round it with its collision boxes. Replayed in a {@link BlockWorld} of those blocks
 * (water left out: a BlockWorld has none), from where it was to the node after its next one; {@code
 * climb/stalls.txt} holds those ClimbAiTest replays.
 */
record RecordedStall(String name, BlockWorld world, double x, double y, double z, int[] next, int[] goal, boolean water) {
    private static final Pattern HEAD = Pattern.compile("#(\\d+) at (\\S+ \\S+ \\S+) for \\d+ ticks: (\\w+) on ([\\w ]+?),");
    private static final Pattern POS = Pattern.compile("; pos (\\S+) (\\S+) (\\S+),");
    private static final Pattern NODE = Pattern.compile("(>?)(-?\\d+),(-?\\d+),(-?\\d+):(\\w+)");
    private static final Pattern BLOCK = Pattern.compile("(-?\\d+),(-?\\d+),(-?\\d+)=([^ {]+)(\\{[^}]*\\})?");

    static RecordedStall parse(String line) {
        Matcher h = HEAD.matcher(line);
        if (!h.find()) throw new IllegalArgumentException(line);
        Matcher p = POS.matcher(line);
        if (!p.find()) throw new IllegalArgumentException(line);
        String path = line.substring(line.indexOf("; path ") + 7, line.indexOf("; blocks "));
        List<int[]> nodes = new ArrayList<>();
        int nextAt = -1;
        Matcher n = NODE.matcher(path.contains(" end ") ? path.substring(0, path.indexOf(" end ")) : path);
        while (n.find()) {
            if (!n.group(1).isEmpty()) nextAt = nodes.size();
            nodes.add(new int[]{Integer.parseInt(n.group(2)), Integer.parseInt(n.group(3)), Integer.parseInt(n.group(4))});
        }
        if (nextAt < 0) throw new IllegalArgumentException("no next node: " + line);
        BlockWorld w = new BlockWorld();
        String blocks = line.substring(line.indexOf(": ", line.indexOf("; blocks ")) + 2);
        Matcher b = BLOCK.matcher(blocks);
        boolean water = line.contains("in water");
        while (b.find()) {
            int x = Integer.parseInt(b.group(1)), y = Integer.parseInt(b.group(2)), z = Integer.parseInt(b.group(3));
            String name = b.group(4);
            if (name.endsWith("~")) continue;
            if (name.contains("(fluid")) {
                water = true;
                if (b.group(5) == null && (name.startsWith("water") || name.startsWith("lava") || name.startsWith("bubble_column"))) continue;
            }
            if (b.group(5) == null) { w.block(x, y, z); continue; }
            for (String box : b.group(5).substring(1, b.group(5).length() - 1).split(";")) {
                String[] v = box.trim().split(" ");
                w.box(x, y, z, Double.parseDouble(v[0]), Double.parseDouble(v[1]), Double.parseDouble(v[2]),
                        Double.parseDouble(v[3]), Double.parseDouble(v[4]), Double.parseDouble(v[5]));
            }
        }
        int[] goal = nodes.get(Math.min(nextAt + 1, nodes.size() - 1));
        return new RecordedStall("#" + h.group(1) + " at " + h.group(2) + " (" + h.group(3) + " on " + h.group(4) + ")", w,
                Double.parseDouble(p.group(1)), Double.parseDouble(p.group(2)), Double.parseDouble(p.group(3)), nodes.get(nextAt), goal, water);
    }

    static List<RecordedStall> load(String resource) {
        List<RecordedStall> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(RecordedStall.class.getResourceAsStream(resource), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                out.add(parse(line.substring(line.indexOf("[mossback stall]"))));
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    /** Runs it from where it stalled to the goal node, at a speed modifier, for at most {@code limit} ticks. */
    GameDriver replay(double modifier, int limit) {
        double hx = next[0] + 1.0 - x, hz = next[2] + 1.0 - z;
        if (hx * hx + hz * hz < 1.0E-6) { hx = goal[0] + 1.0 - x; hz = goal[2] + 1.0 - z; }
        if (hx * hx + hz * hz < 1.0E-6) hx = 1.0;
        GameDriver d = new GameDriver(world, x, y, z, hx, hz, modifier).pathTo(goal[0] + 1.0, goal[1], goal[2] + 1.0);
        while (!d.arrived() && d.ticks < limit) d.tick();
        return d;
    }
}
