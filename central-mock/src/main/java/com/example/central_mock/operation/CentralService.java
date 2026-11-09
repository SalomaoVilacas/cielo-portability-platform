package com.example.central_mock.operation;

import com.example.central_mock.shared.Contracts.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CentralService {

    private final JdbcTemplate database;
    private final JsonMapper json;
    private final long delay;

    public CentralService(JdbcTemplate dataSource, JsonMapper mapper, Environment environment) {
        database = dataSource;
        json = mapper;
        delay = environment.getProperty("central.confirmation-delay-ms", Long.class, 5000L);
    }

    public boolean known(String key) {
        return (
            database.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idem_key=?",
                Integer.class,
                key
            ) > 0
        );
    }

    private void lock() {
        database.queryForObject(
            "SELECT id FROM submission_lock WHERE id=1 FOR UPDATE",
            Integer.class
        );
    }

    @Transactional
    public Authorization authorize(AuthorizationRequest request) {
        lock();
        var rows = database.queryForList(
            "SELECT id FROM beneficiary_consent WHERE beneficiary=? AND agreement=?",
            request.beneficiarioId(),
            request.convenioId()
        );
        if (!rows.isEmpty()) return new Authorization(
            (String) rows.getFirst().get("ID"),
            request.beneficiarioId(),
            request.convenioId()
        );
        String id = UUID.randomUUID().toString();
        database.update(
            "INSERT INTO beneficiary_consent(id,beneficiary,agreement,captured_at,consent_version) VALUES(?,?,?,?,?)",
            id,
            request.beneficiarioId(),
            request.convenioId(),
            Instant.now().toString(),
            "agenda-opt-in-v1"
        );
        for (int i = 1; i <= 6; i++) database.update(
            "INSERT INTO installment VALUES(?,?,?,?,?,?,?,?,?)",
            UUID.randomUUID().toString(),
            request.beneficiarioId(),
            request.convenioId(),
            new BigDecimal("350.25").add(BigDecimal.valueOf(i * 20)),
            LocalDate.now().plusMonths(i),
            i == 6 ? "PAGA" : "ABERTA",
            "001",
            "0001",
            "12345-6"
        );
        return new Authorization(id, request.beneficiarioId(), request.convenioId());
    }

    public List<Installment> agenda(String id) {
        if (
            database.queryForObject(
                "SELECT COUNT(*) FROM beneficiary_consent WHERE beneficiary=?",
                Integer.class,
                id
            ) == 0
        ) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Beneficiário sem autorização");
        return database.query(
            "SELECT i.* FROM installment i JOIN beneficiary_consent a ON a.beneficiary=i.beneficiary AND a.agreement=i.agreement WHERE i.beneficiary=? ORDER BY due_date,id",
            (result, n) ->
                new Installment(
                    result.getString("id"),
                    result.getString("agreement"),
                    result.getBigDecimal("amount"),
                    result.getDate("due_date").toLocalDate(),
                    result.getString("status"),
                    new Account(
                        result.getString("bank"),
                        result.getString("branch"),
                        result.getString("account")
                    )
                ),
            id
        );
    }

    @Transactional
    public String submit(String key, Request input) {
        if (key == null || !key.matches("[A-Za-z0-9-]{1,100}")) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Idempotency-Key inválida"
        );
        lock();
        Request request = input.normalized();
        String payload = json.writeValueAsString(request);
        var previous = database.queryForList(
            "SELECT protocol,payload FROM operation WHERE idem_key=?",
            key
        );
        if (!previous.isEmpty()) {
            if (
                !payload.equals(previous.getFirst().get("PAYLOAD"))
            ) throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Chave reutilizada com outro conteúdo"
            );
            return (String) previous.getFirst().get("PROTOCOL");
        }
        var agenda = agenda(request.beneficiarioId());
        for (String id : request.parcelas()) {
            var p = agenda
                .stream()
                .filter(i -> i.parcelaId().equals(id))
                .findFirst()
                .orElseThrow(() ->
                    new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_CONTENT,
                        "Parcela inexistente"
                    )
                );
            if (
                !p.status().equals("ABERTA") ||
                !p.dataDesconto().isAfter(LocalDate.now()) ||
                database.queryForObject(
                    "SELECT COUNT(*) FROM reservation WHERE parcel_id=?",
                    Integer.class,
                    id
                ) > 0
            ) throw new ResponseStatusException(HttpStatus.CONFLICT, "Parcela não elegível");
        }
        String protocol = UUID.randomUUID().toString();
        database.update(
            "INSERT INTO operation VALUES(?,?,?,?,?,?,NULL)",
            protocol,
            key,
            payload,
            "PENDENTE",
            System.currentTimeMillis() + delay,
            request.cenario()
        );
        for (String id : request.parcelas())
            database.update("INSERT INTO reservation VALUES(?,?)", id, protocol);
        return protocol;
    }

    @Transactional
    public CentralStatus status(String protocol) {
        lock();
        var rows = database.queryForList("SELECT * FROM operation WHERE protocol=?", protocol);
        if (rows.isEmpty()) throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Protocolo não encontrado"
        );
        var row = rows.getFirst();
        String state = (String) row.get("STATUS");
        if (
            state.equals("PENDENTE") &&
            System.currentTimeMillis() >= ((Number) row.get("READY_AT")).longValue()
        ) {
            Request request = json.readValue((String) row.get("PAYLOAD"), Request.class);
            boolean rejected = request.cenario().equals("REJEITAR");
            state = rejected ? "REJEITADA" : "CONFIRMADA";
            String reason = rejected
                ? "Central recusou a transferência no cenário de demonstração"
                : null;
            if (!rejected) {
                for (String id : request.parcelas())
                    database.update(
                        "UPDATE installment SET status='PORTADA',bank=?,branch=?,account=? WHERE id=?",
                        request.contaRecebimento().banco(),
                        request.contaRecebimento().agencia(),
                        request.contaRecebimento().conta(),
                        id
                    );
            } else database.update("DELETE FROM reservation WHERE protocol=?", protocol);
            database.update(
                "UPDATE operation SET status=?,reason=? WHERE protocol=?",
                state,
                reason,
                protocol
            );
            return new CentralStatus(state, reason);
        }
        return new CentralStatus(state, (String) row.get("REASON"));
    }
}
