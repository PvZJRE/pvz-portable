/*
 * PORT-NOTE (deviation): the version jar's own launch agent (its manifest declares
 * {@code Launcher-Agent-Class}), which makes a plain `java -jar` (and a double click) start the
 * game the way a launcher does. The manifest deliberately carries no {@code Class-Path}: the JVM
 * expands that attribute onto the application class loader of every launch shape (including the
 * launchers that pass their own -cp), and the libraries then being visible to Fabric Loader's
 * game class loader breaks its class-loader split - the game's entrypoint interfaces end up
 * defined twice and every mod entrypoint fails to cast.
 *
 * <p>Instead the manifest carries the custom {@code X-Class-Path} attribute, which only this
 * agent reads: before the main class, it appends the library jars to the system class loader
 * ({@link Instrumentation#appendToSystemClassLoaderSearch}) and rewrites {@code java.class.path}
 * to the same list, which is exactly the shape a launcher's -cp produces. A launcher launch is
 * left untouched: the rewrite only happens when the class path still names this jar alone.
 */
package io.github.pvzjre.provider;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public final class VersionJarAgent {

    /** The appended jars must stay open for the lifetime of the class loader. */
    private static final List<JarFile> OPEN_JARS = new ArrayList<>();

    private VersionJarAgent() {
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) {
        agentmain(agentArguments, instrumentation);
    }

    /** The -jar manifest ({@code Launcher-Agent-Class}) invokes the agentmain method. */
    public static void agentmain(String agentArguments, Instrumentation instrumentation) {
        try {
            File jar = new File(VersionJarAgent.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (!jar.isFile()) {
                return;
            }
            java.util.jar.Manifest manifest;
            try (JarFile jarFile = new JarFile(jar)) {
                manifest = jarFile.getManifest();
            }
            String classPath = manifest == null ? null : manifest.getMainAttributes().getValue("X-Class-Path");
            if (classPath == null || classPath.isBlank()) {
                return;
            }

            String current = System.getProperty("java.class.path", "");
            File currentFile = new File(current);
            // only rewrite the plain -jar shape (the class path names this jar alone): a launcher
            // -cp lists its own entries and is left exactly as the launcher built it
            boolean namesThisJarAlone = !current.contains(File.pathSeparator)
                    && currentFile.getAbsoluteFile().equals(jar.getAbsoluteFile());
            if (!namesThisJarAlone) {
                return;
            }

            File folder = jar.getAbsoluteFile().getParentFile();
            // the entry paths are maven-layout paths; the two layouts that carry the libraries:
            // the installed version folder (../../libraries) and the jpackage app image
            // (app/libraries, beside the jar)
            List<File> bases = List.of(
                    folder,
                    new File(folder, "libraries"),
                    new File(new File(folder, ".."), "../libraries"));
            StringBuilder resolved = new StringBuilder(jar.getAbsolutePath());
            for (String entry : classPath.split(" +")) {
                if (entry.isEmpty()) {
                    continue;
                }
                File library = null;
                for (File base : bases) {
                    File candidate = new File(base, entry);
                    if (candidate.isFile()) {
                        library = candidate;
                        break;
                    }
                }
                if (library == null) {
                    continue; // a natives jar of another operating system
                }
                // the application class loader must see the libraries before the main class:
                // KnotClient and the loader live there
                JarFile libraryJar = new JarFile(library);
                OPEN_JARS.add(libraryJar);
                instrumentation.appendToSystemClassLoaderSearch(libraryJar);
                resolved.append(File.pathSeparatorChar).append(library.getAbsolutePath());
            }
            System.setProperty("java.class.path", resolved.toString());
        } catch (Exception theAny) {
            // never block the launch: whatever the class path holds still stands
        }
    }
}
