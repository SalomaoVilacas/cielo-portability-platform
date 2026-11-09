package com.example.portability.shared;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class Contracts {

    private Contracts() {}

    public record AuthorizationRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,60}") String beneficiarioId,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,60}") String convenioId
    ) {}

    public record Authorization(String autorizacaoId, String beneficiarioId, String convenioId) {}

    public record Account(
        @NotBlank @Pattern(regexp = "[0-9]{3}") String banco,
        @NotBlank @Pattern(regexp = "[0-9]{1,6}") String agencia,
        @NotBlank @Pattern(regexp = "[0-9-]{1,20}") String conta
    ) {}

    public record Request(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{1,60}") String beneficiarioId,
        @NotEmpty @Size(max = 50) List<@NotBlank String> parcelas,
        @NotBlank @Size(max = 100) String credorCessionario,
        @NotNull @Valid Account contaRecebimento,
        @Pattern(
            regexp = "CONFIRMAR|REJEITAR|INDISPONIVEL|TIMEOUT|RESPOSTA_INVALIDA|ASSINATURA_INVALIDA"
        )
        String cenario
    ) {
        public Request normalized() {
            return new Request(
                beneficiarioId,
                parcelas.stream().distinct().sorted().toList(),
                credorCessionario.trim(),
                contaRecebimento,
                cenario == null ? "CONFIRMAR" : cenario
            );
        }
    }

    public record Installment(
        String parcelaId,
        String convenioId,
        BigDecimal valor,
        LocalDate dataDesconto,
        String status,
        Account contaRecebimento
    ) {}

    public record CentralStatus(String status, String motivoRejeicao) {}
}
