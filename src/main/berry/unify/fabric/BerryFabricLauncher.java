package berry.unify.fabric;

import java.io.*;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.jar.Manifest;

import berry.loader.BerryClassLoader;
import org.apache.commons.lang3.NotImplementedException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.spongepowered.asm.mixin.Mixins;

import com.google.gson.JsonParser;

import berry.api.mixins.BerryMixinService;
import berry.api.asm.ClassFile;
import berry.loader.BerryClassTransformer;
import berry.loader.BerryLoader;
import berry.utils.Graph;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.game.minecraft.McVersion;
import net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider;
import net.fabricmc.loader.impl.game.minecraft.patch.EntrypointPatch;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.transformer.FabricTransformer;
import net.fabricmc.loader.impl.util.Arguments;

public class BerryFabricLauncher extends FabricLauncherBase {
    private final EnvType envtype;
    private final MinecraftGameProvider mgp;
    public static BufferedReader mapAWs (BufferedReader orig) {
        try {
            StringBuilder result = new StringBuilder ();
            // First line: replace intermediary with named
            result.append (orig.readLine () .replace ("intermediary", "named"));
            result.append ('\n');
            // Then: replace the lines with BerryFabricLauncher.replace
            String li;
            while ((li = orig.readLine ()) != null) {
                result.append (BerryFabricLauncher.replace (li));
                result.append ('\n');
            }
            return new BufferedReader (new StringReader (result.toString ()));
        } catch (IOException e) {
            return orig;
        }
    }
    public BerryFabricLauncher () {
        super ();

        EnvType env;
        if (BerryLoader.getSide () .equals ("CLIENT")) env = EnvType.CLIENT;
        else env = EnvType.SERVER;
        envtype = env;

        MinecraftGameProvider provider = new MinecraftGameProvider ();
        this.mgp = provider;

        mapinit ();
        var graph = BerryClassTransformer.instance () .all.graph;
        BerryClassTransformer.ByteCodeTransformer awreader = (loader, name, clazz, domain, code) -> {
            if (name.equals ("net/fabricmc/loader/impl/lib/accesswidener/AccessWidenerReader")) {
                ClassReader cr = new ClassReader (code);
                ClassNode cn = new ClassNode ();
                cr.accept (cn, 0);
                MethodNode m = null;
                for (var n : cn.methods) {
                    if (n.name.equals ("read") && n.desc.contains ("BufferedReader")) {
                        m = n;
                    }
                }
                if (m != null) {
                    /* instructions:
                     * aload_1
                     * invokestatic
                     * astore_1
                     */
                    var iter = m.instructions.iterator ();
                    iter.add (new VarInsnNode (Opcodes.ALOAD, 1));
                    iter.add (new MethodInsnNode (Opcodes.INVOKESTATIC, "berry/unify/fabric/BerryFabricLauncher", "mapAWs", "(Ljava/io/BufferedReader;)Ljava/io/BufferedReader;"));
                    iter.add (new VarInsnNode (Opcodes.ASTORE, 1));
                }
                ClassWriter cw = new ClassWriter (0);
                cn.accept (cw);
                return cw.toByteArray ();
            } else return code;
        };
        graph.addVertex (new Graph.Vertex ("berry::transawr", awreader));
        var remap = BerryClassTransformer.instance () .remapper.graph;
        BerryClassTransformer.ByteCodeTransformer trans = (loader, name, clazz, domain, code) -> remap (name, code);
        var vtrans = new Graph.Vertex ("berry::fabric", trans);
        remap.addVertex (vtrans);
        var vremap = graph.getVertices () .get ("berry::remap");
        BerryClassTransformer.ByteCodeTransformer pther = (loader, name, clazz, domain, code) -> patch (name, code);
        var vpatch = new Graph.Vertex ("berry::fabricpatch", pther);
        graph.addVertex (vpatch);
        graph.addEdge (null, vpatch, vremap, null);
        trans = (loader, name, clazz, domain, code) -> {
            if (code == null) return code;
            if (name.startsWith ("net/fabricmc/loader/")) return code;
            if (name.startsWith ("net/fabricmc/api/")) return code;
            return FabricTransformer.transform (BerryLoader.isDevelopment (), envtype, name.replace ('/', '.'), code);
        };
        vtrans = new Graph.Vertex ("berry::fabrictrans", trans);
        graph.addVertex (vtrans);
        graph.addEdge (null, vtrans, vpatch, null);

        var loader = FabricLoaderImpl.INSTANCE;
        loader.setGameProvider (provider);
        // Used AT
        provider.setupLogHandler (this, false);

        McVersion.Builder builder = new McVersion.Builder ();
        String vers;
        var is = this.getClass () .getClassLoader () .getResourceAsStream ("version.json");
        InputStreamReader reader = new InputStreamReader (is);
        var obj = JsonParser.parseReader (reader) .getAsJsonObject ();
        vers = obj.get ("id") .getAsString ();
        builder.setNameAndRelease (vers);
        provider.versionData = builder.build ();

        Arguments args = new Arguments ();
        args.parse (BerryLoader.getArgs ());
        provider.arguments = args;

        loader.setGameDir (Path.of (BerryLoader.getGameDirectory ()));

        loader.load (); loader.freeze ();
        loader.loadAccessWideners ();
        BerryLoader.preloaders.add (cl -> patch ());
        BerryLoader.preloaders.add (cl -> {
            Map <String, ModContainerImpl> configToModMap = new HashMap <> ();
            for (ModContainerImpl mod : loader.getModsInternal ()) {
                for (String config : mod.getMetadata () .getMixinConfigs (env)) {
                    ModContainerImpl prev = configToModMap.putIfAbsent (config, mod);
                    if (prev != null) throw new RuntimeException (
                        String.format ("Non-unique Mixin config name %s used by the mods %s and %s", config, prev.getMetadata () .getId (), mod.getMetadata () .getId ()));
                    try {
                        // get refmap
                        var cfg = this.getClass () .getClassLoader () .getResourceAsStream (config);
                        var cfgobj = JsonParser.parseReader (new InputStreamReader (cfg)) .getAsJsonObject ();
                        var refmap = cfgobj.get ("refmap");
                        if (refmap != null) {
                            BerryClassLoader.getInstance () .controlResourceStream (
                                refmap.getAsString (),
                                orig -> {
                                    try {
                                        var content = orig.readAllBytes ();
                                        orig.close ();
                                        return new ByteArrayInputStream (replace (new String (content)) .getBytes ());
                                    } catch (IOException e) {
                                        return null;
                                    }
                                }
                            );
                        }
                        Mixins.addConfiguration (config);
                    } catch (Throwable t) {
                        throw new RuntimeException (String.format ("Error parsing or using Mixin config %s for mod %s", config, mod.getMetadata () .getId ()), t);
                    }
                }
            }
        });
        BerryLoader.preloaders.add (cl -> prelaunch ());
    }
    private void prelaunch () {
        FabricLoaderImpl.INSTANCE.invokeEntrypoints ("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
    }
    private static final Set <String> loaded = new HashSet <> ();
    private static final Map <String, String> map = new HashMap <> ();
    private static record ClMap (String deobf, Map <String, String> fields, Map <String, String> methods) {
        public ClMap (String deobf) {
            this (deobf, new HashMap <> (), new HashMap <> ());
        }
    }
    private static void mapinit () {
        String mapname = "";
        if (BerryLoader.isDevelopment ()) mapname = "../";
        mapname += BerryLoader.getSide () .toLowerCase () + ".tsrg";
        Map <String, ClMap> srgm = new HashMap <> ();
        try (InputStream stream = new FileInputStream (mapname); Scanner scanner = new Scanner (stream)) {
            ClMap cur = null;
            while (scanner.hasNextLine ()) {
                String line = scanner.nextLine () .replace ('.', '/');
                if (line.startsWith ("\t")) {
                    var t = line.strip () .split (" ");
                    if (t.length == 2) cur.fields.put (t [0], t [1]);
                    else cur.methods.put (t [0] + t [1], t [2]);
                } else {
                    var cls = line.strip () .split (" ");
                    srgm.put (cls [0], cur = new ClMap (cls [1]));
                }
            }
        } catch (IOException e) {
            throw new RuntimeException (e);
        }
        try (
            InputStream tin = BerryFabricLauncher.class.getClassLoader () .getResourceAsStream ("mappings/mappings.tiny");
            Scanner scanner = new Scanner (tin);
        ) {
            while (scanner.hasNextLine ()) {
                String inline = scanner.nextLine () .strip ();
                if (inline.startsWith ("CLASS")) {
                    var t = inline.split ("\t");
                    if (srgm.containsKey (t [1])) {
                        addmap (t [2], srgm.get (t [1]) .deobf);
                        var fro = t [2] .split ("\\$");
                        var to = srgm.get (t [1]) .deobf.split ("\\$");
                        if (fro.length > 1) {
                            int i;
                            for (i=1; i<fro.length; i++) {
                                if (fro [i] .equals (to [i])) continue;
                                addmap (fro [i], to [i]);
                            }
                        }
                    }
                } else if (inline.startsWith ("FIELD")) {
                    var t = inline.split ("\t");
                    if (srgm.containsKey (t [1])) {
                        addmap (t [4], srgm.get (t [1]) .fields.get (t [3]));
                    }
                } else if (inline.startsWith ("METHOD")) {
                    var t = inline.split ("\t");
                    if (srgm.containsKey (t [1])) {
                        addmap (t [4], srgm.get (t [1]) .methods.get (t [3] + t [2]));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException (e);
        }
    }
    private static void addmap (String from, String to) {
        map.put (from, to);
        if (from.startsWith ("net")) {
            from = from.replace ('/', '.');
            to = to.replace ('/', '.');
        } else {
            char c;
            c = from.charAt (0); c -= 'a' - 'A'; from = c + from.substring (1, from.length ());
            c = to.charAt (0); c -= 'a' - 'A'; to = c + to.substring (1, to.length ());
        }
        map.put (from, to);
    }
    private static final List <String> prefixes = List.of (
        "net/minecraft/class", "field", "method", "comp",
        "net.minecraft.class", "Field", "Method", "Comp",
        "class"
    );
    public static String replace (String original) {
        return replace (original, false);
    }
    public static String replace (String original, boolean debug) {
        // TODO: optimization?
        // fku java.util.regex i hate u
        int state = 0;
        String cur = "";
        StringBuilder result = new StringBuilder ();
        int i; for (i=0; i<original.length(); i++) {
            char c = original.charAt (i);
            switch (state) {
                case 0:
                    if (c == '_') {
                        // state = 1;
                        for (String suffix : prefixes) {
                            if (cur.endsWith (suffix)) {
                                String pre = cur.substring (0, cur.length () - suffix.length ());
                                result.append (pre);
                                if (debug) System.err.println ("Pref=" + pre + " suff=" + suffix);
                                cur = suffix;
                                state = 1;
                                break;
                            }
                        }
                    }
                    cur += c;
                    break;
                case 1:
                    if (c >= '0' && c <= '9') cur += c;
                    else if (c == '$' && cur.startsWith ("net")) {
                        state = 2;
                        cur += c;
                    } else {                                                             
                        result.append (map.getOrDefault (cur, cur));
                        cur = "" + c; state = 0;
                    }
                    break;
                case 2:
                    cur += c;
                    if (c >= '0' && c <= '9') state = 1;
            }
        }
        result.append (map.getOrDefault (cur, cur));
        return result.toString ();
    }
    public byte[] remap (String name, byte[] code) throws IOException {
        if (code == null) return code;
        try {
            ClassFile cf = new ClassFile (code);
            for (var constant : cf.constants) {
                if (constant == null) continue;
                if (constant.type == 1) {
                    String f = new String (constant.data);
                    String d = replace (f);
                    constant.data = d.getBytes ();
                }
            }
            // Add class to loaded class list, although it's not loaded yet :\
            loaded.add (name.replace (".", "/"));
            return cf.get ();
        } catch (Exception e) {
            System.err.println ("Error while transforming class " + name);
            e.printStackTrace ();
            return null;
        }
    }
    // Inject entrypoints
    private final Map <String, ClassNode> patched = new HashMap <> ();
    private void patch () {
        Function <String, ClassNode> source = (str) -> {
            str = str.replace ('.', '/');
            try {
                return BerryMixinService.getClassNode (str, 0);
            } catch (IOException e) {
                throw new RuntimeException (e);
            }
        };
        Consumer <ClassNode> emitter = (node) -> patched.put (node.name.replace ('.', '/'), node);
        var patcher = new EntrypointPatch (mgp);
        patcher.process (this, source, emitter);
    }
    public byte[] patch (String name, byte[] code) {
        name = name.replace ('.', '/'); // Probably useless?
        if (patched.containsKey (name)) {
            ClassWriter writer = new ClassWriter (0);
            ClassNode node = patched.get (name);
            node.accept (writer);
            return writer.toByteArray ();
        }
        return code;
    }
    @Override
	public String getTargetNamespace () {
        return "named";
    }
    @Override
    public boolean isClassLoaded (String name) {
        return loaded.contains (name.replace (".", "/"));
    }
    @Override
    public byte[] getClassByteArray (String name, boolean runTransformers) throws IOException {
        throw new NotImplementedException ("Method is not supported");
    }
    @Override
    public EnvType getEnvironmentType () {
        return this.envtype;
    }
    @Override
    public Class <?> loadIntoTarget (String name) throws ClassNotFoundException {
        return Class.forName (name);
    }
    @Override
    public ClassLoader getTargetClassLoader () {
        return this.getClass () .getClassLoader ();
    }
    @Override
    public boolean isDevelopment () {
        // remapping usage
        return true;
    }
    @Override
    public String getEntrypoint () {
        return BerryLoader.getEntrypoint ();
    }
    @Override
    public List <Path> getClassPath () {
        throw new NotImplementedException ("Method is not supported");
    }
    @Override
    public void addToClassPath (Path path, String... allowedPrefixes) {
        BerryClassLoader.getInstance () .appendToClassPathForInstrumentation (path.toString ());
    }
    @Override
    public InputStream getResourceAsStream (String name) {
        return this.getClass () .getClassLoader () .getResourceAsStream (name);
    }
    @Override
    public void setValidParentClassPath (Collection <Path> paths) {
        // throw new NotImplementedException ("Method is not supported");
    }
    @Override
    public void setAllowedPrefixes (Path path, String... prefixes) {
        // throw new NotImplementedException ("Method is not supported");
    }
    @Override
	public Manifest getManifest (Path originPath) {
        throw new NotImplementedException ("Method is not supported");
	}
}
