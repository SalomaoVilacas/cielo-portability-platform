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

    private final JdbcTemplate db;
    private final JsonMapper json;
    private final long delay;

    public CentralService(JdbcTemplate d, JsonMapper j, Environment e) {
        db = d;
        json = j;
        delay = e.getProperty("central.confirmation-delay-ms", Long.class, 5000L);
    }

    public boolean known(String key) {
        return (
            db.queryForObject(
                "SELECT COUNT(*) FROM operation WHERE idem_key=?",
                Integer.class,
                key
            ) > 0
        );
    }

    private void lock() {
        db.queryForObject("SELECT id FROM submission_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    @Transactional
    public Authorization authorize(AuthorizationRequest r) {
        lock();
        var rows = db.queryForList(
            "SELECT id FROM beneficiary_consent WHERE beneficiary=? AND agreement=?",
            r.beneficiarioId(),
            r.convenioId()
        );
        if (!rows.isEmpty()) return new Authorization(
            (String) rows.getFirst().get("ID"),
            r.beneficiarioId(),
            r.convenioId()
        );
        String id = UUID.randomUUID().toString();
        db.update(
            "INSERT INTO beneficiary_consent VALUES(?,?,?)",
            id,
            r.beneficiarioId(),
            r.convenioId()
        );
        for (int i = 1; i <= 6; i++) db.update(
            "INSERT INTO installment VALUES(?,?,?,?,?,?,?,?,?)",
            r.beneficiarioId() + "-" + r.convenioId() + "-" + i,
            r.beneficiarioId(),
            r.convenioId(),
            new BigDecimal("350.25").add(BigDecimal.valueOf(i * 20)),
            LocalDate.now().plusMonths(i),
            i == 6 ? "PAGA" : "ABERTA",
            "001",
            "0001",
            "12345-6"
        );
        return new Authorization(id, r.beneficiarioId(), r.convenioId());
    }

    public List<Installment> agenda(String id) {
        if (
            db.queryForObject(
                "SELECT COUNT(*) FROM beneficiary_consent WHERE beneficiary=?",
                Integer.class,
                id
            ) == 0
        ) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Beneficiário sem autorização");
        return db.query(
            "SELECT i.* FROM installment i JOIN beneficiary_consent a ON a.beneficiary=i.beneficiary AND a.agreement=i.agreement WHERE i.beneficiary=? ORDER BY due_date,id",
            (rs, n) ->
                new Installment(
                    rs.getString("id"),
                    rs.getString("agreement"),
                    rs.getBigDecimal("amount"),
                    rs.getDate("due_date").toLocalDate(),
                    rs.getString("status"),
                    new Account(
                        rs.getString("bank"),
                        rs.getString("branch"),
                        rs.getString("account")
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
        Request r = input.normalized();
        String payload = json.writeValueAsString(r);
        var previous = db.queryForList(
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
        var agenda = agenda(r.beneficiarioId());
        for (String id : r.parcelas()) {
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
                db.queryForObject(
                    "SELECT COUNT(*) FROM reservation WHERE parcel_id=?",
                    Integer.class,
                    id
                ) > 0
            ) throw new ResponseStatusException(HttpStatus.CONFLICT, "Parcela não elegível");
        }
        String protocol = UUID.randomUUID().toString();
        db.update(
            "INSERT INTO operation VALUES(?,?,?,?,?,?,NULL)",
            protocol,
            key,
            payload,
            "PENDENTE",
            System.currentTimeMillis() + delay,
            r.cenario()
        );
        for (String id : r.parcelas())
            db.update("INSERT INTO reservation VALUES(?,?)", id, protocol);
        return protocol;
    }

    @Transactional
    public CentralStatus status(String protocol) {
        lock();
        var rows = db.queryForList("SELECT * FROM operation WHERE protocol=?", protocol);
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
            Request r = json.readValue((String) row.get("PAYLOAD"), Request.class);
            boolean rejected = r.cenario().equals("REJEITAR");
            state = rejected ? "REJEITADA" : "CONFIRMADA";
            String reason = rejected
                ? "Central recusou a transferência no cenário de demonstração"
                : null;
            if (!rejected) {
                for (String id : r.parcelas())
                    db.update(
                        "UPDATE installment SET status='PORTADA',bank=?,branch=?,account=? WHERE id=?",
                        r.contaRecebimento().banco(),
                        r.contaRecebimento().agencia(),
                        r.contaRecebimento().conta(),
                        id
                    );
            } else db.update("DELETE FROM reservation WHERE protocol=?", protocol);
            db.update(
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
