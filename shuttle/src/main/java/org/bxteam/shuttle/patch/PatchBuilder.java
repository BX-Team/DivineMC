package org.bxteam.shuttle.patch;

import io.sigpipe.jbsdiff.InvalidHeaderException;
import io.sigpipe.jbsdiff.Patch;
import org.apache.commons.compress.compressors.CompressorException;
import org.bxteam.shuttle.Shuttle;
import org.bxteam.shuttle.logger.Logger;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class PatchBuilder {
    private static final String CACHE_DIR = "cache";
    private static final String VERSIONS_DIR = "versions";
    private static final String META_INF_PREFIX = "/META-INF/";
    private static final int BUFFER_SIZE = 8192;

    private static String readResourceField(int index, String resourceName) {
        final String resourcePath = META_INF_PREFIX + resourceName;

        try (InputStream inputStream = PatchBuilder.class.getResourceAsStream(resourcePath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {

            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            String line = reader.readLine();
            if (line == null) {
                throw new IOException("Empty resource file: " + resourcePath);
            }

            String[] parts = line.split("\\s+");
            if (parts.length <= index) {
                throw new IOException("Invalid resource format or index out of bounds: " + resourcePath);
            }

            return parts[index].trim();

        } catch (IOException e) {
            throw new RuntimeException("Unable to read " + resourceName + " at index " + index, e);
        }
    }

    public static String computeFileSha256(File file) throws NoSuchAlgorithmException, IOException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (FileInputStream fis = new FileInputStream(file);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = bis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hashBytes = digest.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : hashBytes) {
            hexString.append(String.format("%02x", b));
        }

        return hexString.toString();
    }

    public void start(Shuttle.Provider<String> versionProvider) throws IOException {
        try {
            start(versionProvider.get());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Error during patch building process", e);
        }
    }

    public void start(String mcVersion) throws IOException {
        if (mcVersion == null || mcVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("Minecraft version cannot be null or empty");
        }

        Logger.info("Loading Minecraft version " + mcVersion);

        try {
            createDirectories();

            String sha256Hash = readResourceField(0, "download-context");
            String vanillaUrl = readResourceField(1, "download-context");

            Path vanillaBundler = downloadVanillaBundler(mcVersion, vanillaUrl, sha256Hash);
            String patchedJarName = extractPatchedJarName();

            applyPatches(mcVersion, vanillaBundler, patchedJarName);
        } catch (Exception e) {
            throw new IOException("Failed to build patched jar for version " + mcVersion, e);
        }
    }

    private void createDirectories() throws IOException {
        Files.createDirectories(Paths.get(VERSIONS_DIR));
        Files.createDirectories(Paths.get(CACHE_DIR));
    }

    private Path downloadVanillaBundler(String mcVersion, String vanillaUrl, String expectedSha256) throws IOException {
        Path vanillaBundler = Paths.get(CACHE_DIR, "vanilla-bundler-" + mcVersion + ".jar");

        boolean needsDownload = !Files.exists(vanillaBundler);

        if (!needsDownload) {
            try {
                String actualSha256 = computeFileSha256(vanillaBundler.toFile());
                needsDownload = !expectedSha256.equals(actualSha256);
                if (needsDownload) {
                    Logger.info("SHA-256 mismatch, re-downloading vanilla jar");
                }
            } catch (NoSuchAlgorithmException e) {
                throw new IOException("SHA-256 algorithm not available", e);
            }
        }

        if (needsDownload) {
            Logger.info("Downloading vanilla jar...");
            downloadFile(vanillaUrl, vanillaBundler);
        }

        return vanillaBundler;
    }

    /**
     * Downloads a file from a URL.
     */
    private void downloadFile(String urlString, Path outputPath) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(60000);

        try (BufferedInputStream in = new BufferedInputStream(connection.getInputStream());
             FileOutputStream fos = new FileOutputStream(outputPath.toFile());
             BufferedOutputStream out = new BufferedOutputStream(fos)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String extractPatchedJarName() {
        String versionListEntry = readResourceField(2, "versions.list");
        String[] parts = versionListEntry.split("/");
        if (parts.length < 2) {
            throw new RuntimeException("Invalid versions.list format");
        }
        return parts[1];
    }

    private void applyPatches(String mcVersion, Path vanillaBundler, String patchedJarName) throws IOException {
        Logger.info("Applying patches...");

        Path vanillaJar = extractVanillaJar(mcVersion, vanillaBundler);

        File patchFile = extractPatchFile(mcVersion);

        Path outputJar = Paths.get(VERSIONS_DIR, mcVersion, patchedJarName);
        Files.createDirectories(outputJar.getParent());

        try {
            applyPatch(vanillaJar.toFile(), patchFile, outputJar.toFile());
            addToClasspath(outputJar.toFile());
        } catch (Exception e) {
            throw new IOException("Failed to apply patch", e);
        }
    }

    private Path extractVanillaJar(String mcVersion, Path vanillaBundler) throws IOException {
        Path vanillaJar = Paths.get(CACHE_DIR, "vanilla-" + mcVersion + ".jar");

        try (JarFile jarFile = new JarFile(vanillaBundler.toFile())) {
            JarEntry entry = jarFile.getJarEntry("META-INF/versions/" + mcVersion + "/server-" + mcVersion + ".jar");

            if (entry == null) {
                throw new IOException("Vanilla jar entry not found in bundler for version " + mcVersion);
            }

            try (InputStream inputStream = jarFile.getInputStream(entry)) {
                Files.copy(inputStream, vanillaJar, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        return vanillaJar;
    }

    private File extractPatchFile(String mcVersion) throws IOException {
        String resourcePath = "/META-INF/versions/" + mcVersion + "/server-" + mcVersion + ".jar.patch";
        return extractResourceToFile(resourcePath, CACHE_DIR);
    }

    private File extractResourceToFile(String resourcePath, String outputDir) throws IOException {
        try (InputStream resourceStream = PatchBuilder.class.getResourceAsStream(resourcePath)) {
            if (resourceStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            Path outputDirectory = Paths.get(outputDir);
            Files.createDirectories(outputDirectory);

            String fileName = Paths.get(resourcePath).getFileName().toString();
            Path outputPath = outputDirectory.resolve(fileName);

            Files.copy(resourceStream, outputPath, StandardCopyOption.REPLACE_EXISTING);
            return outputPath.toFile();
        }
    }

    private void applyPatch(File vanillaJar, File patchFile, File outputJar) throws IOException, CompressorException, InvalidHeaderException {
        byte[] vanillaBytes = Files.readAllBytes(vanillaJar.toPath());
        byte[] patchBytes = Files.readAllBytes(patchFile.toPath());

        try (FileOutputStream outputStream = new FileOutputStream(outputJar)) {
            Patch.patch(vanillaBytes, patchBytes, outputStream);
        }

        if (!outputJar.exists()) {
            throw new IOException("Patched jar was not created successfully");
        }
    }

    private void addToClasspath(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            InstrumentationManager.getInstrumentation().appendToSystemClassLoaderSearch(jar);
        }
    }
}
