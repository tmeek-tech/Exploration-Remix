package com.explorationremix.world.eruption;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A lava bomb's blast sends no explode packet (0.9.0 eruption effects, round 2), read from Blast's bytecode as the game
 * would run it: {@code burst} calls {@code Level.explode}'s 10-argument worker with {@code spawnParticles} false, never
 * the 9-argument {@code ServerLevel.explode} that adds the packet (which drew the game's white TNT puffs over every
 * burst); and, with no packet, a player's push must still reach them by the motion packet: {@code strike} sets
 * {@code hurtMarked}. The engine path (mc: ServerLevel.java:1022-1035, Level.java:534-559, Explosion.java:173-220,
 * ServerEntity.java:199-203) is in the class's comments.
 */
class BlastQuietTest {
    private static final String EXPLOSION = "Lnet/minecraft/world/level/Explosion;";
    private static final String WORKER = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;"
            + "Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;Z)" + EXPLOSION;
    private static final String WITH_PACKET = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/damagesource/DamageSource;"
            + "Lnet/minecraft/world/level/ExplosionDamageCalculator;DDDFZLnet/minecraft/world/level/Level$ExplosionInteraction;)" + EXPLOSION;

    @Test
    void theBurstCallsTheQuietWorker() throws IOException {
        MethodNode burst = method("burst");
        List<MethodInsnNode> explodes = new ArrayList<>();
        for (AbstractInsnNode in : burst.instructions) {
            if (in instanceof MethodInsnNode m && m.name.equals("explode")) explodes.add(m);
        }
        assertEquals(1, explodes.size(), "one blast a burst");
        MethodInsnNode call = explodes.get(0);
        assertTrue(!call.desc.equals(WITH_PACKET), "the 9-argument ServerLevel.explode sends the explode packet (the white TNT puffs)");
        assertEquals(WORKER, call.desc, "the 10-argument worker");
        assertEquals(Opcodes.ICONST_0, before(call).getOpcode(), "spawnParticles false, as ServerLevel passes it");
    }

    @Test
    void aStruckPlayersPushIsSentByTheMotionPacket() throws IOException {
        MethodNode strike = method("strike");
        boolean marked = false, moved = false;
        for (AbstractInsnNode in : strike.instructions) {
            if (in instanceof FieldInsnNode f && f.getOpcode() == Opcodes.PUTFIELD && f.name.equals("hurtMarked")) {
                marked |= before(f).getOpcode() == Opcodes.ICONST_1;
            }
            if (in instanceof MethodInsnNode m && m.name.equals("setDeltaMovement")) moved = true;
        }
        assertTrue(moved, "strike pushes what it strikes");
        assertTrue(marked, "and sets hurtMarked, so the push reaches a player's client with no explode packet");
    }

    /** The instruction before {@code in}, past labels, line numbers and frames. */
    private static AbstractInsnNode before(AbstractInsnNode in) {
        AbstractInsnNode p = in.getPrevious();
        while (p != null && p.getOpcode() < 0) p = p.getPrevious();
        assertTrue(p != null, "nothing before " + in);
        return p;
    }

    private static MethodNode method(String name) throws IOException {
        try (InputStream in = Blast.class.getResourceAsStream("Blast.class")) {
            assertTrue(in != null, "Blast.class is not on the test classpath");
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            for (MethodNode m : node.methods) if (m.name.equals(name)) return m;
        }
        throw new AssertionError("Blast has no method " + name);
    }
}
