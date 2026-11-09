# Harness e uso de IA

Ferramentas: planejamento no ChatGPT; implementação assistida por Codex desktop sobre o projeto local existente. PDF oficial recuperado e lido antes da implementação.

Diretrizes: inicialmente execução guiada, depois diagnóstico somente leitura, preparação Git e push autorizado. A mensagem final revogou as pausas e autorizou implementação integral com testes e documentação, sem placeholders. Nenhuma instalação global ou download de bibliotecas foi realizado durante a implementação.

Processo: inspeção → contratos/migrações → backend/worker → Central assinada → frontend → testes locais/HTTP → revisão/documentação.

Resultados que mudaram a implementação:
- Linha vazia antes da declaração XML corrigida no setup.
- Nome SQL reservado pelo H2 substituído por beneficiary_consent.
- Teste concorrente encontrou corrida entre reserva e replay; replay passou a preceder elegibilidade dentro do lock.
- TIMEOUT atrasa somente o primeiro aceite para exercitar reconciliação.
- Chave de idempotência foi incorporada à assinatura.
- JDBC substituiu JPA não utilizado.

Validação Java offline, dependências frontend reutilizadas localmente. A opção aether.syncContext.named.factory=noop foi usada somente no ambiente restrito do agente para evitar gravação de locks no cache Maven. Não é exigência do projeto nem recomendação para builds concorrentes.

O candidato deve executar o README e estudar CASE_REVIEW.md. O uso de IA não substitui compreensão das invariantes, limites e testes. As hipóteses e limitações estão documentadas, sem alegação de prontidão de produção.
