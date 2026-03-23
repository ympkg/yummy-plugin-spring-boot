package ym.plugin.springboot;

import ym.api.TaskContext;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Builds a Spring Boot executable JAR with BOOT-INF layout.
 * Modeled after Spring Boot's Packager/BootZipCopyAction implementation.
 *
 * JAR structure:
 *   META-INF/
 *   META-INF/MANIFEST.MF
 *   org/springframework/boot/loader/...     (loader classes at root)
 *   BOOT-INF/
 *   BOOT-INF/classes/...                    (app classes + resources)
 *   BOOT-INF/lib/*.jar                      (dependency JARs, STORED)
 *   BOOT-INF/classpath.idx                  (classpath index)
 */
public class SpringBootJarTask {

    private static final String YM_DIR = ".ym";
    private static final String MAVEN_CACHE_DIR = "maven";

    private final SpringBootExtension config;
    private final Set<String> writtenEntries = new HashSet<>();

    public SpringBootJarTask(SpringBootExtension config) {
        this.config = config;
    }

    public void execute(TaskContext ctx) {
        try {
            doExecute(ctx);
        } catch (Exception e) {
            throw new RuntimeException("Spring Boot JAR packaging failed: " + e.getMessage(), e);
        }
    }

    private void doExecute(TaskContext ctx) throws Exception {
        var project = ctx.project();
        var classesDir = ctx.classesDir();
        var resourcesDir = ctx.resourcesDir();
        var runtimeJars = ctx.runtimeClasspath();
        var mainClass = config.mainClass().get();
        var loaderVersion = config.loaderVersion().get();

        if (mainClass == null || mainClass.isEmpty()) {
            throw new IllegalStateException("mainClass is required for Spring Boot packaging. " +
                    "Set it in ym.json 'main' field or via SpringBootPlugin.mainClass()");
        }

        var version = project.version();
        var jarName = project.name() + "-" + version + ".jar";
        var releaseDir = project.projectDir().resolve("out").resolve("release");
        Files.createDirectories(releaseDir);
        var outputJar = releaseDir.resolve(jarName);

        var loaderJar = findLoaderJar(runtimeJars, loaderVersion);

        try (var fos = new FileOutputStream(outputJar.toFile());
             var bos = new BufferedOutputStream(fos);
             var zos = new ZipOutputStream(bos)) {

            // 1. META-INF/ directory + MANIFEST.MF (must be first)
            writeDirectory(zos, "META-INF/");
            writeManifest(zos, mainClass, loaderVersion);

            // 2. Loader classes at JAR root (extracted from spring-boot-loader JAR)
            if (loaderJar != null) {
                extractLoaderEntries(zos, loaderJar);
            }

            // 3. BOOT-INF/ directory structure
            writeDirectory(zos, "BOOT-INF/");
            writeDirectory(zos, "BOOT-INF/classes/");
            writeDirectory(zos, "BOOT-INF/lib/");

            // 4. BOOT-INF/classes/ — app classes + resources
            //    ym copies src/main/resources/ to out/classes/ during compilation
            if (Files.exists(classesDir)) {
                addDirectoryToJar(zos, classesDir, "BOOT-INF/classes/");
            }
            // Supplement with resources not copied by ym
            if (Files.exists(resourcesDir)) {
                addDirectoryToJar(zos, resourcesDir, "BOOT-INF/classes/");
            }

            // 5. BOOT-INF/lib/ — all dependency JARs + workspace module directories
            // Runtime classpath contains both:
            //   - JAR files (external dependencies from Maven cache)
            //   - Directories (workspace modules compiled by ym to out/classes/)
            // For directories: package them into a JAR on-the-fly, then add as STORED.
            // This matches Spring Boot Gradle plugin behavior.
            var classpathEntries = new ArrayList<String>();
            System.err.println("[spring-boot] Runtime classpath (" + runtimeJars.size() + " entries):");
            for (var p : runtimeJars) {
                System.err.println("[spring-boot]   " + (Files.isDirectory(p) ? "DIR " : "JAR ") + p);
            }
            for (var jar : runtimeJars) {
                if (!Files.exists(jar)) continue;

                if (Files.isDirectory(jar)) {
                    // Workspace module directory — package as a JAR
                    var dirName = inferModuleName(jar);
                    var entryName = "BOOT-INF/lib/" + dirName + ".jar";
                    addDirectoryAsStoredJar(zos, jar, entryName);
                    classpathEntries.add("- \"" + entryName + "\"");
                    continue;
                }

                var jarFileName = jar.getFileName().toString();
                if (!jarFileName.endsWith(".jar")) continue;
                // Exclude spring-boot-loader itself
                if (jarFileName.contains("spring-boot-loader") && !jarFileName.contains("tools")) continue;
                // Exclude plugin JARs
                if (jarFileName.contains("yummy-plugin")) continue;
                // Exclude empty thin JARs (workspace modules already handled as directories)
                if (jarFileName.contains(".thin.") && Files.size(jar) < 100) continue;

                var entryName = "BOOT-INF/lib/" + jarFileName;
                addStoredJar(zos, jar, entryName);
                classpathEntries.add("- \"" + entryName + "\"");
            }

            // 6. BOOT-INF/classpath.idx
            writeClasspathIdx(zos, classpathEntries);
        }

        System.err.printf("[spring-boot] Packaged %s (%s)%n", jarName, formatSize(Files.size(outputJar)));
    }

