import java.nio.file.Path

plugins {
    id("net.mcberry.stem") version "1.0.1"
    id("maven-publish")
}

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "Fabric Maven"
        url = uri("https://maven.fabricmc.net/")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

val berry_version: String by project
val fabric_loader_version: String by project
val jetbrains_annotations_version: String by project
dependencies {
    compileOnly("net.mcberry:berry:${berry_version}")
    compileOnly("net.fabricmc:fabric-loader:${fabric_loader_version}")
    compileOnly("org.jetbrains:annotations:${jetbrains_annotations_version}")
}

val petunia_version: String by project
tasks.jar {
    manifest {
        attributes (
            "Petunia-Version" to petunia_version,
            "Berry-Base-Mod" to "net.mcberry.petunia.PetuniaInitializer",
            "Berry-Base-Mod-Name" to "petunia"
        )
    }
}

val minecraft_version: String by project
stem {
    minecraftVersion(minecraft_version)

    playerName("BerryDev")

    projectMod {
        setArchiveTask("jar")
    }

    externalMod {
        setDependency("net.mcberry:berry:${berry_version}")
    }

    transformArgs { jvmArgs, programArgs, classpath, mainClass, runDir ->
        jvmArgs += "-Dberry.indev=true"

        val side: String
        var finalMainClass = mainClass
        if (mainClass == "net.minecraft.bundler.Main") {
            jvmArgs += "-Dberry.side=SERVER"
            jvmArgs += "-DbundlerRepoDir=" + gradle.gradleUserHomeDir.resolve("caches").resolve("stem_mcdev").resolve("server_libraries")
            programArgs.add("--nogui")
            finalMainClass = "net.minecraft.server.Main"
            side = "server"
        } else {
            side = "client"
        }

        classpath += File(runDir, "mods/berry-${berry_version}.jar").absoluteFile
        jvmArgs += "-Dberry.main=$finalMainClass"

        // Both dump modes write into one directory. -PdumpDir overrides the default; the value is
        // also handed to the game, so resolve it here once and keep the two in sync.
        val dumpDirName = (project.findProperty("dumpDir")
                ?: project.findProperty("petunia.dumpPatches.dir")
                ?: project.findProperty("petunia.debugPatches.dir"))?.toString()
                ?: "../../patched_classes/"
        // The game resolves this relative to runDir, where the javaexec working directory is set.
        // File(File, String) does not treat an absolute child as absolute the way Path.resolve does,
        // so check that case explicitly.
        val dumpDirSpec = Path.of(dumpDirName)
        val dumpDir = (if (dumpDirSpec.isAbsolute) dumpDirSpec else runDir.toPath().resolve(dumpDirSpec))
                .toFile().canonicalFile
        if (dumpDir.exists()) {
            if (dumpDir.deleteRecursively()) println("[Petunia] Cleared patch dump: $dumpDir")
            else logger.warn("[Petunia] Could not fully clear patch dump: $dumpDir")
        }

        // Patch-dump entry: ./gradlew runClient -Ppetunia.debugPatches
        // PatcherDebugger dumps every patched class and exits before the game starts.
        if (project.hasProperty("petunia.debugPatches")) {
            programArgs.add("--petunia.debugPatches")
        }
        // Runtime dump: ./gradlew runClient -Ppetunia.dumpPatches
        // Keeps the game running and records every class SimplePatchLoader actually patches.
        if (project.hasProperty("petunia.dumpPatches")) {
            jvmArgs += "-Dpetunia.dumpPatches=true"
        }
        if (dumpDirName != "../../patched_classes/") {
            jvmArgs += "-Dpetunia.dumpPatches.dir=$dumpDirName"
        }

        "net.mcberry.berry.loader.BerryLoaderMain"
    }
}

group = "net.mcberry"
version = petunia_version
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
