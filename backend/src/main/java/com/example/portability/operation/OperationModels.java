package com.example.portability.operation;

import com.example.portability.shared.Contracts.*;
import java.math.BigDecimal;
import java.util.List;

public final class OperationModels {

    private OperationModels() {}

    public record StoredOperation(
        String id,
        String key,
        String payload,
        Request request,
        OperationStatus status,
        String protocol,
        int attempts,
        long nextAttempt,
        String createdAt,
        String reason,
        List<Installment> installments
    ) {}

    public record Details(Request solicitacao, List<Installment> parcelas, BigDecimal valorTotal) {}

    public record View(
        String id,
        String beneficiarioId,
        OperationStatus status,
        String protocolo,
        String criadaEm,
        String motivo,
        int tentativas,
        Details detalhes
    ) {}

    public record Event(String instante, String tipo, String detalhe) {}
}
