package org.bxteam.shuttle.patch;

import org.bxteam.shuttle.Shuttle;
import org.bxteam.shuttle.logger.Logger;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class LibraryLoader {
    private static final String LIBRARIES_DIR = "libraries";
    private static final String CACHE_DIR = "cache";
    private static final String LIBRARIES_LIST_RESOURCE = "/META-INF/libraries.list";
    private static final String LIBRARIES_PREFIX = "META-INF/libraries/";
    private static final String PATCH_EXTENSION = ".patch";
    private static final int BUFFER_SIZE = 8192;

    private static final List<String> MAVEN_REPOSITORIES = List.of(
        "https://repo.papermc.io/repository/maven-public/",
        "https://jitpack.io",
        "https://s01.oss.sonatype.org/content/repositories/snapshots/",
        "https://repo1.maven.org/maven2/",
        "https://libraries.minecraft.net/"
    );

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
    }

    public void start(Shuttle.Provider<String> versionProvider) throws IOException {
        try {
            start(versionProvider.get());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Error during library loading process", e);
        }
    }

    public void start(String mcVersion) throws IOException {
        if (mcVersion == null || mcVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("Minecraft version cannot be null or empty");
        }

        Logger.info("Unpacking and linking library jars");

        try {
            createLibrariesDirectory();

            Path currentJar = getCurrentJarPath();
            extractLibrariesFromJar(currentJar);

            Path vanillaBundler = Paths.get(CACHE_DIR, "vanilla-bundler-" + mcVersion + ".jar");
            if (Files.exists(vanillaBundler)) {
                extractLibrariesFromJar(vanillaBundler);
            }
        } catch (Exception e) {
            throw new IOException("Failed to load libraries for version " + mcVersion, e);
        }
    }

    private void createLibrariesDirectory() throws IOException {
        Path librariesDir = Paths.get(LIBRARIES_DIR);
        if (!Files.exists(librariesDir)) {
            Files.createDirectories(librariesDir);
        }
    }

    private Path getCurrentJarPath() throws IOException {
        try {
            return Paths.get(LibraryLoader.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IOException("Failed to get current JAR path", e);
        }
    }

    private void extractLibrariesFromJar(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();

                if (entry.getName().startsWith(LIBRARIES_PREFIX)) {
                    processLibraryEntry(jarFile, entry);
                }
            }
        }
    }

    private void processLibraryEntry(JarFile jarFile, JarEntry entry) throws IOException {
        String relativePath = entry.getName().substring(LIBRARIES_PREFIX.length());
        File extractedFile = new File(LIBRARIES_DIR, relativePath);

        if (entry.isDirectory()) {
            Files.createDirectories(extractedFile.toPath());
        } else if (entry.getName().endsWith(PATCH_EXTENSION)) {
            processPatchEntry(entry);
        } else {
            extractLibraryFile(jarFile, entry, extractedFile);
        }
    }

    private void extractLibraryFile(JarFile jarFile, JarEntry entry, File extractedFile) throws IOException {
        File parentDir = extractedFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        try (InputStream input = jarFile.getInputStream(entry);
             FileOutputStream output = new FileOutputStream(extractedFile)) {
            copyStream(input, output);
        }

        addToClasspath(extractedFile);
    }

    private void processPatchEntry(JarEntry entry) throws IOException {
        try (InputStream inputStream = LibraryLoader.class.getResourceAsStream(LIBRARIES_LIST_RESOURCE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            if (inputStream == null) {
                throw new IOException("Libraries list resource not found: " + LIBRARIES_LIST_RESOURCE);
            }

            String entryDirectoryName = extractDirectoryName(entry.getName());
            boolean foundArtifact = false;
            String line;

            while ((line = reader.readLine()) != null) {
                LibraryInfo libraryInfo = parseLibraryLine(line);
                if (libraryInfo == null) continue;

                String libraryDirectoryName = extractDirectoryName(libraryInfo.jarPath);

                if (entryDirectoryName.equalsIgnoreCase(libraryDirectoryName)) {
                    foundArtifact = true;
                    handleLibraryFromPatch(libraryInfo);
                }
            }

            if (!foundArtifact) {
                String artifactName = extractArtifactName(entry.getName());
                Logger.error("Unable to find library: " + artifactName);
                throw new RuntimeException("Missing library: " + artifactName);
            }
        }
    }

    private String extractDirectoryName(String path) {
        return path.replaceFirst("^" + LIBRARIES_PREFIX, "")
                  .replaceFirst("/[^/]+$", "");
    }

    private String extractArtifactName(String entryName) {
        String[] parts = entryName.split("/");
        String fileName = parts[parts.length - 1];
        return fileName.replace(PATCH_EXTENSION, "");
    }

    private LibraryInfo parseLibraryLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            return null;
        }

        return new LibraryInfo(parts[1], parts[2]);
    }

    private void handleLibraryFromPatch(LibraryInfo libraryInfo) throws IOException {
        File libraryFile = new File(LIBRARIES_DIR, libraryInfo.jarPath);

        if (!libraryFile.exists()) {
            File downloadedFile = downloadLibrary(libraryInfo.jarPath);

            if (downloadedFile == null) {
                throw new IOException("Failed to download missing library: " + libraryInfo.artifact);
            }

            libraryFile = downloadedFile;
        }

        addToClasspath(libraryFile);
    }

    private File downloadLibrary(String artifactPath) {
        for (String repository : MAVEN_REPOSITORIES) {
            try {
                String downloadUrl = repository + artifactPath;
                File downloadedFile = new File(LIBRARIES_DIR, artifactPath);

                if (downloadFile(downloadUrl, downloadedFile)) {
                    return downloadedFile;
                }
            } catch (Exception e) {
                // Continue trying other repositories
            }
        }

        return null;
    }

    private boolean downloadFile(String urlString, File outputFile) {
        try {
            File parentDir = outputFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            URL url = new URL(urlString);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(outputFile)) {
                copyStream(input, output);
            }

            return outputFile.exists() && outputFile.length() > 0;

        } catch (Exception e) {
            if (outputFile.exists()) {
                outputFile.delete();
            }

            return false;
        }
    }

    private void addToClasspath(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            InstrumentationManager.getInstrumentation().appendToSystemClassLoaderSearch(jar);
        }
    }

    private record LibraryInfo(String artifact, String jarPath) { }
}
