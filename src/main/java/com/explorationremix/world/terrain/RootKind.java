package com.explorationremix.world.terrain;

/**
 * The kind of trees growing on the surface over a spot, which decides whether the Rootvaults sit under it and which
 * wood their roots are made of. {@link #NONE} grows no Rootvaults (plains, deserts, peaks, oceans and the like).
 */
public enum RootKind {
    NONE("none"),
    OAK("oak"),
    BIRCH("birch"),
    SPRUCE("spruce"),
    JUNGLE("jungle"),
    DARK_OAK("dark_oak"),
    MANGROVE("mangrove"),
    CHERRY("cherry");

    /** The name of its biome tag, {@code explorationremix:rootvaults/<id>}, and of the kind in feature configs. */
    public final String id;

    RootKind(String id) { this.id = id; }

    public boolean trees() { return this != NONE; }

    /** Big trees with thick trunks send down thick roots. */
    public boolean thick() { return this == JUNGLE || this == DARK_OAK; }

    /** The kinds that have a biome tag, in the order they are checked (the first match wins). */
    public static final RootKind[] TAGGED = {MANGROVE, CHERRY, DARK_OAK, BIRCH, JUNGLE, SPRUCE, OAK};

    public static RootKind byId(String id) {
        for (RootKind k : values()) if (k.id.equals(id)) return k;
        return NONE;
    }
}
