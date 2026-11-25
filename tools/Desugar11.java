import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Convierte clases Java 9-11 (p. ej. classes.jar de libVLC) a Java 8 para que las acepte "dx":
 *  - versión de clase 52
 *  - elimina NestHost/NestMembers y hace "de paquete" los miembros privados (acceso entre clases anidadas)
 *  - invokespecial a métodos propios (antes privados) pasa a invokevirtual
 * Aborta si detecta construcciones que no sabe convertir o si un cambio pudiera alterar la herencia.
 * Uso: java -cp asm.jar:asm-tree.jar Desugar11 entrada.jar salida.jar
 */
public class Desugar11 {
    public static void main(String[] a) throws Exception {
        Map<String, ClassNode> classes = new LinkedHashMap<>();
        Map<String, byte[]> other = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(a[0])) {
            for (ZipEntry e : Collections.list(z.entries())) {
                if (e.isDirectory()) continue;
                byte[] b;
                try (InputStream in = z.getInputStream(e)) { b = in.readAllBytes(); }
                if (e.getName().endsWith(".class") && !e.getName().endsWith("module-info.class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(b).accept(cn, 0);
                    classes.put(cn.name, cn);
                } else if (!e.getName().endsWith("module-info.class")) {
                    other.put(e.getName(), b);
                }
            }
        }
        int changed = 0;
        for (ClassNode cn : classes.values()) {
            boolean iface = (cn.access & Opcodes.ACC_INTERFACE) != 0;
            cn.nestHostClass = null;
            cn.nestMembers = null;
            if (cn.version > Opcodes.V1_8) cn.version = Opcodes.V1_8;
            for (FieldNode f : cn.fields) {
                if ((f.access & Opcodes.ACC_PRIVATE) != 0) { f.access &= ~Opcodes.ACC_PRIVATE; changed++; }
            }
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode i : m.instructions) {
                    if (i.getOpcode() == Opcodes.INVOKEDYNAMIC) throw new IllegalStateException("invokedynamic en " + cn.name);
                }
                if ((m.access & Opcodes.ACC_PRIVATE) != 0 && !m.name.startsWith("<")) {
                    if (iface) throw new IllegalStateException("método privado en interfaz " + cn.name + "." + m.name);
                    if ((m.access & Opcodes.ACC_STATIC) == 0) checkNoOverride(classes, cn, m);
                    m.access &= ~Opcodes.ACC_PRIVATE;
                    changed++;
                }
            }
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode i : m.instructions) {
                    if (i.getOpcode() == Opcodes.INVOKESPECIAL) {
                        MethodInsnNode mi = (MethodInsnNode) i;
                        if (mi.owner.equals(cn.name) && !mi.name.equals("<init>")) mi.setOpcode(Opcodes.INVOKEVIRTUAL);
                    }
                }
            }
        }
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(Paths.get(a[1])))) {
            for (ClassNode cn : classes.values()) {
                ClassWriter cw = new ClassWriter(0);
                cn.accept(cw);
                out.putNextEntry(new ZipEntry(cn.name + ".class"));
                out.write(cw.toByteArray());
                out.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : other.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        System.out.println("Clases: " + classes.size() + ", miembros privados convertidos: " + changed);
    }

    /** Un método privado que pase a "de paquete" no debe sobrescribir ni ser sobrescrito por otro. */
    static void checkNoOverride(Map<String, ClassNode> classes, ClassNode owner, MethodNode m) {
        for (ClassNode c : classes.values()) {
            if (c == owner) continue;
            if (!isSubclass(classes, c, owner.name) && !isSubclass(classes, owner, c.name)) continue;
            for (MethodNode o : c.methods) {
                if (o.name.equals(m.name) && o.desc.equals(m.desc) && (o.access & Opcodes.ACC_STATIC) == 0) {
                    throw new IllegalStateException("conflicto de herencia: " + owner.name + "." + m.name + " / " + c.name);
                }
            }
        }
    }

    static boolean isSubclass(Map<String, ClassNode> classes, ClassNode c, String sup) {
        for (int guard = 0; c != null && guard < 64; guard++) {
            if (sup.equals(c.superName)) return true;
            c = classes.get(c.superName);
        }
        return false;
    }
}
