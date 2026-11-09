package com.explorationremix.world.lava;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The quake, eruption and debris messages (0.9.0, spec 6.1) come back from the wire as they were sent, with the
 * protocol version raised for them.
 */
class LavaNetTest {
    @Test
    void theVersionIsThree() {
        // any change to a message's fields needs a new version, so an old client is refused cleanly when it joins
        assertEquals("3", LavaNet.VERSION);
    }

    private static FriendlyByteBuf buf() { return new FriendlyByteBuf(Unpooled.buffer()); }

    @Test
    void aQuakeComesBackAsSent() {
        LavaNet.Quake[] all = {
                new LavaNet.Quake(LavaNet.Quake.BIG, 7, 123_456_789_012L, 200, 0.75F, -123_456, -1, true),
                new LavaNet.Quake(LavaNet.Quake.TREMOR, 1, 0, 30, 0.15F, 0, -1, false),
                new LavaNet.Quake(LavaNet.Quake.CLIMB, 1 << 20, -5, 50, 0.4F, Integer.MAX_VALUE, 49, false),
                new LavaNet.Quake(LavaNet.Quake.BIG, 3, 1000, 250, 1F, Integer.MIN_VALUE, 0, true),
                LavaNet.Quake.end(42)};
        for (LavaNet.Quake q : all) {
            FriendlyByteBuf b = buf();
            q.write(b);
            assertTrue(b.readableBytes() <= 32, "about 26 bytes: " + b.readableBytes());
            assertEquals(q, LavaNet.Quake.read(b));
            assertEquals(0, b.readableBytes());
        }
        assertEquals(LavaNet.Quake.END, LavaNet.Quake.end(42).kind());
        assertEquals(42, LavaNet.Quake.end(42).id());
    }

    @Test
    void anEruptionComesBackAsSent() {
        LavaNet.Eruption[] all = {
                new LavaNet.Eruption(LavaNet.Eruption.START, 9, -1234.5, 98765.25, -781, -770, 2.4F, 5_000_000L, 80, 200, 0.8F, 1.5F,
                        -8_675_309L, -1, 256),
                new LavaNet.Eruption(LavaNet.Eruption.START, 10, 0, 0, -800, -760, 1.6F, 0, 0, 40, 1F, 1F, Long.MAX_VALUE, 120, 2048),
                LavaNet.Eruption.end(11)};
        for (LavaNet.Eruption e : all) {
            FriendlyByteBuf b = buf();
            e.write(b);
            assertTrue(b.readableBytes() <= 80, "about 75 bytes: " + b.readableBytes());
            assertEquals(e, LavaNet.Eruption.read(b));
            assertEquals(0, b.readableBytes());
        }
    }

    @Test
    void debrisComesBackAsSent() {
        LavaNet.Debris[] all = {
                new LavaNet.Debris(LavaNet.Debris.WARN, 0x1234_5678_9ABCL, 15_000, 7_000_000L, -800, Integer.MIN_VALUE, -1),
                new LavaNet.Debris(LavaNet.Debris.WARN, -1L, 1, 0, -823, -783, 6),
                new LavaNet.Debris(LavaNet.Debris.CANCEL, 5L, 0, 0, 0, Integer.MIN_VALUE, -1),
                new LavaNet.Debris(LavaNet.Debris.SHATTER, 77L, 2048, 99, -790, Integer.MIN_VALUE, -1),
                new LavaNet.Debris(LavaNet.Debris.CREAK, Long.MIN_VALUE, 4, 0, 0, Integer.MIN_VALUE, -1)};
        for (LavaNet.Debris d : all) {
            FriendlyByteBuf b = buf();
            d.write(b);
            assertTrue(b.readableBytes() <= 36, "about 31 bytes: " + b.readableBytes());
            assertEquals(d, LavaNet.Debris.read(b));
            assertEquals(0, b.readableBytes());
        }
    }
}
