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

    public HttpCentralAdapter(JsonMapper json, Environment environment) {
        this.json = json;
        url = environment.getRequiredProperty("integration.central-url");
        secret = environment.getRequiredProperty("integration.secret");
        timeout = Duration.ofMillis(
            environment.getProperty("integration.timeout-ms", Long.class, 1500L)
        );
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

    public Authorization authorize(AuthorizationRequest request) {
        var response = exchange("POST", "/autorizacoes", request, null);
        expect(response, 200, 201);
        Authorization authorization = decode(response.body(), Authorization.class);
        if (
            blank(authorization.autorizacaoId()) ||
            !request.beneficiarioId().equals(authorization.beneficiarioId()) ||
            !request.convenioId().equals(authorization.convenioId())
        ) throw invalid("Autorização incompleta ou divergente");
        return authorization;
    }

    public List<Installment> agenda(String id) {
        var response = exchange("GET", "/beneficiarios/" + id + "/agenda", null, null);
        expect(response, 200);
        List<Installment> installments = Arrays.asList(
            decode(response.body(), Installment[].class)
        );
        Set<String> ids = new HashSet<>();
        for (Installment installment : installments) {
            if (
                installment == null ||
                blank(installment.parcelaId()) ||
                !ids.add(installment.parcelaId()) ||
                blank(installment.convenioId()) ||
                installment.valor() == null ||
                installment.valor().signum() <= 0 ||
                installment.dataDesconto() == null ||
                blank(installment.status()) ||
                !Set.of("ABERTA", "PAGA", "PORTADA", "RESERVADA").contains(installment.status()) ||
                installment.contaRecebimento() == null ||
                blank(installment.contaRecebimento().banco()) ||
                blank(installment.contaRecebimento().agencia()) ||
                blank(installment.contaRecebimento().conta())
            ) throw invalid("Agenda incompleta ou inválida");
        }
        return installments;
    }

    public String submit(String key, Request request) {
        var response = exchange("POST", "/operacoes-portabilidade", request, key);
        expect(response, 202);
        try {
            String protocol = decode(response.body(), Protocol.class).protocolo();
            UUID.fromString(protocol);
            return protocol;
        } catch (RuntimeException e) {
            throw new IntegrationFailure("Protocolo inválido da Central", true);
        }
    }

    public PollResult poll(String protocol) {
        var response = exchange("GET", "/operacoes-portabilidade/" + protocol, null, null);
        expect(response, 200, 204);
        if (response.statusCode() == 204) {
            try {
                return new PollResult(
                    null,
                    Math.max(
                        1000,
                        Math.min(
                            30000,
                            Long.parseLong(
                                response.headers().firstValue("Retry-After").orElse("1")
                            ) * 1000
                        )
                    )
                );
            } catch (NumberFormatException e) {
                throw new IntegrationFailure("Retry-After inválido", true);
            }
        }
        CentralStatus state = decode(response.body(), CentralStatus.class);
        if (
            state.status() == null ||
            !Set.of("PENDENTE", "CONFIRMADA", "REJEITADA").contains(state.status())
        ) throw new IntegrationFailure("Estado inválido da Central", true);
        return new PollResult(state, 1000);
    }

    private record Protocol(String protocolo) {}

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IntegrationFailure invalid(String reason) {
        return new IntegrationFailure(reason, true);
    }

    private static void expect(HttpResponse<String> response, int... statuses) {
        for (int status : statuses) if (response.statusCode() == status) return;
        throw invalid("Status HTTP inesperado para este endpoint");
    }

    private <T> T decode(String body, Class<T> type) {
        try {
            T value = json.readValue(body, type);
            if (value == null) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException e) {
            throw new IntegrationFailure("JSON inválido da Central", true);
        }
    }
}
