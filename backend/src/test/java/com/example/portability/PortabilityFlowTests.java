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
    org.springframework.jdbc.core.JdbcTemplate database;

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
        boolean unexpected;
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
            if (unexpected && r.beneficiarioId().equals("B")) throw new IllegalStateException(
                "Unexpected test failure"
            );
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
        database.update("UPDATE operation SET next_attempt=0");
        worker.tick();
    }

    @BeforeEach
    void clear() {
        database.update("DELETE FROM audit_event");
        database.update("DELETE FROM reservation");
        database.update("DELETE FROM operation");
        database.update("DELETE FROM beneficiary_consent");
        central.fail = false;
        central.unexpected = false;
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
        assertEquals(op.id(), service.submit("key", request("B")).id());
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
        assertEquals(
            "PENDENTE",
            store
                .get((String) op.id())
                .status()
                .name()
        );
        ready();
        assertEquals(
            "CONFIRMADA",
            store
                .get((String) op.id())
                .status()
                .name()
        );
        ready();
        assertEquals(1, central.submissions);
        assertEquals(3, store.events((String) op.id()).size());
    }

    @Test
    void retriesExhaustAndResumeWithSameOperation() {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        var op = service.submit("key", request("B"));
        String id = (String) op.id();
        central.fail = true;
        ready();
        assertEquals("PENDENTE_ENVIO", store.get(id).status().name());
        ready();
        assertEquals("FALHA_TECNICA", store.get(id).status().name());
        assertEquals(1, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
        central.fail = false;
        service.retry(id);
        ready();
        ready();
        assertEquals("CONFIRMADA", store.get(id).status().name());
        assertEquals(1, store.list().size());
    }

    @Test
    void rejectionReleasesReservations() {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        var op = service.submit("key", request("B"));
        central.reject = true;
        ready();
        ready();
        assertEquals(
            "REJEITADA",
            store
                .get((String) op.id())
                .status()
                .name()
        );
        assertEquals(0, database.queryForObject("SELECT COUNT(*) FROM reservation", Integer.class));
        assertNotNull(service.submit("new", request("B")));
    }

    @Test
    void concurrentDuplicateCreatesOneOperation() throws Exception {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<String>>();
            for (int i = 0; i < 8; i++) tasks.add(
                () -> (String) service.submit("same", request("B")).id()
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

    @Test
    void unexpectedFailureDoesNotBlockOtherOperations() {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        service.authorize(new AuthorizationRequest("OTHER", "CONV"));
        var broken = service.submit("broken", request("B"));
        var healthy = service.submit("healthy", request("OTHER"));
        central.unexpected = true;
        ready();
        assertEquals(1, store.get(broken.id()).tentativas());
        assertEquals(OperationStatus.PENDENTE, store.get(healthy.id()).status());
    }

    @Test
    void competingKeysCannotReserveTheSameParcel() throws Exception {
        service.authorize(new AuthorizationRequest("B", "CONV"));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var results = pool.invokeAll(
                List.<Callable<Boolean>>of(() -> attempt("first"), () -> attempt("second"))
            );
            assertEquals(
                1,
                results
                    .stream()
                    .filter(result -> {
                        try {
                            return result.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .count()
            );
            assertEquals(1, store.list().size());
        }
    }

    private boolean attempt(String key) {
        try {
            service.submit(key, request("B"));
            return true;
        } catch (ResponseStatusException failure) {
            assertEquals(409, failure.getStatusCode().value());
            return false;
        }
    }
}
