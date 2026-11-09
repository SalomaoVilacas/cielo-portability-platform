package com.example.portability.integration;

import com.example.portability.shared.Contracts.*;
import com.example.portability.shared.Integrity;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class HttpCentralAdapter implements CentralPort {

    private final JsonMapper json;
    private final String url, secret;
    private final Duration timeout;
    private final HttpClient client;

    public HttpCentralAdapter(JsonMapper j, Environment e) {
        json = j;
        url = e.getRequiredProperty("integration.central-url");
        secret = e.getRequiredProperty("integration.secret");
        timeout = Duration.ofMillis(e.getProperty("integration.timeout-ms", Long.class, 1500L));
        client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private HttpResponse<String> exchange(String method, String path, Object payload, String key) {
        String body = payload == null ? "" : json.writeValueAsString(payload),
            time = Long.toString(Instant.now().getEpochSecond());
        var request = HttpRequest.newBuilder(URI.create(url + path))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("X-Timestamp", time)
            .header(
                "X-Signature",
                Integrity.sign(
                    secret,
                    Integrity.request(method, path + "|" + Objects.toString(key, ""), time, body)
                )
            );
        if (key != null) request.header("Idempotency-Key", key);
        try {
            var r = client.send(
                request
                    .method(
                        method,
                        body.isEmpty()
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body)
                    )
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            );
            String retry = r.headers().firstValue("Retry-After").orElse("");
            if (
                !Integrity.valid(
                    secret,
                    Integrity.response(r.statusCode(), retry, r.body()),
                    r.headers().firstValue("X-Signature").orElse(null)
                )
            ) throw new IntegrationFailure("Assinatura inválida na resposta da Central", true);
            if (r.statusCode() >= 400) throw new IntegrationFailure(
                "Central retornou HTTP " + r.statusCode(),
                r.statusCode() >= 500 || r.statusCode() == 429
            );
            if (!Set.of(200, 201, 202, 204).contains(r.statusCode())) throw new IntegrationFailure(
                "Resposta inesperada da Central",
                true
            );
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IntegrationFailure("Integração interrompida", true);
        } catch (java.io.IOException e) {
            throw new IntegrationFailure("Central indisponível ou timeout", true);
        }
    }

    public Authorization authorize(AuthorizationRequest r) {
        return decode(exchange("POST", "/autorizacoes", r, null).body(), Authorization.class);
    }

    public List<Installment> agenda(String id) {
        return Arrays.asList(
            decode(
                exchange("GET", "/beneficiarios/" + id + "/agenda", null, null).body(),
                Installment[].class
            )
        );
    }

    public String submit(String key, Request r) {
        var response = exchange("POST", "/operacoes-portabilidade", r, key);
        try {
            String p = json.readTree(response.body()).path("protocolo").asText();
            UUID.fromString(p);
            return p;
        } catch (RuntimeException e) {
            throw new IntegrationFailure("Protocolo inválido da Central", true);
        }
    }

    public PollResult poll(String p) {
        var r = exchange("GET", "/operacoes-portabilidade/" + p, null, null);
        if (r.statusCode() == 204) {
            try {
                return new PollResult(
                    null,
                    Math.max(
                        1000,
                        Math.min(
                            30000,
                            Long.parseLong(r.headers().firstValue("Retry-After").orElse("1")) * 1000
                        )
                    )
                );
            } catch (NumberFormatException e) {
                throw new IntegrationFailure("Retry-After inválido", true);
            }
        }
        CentralStatus state = decode(r.body(), CentralStatus.class);
        if (
            !Set.of("PENDENTE", "CONFIRMADA", "REJEITADA").contains(state.status())
        ) throw new IntegrationFailure("Estado inválido da Central", true);
        return new PollResult(state, 1000);
    }

    private <T> T decode(String b, Class<T> t) {
        try {
            T value = json.readValue(b, t);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) {
            throw new IntegrationFailure("JSON inválido da Central", true);
        }
    }
}
