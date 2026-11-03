package com.nexus.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexus.common.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fake HTTP provider kiểm tra wire contract IAM, mutation, lỗi provider và cấu hình thiếu mà không cần Keycloak thật. */
class KeycloakClientTest {
    private HttpServer server;
    private KeycloakClient client;
    private final UUID roleId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private int status = 200;

    @BeforeEach
    void startProvider() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (calls) { calls.add(exchange.getRequestMethod() + " " + path); }
            exchange.getRequestBody().readAllBytes();
            String role = "{\"id\":\"" + roleId + "\",\"name\":\"ROLE_BUYER\",\"description\":\"Buyer\"}";
            String privilege = "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"PROFILE.VIEW\"}";
            String body = path.endsWith("/userinfo") ? "{\"sub\":\"" + userId + "\"}" :
                    path.endsWith("/token") ? "{\"access_token\":\"admin-token\"}" :
                    path.contains("/role-mappings/") ? "[" + role + "," + privilege + "]" :
                    path.endsWith("/composites") ? "[" + privilege + "]" : "[" + role + "," + privilege + "]";
            int responseStatus = status;
            if (!exchange.getRequestMethod().equals("GET") && !path.endsWith("/token") && status == 200) {
                responseStatus = 204;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, responseStatus == 204 ? -1 : bytes.length);
            if (responseStatus != 204) { exchange.getResponseBody().write(bytes); }
            exchange.close();
        });
        server.start();
        client = new KeycloakClient("http://127.0.0.1:" + server.getAddress().getPort(), "realm", "admin", "test-only", "web");
    }

    @AfterEach
    void stopProvider() { server.stop(0); }

    @Test
    void readsRolesCompositesAndUserAuthorities() {
        assertEquals(1, client.roles().size());
        assertEquals("PROFILE.VIEW", client.privilegeRoles().getFirst().name());
        assertEquals(Set.of("PROFILE.VIEW"), client.privileges(roleId.toString()));
        assertEquals(Set.of("ROLE_BUYER", "PROFILE.VIEW"), client.authorities(userId));
    }

    @Test
    void assignsKnownRolesAndRejectsUnknownBeforeMutation() {
        client.assignRoles(userId, Set.of("BUYER"));
        assertTrue(calls.contains("POST /admin/realms/realm/users/" + userId + "/role-mappings/realm"));
        assertThrows(IllegalArgumentException.class, () -> client.assignRoles(userId, Set.of("UNKNOWN")));
    }

    @Test
    void removesOldApplicationRolesAndDisablesSessions() {
        client.assignRoles(userId, Set.of());
        client.disable(userId);
        client.sendResetEmail(userId);
        client.changePassword(new KeycloakClient.UserIdentity(userId, "user@example.com"), "old-pass", "new-pass");
        assertTrue(calls.contains("DELETE /admin/realms/realm/users/" + userId + "/role-mappings/realm"));
        assertTrue(calls.contains("PUT /admin/realms/realm/users/" + userId + "/reset-password"));
        assertTrue(calls.contains("POST /admin/realms/realm/users/" + userId + "/logout"));
    }

    @Test
    void mapsProviderErrorsWithoutExposingResponseOrSecret() {
        status = 500;
        ApiException error = assertThrows(ApiException.class, () -> client.roles());
        assertEquals(502, error.getHttpStatus().value());
        var missing = new KeycloakClient("http://127.0.0.1:" + server.getAddress().getPort(), "realm", "admin", "", "web");
        assertEquals(503, assertThrows(ApiException.class, () -> missing.roles()).getHttpStatus().value());
    }
}