    /**
     * Write a directory entry. Spring Boot loader requires explicit directory entries.
     */
    private void writeDirectory(ZipOutputStream zos, String name) throws IOException {
        if (!name.endsWith("/")) name += "/";
        if (!writtenEntries.add(name)) return;
        var entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(0);
        entry.setCompressedSize(0);
        entry.setCrc(0);
        zos.putNextEntry(entry);
        zos.closeEntry();
    }

    /**
     * Write MANIFEST.MF using JDK's Manifest class for correct formatting.
     */
    private void writeManifest(ZipOutputStream zos, String mainClass, String loaderVersion) throws IOException {
        var manifest = new Manifest();
        var attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.put(Attributes.Name.MAIN_CLASS, "org.springframework.boot.loader.launch.JarLauncher");
        attrs.putValue("Start-Class", mainClass);
        attrs.putValue("Spring-Boot-Version", loaderVersion);
        attrs.putValue("Spring-Boot-Classes", "BOOT-INF/classes/");
        attrs.putValue("Spring-Boot-Lib", "BOOT-INF/lib/");
        attrs.putValue("Spring-Boot-Classpath-Index", "BOOT-INF/classpath.idx");
        attrs.putValue("Built-By", "ym");

        var entry = new ZipEntry("META-INF/MANIFEST.MF");
        zos.putNextEntry(entry);
        manifest.write(zos);
        zos.closeEntry();
        writtenEntries.add("META-INF/MANIFEST.MF");
    }

