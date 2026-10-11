package net.mcberry.petunia.patches;

import net.mcberry.berry.api.asm.ClassFile;
import net.mcberry.berry.loader.classloading.BerryClassTransformer;
import net.mcberry.berry.utils.ReflectionUtil;

import java.io.IOException;
import java.io.InputStream;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Discovers {@link IClassPatcher} implementations in a package of a jar and applies them, keyed by
 * target.
 * <p>
 * This class is the single place that knows how patches are found and instantiated.
 * {@link PatcherDebugger} builds one of these instead of repeating the discovery, so a patch the
 * debugger can dump is by construction a patch the game would run.
 */
public class SimplePatchLoader implements BerryClassTransformer.ByteCodeTransformer {
    /** Patches keyed by target. A later patch for the same target replaces the earlier one. */
    private final Map<String, PatcherEntry> entries = new LinkedHashMap<>();

    /**
     * When set, every patched class is also written to disk as it is applied during a normal run.
     * <p>
     * This is independent of {@link PatcherDebugger}: that one produces a reproducible dump of all
     * patches and exits, while this one records what actually happened, in class-loading order, and
     * lets the game continue. Both can be enabled at once; they write to different directories
     * unless {@link PatchDump#DIR_PROPERTY} overrides them onto the same one.
     */
    private final boolean dumpRuntimePatches;

    @Override
    public byte[] transform(ClassLoader loader, String name, Class<?> clazz, ProtectionDomain domain, byte[] buf) {
        // There are two types of patches:
        // 1. Modify one class per patch, like what (Neo)?Forge etc. do
        // These patches are easier, but may cause problems with some patches
        // 2. Modify mod classes, so they work fine
        // SimplePatchLoader loads type 1 patches from given package of given jar
        // A patch must implement IClassPatcher. By default, the target is loaded from
        // @Patch annotation, however a patch can override this method.
        // Multiple patches cannot share the same target in the same SimplePatchLoader.
        PatcherEntry entry = entries.get(name);
        if (entry != null) {
            // A patch is present for the current class
            byte[] res = entry.patcher().patch(buf);
            if (res == null) return buf;
            if (dumpRuntimePatches) PatchDump.dump(name, res, PatchDump.directory());
            return res;
        }
        return buf;
    }

    /**
     * Scans {@code pkg} inside {@code jar} for {@link IClassPatcher} implementations.
     * <p>
     * Entries are visited in name order so the resulting set, the duplicate warning and the
     * debugger's dump order are all deterministic. The runtime dump is left off.
     *
     * @param jar The jar holding the patch classes, normally the jar this loader itself lives in
     * @param pkg Package to scan, e.g. {@code net.mcberry.petunia.patches.app}
     */
    public SimplePatchLoader(JarFile jar, String pkg) {
        this(jar, pkg, false);
    }

    /**
     * Scans {@code pkg} inside {@code jar} for {@link IClassPatcher} implementations.
     *
     * @param jar                The jar holding the patch classes
     * @param pkg                Package to scan, e.g. {@code net.mcberry.petunia.patches.app}
     * @param dumpRuntimePatches When true, every class this loader patches during a normal run is
     *                           also written to disk. Enabling it is what starts
     *                           {@link PatchDump}'s background writer.
     */
    public SimplePatchLoader(JarFile jar, String pkg, boolean dumpRuntimePatches) {
        String prefix = pkg.replace('.', '/') + "/";
        String wanted = ReflectionUtil.getInternalName(IClassPatcher.class);

        List<JarEntry> candidates = new ArrayList<>();
        jar.entries().asIterator().forEachRemaining(entry -> {
            if (entry.isDirectory()) return;
            String entryName = entry.getName();
            if (!entryName.startsWith(prefix) || !entryName.endsWith(".class")) return;
            // Inner and anonymous classes cannot be instantiated from a manifest entry.
            if (entryName.contains("$")) return;
            candidates.add(entry);
        });
        candidates.sort(Comparator.comparing(JarEntry::getName));

        for (JarEntry entry : candidates) {
            String entryName = entry.getName();
            IClassPatcher instance = instantiate(jar, entry, entryName, wanted);
            if (instance != null) add(instance, entryName);
        }

        this.dumpRuntimePatches = dumpRuntimePatches;
        if (dumpRuntimePatches) {
            PatchDump.setRuntimeEnabled(true);
            System.out.println("[PETUNIA/PATCH] Runtime patch dump enabled -> "
                    + PatchDump.directory());
        }
    }

