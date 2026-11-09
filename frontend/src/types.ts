export type Account = { banco: string; agencia: string; conta: string };
export type Installment = {
    parcelaId: string;
    convenioId: string;
    valor: number;
    dataDesconto: string;
    status: string;
    contaRecebimento: Account;
};
export type Operation = {
    id: string;
    beneficiarioId: string;
    status: string;
    protocolo: string | null;
    criadaEm: string;
    motivo: string | null;
    tentativas: number;
    detalhes?: { solicitacao: Submission; parcelas: Installment[]; valorTotal: number | null };
};
export type AuditEvent = { instante: string; tipo: string; detalhe: string };
export type Submission = {
    beneficiarioId: string;
    parcelas: string[];
    credorCessionario: string;
    contaRecebimento: Account;
    cenario: string;
};
