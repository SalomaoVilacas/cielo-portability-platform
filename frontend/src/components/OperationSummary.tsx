import type { Operation } from "../types";
export default function OperationSummary({ operation }: { operation: Operation }) {
    const details = operation.detalhes;
    if (!details) return null;
    const request = details.solicitacao;
    return (
        <div className="operation-summary">
            <dl className="fields">
                <div>
                    <dt>Novo credor</dt>
                    <dd>{request.credorCessionario}</dd>
                </div>
                <div>
                    <dt>Conta de destino</dt>
                    <dd>
                        Banco {request.contaRecebimento.banco} · Agência{" "}
                        {request.contaRecebimento.agencia} · Conta {request.contaRecebimento.conta}
                    </dd>
                </div>
                <div>
                    <dt>Valor solicitado</dt>
                    <dd>
                        {details.valorTotal === null
                            ? "Indisponível no registro histórico"
                            : details.valorTotal.toLocaleString("pt-BR", {
                                  style: "currency",
                                  currency: "BRL",
                              })}
                    </dd>
                </div>
            </dl>
            <details>
                <summary>Parcelas da solicitação ({request.parcelas.length})</summary>
                <ul>
                    {request.parcelas.map((id) => (
                        <li key={id}>
                            <code>{id}</code>
                        </li>
                    ))}
                </ul>
            </details>
        </div>
    );
}
