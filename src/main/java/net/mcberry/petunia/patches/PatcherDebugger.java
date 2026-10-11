package net.mcberry.petunia.patches;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

/**
 * Debug entry point that applies every {@link IClassPatcher} to a fresh copy of its target Minecraft
 * class, writes the patched byte code to disk and exits, never starting Minecraft.
 * <p>
 * It exists to make patch development fast: run the game once with the trigger argument, then open
 * the dumped classes in a decompiler instead of guessing what the ASM code did. Because the dump
 * uses the same {@link SimplePatchLoader} the game uses, whatever the debugger shows is what the
 * runtime will do.
 *
 * <h2>Output</h2>
 * Files go to {@code ../../patched_classes/} relative to the working directory, or wherever
 * {@code petunia.debugPatches.dir} points. The package layout is preserved, so
 * {@code net/minecraft/SharedConstants} becomes
 * {@code <dir>/net/minecraft/SharedConstants.class}. That keeps the directory usable as a classpath
 * entry, which makes it easy to reproduce a patching bug under a debugger.
 *
 * <h2>Exit code</h2>
 * 0 when every discovered patch was dumped, 1 when anything failed. The process always exits, so a
 * failing dump cannot be mistaken for a successful game start.
 */
public final class PatcherDebugger {
    /** Program argument that triggers the dump. */
    public static final String ARG = "--petunia.debugPatches";
    /** System property that triggers the dump. */
    public static final String PROPERTY = "petunia.debugPatches";
    private PatcherDebugger() {}

    /** @return true if the current invocation asked for a patch dump */
    public static boolean isRequested(String[] args) {
        if (Boolean.parseBoolean(System.getProperty(PROPERTY, "false")))
            return true;
        if (args != null)
            for (String arg : args)
                if (ARG.equals(arg))
                    return true;
        return false;
    }

    /** Directory the dump is written to; shared with the runtime dump. */
    public static String outputDirectory() {
        return PatchDump.directory();
    }

    /**
     * Dumps every discovered patch and terminates the JVM; this method never returns.
     *
     * @param loader Class loader exposing the <em>unpatched</em> Minecraft classes. This is the case
     *               for {@code BerryClassLoader} because resource lookups bypass the transformers.
     * @param jar    Jar to scan for patches, normally the Petunia jar
     * @param pkg    Package holding the patch classes
     */
    public static void run(ClassLoader loader, JarFile jar, String pkg) {
        String outputDir = outputDirectory();
        System.out.println("[PETUNIA/DEBUG] Patch dump requested (arg " + ARG + " or -D" + PROPERTY + ")");
        System.out.println("[PETUNIA/DEBUG] Patch package:  " + pkg);
        System.out.println("[PETUNIA/DEBUG] Output directory: " + new File(outputDir).getAbsolutePath());

        int exitCode = 1;
        try {
            SimplePatchLoader patchLoader = new SimplePatchLoader(jar, pkg, false);
            List<PatcherEntry> entries = patchLoader.attachRawBytes(SimplePatchLoader.sourceFor(loader));
            System.out.println("[PETUNIA/DEBUG] Found " + patchLoader.size() + " patch(es)");

            List<String> failures = new ArrayList<>();
            List<String> stale = new ArrayList<>();
            int dumped = 0;
            int unresolved = 0;
            for (PatcherEntry entry : entries) {
                String patchName = entry.patcher().getClass().getName();
                if (!entry.isLoadable()) {
                    System.out.println("[PETUNIA/DEBUG] SKIP " + entry.target()
                            + " <- " + patchName + ": target class not found");
                    failures.add(entry.target() + ": target class not found on the loader");
                    unresolved++;
                    continue;
                }
                // Guard pass: a patch that no longer fits the target is reported as stale rather
                // than dumped, since its output would be misleading.
                try {
                    if (!entry.validate()) {
                        System.out.println("[PETUNIA/DEBUG] STALE " + entry.target()
                                + " <- " + patchName + ": validate() returned false");
                        stale.add(entry.target() + " <- " + patchName);
                        continue;
                    }
                } catch (Throwable t) {
                    System.out.println("[PETUNIA/DEBUG] STALE " + entry.target()
                            + " <- " + patchName + ": validate() threw");
                    t.printStackTrace(System.out);
                    stale.add(entry.target() + " <- " + patchName + ": " + t);
                    continue;
                }
                try {
                    byte[] patched = entry.apply();
                    PatchDump.writeNow(entry.target(), patched, outputDir);
                    System.out.println("[PETUNIA/DEBUG] " + entry.target()
                            + "  <-  " + patchName
                            + "  (" + entry.rawBytes().length + " -> " + patched.length + " bytes)");
                    dumped++;
                } catch (Throwable t) {
                    System.out.println("[PETUNIA/DEBUG] FAILED " + entry.target()
                            + " <- " + patchName);
                    t.printStackTrace(System.out);
                    failures.add(entry.target() + ": " + t);
                }
            }

            System.out.println("[PETUNIA/DEBUG] Dumped " + dumped + "/" + entries.size() + " class(es)"
                    + (unresolved == 0 ? "" : ", " + unresolved + " target(s) not on the classpath")
                    + (stale.isEmpty() ? "" : ", " + stale.size() + " stale"));

            if (!stale.isEmpty()) {
                System.out.println("[PETUNIA/DEBUG] " + stale.size() + " stale patch(es) - these are outdated:");
                for (String s : stale)
                    System.out.println("[PETUNIA/DEBUG]   - " + s);
            }

            if (failures.isEmpty() && stale.isEmpty()) {
                System.out.println("[PETUNIA/DEBUG] All patchers validated and applied successfully.");
                exitCode = 0;
            } else {
                if (!failures.isEmpty()) {
                    System.out.println("[PETUNIA/DEBUG] " + failures.size() + " problem(s):");
                    for (String failure : failures)
                        System.out.println("[PETUNIA/DEBUG]   - " + failure);
                }
                exitCode = 1;
            }
        } catch (Throwable t) {
            System.out.println("[PETUNIA/DEBUG] Patch dump crashed:");
            t.printStackTrace(System.out);
        }

        // The runtime dump writes through a background queue; since both modes share a directory,
        // an already-loaded class may have queued a write. Flush before terminating the JVM.
        PatchDump.flush();

        System.out.flush();
        System.err.flush();
        System.exit(exitCode);
    }


}
