package net.mcberry.petunia.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

/**
 * A patch for exactly one class.
 * <p>
 * Implementations are discovered by {@link SimplePatchLoader} from a package of the loader's own
 * jar. The target is normally taken from {@link Patch}; override {@link #getTarget()} for cases the
 * annotation cannot express.
 */
public interface IClassPatcher {
    /**
     * Patch byte code of a class.
     *
     * @param buffer The byte code buffer
     * @return Patched buffer, or null if unpatched
     */
    default byte[] patch(byte[] buffer) {
        ClassReader cr = new ClassReader(buffer);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        patch(cn);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    /**
     * Patch a ClassNode
     *
     * @param cn ClassNode of the class to be patched
     */
    default void patch(ClassNode cn) {
    }

    /**
     * Internal name of the class to be patched
     *
     * @return The internal name
     */
    default String getTarget() {
        // The class MUST be annotated with @Patch to use this default method
        var annotation = this.getClass().getAnnotation(Patch.class);
        if (annotation != null) return annotation.value();
        throw new UnsupportedOperationException("Patch must be annotated with @Patch or override getTarget()");
    }

    /**
     * Check that this patch still fits the target class, i.e. that it has not gone stale.
     * <p>
     * This is a guard, not a transformation: it must not modify the class. Assert whatever the patch
     * relies on and return false when it no longer holds - a method that was renamed, a field that is
     * gone, an injection point that moved, or a member this patch adds that vanilla has since grown
     * itself.
     * <p>
     * It is only called by {@link PatcherDebugger}, deliberately. Verifying has a cost and the answer
     * cannot change while a single run is in progress, so the game does not pay for it; the dump is
     * where a stale patch should be reported.
     * <p>
     * Override either this method or {@link #validate(ClassNode)}; both work, because the default of
     * each delegates to the other's shape. {@link PatcherDebugger} calls this one, so a patch that
     * overrides only the {@code ClassNode} form still gets invoked.
     *
     * @param buffer The unpatched target class bytes
     * @return true if the patch can still be applied, false if it is stale
     */
    default boolean validate(byte[] buffer) {
        ClassNode cn = new ClassNode();
        new ClassReader(buffer).accept(cn, 0);
        return validate(cn);
    }

    /**
     * Check that this patch still fits the target class, using an already parsed node.
     * <p>
     * Prefer this form: it avoids re-parsing the class. As with {@link #validate(byte[])}, override
     * either one, not both - they are two views of the same guard.
     *
     * @param cn The unpatched target class
     * @return true if the patch can still be applied, false if it is stale
     */
    default boolean validate(ClassNode cn) {
        return true;
    }
}
