package net.mcberry.petunia.patches.app;

import io.netty.util.ResourceLeakDetector;
import net.mcberry.berry.loader.BerryLoader;
import net.mcberry.berry.utils.ReflectionUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import net.mcberry.petunia.patches.IClassPatcher;
import net.mcberry.petunia.patches.Patch;

import java.lang.management.ManagementFactory;

@Patch(SharedConstantsPatch.target)
public class SharedConstantsPatch implements IClassPatcher {
    static final String target = "net/minecraft/SharedConstants";

    /**
     * Guards against every assumption this patch makes.
     * <ul>
     *   <li>{@code IS_RUNNING_WITH_JDWP} must not exist yet, or the added field would be a
     *       duplicate.</li>
     *   <li>{@code <clinit>} must exist, since the field is initialised by injecting into its head.</li>
     *   <li>{@code <clinit>} must contain exactly one {@code ResourceLeakDetector.setLevel(Level)}
     *       call. That call is what gets redirected to {@link #setLevel} to install the "do not
     *       override an explicitly configured level" guard, so the patch would silently lose half of
     *       its behaviour if the call were renamed, removed, or split.</li>
     * </ul>
     */
    @Override
    public boolean validate(ClassNode cn) {
        for (FieldNode fn : cn.fields)
            if (fn.name.equals("IS_RUNNING_WITH_JDWP"))
                return false;

        MethodNode clinit = null;
        for (MethodNode mn : cn.methods)
            if (mn.name.equals("<clinit>")) clinit = mn;
        if (clinit == null) return false;

        int setLevel = 0;
        for (var insn : clinit.instructions) {
            if (insn instanceof MethodInsnNode mi && isSetLevel(mi)) setLevel++;
        }
        return setLevel == 1;
    }

    /**
     * The {@code ResourceLeakDetector.setLevel} call the patch redirects. Matched on owner, name and
     * descriptor so a different {@code setLevel} on another type is not retargeted by accident.
     */
    private static boolean isSetLevel(MethodInsnNode mi) {
        return mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals("io/netty/util/ResourceLeakDetector")
                && mi.name.equals("setLevel")
                && mi.desc.equals("(Lio/netty/util/ResourceLeakDetector$Level;)V");
    }

    /**
     * Applies the patch. Assumes {@link #validate(ClassNode)} accepted this class: {@code <clinit>}
     * and the {@code ResourceLeakDetector.setLevel} call it redirects are taken as given.
     */
    @Override
    public void patch(ClassNode cn) {
        final String myname = ReflectionUtil.getInternalName(SharedConstantsPatch.class);

        // NeoForge: public static final boolean IS_RUNNING_WITH_JDWP
        cn.fields.add(new FieldNode(
                Opcodes.ASM9, Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "IS_RUNNING_WITH_JDWP", "Z", "Z", null
        ));

        MethodNode clinit = null;
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<clinit>")) {
                clinit = mn;
                break;
            }
        }
        var iter = clinit.instructions.iterator();

        // NeoForge: IS_RUNNING_WITH_JDWP = ManagementFactory.getRuntimeMXBean().getInputArguments()
        //      .stream().anyMatch(str->str.startsWith("-agentlib:jdwp"))
        iter.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                myname,
                "isRunningWithJDWP",
                "()Z"
        ));
        iter.add(new FieldInsnNode(Opcodes.PUTSTATIC, target, "IS_RUNNING_WITH_JDWP", "Z"));
        // IS_RUNNING_IN_IDE = BerryLoader.isDevelopment()
        iter.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                ReflectionUtil.getInternalName(BerryLoader.class),
                "isDevelopment",
                "()Z"
        ));
        iter.add(new FieldInsnNode(Opcodes.PUTSTATIC, target, "IS_RUNNING_IN_IDE", "Z"));

        while (iter.hasNext()) {
            var insn = iter.next();
            if (insn instanceof MethodInsnNode meth && isSetLevel(meth)) {
                meth.owner = myname;
            }
        }
    }

    public static boolean isRunningWithJDWP() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments()
                .stream().anyMatch(str -> str.startsWith("-agentlib:jdwp"));
    }

    public static void setLevel(ResourceLeakDetector.Level level) {
        if (System.getProperty("io.netty.leakDetection.level") == null)
            ResourceLeakDetector.setLevel(level);
    }
}