package net.mcberry.petunia.patches;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Shared sink for dumping patched classes to disk.
 * <p>
 * Two callers use it:
 * <ul>
 *   <li>{@link PatcherDebugger}, which dumps every patch by re-applying it to a fresh copy of the
 *       target and then exits, so the output is reproducible.</li>
 *   <li>{@link SimplePatchLoader}, which can additionally dump the bytes it actually applies while
 *       the game runs. That output reflects the real class-loading order and can differ from the
 *       debugger's if patches interact or a target is loaded more than once.</li>
 * </ul>
 * Both write into the same directory layout, so the results line up one-to-one.
 *
 * <h2>Properties</h2>
 * <ul>
 *   <li>{@code petunia.dumpPatches=true} - enable the runtime dump</li>
 *   <li>{@code petunia.dumpPatches.dir=<path>} - output directory</li>
 * </ul>
 *
 * <h2>Shared output directory</h2>
 * Both modes write into {@link #directory()}, so a debugger run and a runtime run line up
 * file-for-file and can be diffed directly. Because they produce the same set of names, whichever
 * mode runs second lands on {@link #REPEAT_SUFFIX} beside the first instead of overwriting it.
 * Each caller logs its own mode prefix, so the two populations stay distinguishable.
 *
 * <h2>Threading</h2>
 * Writing happens on a single background thread so that class loading is not blocked by file I/O.
 * {@link #setRuntimeEnabled(boolean)} registers the shutdown hook that flushes pending writes,
 * which is what makes the runtime dump survive a normal game exit. The debugger instead calls
 * {@link #flush()} explicitly before it terminates the JVM.
 */
public final class PatchDump {
    /** Enables dumping the bytes applied during a normal run. */
    public static final String ENABLE_PROPERTY = "petunia.dumpPatches";
    /** Output directory for both dump modes. */
    public static final String DIR_PROPERTY = "petunia.dumpPatches.dir";
    /** Suffix appended when a class is dumped more than once, to keep every version. */
    public static final String REPEAT_SUFFIX = ".dup";

    /** Default output directory, shared by both dump modes. */
    static final String DEFAULT_DIR = "../../patched_classes/";

    private static volatile ExecutorService writer;
    private static volatile boolean closed;

    private PatchDump() {}

    /**
     * The single output directory used by both the debugger and the runtime dump.
     *
     * @return {@link #DIR_PROPERTY} if set, otherwise {@link #DEFAULT_DIR}
     */
    public static String directory() {
        return System.getProperty(DIR_PROPERTY, DEFAULT_DIR);
    }

    /**
     * Whether the runtime dump was requested through {@link #ENABLE_PROPERTY}.
     */
    public static boolean runtimeRequested() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "false"));
    }

    /**
     * Start the background writer and make sure it is flushed when the JVM exits.
     * <p>
     * Called by {@link SimplePatchLoader} only when its runtime dump is enabled, so a normal run
     * allocates nothing.
     */
    public static synchronized void setRuntimeEnabled(boolean enabled) {
        if (!enabled || writer != null) return;
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "petunia-patch-dump");
            t.setDaemon(true);
            return t;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(PatchDump::flush, "petunia-patch-dump-flush"));
    }

    /**
     * Queue one patched class for writing.
     * <p>
     * Must be called on the class-loading thread: the thread name is captured here for the log line.
     * Never throws; a dump failure must not break class loading.
     *
     * @param target internal name of the class, e.g. {@code net/minecraft/SharedConstants}
     * @param bytes  the bytes that are being applied
     * @param dir    output directory
     */
    public static void dump(String target, byte[] bytes, String dir) {
        ExecutorService w = writer;
        if (w == null) return;
        String threadName = Thread.currentThread().getName();
        try {
            w.execute(() -> write(target, bytes, dir, threadName));
        } catch (RuntimeException e) {
            // The executor was shut down between the check and the submit; nothing useful to do.
        }
    }

    /** Write synchronously, on the calling thread. Used by the debugger. */
    public static void writeNow(String target, byte[] bytes, String dir) throws IOException {
        write(target, bytes, dir, Thread.currentThread().getName());
    }

    private static void write(String target, byte[] bytes, String dir, String threadName) {
        try {
            Path out = pathFor(target, dir);
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
            System.out.println("[PETUNIA/PATCHDUMP] " + target + " (" + bytes.length
                    + " bytes) -> " + out + "  [" + threadName + "]");
        } catch (Throwable t) {
            // A failed dump must never take class loading down with it.
            System.err.println("[PETUNIA/PATCHDUMP] Failed to dump " + target + ": " + t);
        }
    }

    /**
     * Resolve the output path for a target, keeping the package layout so the directory can be used
     * as a classpath entry.
     * <p>
     * Because both dump modes share a directory and run in separate JVMs, the in-process set is not
     * enough to detect a collision: the debugger does not know what the previous run wrote. So a
     * candidate is also rejected when the file already exists on disk, and the counter advances
     * until a free name is found. Nothing is ever overwritten.
     */
    private static Path pathFor(String target, String dir) {
        Path candidate = Path.of(dir, target + ".class");
        for (int attempt = 1; !CLOSED_FOR_WRITE.add(candidate) || Files.exists(candidate); attempt++) {
            candidate = Path.of(dir, target + REPEAT_SUFFIX + attempt + ".class");
        }
        return candidate;
    }

    /** Paths already handed out by this JVM, so two writes cannot pick the same name. */
    private static final Set<Path> CLOSED_FOR_WRITE = ConcurrentHashMap.newKeySet();

    /** Wait for queued writes to finish. Safe to call more than once. */
    public static synchronized void flush() {
        ExecutorService w = writer;
        if (w == null || closed) return;
        closed = true;
        w.shutdown();
        try {
            if (!w.awaitTermination(10, TimeUnit.SECONDS))
                System.err.println("[PETUNIA/PATCHDUMP] Timed out flushing pending dumps");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
