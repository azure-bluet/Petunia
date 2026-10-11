package net.mcberry.petunia.patches;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;

/**
 * One patch bound to its target class.
 * <p>
 * This is the unit both the runtime transform pipeline and {@link PatcherDebugger} work with, so
 * that the debugger exercises exactly the same pairing the game does.
 * <p>
 * {@link #rawBytes} is only needed by the debugger. The runtime transform path already has the
 * class bytes in hand when it consults a patch, so it leaves the field {@code null}.
 */
public record PatcherEntry(String target, IClassPatcher patcher, byte[] rawBytes) {
    public PatcherEntry(String target, IClassPatcher patcher) {
        this(target, patcher, null);
    }

    /** @return true if this entry carries the unpatched class bytes needed to patch standalone */
    public boolean isLoadable() {
        return rawBytes != null && rawBytes.length > 0;
    }

    /**
     * Ask the patch whether it still fits the target, via {@link IClassPatcher#validate(byte[])}.
     * <p>
     * Goes through the byte array form so that a patch overriding either {@code validate} overload is
     * honoured. Nothing is parsed here, and validation cannot affect the class that
     * {@link #apply()} later produces.
     *
     * @return true if the patch is still valid
     * @throws IllegalStateException if this entry has no raw bytes
     */
    public boolean validate() {
        if (!isLoadable())
            throw new IllegalStateException(
                    "No raw class bytes for " + target + "; only PatcherDebugger populates them");

        return patcher.validate(rawBytes);
    }

    /**
     * Run the patcher over a fresh {@link ClassNode} built from {@link #rawBytes}.
     * <p>
     * The input is always the untouched target class, so the result does not depend on what any
     * other patcher did and repeated runs produce identical output.
     * <p>
     * Validate separately via {@link #validate()}; {@code patch} is allowed to assume it has already
     * been accepted, so this method does not call it.
     *
     * @return the patched class bytes
     * @throws IllegalStateException if this entry has no raw bytes
     */
    public byte[] apply() {
        if (!isLoadable())
            throw new IllegalStateException(
                    "No raw class bytes for " + target + "; only PatcherDebugger populates them");

        ClassNode node = new ClassNode();
        new ClassReader(rawBytes).accept(node, 0);
        patcher.patch(node);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    public PatcherEntry withRawBytes(byte[] bytes) {
        return new PatcherEntry(target, patcher, bytes);
    }

    /** Where {@link PatcherEntry} reads the unpatched class from. */
    public interface Source {
        /** @return the class bytes, or null if the source does not have this class */
        byte[] read(String internalName) throws IOException;
    }
}
