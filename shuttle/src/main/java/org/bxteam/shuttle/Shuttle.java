package org.bxteam.shuttle;

import org.bxteam.shuttle.logger.Logger;
import org.bxteam.shuttle.patch.LibraryLoader;
import org.bxteam.shuttle.patch.PatchBuilder;

import java.io.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Shuttle {
    private static final String MAIN_CLASS_RESOURCE = "/META-INF/main-class";
    private static final String VERSION_RESOURCE = "/version.json";
    private static final String PATCH_ONLY_PROPERTY = "paperclip.patchonly";
    private static final String BUNDLER_MAIN_CLASS_PROPERTY = "bundlerMainClass";
    private static final String BUNDLER_REPO_DIR_PROPERTY = "bundlerRepoDir";
    private static final String MAIN_THREAD_NAME = "main";

    public static void main(String[] arguments) {
        new Shuttle().run(arguments);
    }

    private void run(String[] arguments) {
        try {
            String defaultMainClassName = readMainClass();
            String mainClassName = System.getProperty(BUNDLER_MAIN_CLASS_PROPERTY, defaultMainClassName);
            String repoDir = System.getProperty(BUNDLER_REPO_DIR_PROPERTY, "");

            setupDirectories(repoDir);

            Provider<String> versionProvider = this::readVersionFromResource;

            executePatchingPhase(versionProvider);

            if (shouldExitAfterPatching()) {
                System.exit(0);
            }

            executeLibraryLoadingPhase(versionProvider);

            startTargetApplication(mainClassName, arguments);
        } catch (Exception e) {
            Logger.error("Failed to extract server libraries, exiting", e);
            System.exit(1);
        }
    }

    private String readMainClass() throws IOException {
        return readResourceContent(MAIN_CLASS_RESOURCE, BufferedReader::readLine);
    }

    private String readVersionFromResource() throws IOException {
        String jsonContent = readResourceContent(VERSION_RESOURCE, this::readAllLines);
        return extractVersionFromJson(jsonContent);
    }

    private <T> T readResourceContent(String resourcePath, ResourceParser<T> parser) throws IOException {
        try (InputStream inputStream = getClass().getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                return parser.parse(reader);
            }
        }
    }

    private String readAllLines(BufferedReader reader) throws IOException {
        StringBuilder content = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            content.append(line);
        }
        return content.toString();
    }

    private String extractVersionFromJson(String jsonContent) throws IOException {
        String prefix = "\"id\": \"";
        int startIndex = jsonContent.indexOf(prefix);

        if (startIndex == -1) {
            throw new IOException("Version ID not found in JSON content");
        }

        startIndex += prefix.length();
        int endIndex = jsonContent.indexOf("\"", startIndex);

        if (endIndex == -1) {
            throw new IOException("Malformed version ID in JSON content");
        }

        return jsonContent.substring(startIndex, endIndex);
    }

    private void setupDirectories(String repoDir) throws IOException {
        if (!repoDir.isEmpty()) {
            Path outputDir = Paths.get(repoDir);
            Files.createDirectories(outputDir);
        }
    }

    private void executePatchingPhase(Provider<String> versionProvider) throws IOException {
        new PatchBuilder().start(versionProvider);
    }

    private boolean shouldExitAfterPatching() {
        return Boolean.getBoolean(PATCH_ONLY_PROPERTY);
    }

    private void executeLibraryLoadingPhase(Provider<String> versionProvider) throws IOException {
        new LibraryLoader().start(versionProvider);
    }

    private void startTargetApplication(String mainClassName, String[] arguments) {
        if (mainClassName == null || mainClassName.trim().isEmpty()) {
            Logger.warn("No main class specified, exiting");
            return;
        }

        Logger.info("Starting " + mainClassName);

        Thread applicationThread = new Thread(() -> {
            try {
                invokeMainMethod(mainClassName, arguments);
            } catch (Throwable e) {
                ExceptionHandler.INSTANCE.rethrow(e);
            }
        }, MAIN_THREAD_NAME);

        applicationThread.start();
    }

    private void invokeMainMethod(String mainClassName, String[] arguments) throws Throwable {
        Class<?> mainClass = Class.forName(mainClassName);
        MethodHandle mainHandle = MethodHandles.lookup()
            .findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class))
            .asFixedArity();

        mainHandle.invoke((Object) arguments);
    }

    @FunctionalInterface
    private interface ResourceParser<T> {
        T parse(BufferedReader reader) throws IOException;
    }

    @FunctionalInterface
    public interface Provider<T> {
        T get() throws IOException;
    }

    private static final class ExceptionHandler {
        static final ExceptionHandler INSTANCE = new ExceptionHandler();

        private ExceptionHandler() {
            throw new UnsupportedOperationException("Utility class cannot be instantiated");
        }

        void rethrow(Throwable exception) {
            throw new RuntimeException("Exception in target application", exception);
        }
    }
}
