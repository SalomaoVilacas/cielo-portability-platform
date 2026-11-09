# Revisão e refinamento técnico

## Entrega e escopo

Redesign publicado separadamente no commit 6f978e7. Refinamentos seguintes preservam endpoints e campos existentes, acrescentando detalhes tipados. Não há instalação nova de bibliotecas.

## Decisões

- OperationModels e OperationStatus substituem mapas e strings dispersas na camada de aplicação.
- OperationStore concentra consultas, reservas, transições e auditoria. TransactionTemplate continua controlando a unidade atômica no serviço/worker.
- Worker trata falhas por operação e registra falhas inesperadas, sem ocultar o stack trace nos logs.
- Adaptador valida esquema de resposta e status esperado de cada endpoint. JSON com status ausente/nulo é IntegrationFailure, não NullPointerException.
- Novas parcelas usam UUID. IDs antigos continuam válidos; V1 não foi alterada.
- V2 guarda evidência de consentimento e snapshot das parcelas. Não há backfill fictício de data ou valor.
- Métricas evitam tags de alta cardinalidade; dados bancários não são registrados em logs.
- Hook useOperations separa efeitos de renderização, cancela requests e atualiza a agenda quando operações atingem estados terminais.
- Sem dependências de lint não utilizadas. Dependências/lockfile do frontend foram reduzidos.
- Testes de contrato HTTP, concorrência de chaves diferentes, isolamento do lote, múltiplas parcelas, migração e renderização foram adicionados.

## Diagnóstico dos dados existentes

Foram consultadas cópias dos dois arquivos H2 em modo somente leitura, sem mudar os originais. As duas operações confirmadas tinham quatro parcelas ao todo. Todas estavam PORTADA na Central e com a conta de destino correspondente. Zero divergências nessa amostra. Isso não prova o que foi exibido na tela em momento anterior, mas não houve evidência de perda persistida.

Os serviços antigos responderam HTTP 500 durante o diagnóstico. Os arquivos de JAR abertos por esses processos estavam em target e haviam sido substituídos/renomeados por novos builds. O novo launcher usa cópias imutáveis. Os processos externos à sessão não puderam ser encerrados pelo agente; precisam ser reiniciados pelo usuário para carregar as mudanças e migrar os bancos.

## Limites preservados

Uma instância de worker, H2 local, autenticação ausente no simulador, HMAC simplificado e auditoria protegida pela aplicação. Não há alegação de prontidão de produção. CI depende de permissão workflow no GitHub. O fluxo browser foi executado pelo navegador conectado; o runner CLI requer navegador/Playwright já existentes.
