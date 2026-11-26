package com.explorationremix.world.feature;

import com.explorationremix.rune.stone.RuneStoneHost;
import com.explorationremix.rune.stone.RuneStoneKind;
import com.explorationremix.rune.stone.RuneStones;
import com.explorationremix.world.terrain.Hashing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.StructureType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rune stones in the game's own structures (0.23.1; Taran: "the Reaches runes in ponds and ancient cities, and the
 * Upper Caves runes in strongholds"; card picks "About six" in a stronghold, "About four" in a city, and the Upper Caves'
 * in strongholds only): a stronghold's walls hide five to seven Upper Caves stones in its stone bricks (mossy and
 * cracked too), an ancient city's three to five Blue Reaches stones in its deepslate bricks, tiles and polished stone,
 * never its plain rock. Set as each chunk of the structure is built (StructureStartMixin, after its pieces), so the
 * structure is never touched again afterwards and the vanilla structure stays as it is everywhere else.
 *
 * <p>Which pieces take one is the start's alone (its position and the world's seed), so every chunk agrees: of its
 * pieces big enough to hold a room or a corridor (and for a city, its buildings, walls and centre, not its paths and
 * props), the first few in an order of its own, one stone each, in the chunk that holds most of the piece. There the
 * stone takes the place of a wall block with open air beside it inside the piece, at eye level over the floor where it
 * can (else at the feet, else just over the head). A piece whose share of that chunk offers no such block gives none.
 */
public final class RuneStoneStructures {
    private RuneStoneStructures() {}

    /** A piece with fewer blocks in its box than this is passed over. */
    static final int MIN_VOLUME = 150;
    private static final long SALT = 0x53545231L;
    private static final int[][] NB4 = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /** For /remix runestones: stones set in strongholds and in cities. */
    public static final AtomicLong STRONGHOLDS = new AtomicLong(), CITIES = new AtomicLong();

    /** The game's structures that hide stones: about how many each holds (one fewer to one more, by the start), of what kind. */
    public enum Site {
        STRONGHOLD(6, RuneStoneKind.UPPER_CAVES), CITY(4, RuneStoneKind.BLUE_REACHES);

        public final int about;
        public final RuneStoneKind kind;

        Site(int about, RuneStoneKind kind) {
            this.about = about;
            this.kind = kind;
        }

        /** The site structure s is, or null for one that hides none. */
        @Nullable
        public static Site of(RegistryAccess access, Structure s) {
            if (s.type() == StructureType.STRONGHOLD) return STRONGHOLD;
            if (s.type() != StructureType.JIGSAW) return null;
            return access.registryOrThrow(Registries.STRUCTURE).getResourceKey(s).filter(BuiltinStructures.ANCIENT_CITY::equals).isPresent() ? CITY : null;
        }
    }

    /** After start's pieces are built in the chunk at {@code chunkPos} ({@code box}: its blocks). */
    public static void placed(StructureStart start, WorldGenLevel level, BoundingBox box, ChunkPos chunkPos) {
        Site site = Site.of(level.registryAccess(), start.getStructure());
        if (site == null) return;
        List<StructurePiece> pieces = start.getPieces();
        long h = hash(level.getSeed(), start);
        for (int i : chosen(start, level.getSeed(), site)) {
            BoundingBox b = pieces.get(i).getBoundingBox();
            if (!homeOf(b).equals(chunkPos)) continue;
            BlockPos at = spot(level, site, b, box, Hashing.mix(h, 3, i, 0));
            if (at == null) continue;
            RuneStoneHost host = RuneStoneHost.of(level.getBlockState(at));
            if (host == null) continue;
            level.setBlock(at, site.kind.state(host), 2);
            (site == Site.CITY ? CITIES : STRONGHOLDS).incrementAndGet();
            RuneStones.placed(site.kind);
        }
    }

    private static long hash(long seed, StructureStart start) {
        return Hashing.mix(seed, SALT, start.getChunkPos().x, start.getChunkPos().z);
    }

