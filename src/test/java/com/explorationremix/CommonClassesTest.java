package com.explorationremix;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client code stays out of the common classes (0.9.0, spec 3.6), so the dedicated server, whose jar has no
 * {@code net.minecraft.client} classes, loads and runs this mod. Every compiled class outside {@code client/} and
 * {@code devtools/} (and the client mixins named by the mixin configs) is read as bytecode: none may name a client type
 * (Minecraft's, Blaze3D's, Forge's client package or this mod's {@code client} package) in its fields, its methods'
 * signatures or their code. The one exception is the pattern {@code LavaNet}'s handlers use, {@code
 * DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> Effects.accept(this))}: inside a lambda's own synthetic method a
 * static call to this mod's client classes with plain-typed arguments, which the server never runs and its verifier
 * never loads.
 */
class CommonClassesTest {
    private static final String[] CLIENT = {"net/minecraft/client/", "com/mojang/blaze3d/", "net/minecraftforge/client/", "com/explorationremix/client/"};
    private static final String[] EXEMPT = {"com/explorationremix/client/", "com/explorationremix/devtools/"};

    @Test
    void commonClassesNameNoClientType() throws IOException, URISyntaxException {
        URL main = CommonClassesTest.class.getClassLoader().getResource("com/explorationremix/ExplorationRemix.class");
        assertTrue(main != null && "file".equals(main.getProtocol()), "the mod's compiled classes are not on the test classpath as files: " + main);
        Path pkg = Paths.get(main.toURI()).getParent();
        Path root = pkg.getParent().getParent();
        Set<String> clientMixins = clientMixins();
        List<String> found = new ArrayList<>();
        int read = 0;
        try (Stream<Path> files = Files.walk(pkg)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                String name = root.relativize(f).toString().replace('\\', '/');
                name = name.substring(0, name.length() - ".class".length());
                if (exempt(name, clientMixins)) continue;
                read++;
                try (InputStream in = Files.newInputStream(f)) {
                    new ClassReader(in).accept(new Scan(name, found), 0);
                }
            }
        }
        assertTrue(read > 100, "only " + read + " common classes were read from " + pkg);
        assertTrue(found.isEmpty(), "common classes name client types (a dedicated server would fail to load them):\n  " + String.join("\n  ", found));
    }

    /** The scan finds a client type in a common class, and lets the DistExecutor lambda's static call through. */
    @Test
    void theScanSeesAClientTypeAndLetsTheLambdaThrough() throws IOException {
        List<String> found = new ArrayList<>();
        try (InputStream in = CommonClassesTest.class.getResourceAsStream("CommonClassesTest$Sample.class")) {
            assertTrue(in != null, "the sample class was not compiled");
            new ClassReader(in).accept(new Scan("Sample", found), 0);
        }
        assertTrue(found.size() == 1 && found.get(0).contains("bad") && found.get(0).contains("net/minecraft/client/Minecraft"), String.valueOf(found));
    }

    /** A common class done wrong in one method and right in a lambda, for the scan's own test. */
    @SuppressWarnings("unused")
    private static final class Sample {
        static Object bad() { return net.minecraft.client.Minecraft.class; }

        static Runnable good() { return () -> com.explorationremix.client.QuakeEffects.accept(null); }
    }

    // ================================================================================================ helpers

    private static boolean exempt(String name, Set<String> clientMixins) {
        for (String e : EXEMPT) if (name.startsWith(e)) return true;
        String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
        return clientMixins.contains(outer);
    }

    /** The mixins the mixin configs apply only on the client (their "client" lists), as class names. */
    private static Set<String> clientMixins() throws IOException {
        Set<String> out = new HashSet<>();
        Pattern pkg = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");
        Pattern list = Pattern.compile("\"client\"\\s*:\\s*\\[([^\\]]*)\\]");
        Pattern entry = Pattern.compile("\"([^\"]+)\"");
        for (String config : new String[]{"explorationremix.mixins.json", "explorationremix.compat.mixins.json"}) {
            try (InputStream in = CommonClassesTest.class.getClassLoader().getResourceAsStream(config)) {
                assertTrue(in != null, config + " is not on the test classpath");
                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                Matcher p = pkg.matcher(json), l = list.matcher(json);
                assertTrue(p.find(), config + " has no package");
                if (!l.find()) continue;
                for (Matcher e = entry.matcher(l.group(1)); e.find(); ) out.add((p.group(1) + "." + e.group(1)).replace('.', '/'));
            }
        }
        return out;
    }

    private static boolean client(String internalName) {
        if (internalName == null) return false;
        for (String c : CLIENT) if (internalName.startsWith(c)) return true;
        return false;
    }

    /** True when a descriptor, signature or type name names a client type. */
    private static boolean names(String s) {
        if (s == null) return false;
        if (client(s)) return true;
        for (String c : CLIENT) if (s.contains("L" + c)) return true;
        return false;
    }

    private static final class Scan extends ClassVisitor {
        private final String cls;
        private final List<String> found;

        Scan(String cls, List<String> found) {
            super(Opcodes.ASM9);
            this.cls = cls;
            this.found = found;
        }

        private void flag(String where, String what) { found.add(cls + " " + where + ": " + what); }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (client(superName)) flag("extends", superName);
            if (interfaces != null) for (String i : interfaces) if (client(i)) flag("implements", i);
            if (names(signature)) flag("signature", signature);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            if (names(descriptor) || names(signature)) flag("field " + name, descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String where = "method " + name + descriptor;
            if (names(descriptor) || names(signature)) flag(where, "its signature");
            if (exceptions != null) for (String e : exceptions) if (client(e)) flag(where, "throws " + e);
            // a lambda's own method: the DistExecutor pattern's static call is let through
            boolean lambda = (access & Opcodes.ACC_SYNTHETIC) != 0 && name.startsWith("lambda$");
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitTypeInsn(int opcode, String type) { if (names(type)) flag(where, "type " + type); }

                @Override
                public void visitFieldInsn(int opcode, String owner, String fname, String fdesc) {
                    if (client(owner) || names(fdesc)) flag(where, "field " + owner + "." + fname);
                }

                @Override
                public void visitMethodInsn(int opcode, String owner, String mname, String mdesc, boolean itf) {
                    boolean allowed = lambda && opcode == Opcodes.INVOKESTATIC && owner.startsWith("com/explorationremix/client/") && !names(mdesc);
                    if ((client(owner) || names(mdesc)) && !allowed) flag(where, "call " + owner + "." + mname + mdesc);
                }

                @Override
                public void visitInvokeDynamicInsn(String iname, String idesc, Handle bsm, Object... args) {
                    if (names(idesc)) flag(where, "invokedynamic " + idesc);
                    for (Object a : args) {
                        if (a instanceof Type t && names(t.getDescriptor())) flag(where, "invokedynamic argument " + t);
                        if (a instanceof Handle h && (client(h.getOwner()) || names(h.getDesc()))) flag(where, "invokedynamic handle " + h);
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Type t && names(t.getDescriptor())) flag(where, "constant " + t);
                }

                @Override
                public void visitMultiANewArrayInsn(String mdesc, int dims) { if (names(mdesc)) flag(where, "array " + mdesc); }

                @Override
                public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                    if (client(type)) flag(where, "catch " + type);
                }

                @Override
                public void visitLocalVariable(String lname, String ldesc, String lsig, Label start, Label end, int index) {
                    if (names(ldesc) || names(lsig)) flag(where, "local " + lname + " " + ldesc);
                }
            };
        }
    }
}