    /**
     * Extract loader classes AND META-INF/services/ from spring-boot-loader JAR.
     * Loader classes go to JAR root so JVM's default classloader can find JarLauncher.
     */
    private void extractLoaderEntries(ZipOutputStream zos, Path loaderJar) throws IOException {
        try (var jis = new JarInputStream(new BufferedInputStream(new FileInputStream(loaderJar.toFile())))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                var name = entry.getName();
                // Skip manifest (we write our own) and META-INF/ directory itself
                if (name.equals("META-INF/MANIFEST.MF") || name.equals("META-INF/")) continue;

                // Extract: loader classes + META-INF/services/ (SPI declarations)
                boolean shouldExtract = name.startsWith("org/springframework/boot/loader/")
                        || name.startsWith("META-INF/services/");
                if (!shouldExtract) continue;

                if (writtenEntries.add(name)) {
                    if (entry.isDirectory()) {
                        writeDirectory(zos, name);
                    } else {
                        var newEntry = new ZipEntry(name);
                        zos.putNextEntry(newEntry);
                        jis.transferTo(zos);
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    /**
     * Add directory contents to JAR with a path prefix.
     * Creates parent directory entries as needed.
     */
    private void addDirectoryToJar(ZipOutputStream zos, Path dir, String prefix) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                if (!d.equals(dir)) {
                    var relative = dir.relativize(d).toString().replace('\\', '/');
                    writeDirectory(zos, prefix + relative + "/");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var relative = dir.relativize(file).toString().replace('\\', '/');
                var entryName = prefix + relative;
                if (writtenEntries.add(entryName)) {
                    var entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);
                    Files.copy(file, zos);
                    zos.closeEntry();
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Add a dependency JAR as STORED (uncompressed) entry in BOOT-INF/lib/.
     * Spring Boot's NestedJarFile needs uncompressed nested JARs to read by offset.
     * CRC/size must be pre-set to avoid Data Descriptor generation.
     */
    private void addStoredJar(ZipOutputStream zos, Path jarPath, String entryName) throws IOException {
        var data = Files.readAllBytes(jarPath);
        var entry = new ZipEntry(entryName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);

        var crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());

        writtenEntries.add(entryName);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    /**
     * Write BOOT-INF/classpath.idx in Spring Boot's YAML-compatible format.
     * Each line: - "BOOT-INF/lib/xxx.jar"
     */
    private void writeClasspathIdx(ZipOutputStream zos, List<String> entries) throws IOException {
        var entry = new ZipEntry("BOOT-INF/classpath.idx");
        writtenEntries.add("BOOT-INF/classpath.idx");
        zos.putNextEntry(entry);
        var writer = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));
        for (var line : entries) {
            writer.write(line);
            writer.write("\n");
        }
        writer.flush();
        zos.closeEntry();
    }

    /**
     * Package a directory (workspace module classes + resources) into a STORED JAR entry.
     * Creates an in-memory JAR from the directory tree, then writes it as a STORED entry
     * so Spring Boot's NestedJarFile can read it.
     */
    private void addDirectoryAsStoredJar(ZipOutputStream zos, Path dir, String entryName) throws IOException {
        var baos = new ByteArrayOutputStream();
        try (var jarOs = new JarOutputStream(baos)) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    if (!d.equals(dir)) {
                        var relative = dir.relativize(d).toString().replace('\\', '/') + "/";
                        var dirEntry = new ZipEntry(relative);
                        jarOs.putNextEntry(dirEntry);
                        jarOs.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    var relative = dir.relativize(file).toString().replace('\\', '/');
                    var fileEntry = new ZipEntry(relative);
                    jarOs.putNextEntry(fileEntry);
                    Files.copy(file, jarOs);
                    jarOs.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        var data = baos.toByteArray();
        var entry = new ZipEntry(entryName);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        var crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());

        writtenEntries.add(entryName);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    /**
     * Infer a module name from a workspace module's classes directory path.
     * E.g., /path/to/libs/core/omnirepo-auth/out/classes → omnirepo-auth
     */
    private String inferModuleName(Path classesDir) {
        // Walk up from "out/classes" to find the module directory
        var current = classesDir;
        for (int i = 0; i < 5 && current != null; i++) {
            if (current.getFileName() != null && current.getFileName().toString().equals("out")) {
                var parent = current.getParent();
                if (parent != null && parent.getFileName() != null) {
                    return parent.getFileName().toString();
                }
            }
            current = current.getParent();
        }
        // Fallback: use the directory name itself
        return classesDir.getFileName().toString();
    }

    /**
     * Find spring-boot-loader JAR from classpath or ym Maven cache.
     * Loader is declared as a dependency of this plugin, resolved by ym.
     */
    private Path findLoaderJar(java.util.List<Path> classpath, String version) {
        for (var jar : classpath) {
            var name = jar.getFileName().toString();
            if (name.startsWith("spring-boot-loader-") && !name.contains("tools") && name.endsWith(".jar")) {
                return jar;
            }
        }
        var home = System.getProperty("user.home", ".");
        var cached = Path.of(home, YM_DIR, MAVEN_CACHE_DIR,
                "org.springframework.boot", "spring-boot-loader", version,
                "spring-boot-loader-" + version + ".jar");
        if (Files.exists(cached)) return cached;

        System.err.println("[spring-boot] ERROR: spring-boot-loader-" + version + ".jar not found.");
        return null;
    }

    private String formatSize(long bytes) {
        if (bytes >= 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.0f KB", bytes / 1024.0);
    }
}
