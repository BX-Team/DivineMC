package org.bxteam.divinemc.profile;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.services.ProfileResult;
import com.mojang.logging.LogUtils;
import com.mojang.util.UndashedUuid;
import io.papermc.paper.profile.MutablePropertyMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import org.bxteam.divinemc.config.DivineConfig;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

@NullMarked
public final class ExtraYggdrasil {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build();

    private ExtraYggdrasil() {}

    @FunctionalInterface
    public interface Mojang {
        @Nullable ProfileResult hasJoined() throws AuthenticationUnavailableException;
    }

    public static @Nullable ProfileResult hasJoined(String name, String serverId, @Nullable InetAddress address, Mojang mojang) throws AuthenticationUnavailableException {
        List<String> services = DivineConfig.NetworkCategory.extraYggdrasilServices;
        if (!DivineConfig.NetworkCategory.extraYggdrasilEnabled || services.isEmpty()) return mojang.hasJoined();
        Function<String, @Nullable UUID> known = DivineConfig.NetworkCategory.extraYggdrasilProtectKnownNames ? ExtraYggdrasil::cachedId : n -> null;
        return hasJoined(name, serverId, address, mojang, services, known);
    }

    static @Nullable ProfileResult hasJoined(
        String name,
        String serverId,
        @Nullable InetAddress address,
        Mojang mojang,
        List<String> services,
        Function<String, @Nullable UUID> known
    ) throws AuthenticationUnavailableException {
        AuthenticationUnavailableException mojangDown = null;
        try {
            ProfileResult result = mojang.hasJoined();
            if (result != null) return result;
        } catch (AuthenticationUnavailableException e) {
            mojangDown = e;
        }
        @Nullable UUID owner = known.apply(name);
        boolean anyAnswered = mojangDown == null;
        for (String service : services) {
            Response response;
            @Nullable UUID id;
            try {
                response = ask(service, name, serverId, address);
                id = response == null ? null : UndashedUuid.fromStringLenient(Objects.requireNonNull(response.id));
            } catch (IOException | JsonParseException | IllegalArgumentException e) {
                LOGGER.warn("Yggdrasil service {} cannot check {}: {}", service, name, e.toString());
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            anyAnswered = true;
            if (response == null || id == null) continue;
            // The server has seen this name under another UUID: whoever owns it here, it is not this service's player.
            if (owner != null && !owner.equals(id)) {
                LOGGER.warn("Yggdrasil service {} vouches for {} as {}, but this server knows that name as {}; refused", service, name, id, owner);
                continue;
            }
            return new ProfileResult(response.profile(id), Set.of());
        }
        if (!anyAnswered) throw mojangDown != null ? mojangDown : new AuthenticationUnavailableException();
        return null;
    }

    private static @Nullable UUID cachedId(String name) {
        NameAndId cached = MinecraftServer.getServer().services().nameToIdCache().getIfCached(name);
        return cached == null ? null : cached.id();
    }

    private static @Nullable Response ask(String service, String name, String serverId, @Nullable InetAddress address) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder("?username=").append(encode(name)).append("&serverId=").append(encode(serverId));
        if (address != null) query.append("&ip=").append(encode(address.getHostAddress()));
        URI uri = URI.create(service.replaceAll("/+$", "") + "/sessionserver/session/minecraft/hasJoined" + query);
        HttpResponse<String> answer = HTTP.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (answer.statusCode() == 204 || answer.statusCode() == 404) return null;
        if (answer.statusCode() != 200) throw new IOException("HTTP " + answer.statusCode());
        Response response = GSON.fromJson(answer.body(), Response.class);
        if (response == null || response.id == null || response.name == null) return null;
        // A service may only answer for the name that is logging in.
        return response.name.equalsIgnoreCase(name) ? response : null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class Response {
        @Nullable String id;
        @Nullable String name;
        @Nullable List<Prop> properties;

        GameProfile profile(UUID id) {
            MutablePropertyMap map = new MutablePropertyMap();
            if (this.properties != null) {
                for (Prop property : this.properties) {
                    if (property.name == null || property.value == null) continue;
                    map.put(property.name, new Property(property.name, property.value, property.signature));
                }
            }
            return new GameProfile(id, Objects.requireNonNull(this.name), map);
        }
    }

    private static final class Prop {
        @Nullable String name;
        @Nullable String value;
        @Nullable String signature;
    }
}
