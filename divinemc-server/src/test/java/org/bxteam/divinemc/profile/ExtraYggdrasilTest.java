package org.bxteam.divinemc.profile;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.services.ProfileResult;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.support.environment.Normal;
import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@Normal
@NullMarked
class ExtraYggdrasilTest {
    private static final UUID NICK = UUID.fromString("76d30f3f-1628-3cc8-851f-c87c8b277262");
    private static final UUID MICROSOFT = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    // Mojang wins over the service, and the service cannot take a name the server knows under another UUID
    @Test
    void serviceAnswersOnlyWhatMojangAndTheServerLeaveOpen() throws Exception {
        AtomicInteger asked = new AtomicInteger();
        HttpServer service = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.createContext("/root/sessionserver/session/minecraft/hasJoined", exchange -> {
            asked.incrementAndGet();
            String query = exchange.getRequestURI().getQuery();
            String name = query.replaceAll(".*username=([^&]*).*", "$1");
            byte[] body = ("{\"id\":\"76d30f3f16283cc8851fc87c8b277262\",\"name\":\"" + (name.equals("Liar") ? "Someone" : name)
                + "\",\"properties\":[{\"name\":\"textures\",\"value\":\"e30=\",\"signature\":\"c2ln\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        service.start();
        try {
            List<String> services = List.of("http://127.0.0.1:" + service.getAddress().getPort() + "/root/");
            ProfileResult mojang = new ProfileResult(new GameProfile(MICROSOFT, "Steve"));

            assertSame(mojang, ExtraYggdrasil.hasJoined("Steve", "s", null, () -> mojang, services, name -> null));
            assertEquals(0, asked.get());

            ProfileResult nick = ExtraYggdrasil.hasJoined("AliceNick", "s", null, () -> null, services, name -> null);
            assertEquals(NICK, nick.profile().id());
            assertEquals("e30=", nick.profile().properties().get("textures").iterator().next().value());

            assertEquals(NICK, ExtraYggdrasil.hasJoined("AliceNick", "s", null, () -> null, services, name -> NICK).profile().id());
            assertNull(ExtraYggdrasil.hasJoined("AliceNick", "s", null, () -> null, services, name -> MICROSOFT));
            assertNull(ExtraYggdrasil.hasJoined("Liar", "s", null, () -> null, services, name -> null));
        } finally {
            service.stop(0);
        }
    }
}