    /**
     * The pieces of start that take a stone, as indices into its pieces: of those that may hold one ({@link #holds}),
     * the first few in an order of the start's own, as many as it hides.
     */
    public static int[] chosen(StructureStart start, long seed, Site site) {
        List<StructurePiece> pieces = start.getPieces();
        long h = hash(seed, start);
        int want = site.about - 1 + (int) (Hashing.unit(Hashing.mix(h, 1, 0, 0)) * 3);
        List<long[]> order = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++)
            if (holds(pieces.get(i), site == Site.CITY)) order.add(new long[]{Hashing.mix(h, 2, i, 0), i});
        order.sort(Comparator.comparingLong(o -> o[0]));
        int[] out = new int[Math.min(want, order.size())];
        for (int n = 0; n < out.length; n++) out[n] = (int) order.get(n)[1];
        return out;
    }

    /** Whether a piece may hold a stone: big enough, and for a city one of its buildings, walls or its centre. */
    static boolean holds(StructurePiece piece, boolean city) {
        BoundingBox b = piece.getBoundingBox();
        if ((long) b.getXSpan() * b.getYSpan() * b.getZSpan() < MIN_VOLUME) return false;
        if (!city) return true;
        if (!(piece instanceof PoolElementStructurePiece pe)) return false;
        String e = pe.getElement().toString();
        return e.contains("/structures/") || e.contains("/city_center/") || e.contains("/walls/");
    }

    /** The chunk that holds most of box b's columns (the first of equals, west then north): the one that sets its stone. */
    public static ChunkPos homeOf(BoundingBox b) {
        int best = -1, bx = 0, bz = 0;
        for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++)
            for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
                int ox = Math.min(b.maxX(), (cx << 4) + 15) - Math.max(b.minX(), cx << 4) + 1;
                int oz = Math.min(b.maxZ(), (cz << 4) + 15) - Math.max(b.minZ(), cz << 4) + 1;
                if (ox * oz > best) {
                    best = ox * oz;
                    bx = cx;
                    bz = cz;
                }
            }
        return new ChunkPos(bx, bz);
    }

    /**
     * The wall block of piece box {@code b} in the chunk's {@code box} that takes the stone: a stone the kind hides in
     * (in a city, not plain deepslate, which is the cavern's own) with open air beside it, inside the piece and the
     * chunk, two blocks over the floor there best (eye level), then one, then three; among equals, by {@code h}.
     */
    @Nullable
    static BlockPos spot(WorldGenLevel level, Site site, BoundingBox b, BoundingBox box, long h) {
        boolean city = site == Site.CITY;
        int x0 = Math.max(b.minX(), box.minX()), x1 = Math.min(b.maxX(), box.maxX());
        int z0 = Math.max(b.minZ(), box.minZ()), z1 = Math.min(b.maxZ(), box.maxZ());
        int y0 = Math.max(b.minY(), level.getMinBuildHeight()), y1 = Math.min(b.maxY(), level.getMaxBuildHeight() - 1);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(), q = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        int bestScore = 0;
        long bestTie = Long.MAX_VALUE;
        for (int x = x0; x <= x1; x++)
            for (int z = z0; z <= z1; z++)
                for (int y = y0; y <= y1; y++) {
                    BlockState s = level.getBlockState(p.set(x, y, z));
                    RuneStoneHost host = RuneStoneHost.of(s);
                    if (host == null || !site.kind.takes(host) || (city && host == RuneStoneHost.DEEPSLATE)) continue;
                    int score = 0;
                    for (int[] d : NB4) {
                        int nx = x + d[0], nz = z + d[1];
                        if (nx < x0 || nx > x1 || nz < z0 || nz > z1) continue;
                        if (!level.getBlockState(q.set(nx, y, nz)).isAir()) continue;
                        int up = 1;
                        while (up <= 3 && level.getBlockState(q.set(nx, y - up, nz)).isAir()) up++;
                        // up: the rows of air from this block's height down to the floor's top, this one counted
                        score = Math.max(score, up == 2 ? 3 : up == 1 ? 2 : up == 3 ? 1 : 0);
                    }
                    if (score == 0 || score < bestScore) continue;
                    long tie = Hashing.mix(h, x, y, z);
                    if (score > bestScore || tie < bestTie) {
                        best = new BlockPos(x, y, z);
                        bestScore = score;
                        bestTie = tie;
                    }
                }
        return best;
    }
}
