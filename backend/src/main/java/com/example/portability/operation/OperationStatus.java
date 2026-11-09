package com.example.portability.operation;

public enum OperationStatus {
    PENDENTE_ENVIO,
    PENDENTE,
    CONFIRMADA,
    REJEITADA,
    FALHA_TECNICA;

    public boolean terminal() {
        return this == CONFIRMADA || this == REJEITADA;
    }
}
