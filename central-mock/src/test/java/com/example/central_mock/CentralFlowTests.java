package com.example.central_mock;

import static org.junit.jupiter.api.Assertions.*;

import com.example.central_mock.operation.CentralService;
import com.example.central_mock.shared.Contracts.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

@SpringBootTest(
    properties = {
        "spring.datasource.url=jdbc:h2:mem:central-flow;DB_CLOSE_DELAY=-1",
        "central.confirmation-delay-ms=60000",
    }
)
class CentralFlowTests {

    @Autowired
    CentralService service;

    @Autowired
    JdbcTemplate db;

    @BeforeEach
    void clear() {
        db.update("DELETE FROM reservation");
        db.update("DELETE FROM operation");
        db.update("DELETE FROM installment");
        db.update("DELETE FROM beneficiary_consent");
    }

    Request request(String scenario) {
        return new Request(
            "B",
            List.of("B-C-1"),
            "Cielo",
            new Account("237", "0002", "98765-4"),
            scenario
        );
    }

    @Test
    void accountOnlyChangesAfterConfirmation() throws Exception {
        service.authorize(new AuthorizationRequest("B", "C"));
        String p = service.submit("key", request("CONFIRMAR"));
        assertEquals(p, service.submit("key", request("CONFIRMAR")));
        assertEquals("PENDENTE", service.status(p).status());
        assertEquals("001", service.agenda("B").getFirst().contaRecebimento().banco());
        db.update("UPDATE operation SET ready_at=0");
        assertEquals("CONFIRMADA", service.status(p).status());
        assertEquals("237", service.agenda("B").getFirst().contaRecebimento().banco());
        assertEquals("PORTADA", service.agenda("B").getFirst().status());
        assertEquals("CONFIRMADA", service.status(p).status());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM operation", Integer.class));
    }

    @Test
    void rejectionPreservesAccountAndReleasesParcel() throws Exception {
        service.authorize(new AuthorizationRequest("B", "C"));
        String p = service.submit("key", request("REJEITAR"));
        db.update("UPDATE operation SET ready_at=0");
        assertEquals("REJEITADA", service.status(p).status());
        assertEquals("001", service.agenda("B").getFirst().contaRecebimento().banco());
        assertNotNull(service.submit("other", request("CONFIRMAR")));
    }

    @Test
    void consentIsIdempotentAndScoped() {
        var a = service.authorize(new AuthorizationRequest("B", "C"));
        assertEquals(a, service.authorize(new AuthorizationRequest("B", "C")));
        assertEquals(6, service.agenda("B").size());
        assertThrows(ResponseStatusException.class, () -> service.agenda("OTHER"));
    }
}
