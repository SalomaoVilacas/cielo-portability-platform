package com.example.portability;

import static org.junit.jupiter.api.Assertions.*;

import com.example.portability.integration.*;
import com.example.portability.shared.*;
import com.example.portability.shared.Contracts.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;

class HttpCentralContractTests {

    HttpServer server;
    HttpCentralAdapter adapter;
    String body;
    int status;

    @BeforeEach
    void start() throws Exception {
        status = 200;
        body = "{}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange
                .getResponseHeaders()
                .add("X-Signature", Integrity.sign("secret", Integrity.response(status, "", body)));
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        adapter = new HttpCentralAdapter(
            JsonMapper.builder().build(),
            new MockEnvironment()
                .withProperty(
                    "integration.central-url",
                    "http://127.0.0.1:" + server.getAddress().getPort()
                )
                .withProperty("integration.secret", "secret")
        );
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void missingAndNullStatusAreControlledIntegrationFailures() {
        for (String json : List.of("{}", "{\"status\":null}", "{\"status\":\"UNKNOWN\"}")) {
            body = json;
            assertThrows(IntegrationFailure.class, () -> adapter.poll("protocol"));
        }
    }

    @Test
    void malformedAuthorizationAndAgendaAreRejected() {
        assertThrows(IntegrationFailure.class, () ->
            adapter.authorize(new AuthorizationRequest("B", "C"))
        );
        body = "[{}]";
        assertThrows(IntegrationFailure.class, () -> adapter.agenda("B"));
        body = "[null]";
        assertThrows(IntegrationFailure.class, () -> adapter.agenda("B"));
    }

    @Test
    void endpointSpecificStatusIsChecked() {
        status = 201;
        body = "{\"status\":\"CONFIRMADA\"}";
        assertThrows(IntegrationFailure.class, () -> adapter.poll("p"));
    }

    @Test
    void validTerminalStateIsAccepted() {
        body = "{\"status\":\"CONFIRMADA\",\"motivoRejeicao\":null}";
        assertEquals("CONFIRMADA", adapter.poll("p").result().status());
    }
}
