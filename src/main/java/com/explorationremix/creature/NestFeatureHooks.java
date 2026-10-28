package com.explorationremix.creature;

import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/** Listeners that rebuild nest blocks when a nest regrows; registered by the nest placement code. */
public final class NestFeatureHooks {
    private static final List<BiConsumer<ServerLevel, NestRegrowth.Entry>> REGROW = new ArrayList<>();
    private NestFeatureHooks() {}

    public static void onRegrow(BiConsumer<ServerLevel, NestRegrowth.Entry> hook) { REGROW.add(hook); }

    static void regrow(ServerLevel level, String key, NestRegrowth.Entry entry) {
        for (BiConsumer<ServerLevel, NestRegrowth.Entry> h : REGROW) h.accept(level, entry);
    }
}
