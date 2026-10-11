package net.mcberry.petunia.patches.app;

import net.mcberry.berry.utils.ReflectionUtil;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import net.mcberry.petunia.patches.IClassPatcher;
import net.mcberry.petunia.patches.Patch;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

@Patch(UtilPatch.target)
public class UtilPatch implements IClassPatcher {
    static final String target = "net/minecraft/util/Util";

    /** The only {@code error} overload the patch is allowed to rewrite. */
    private static final String LOGGER_ERROR_DESC = "(Ljava/lang/String;Ljava/lang/Object;)V";

    /** {@code SharedConstants.IS_RUNNING_IN_IDE}, the read every rewrite in this patch keys off. */
    private static Predicate<Object> isIdeRead() {
        return o -> o instanceof FieldInsnNode fi
                && fi.getOpcode() == Opcodes.GETSTATIC
                && fi.owner.equals(SharedConstantsPatch.target)
                && fi.name.equals("IS_RUNNING_IN_IDE")
                && fi.desc.equals("Z");
    }

    /**
     * The {@code LOGGER.error} call the patch turns into {@code debug}. Matched on owner, name and
     * descriptor together: {@code Util} contains four other {@code error} overloads that must be left
     * alone, and matching on the name alone would silently demote them too.
     */
    private static Predicate<Object> isTargetError() {
        return o -> o instanceof MethodInsnNode mi
                && mi.getOpcode() == Opcodes.INVOKEINTERFACE
                && mi.owner.equals("org/slf4j/Logger")
                && mi.name.equals("error")
                && mi.desc.equals(LOGGER_ERROR_DESC);
    }

    /**
     * Guards against every assumption this patch makes, because a miss in any of them is silent:
     * an instruction rewrite that matches nothing still produces a valid class and a successful dump.
     * <ul>
     *   <li>{@code doFetchChoiceType}, when present, must still hold the {@code LOGGER.error} call and
     *       the {@code IS_RUNNING_IN_IDE} read, otherwise that part of the patch is a no-op.</li>
     *   <li>At least one {@code *InIde} pause helper must exist and still read
     *       {@code IS_RUNNING_IN_IDE}, otherwise the pause half of the patch is a no-op.</li>
     *   <li>{@code patch_pause} calls {@code this.getClass().getName()} as the field's new owner, so
     *       this class name must be the owner used in the rewritten bytecode.</li>
     * </ul>
     */
    @Override
    public boolean validate(ClassNode cn) {
        MethodNode dfct = null;
        List<MethodNode> pauseHelpers = new ArrayList<>();
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("doFetchChoiceType")) dfct = mn;
            else if (mn.name.endsWith("InIde")) pauseHelpers.add(mn);
        }

        if (pauseHelpers.isEmpty()) return false;
        for (MethodNode mn : pauseHelpers)
            if (!has(mn, isIdeRead())) return false;

        // A missing doFetchChoiceType is fine; vanilla may drop it, and then nothing is rewritten.
        if (dfct == null) return true;
        return has(dfct, isTargetError()) && has(dfct, isIdeRead());
    }

    /**
     * Applies the patch. Assumes {@link #validate(ClassNode)} accepted this class: the {@code *InIde}
     * helpers and every rewrite target it asserts are taken as given.
     */
    @Override
    public void patch(ClassNode cn) {
        MethodNode dfct = null;
        List<MethodNode> pauseHelpers = new ArrayList<>();
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("doFetchChoiceType")) dfct = mn;
            else if (mn.name.endsWith("InIde")) pauseHelpers.add(mn);
        }

        for (MethodNode pii : pauseHelpers) patch_pause(pii);

        if (dfct == null) return; // vanilla dropped it, nothing to rewrite

        // NeoForge: log a missing data fixer at debug level instead of error
        rewrite(dfct, isTargetError(), mi -> ((MethodInsnNode) mi).name = "debug");
        // NeoForge: never throw the exception (in vanilla it is thrown in dev)
        String owner = this.getClass().getName().replace('.', '/');
        rewrite(dfct, isIdeRead(), fi -> ((FieldInsnNode) fi).owner = owner);
    }

    private void patch_pause(MethodNode mn) {
        // NeoForge: pause only with JDWP. Owner is matched as well, so that an unrelated
        // IS_RUNNING_IN_IDE of another class is not retargeted by name alone.
        rewrite(mn, isIdeRead(), fi -> ((FieldInsnNode) fi).name = "IS_RUNNING_WITH_JDWP");
    }

    /** @return true if the method contains an instruction the predicate matches */
    private static boolean has(MethodNode mn, Predicate<Object> match) {
        for (var insn : mn.instructions)
            if (match.test(insn)) return true;
        return false;
    }

    /** Apply {@code action} to every instruction in the method matching {@code match} */
    private static void rewrite(MethodNode mn, Predicate<Object> match, Consumer<Object> action) {
        for (var insn : mn.instructions)
            if (match.test(insn)) action.accept(insn);
    }

    /** A constant the rewritten bytecode refers to, kept so the values stay in one place. */
    public static final boolean IS_RUNNING_IN_IDE = false;
}
