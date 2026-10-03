package org.bxteam.divinemc;

import com.destroystokyo.paper.util.VersionFetcher;
import com.destroystokyo.paper.VersionHistoryManager;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.mojang.logging.LogUtils;
import io.papermc.paper.ServerBuildInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;

import static io.papermc.paper.ServerBuildInfo.StringRepresentation.VERSION_SIMPLE;
import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.TextColor.color;

@DefaultQualifier(NonNull.class)
public class DivineVersionFetcher implements VersionFetcher {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int DISTANCE_ERROR = -1;
    private static final int DISTANCE_UNKNOWN = -2;
    private static final int TIMEOUT_MS = 5000;
    private static final String DOWNLOAD_PAGE = "https://bxteam.org/downloads/divinemc";
    private static final String API_BASE = "https://api.bxteam.org/v1";
    private static final String PROJECT = "divinemc";
    private static final String REPOSITORY = "BX-Team/DivineMC";
    private static final ServerBuildInfo BUILD_INFO = ServerBuildInfo.buildInfo();
    private static final String USER_AGENT = BUILD_INFO.brandName() + "/" + BUILD_INFO.asString(VERSION_SIMPLE) + " (https://bxteam.org)";
    private static final Gson GSON = new Gson();
    private static int distance = DISTANCE_UNKNOWN;

    @Override
    public long getCacheTime() {
        return 720000;
    }

    @Override
    public int distance() {
        return distance;
    }

    @Override
    public Component getVersionMessage() {
        final Component updateMessage;
        if (BUILD_INFO.buildNumber().isEmpty() && BUILD_INFO.gitCommit().isEmpty()) {
            updateMessage = text("You are running a development version without access to version information", color(0xFF5300));
        } else {
            updateMessage = getUpdateStatusMessage();
        }
        final @Nullable Component history = this.getHistory();

        return history != null ? Component.textOfChildren(updateMessage, Component.newline(), history) : updateMessage;
    }

    private static Component getUpdateStatusMessage() {
        int dist = DISTANCE_ERROR;

        final OptionalInt buildNumber = BUILD_INFO.buildNumber();
        if (buildNumber.isPresent()) {
            dist = fetchDistanceFromSiteApi(buildNumber.getAsInt());
        } else {
            final Optional<String> gitBranch = BUILD_INFO.gitBranch();
            final Optional<String> gitCommit = BUILD_INFO.gitCommit();
            if (gitBranch.isPresent() && gitCommit.isPresent()) {
                dist = fetchDistanceFromGitHub(gitBranch.get(), gitCommit.get());
            }
        }

        distance = dist;

        return switch (dist) {
            case DISTANCE_ERROR -> text("Error obtaining version information", NamedTextColor.YELLOW);
            case 0 -> text("You are running the latest version", NamedTextColor.GREEN);
            case DISTANCE_UNKNOWN -> text("Unknown version", NamedTextColor.YELLOW);
            default -> text("You are " + dist + " version(s) behind", NamedTextColor.YELLOW)
                .append(Component.newline())
                .append(text("Download the new version at: ")
                    .append(text(DOWNLOAD_PAGE, NamedTextColor.GOLD)
                        .hoverEvent(text("Click to open", NamedTextColor.WHITE))
                        .clickEvent(ClickEvent.openUrl(DOWNLOAD_PAGE))));
        };
    }

    private static int fetchDistanceFromSiteApi(final int localBuildNumber) {
        final String version = URLEncoder.encode(BUILD_INFO.minecraftVersionId(), StandardCharsets.UTF_8);
        final String url = API_BASE + "/builds/" + PROJECT + "/" + version + "/latest";

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            final int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                return DISTANCE_UNKNOWN;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                LOGGER.error("BX Team API returned HTTP {}: {}", code, readErrorMessage(connection));
                return DISTANCE_ERROR;
            }

            final JsonObject json = readJson(connection.getInputStream());
            final JsonElement buildElement = json == null ? null : json.get("build");
            if (buildElement == null || !buildElement.isJsonPrimitive() || !buildElement.getAsJsonPrimitive().isNumber()) {
                LOGGER.error("Unexpected response from BX Team API: missing numeric 'build' field");
                return DISTANCE_ERROR;
            }

            final int latest = buildElement.getAsInt();
            final int diff = latest - localBuildNumber;
            return diff < 0 ? DISTANCE_UNKNOWN : diff;
        } catch (final JsonParseException e) {
            LOGGER.error("Error parsing json from BX Team API", e);
            return DISTANCE_ERROR;
        } catch (final IOException e) {
            LOGGER.error("Error while fetching version from BX Team API", e);
            return DISTANCE_ERROR;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static @Nullable JsonObject readJson(final InputStream stream) throws IOException {
        try (final BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return GSON.fromJson(reader, JsonObject.class);
        }
    }

    private static String readErrorMessage(final HttpURLConnection connection) {
        final InputStream errorStream = connection.getErrorStream();
        if (errorStream == null) {
            return "<no body>";
        }
        try {
            final JsonObject json = readJson(errorStream);
            if (json != null) {
                final JsonElement message = json.get("message");
                final JsonElement error = json.get("error");
                if (message != null && message.isJsonPrimitive()) return message.getAsString();
                if (error != null && error.isJsonPrimitive()) return error.getAsString();
            }
        } catch (final IOException | JsonParseException ignored) {
        }
        return "<unreadable body>";
    }

    // Contributed by Techcable <Techcable@outlook.com> in GH-65
    private static int fetchDistanceFromGitHub(final String branch, final String hash) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(
                "https://api.github.com/repos/%s/compare/%s...%s".formatted(REPOSITORY, branch, hash)
            ).toURL().openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.connect();
            if (connection.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND) return DISTANCE_UNKNOWN; // Unknown commit

            final JsonObject obj = readJson(connection.getInputStream());
            if (obj == null) return DISTANCE_ERROR;
            final String status = obj.get("status").getAsString();
            return switch (status) {
                case "identical" -> 0;
                case "behind" -> obj.get("behind_by").getAsInt();
                default -> DISTANCE_ERROR;
            };
        } catch (final JsonParseException | NumberFormatException | NullPointerException e) {
            LOGGER.error("Error parsing json from GitHub's API", e);
            return DISTANCE_ERROR;
        } catch (final IOException e) {
            LOGGER.error("Error while parsing version", e);
            return DISTANCE_ERROR;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private @Nullable Component getHistory() {
        final VersionHistoryManager.@Nullable VersionData data = VersionHistoryManager.INSTANCE.getVersionData();
        if (data == null) {
            return null;
        }

        final @Nullable String oldVersion = data.getOldVersion();
        if (oldVersion == null) {
            return null;
        }

        return text("Previous version: " + oldVersion, NamedTextColor.GRAY, TextDecoration.ITALIC);
    }
}