    private void add(IClassPatcher instance, String source) {
        String target;
        try {
            target = instance.getTarget();
        } catch (RuntimeException e) {
            System.out.println("[PETUNIA/PATCH] Skipping " + source + ": getTarget() failed: " + e);
            return;
        }
        if (target == null) {
            System.out.println("[PETUNIA/PATCH] Skipping " + source + ": getTarget() returned null");
            return;
        }
        PatcherEntry previous = entries.put(target, new PatcherEntry(target, instance));
        if (previous != null) {
            // Last one still wins, as before, but say so: the shadowed patch is dead code in the
            // real game and that should not pass unnoticed.
            System.out.println("[PETUNIA/PATCH] WARNING duplicate target " + target + ": "
                    + previous.patcher().getClass().getName()
                    + " is shadowed by " + instance.getClass().getName());
        }
    }

    private static IClassPatcher instantiate(JarFile jar, JarEntry entry, String entryName, String wanted) {
        try {
            byte[] bytes;
            try (InputStream in = jar.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            ClassFile cf = new ClassFile(bytes);
            if (cf.interfaces == null) return null;
            boolean isPatcher = false;
            for (int ref : cf.interfaces) {
                if (wanted.equals(cf.cls_name(ref))) {
                    isPatcher = true;
                    break;
                }
            }
            if (!isPatcher) return null;

            String className = entryName.substring(0, entryName.length() - ".class".length()).replace('/', '.');
            return (IClassPatcher) Class.forName(className).getConstructor().newInstance();
        } catch (IOException e) {
            System.err.println("IOException while loading a potential patch: " + entryName);
            e.printStackTrace();
        } catch (ReflectiveOperationException | ClassCastException e) {
            System.err.println("Failed to instantiate patch class " + entryName);
            e.printStackTrace();
        }
        return null;
    }

    /**
     * The discovered patches in target order.
     * <p>
     * Entries carry no raw class bytes until {@link #attachRawBytes} fills them in.
     */
    public List<PatcherEntry> entries() {
        return List.copyOf(entries.values());
    }

    /** @return number of distinct targets, i.e. how many patches can actually run */
    public int size() {
        return entries.size();
    }

    /**
     * Read the unpatched form of every target and attach it, making the entries replayable through
     * {@link PatcherEntry#apply()}.
     * <p>
     * Returns freshly built entries: {@link PatcherEntry} is immutable and {@link #entries()} hands
     * out a snapshot, so a caller that kept an earlier list would not see the bytes.
     *
     * @param source Where the untouched classes are read from
     * @return the entries, all of which carry their raw bytes
     * @throws IOException if reading a target fails
     */
    public List<PatcherEntry> attachRawBytes(PatcherEntry.Source source) throws IOException {
        List<PatcherEntry> loaded = new ArrayList<>(entries.size());
        for (Map.Entry<String, PatcherEntry> e : entries.entrySet()) {
            PatcherEntry entry = e.getValue();
            byte[] bytes = source.read(entry.target());
            PatcherEntry resolved = bytes == null ? entry : entry.withRawBytes(bytes);
            e.setValue(resolved);
            loaded.add(resolved);
        }
        return List.copyOf(loaded);
    }

    /**
     * A {@link PatcherEntry.Source} backed by a class loader.
     * <p>
     * Uses {@link ClassLoader#getResourceAsStream(String)}, which for {@code BerryClassLoader}
     * returns the stored file rather than running the transform pipeline, i.e. the unpatched bytes.
     */
    public static PatcherEntry.Source sourceFor(ClassLoader loader) {
        return internalName -> {
            try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
                return in == null ? null : in.readAllBytes();
            }
        };
    }
}
