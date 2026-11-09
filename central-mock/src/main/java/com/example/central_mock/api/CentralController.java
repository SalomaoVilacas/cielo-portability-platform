package com.example.central_mock.api;

import com.example.central_mock.operation.CentralService;
import com.example.central_mock.shared.Contracts.*;
import com.example.central_mock.shared.Integrity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@RestController
public class CentralController {

    private final CentralService service;
    private final JsonMapper json;
    private final Validator validator;
    private final String secret;

    public CentralController(
        CentralService centralService,
        JsonMapper mapper,
        Validator value,
        Environment environment
    ) {
        service = centralService;
        json = mapper;
        validator = value;
        secret = environment.getRequiredProperty("integration.secret");
    }

    private void verify(HttpServletRequest httpRequest, String body) {
        String timestamp = httpRequest.getHeader("X-Timestamp");
        if (
            !Integrity.fresh(timestamp) ||
            !Integrity.valid(
                secret,
                Integrity.request(
                    httpRequest.getMethod(),
                    httpRequest.getRequestURI() +
                        "|" +
                        Objects.toString(httpRequest.getHeader("Idempotency-Key"), ""),
                    timestamp,
                    body
                ),
                httpRequest.getHeader("X-Signature")
            )
        ) throw new ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Assinatura inválida ou expirada"
        );
    }

    private <T> T decode(String body, Class<T> type) {
        try {
            T value = json.readValue(body, type);
            if (!validator.validate(value).isEmpty()) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException environment) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dados inválidos");
        }
    }

    private ResponseEntity<String> signed(int status, Object value, String retry) {
        String body = value == null ? "" : json.writeValueAsString(value);
        var result = ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Signature", Integrity.sign(secret, Integrity.response(status, retry, body)));
        if (!retry.isEmpty()) result.header("Retry-After", retry);
        return result.body(body);
    }

    @PostMapping("/autorizacoes")
    public ResponseEntity<String> authorize(
        HttpServletRequest httpRequest,
        @RequestBody String body
    ) {
        verify(httpRequest, body);
        return signed(201, service.authorize(decode(body, AuthorizationRequest.class)), "");
    }

    @GetMapping("/beneficiarios/{id}/agenda")
    public ResponseEntity<String> agenda(HttpServletRequest httpRequest, @PathVariable String id) {
        verify(httpRequest, "");
        return signed(200, service.agenda(id), "");
    }

    @PostMapping("/operacoes-portabilidade")
    public ResponseEntity<String> submit(HttpServletRequest httpRequest, @RequestBody String body) {
        verify(httpRequest, body);
        Request request = decode(body, Request.class).normalized();
        if (request.cenario().equals("INDISPONIVEL")) return signed(
            503,
            Map.of("detail", "Indisponibilidade simulada"),
            ""
        );
        boolean known = service.known(httpRequest.getHeader("Idempotency-Key"));
        String protocol = service.submit(httpRequest.getHeader("Idempotency-Key"), request);
        if (!known && request.cenario().equals("TIMEOUT")) try {
            Thread.sleep(2500);
        } catch (InterruptedException environment) {
            Thread.currentThread().interrupt();
        }
        if (request.cenario().equals("RESPOSTA_INVALIDA")) return signed(
            202,
            Map.of("protocolo", "invalido"),
            ""
        );
        if (request.cenario().equals("ASSINATURA_INVALIDA")) return ResponseEntity.accepted()
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Signature", "invalid")
            .body(json.writeValueAsString(Map.of("protocolo", protocol)));
        return signed(202, Map.of("protocolo", protocol), "");
    }

    @GetMapping("/operacoes-portabilidade/{protocol}")
    public ResponseEntity<String> status(
        HttpServletRequest httpRequest,
        @PathVariable String protocol
    ) {
        verify(httpRequest, "");
        var state = service.status(protocol);
        return state.status().equals("PENDENTE") ? signed(204, null, "1") : signed(200, state, "");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<String> error(ResponseStatusException environment) {
        return signed(
            environment.getStatusCode().value(),
            Map.of("detail", Objects.toString(environment.getReason(), "Erro na Central")),
            ""
        );
    }
}
