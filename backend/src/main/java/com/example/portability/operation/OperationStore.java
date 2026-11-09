package com.example.portability.operation;

import com.example.portability.operation.OperationModels.*;
import com.example.portability.shared.Contracts.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OperationStore {

    private final JdbcTemplate database;
    private final JsonMapper json;

    public OperationStore(JdbcTemplate database, JsonMapper json) {
        this.database = database;
        this.json = json;
    }

    public void lockSubmission() {
        database.queryForObject(
            "SELECT id FROM submission_lock WHERE id=1 FOR UPDATE",
            Integer.class
        );
    }

    public boolean hasConsent(String beneficiary) {
        return (
            database.queryForObject(
                "SELECT COUNT(*) FROM beneficiary_consent WHERE beneficiary=?",
                Integer.class,
                beneficiary
            ) > 0
        );
    }

    public void recordConsent(Authorization authorization) {
        if (
            database.queryForObject(
                "SELECT COUNT(*) FROM beneficiary_consent WHERE beneficiary=? AND agreement=?",
                Integer.class,
                authorization.beneficiarioId(),
                authorization.convenioId()
            ) == 0
        ) database.update(
            "INSERT INTO beneficiary_consent(id,beneficiary,agreement,captured_at,consent_version) VALUES(?,?,?,?,?)",
            authorization.autorizacaoId(),
            authorization.beneficiarioId(),
            authorization.convenioId(),
            Instant.now().toString(),
            "agenda-opt-in-v1"
        );
    }

    public Set<String> reservedIds() {
        return new HashSet<>(
            database.queryForList("SELECT parcel_id FROM reservation", String.class)
        );
    }

    private StoredOperation map(java.sql.ResultSet result, int index) throws java.sql.SQLException {
        String payload = result.getString("payload"),
            snapshot = result.getString("installment_snapshot");
        return new StoredOperation(
            result.getString("id"),
            result.getString("idem_key"),
            payload,
            json.readValue(payload, Request.class),
            OperationStatus.valueOf(result.getString("status")),
            result.getString("protocol"),
            result.getInt("attempts"),
            result.getLong("next_attempt"),
            result.getString("created_at"),
            result.getString("reason"),
            snapshot == null
                ? List.of()
                : Arrays.asList(json.readValue(snapshot, Installment[].class))
        );
    }

    public Optional<StoredOperation> findByKey(String key) {
        return database
            .query("SELECT * FROM operation WHERE idem_key=?", this::map, key)
            .stream()
            .findFirst();
    }

    public StoredOperation require(String id) {
        return database
            .query("SELECT * FROM operation WHERE id=?", this::map, id)
            .stream()
            .findFirst()
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Operação não encontrada")
            );
    }

    public List<StoredOperation> due() {
        return database.query(
            "SELECT * FROM operation WHERE status IN ('PENDENTE_ENVIO','PENDENTE') AND next_attempt<=? ORDER BY created_at LIMIT 10",
            this::map,
            System.currentTimeMillis()
        );
    }

    public List<View> list() {
        return database
            .query("SELECT * FROM operation ORDER BY created_at DESC", this::map)
            .stream()
            .map(this::view)
            .toList();
    }

    public View get(String id) {
        return view(require(id));
    }

    private View view(StoredOperation operation) {
        BigDecimal total = operation.installments().isEmpty()
            ? null
            : operation
                  .installments()
                  .stream()
                  .map(Installment::valor)
                  .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new View(
            operation.id(),
            operation.request().beneficiarioId(),
            operation.status(),
            operation.protocol(),
            operation.createdAt(),
            operation.reason(),
            operation.attempts(),
            new Details(operation.request(), operation.installments(), total)
        );
    }

    public String create(
        String key,
        String payload,
        Request request,
        List<Installment> installments
    ) {
        Set<String> reserved = reservedIds();
        if (
            request.parcelas().stream().anyMatch(reserved::contains)
        ) throw new ResponseStatusException(HttpStatus.CONFLICT, "Parcela já reservada");
        String id = UUID.randomUUID().toString();
        database.update(
            "INSERT INTO operation(id,idem_key,payload,beneficiary,status,next_attempt,created_at,installment_snapshot) VALUES(?,?,?,?,?,?,?,?)",
            id,
            key,
            payload,
            request.beneficiarioId(),
            OperationStatus.PENDENTE_ENVIO.name(),
            System.currentTimeMillis(),
            Instant.now().toString(),
            json.writeValueAsString(installments)
        );
        for (String parcel : request.parcelas())
            database.update("INSERT INTO reservation VALUES(?,?)", parcel, id);
        event(id, "SOLICITADA", "Solicitação persistida; aguardando envio à Central");
        return id;
    }

    public void accepted(String id, String protocol) {
        database.update(
            "UPDATE operation SET protocol=?,status='PENDENTE',attempts=0,next_attempt=?,reason=NULL WHERE id=?",
            protocol,
            System.currentTimeMillis() + 1000,
            id
        );
        event(id, "PENDENTE", "Central aceitou; protocolo " + protocol);
    }

    public void complete(String id, OperationStatus status, String reason) {
        if (!status.terminal()) throw new IllegalArgumentException("Estado não terminal");
        database.update(
            "UPDATE operation SET status=?,reason=?,attempts=0 WHERE id=?",
            status.name(),
            reason,
            id
        );
        if (status == OperationStatus.REJEITADA) database.update(
            "DELETE FROM reservation WHERE operation_id=?",
            id
        );
        event(
            id,
            status.name(),
            status == OperationStatus.CONFIRMADA
                ? "Central confirmou e atualizou a conta de recebimento"
                : Objects.toString(reason, "Rejeitada pela Central")
        );
    }

    public void schedule(String id, long delay) {
        database.update(
            "UPDATE operation SET attempts=0,next_attempt=? WHERE id=?",
            System.currentTimeMillis() + delay,
            id
        );
    }

    public void failed(
        StoredOperation operation,
        int attempts,
        boolean stop,
        String reason,
        long delay
    ) {
        database.update(
            "UPDATE operation SET attempts=?,status=?,reason=?,next_attempt=? WHERE id=?",
            attempts,
            stop ? OperationStatus.FALHA_TECNICA.name() : operation.status().name(),
            reason,
            System.currentTimeMillis() + delay,
            operation.id()
        );
        event(
            operation.id(),
            stop ? "FALHA_TECNICA" : "RETRY",
            reason + "; tentativa " + attempts + "; espera " + delay + "ms"
        );
    }

    public void resume(String id) {
        database.update(
            "UPDATE operation SET status=CASE WHEN protocol IS NULL THEN 'PENDENTE_ENVIO' ELSE 'PENDENTE' END,attempts=0,next_attempt=?,reason=NULL WHERE id=?",
            System.currentTimeMillis(),
            id
        );
        event(id, "RETOMADA", "Reconciliação manual com a mesma chave de idempotência");
    }

    public List<Event> events(String id) {
        require(id);
        return database.query(
            "SELECT at,type,detail FROM audit_event WHERE operation_id=? ORDER BY sequence",
            (row, index) ->
                new Event(row.getString("at"), row.getString("type"), row.getString("detail")),
            id
        );
    }

    public long pendingCount() {
        return database.queryForObject(
            "SELECT COUNT(*) FROM operation WHERE status IN ('PENDENTE_ENVIO','PENDENTE')",
            Long.class
        );
    }

    public double oldestPendingSeconds() {
        String created = database.queryForObject(
            "SELECT MIN(created_at) FROM operation WHERE status IN ('PENDENTE_ENVIO','PENDENTE')",
            String.class
        );
        return created == null
            ? 0
            : Math.max(
                  0,
                  java.time.Duration.between(Instant.parse(created), Instant.now()).getSeconds()
              );
    }

    public void event(String id, String type, String detail) {
        database.update(
            "INSERT INTO audit_event(operation_id,at,type,detail) VALUES(?,?,?,?)",
            id,
            Instant.now().toString(),
            type,
            detail
        );
    }
}
