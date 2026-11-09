# Mapa de estudo para Case Review

## Fluxo entre arquivos

1. Contracts define mensagens e validação; normalização remove duplicatas/ordena parcelas.
2. ApiController recebe requisições e devolve 202 após persistência.
3. PortabilityService valida autorização, replay, elegibilidade e reserva.
4. OperationStore + V1__platform.sql: constraints, lock, consultas e eventos.
5. OperationWorker: fila durável, chamadas externas fora da transação, retries e polling.
6. CentralPort / HttpCentralAdapter: fronteira substituível, timeout e validação de resposta.
7. Integrity / CentralController: assinatura dos bytes e metadados nos dois sentidos.
8. CentralService: autorização, agenda e conta/estado na mesma transação.
9. App → api → domain: consentimento, formulário, chave estável e polling cancelável.
10. Testes Java e scripts/e2e.py: invariantes locais e integração entre processos reais.

## Patterns efetivos

Port/Adapter (CentralPort e implementação HTTP), Repository (OperationStore), Transaction Script (serviços SQL), máquina de estados, Idempotent Receiver (chaves únicas nos dois sistemas), fila durável (operation) e retry/backoff.

Não há Saga formal nem garantia de exactly-once no transporte. Há tentativas repetíveis com efeitos idempotentes e consistência eventual por reconciliação.

## Perguntas a dominar

**Resposta perdida após aceite:** operação/chave persistidas; retry devolve protocolo existente. TIMEOUT demonstra isso.

**Por que manter a reserva após falha técnica:** a Central pode ter aceitado; liberar permitiria uma segunda operação. Retomar reconcilia a mesma solicitação.

**Concorrência:** lock SQL de seção curta + constraints. Replay dentro da transação ocorre antes de rejeitar a parcela reservada. Teste concorrente valida isso.

**Conta só após confirmação:** a Central atualiza contas e estado na mesma transação após o atraso; rejeição preserva a conta.

**HMAC dos bytes:** serializações JSON podem diferir. Assinamos o corpo enviado, timestamp, método, caminho e chave; respostas incluem status e Retry-After.

**Escala:** substituir lock global, adicionar leases, PostgreSQL, paginação e métricas. Não executar várias réplicas do worker sem adaptação.

**Mudança em relação ao planejamento:** JDBC substituiu ORM sem uso. React nativo substituiu Router/TanStack/formulário para reduzir dependências neste escopo. Documentar o trade-off e evoluir quando houver benefício.

## Hipóteses de domínio

Autorização por beneficiário/convênio, sem expiração/revogação. Elegível = aberta e futura. Duplicatas de seleção são normalizadas. Banco/agência/conta são strings; validação de formato, sem consulta bancária real. Dados de demonstração são gerados ao autorizar.
