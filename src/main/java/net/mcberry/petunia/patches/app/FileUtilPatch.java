package net.mcberry.petunia.patches.app;

import net.mcberry.berry.utils.ReflectionUtil;
import net.minecraft.util.FileUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.mcberry.petunia.patches.IClassPatcher;
import net.mcberry.petunia.patches.Patch;

import java.lang.reflect.Field;
import java.util.regex.Pattern;

@Patch(FileUtilPatch.target)
public class FileUtilPatch implements IClassPatcher {
    static final String target = "net/minecraft/util/FileUtil";

    /**
     * Guards against every assumption this patch makes. A miss in any of them is silent, because the
     * patch replaces a whole method body and rewrites nothing else, so a wrong shape produces a valid
     * class with wrong behaviour rather than an error.
     * <ul>
     *   <li>{@code <clinit>} and {@code isPathPartPortable} must exist - they are the two injection
     *       points.</li>
     *   <li>{@code RESERVED_WINDOWS_FILENAMES_NEOFORGE} must not exist yet, or the added field would
     *       be a duplicate.</li>
     *   <li>{@code RESERVED_WINDOWS_FILENAMES} must still exist, since {@link #choose()} reads it
     *       reflectively and would throw a NullPointerException on every call if it were gone.</li>
     *   <li>{@code isPathPartPortable} must take exactly one {@code String} and return {@code boolean},
     *       because the replacement body is {@code ALOAD 0} + {@code portable(String)} + {@code IRETURN}.
     *       A different descriptor would silently call the wrong thing or misread the stack.</li>
     * </ul>
     */
    @Override
    public boolean validate(ClassNode cn) {
        boolean hasClinit = false, hasOriginalPattern = false;
        MethodNode ippp = null;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<clinit>")) hasClinit = true;
            else if (mn.name.equals("isPathPartPortable")) ippp = mn;
        }
        for (FieldNode fn : cn.fields) {
            if (fn.name.equals("RESERVED_WINDOWS_FILENAMES_NEOFORGE")) return false;
            if (fn.name.equals("RESERVED_WINDOWS_FILENAMES")) hasOriginalPattern = true;
        }
        if (!hasClinit || ippp == null || !hasOriginalPattern) return false;
        // The fixed replacement body only fits this exact shape.
        return ippp.desc.equals("(Ljava/lang/String;)Z")
                && (ippp.access & Opcodes.ACC_STATIC) != 0;
    }

    @Override
    public void patch(ClassNode cn) {
        // NeoForge: RESERVED_WINDOWS_FILENAMES_NEOFORGE =
        //      Pattern.compile(".*\\.|(?:CON|PRN|AUX|NUL|CLOCK\\$|CONIN\\$|CONOUT\\$|(?:COM|LPT)[¹²³0-9])(?:\\..*)?", 2)
        cn.fields.add(new FieldNode(
                Opcodes.ASM9, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "RESERVED_WINDOWS_FILENAMES_NEOFORGE", "Ljava/util/regex/Pattern;", "Ljava/util/regex/Pattern;", null
        ));

        MethodNode clinit = null, ippp = null;
        for (MethodNode mn : cn.methods)
            if (mn.name.equals("<clinit>"))
                clinit = mn;
            else if (mn.name.equals("isPathPartPortable"))
                ippp = mn;
        var iter = clinit.instructions.iterator();
        // Initialize RESERVED_WINDOWS_FILENAMES_NEOFORGE
        iter.add(new LdcInsnNode(".*\\.|(?:CON|PRN|AUX|NUL|CLOCK\\$|CONIN\\$|CONOUT\\$|(?:COM|LPT)[¹²³0-9])(?:\\..*)?"));
        iter.add(new LdcInsnNode(2));
        iter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ReflectionUtil.getInternalName(Pattern.class), "compile", "(Ljava/lang/String;I)Ljava/util/regex/Pattern;"));
        iter.add(new FieldInsnNode(Opcodes.PUTSTATIC, target, "RESERVED_WINDOWS_FILENAMES_NEOFORGE", "Ljava/util/regex/Pattern;"));
        // redirect isPathPartPortable to here
        ippp.instructions.clear();
        iter = ippp.instructions.iterator();
        iter.add(new VarInsnNode(Opcodes.ALOAD, 0));
        iter.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ReflectionUtil.getInternalName(FileUtilPatch.class), "portable", "(Ljava/lang/String;)Z"));
        iter.add(new InsnNode(Opcodes.IRETURN));
    }

    private static Field orig = null, neof = null;

    private static void init() {
        if (orig == null) {
            orig = ReflectionUtil.getField(FileUtil.class, "RESERVED_WINDOWS_FILENAMES");
            neof = ReflectionUtil.getField(FileUtil.class, "RESERVED_WINDOWS_FILENAMES_NEOFORGE");
            orig.setAccessible(true);
            neof.setAccessible(true);
        }
    }

    private static Pattern choose() {
        init();
        try {
            // TODO: Uncomment this when NeoForge initialization part has been done
//            if (NeoForgeMod.getProperFilenameValidation ()) return (Pattern) neof.get (null);
//            else
            return (Pattern) orig.get(null);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static boolean portable(String path) {
        return !choose().matcher(path).matches();
    }
}