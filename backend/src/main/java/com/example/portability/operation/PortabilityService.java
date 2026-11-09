package com.example.portability.operation;

import com.example.portability.integration.CentralPort;
import com.example.portability.operation.OperationModels.*;
import com.example.portability.shared.Contracts.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class PortabilityService {

    private final OperationStore store;
    private final CentralPort central;
    private final TransactionTemplate transactions;
    private final JsonMapper json;

    public PortabilityService(
        OperationStore store,
        CentralPort central,
        TransactionTemplate transactions,
        JsonMapper json
    ) {
        this.store = store;
        this.central = central;
        this.transactions = transactions;
        this.json = json;
    }

    public Authorization authorize(AuthorizationRequest request) {
        Authorization authorization = central.authorize(request);
        transactions.executeWithoutResult(transaction -> {
            store.lockSubmission();
            store.recordConsent(authorization);
        });
        return authorization;
    }

    public List<Installment> agenda(String beneficiary) {
        if (!store.hasConsent(beneficiary)) throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Registre a autorização antes de consultar a agenda"
        );
        Set<String> reserved = store.reservedIds();
        return central
            .agenda(beneficiary)
            .stream()
            .map(parcel ->
                reserved.contains(parcel.parcelaId()) && parcel.status().equals("ABERTA")
                    ? new Installment(
                          parcel.parcelaId(),
                          parcel.convenioId(),
                          parcel.valor(),
                          parcel.dataDesconto(),
                          "RESERVADA",
                          parcel.contaRecebimento()
                      )
                    : parcel
            )
            .toList();
    }

    public View submit(String key, Request input) {
        if (key == null || !key.matches("[A-Za-z0-9-]{1,100}")) throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Idempotency-Key inválida"
        );
        Request request = input.normalized();
        String payload = json.writeValueAsString(request);
        var existing = store.findByKey(key);
        if (existing.isPresent()) return replay(existing.get(), payload);
        List<Installment> agenda = agenda(request.beneficiarioId());
        return transactions.execute(transaction -> {
            store.lockSubmission();
            var concurrent = store.findByKey(key);
            if (concurrent.isPresent()) return replay(concurrent.get(), payload);
            List<Installment> selected = new ArrayList<>();
            for (String parcelId : request.parcelas()) {
                Installment parcel = agenda
                    .stream()
                    .filter(candidate -> candidate.parcelaId().equals(parcelId))
                    .findFirst()
                    .orElseThrow(() ->
                        new ResponseStatusException(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            "Parcela inexistente para este beneficiário"
                        )
                    );
                if (
                    !parcel.status().equals("ABERTA") ||
                    !parcel.dataDesconto().isAfter(LocalDate.now())
                ) throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Parcela não elegível: " + parcelId
                );
                selected.add(parcel);
            }
            return store.get(store.create(key, payload, request, selected));
        });
    }

    private View replay(StoredOperation operation, String payload) {
        if (!payload.equals(operation.payload())) throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Chave de idempotência já utilizada com outro conteúdo"
        );
        return store.get(operation.id());
    }

    public View retry(String id) {
        return transactions.execute(transaction -> {
            store.lockSubmission();
            if (
                store.require(id).status() != OperationStatus.FALHA_TECNICA
            ) throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Somente falhas técnicas podem ser retomadas"
            );
            store.resume(id);
            return store.get(id);
        });
    }
}
