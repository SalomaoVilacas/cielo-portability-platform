package com.example.portability;

import static org.junit.jupiter.api.Assertions.*;

import com.example.portability.integration.*;
import com.example.portability.operation.*;
import com.example.portability.shared.Contracts.*;
import com.example.portability.shared.Integrity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:flow;DB_CLOSE_DELAY=-1",
        "jobs.enabled=false",
        "integration.retry-base-ms=1",
        "integration.max-attempts=2",
    }
)
class PortabilityFlowTests {

    @Autowired
    PortabilityService service;

    @Autowired
    OperationStore store;

    @Autowired
    OperationWorker worker;

    @Autowired
    FakeCentral central;

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        FakeCentral fake() {
            return new FakeCentral();
        }
    }

    static class FakeCentral implements CentralPort {

        boolean fail;
        boolean reject;
        int submissions;

        public Authorization authorize(AuthorizationRequest r) {
            return new Authorization(
                UUID.randomUUID().toString(),
                r.beneficiarioId(),
                r.convenioId()
            );
        }

        public List<Installment> agenda(String id) {
            return List.of(
                new Installment(
                    id + "-1",
                    "CONV",
                    new BigDecimal("100.25"),
                    LocalDate.now().plusMonths(1),
                    "ABERTA",
                    new Account("001", "1", "1")
                )
            );
        }

        public String submit(String key, Request r) {
            submissions++;
            if (fail) throw new IntegrationFailure("Timeout de teste", true);
            return UUID.randomUUID().toString();
        }

        public PollResult poll(String protocol) {
            return new PollResult(
                new CentralStatus(reject ? "REJEITADA" : "CONFIRMADA", reject ? "Recusada" : null),
                1
            );
        }
    }

    Request request(String id) {
        return new Request(
            id,
            List.of(id + "-1"),
            "Cielo",
            new Account("237", "0001", "12345-6"),
            "CONFIRMAR"
        );
    }

    void ready() {
        store.db().update("UPDATE operation SET next_attempt=0");
        worker.tick();
    }

    @BeforeEach
    void clear() {
        store.db().update("DELETE FROM audit_event");
        store.db().update("DELETE FROM reservation");
        store.db().update("DELETE FROM operation");
        store.db().update("DELETE FROM beneficiary_consent");
        central.fail = false;
        central.reject = false;
        central.submissions = 0;
    }

    @Test
    void requiresConsent() {
        assertEquals(
            403,
            assertThrows(ResponseStatusException.class, () -> service.agenda("B"))
                .getStatusCode()
                .value()
        );
    }

    @Test
    void idempotencyAndConfirmation() {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        var op = service.submit("key", request("B"));
        assertEquals(op.get("id"), service.submit("key", request("B")).get("id"));
        assertEquals(
            409,
            assertThrows(ResponseStatusException.class, () ->
                service.submit(
                    "key",
                    new Request(
                        "B",
                        List.of("B-1"),
                        "Other",
                        new Account("001", "1", "1"),
                        "CONFIRMAR"
                    )
                )
            )
                .getStatusCode()
                .value()
        );
        assertEquals(
            409,
            assertThrows(ResponseStatusException.class, () -> service.submit("other", request("B")))
                .getStatusCode()
                .value()
        );
        ready();
        assertEquals("PENDENTE", store.get((String) op.get("id")).get("status"));
        ready();
        assertEquals("CONFIRMADA", store.get((String) op.get("id")).get("status"));
        ready();
        assertEquals(1, central.submissions);
        assertEquals(3, store.events((String) op.get("id")).size());
    }

    @Test
    void retriesExhaustAndResumeWithSameOperation() {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        var op = service.submit("key", request("B"));
        String id = (String) op.get("id");
        central.fail = true;
        ready();
        assertEquals("PENDENTE_ENVIO", store.get(id).get("status"));
        ready();
        assertEquals("FALHA_TECNICA", store.get(id).get("status"));
        assertEquals(
            1,
            store.db().queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)
        );
        central.fail = false;
        service.retry(id);
        ready();
        ready();
        assertEquals("CONFIRMADA", store.get(id).get("status"));
        assertEquals(1, store.list().size());
    }

    @Test
    void rejectionReleasesReservations() {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        var op = service.submit("key", request("B"));
        central.reject = true;
        ready();
        ready();
        assertEquals("REJEITADA", store.get((String) op.get("id")).get("status"));
        assertEquals(
            0,
            store.db().queryForObject("SELECT COUNT(*) FROM reservation", Integer.class)
        );
        assertNotNull(service.submit("new", request("B")));
    }

    @Test
    void concurrentDuplicateCreatesOneOperation() throws Exception {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 8; i++) tasks.add(
                () -> (String) service.submit("same", request("B")).get("id")
            );
            var results = pool.invokeAll(tasks);
            Set<String> ids = new HashSet<>();
            for (var result : results) ids.add(result.get());
            assertEquals(1, ids.size());
            assertEquals(1, store.list().size());
        }
    }

    @Test
    void hmacDetectsTamperingAndExpiry() {
        String signature = Integrity.sign("secret", "payload");
        assertTrue(Integrity.valid("secret", "payload", signature));
        assertFalse(Integrity.valid("secret", "tampered", signature));
        assertFalse(Integrity.fresh("0"));
    }
}
