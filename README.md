# Porta — Plataforma de Portabilidade de Convênios

Case full stack: consentimento, agenda, seleção de parcelas, portabilidade assíncrona, atualização da conta e auditoria. Java 21 + Spring Boot 4.1.2 + React/TypeScript.

## Executar

Pré-requisitos: Java 21 e Node 24. Não exige Maven global, Docker ou banco externo. Execute na raiz:

~~~sh
./mvnw verify
~~~

Terminal 1 — Central:

~~~sh
java -jar central-mock/target/central-mock-0.1.0-SNAPSHOT.jar
~~~

Terminal 2 — backend:

~~~sh
java -jar backend/target/backend-0.1.0-SNAPSHOT.jar
~~~

Terminal 3 — frontend:

~~~sh
cd frontend
npm ci
npm run dev
~~~

Abra http://localhost:5173. No IntelliJ, os métodos main de CentralMockApplication e BackendApplication também funcionam. Configure o diretório de trabalho como a raiz do repositório. Pare execuções anteriores da IDE se ocuparem 8080/8081.

A validação local do agente não instalou bibliotecas: Maven usou o cache existente em modo offline; node_modules reutilizou as dependências de outro projeto local por vínculo ignorado pelo Git. Em uma máquina independente, npm ci resolve as versões exatas do package-lock.json. A primeira execução do wrapper em uma máquina nova pode baixar Maven e dependências declaradas. Nenhuma instalação global é necessária.

Com o cache Java disponível:

~~~sh
./mvnw -o verify
~~~

## Demonstração

1. Informe BEN-001 / CONV-001 ou identificadores próprios (letras, números e hífens).
2. Marque o consentimento e autorize. O mock gera seis parcelas: cinco abertas futuras e uma paga.
3. Selecione abertas; informe credor, banco de três dígitos, agência e conta.
4. Solicite a portabilidade. O histórico acompanha o resultado automaticamente.
5. Abra Ver trilha; depois volte à agenda e clique em Atualizar agenda para conferir a conta.
6. Use outro beneficiário ou parcela para repetir após confirmação.

Dados fictícios. Validação bancária apenas de formato. A API de demonstração não possui autenticação de usuário.

### Cenários da Central

Disponíveis em “Cenários de demonstração”:

| Cenário | Resultado |
|---|---|
| CONFIRMAR | 202 e protocolo; confirmação após cinco segundos |
| REJEITAR | Rejeição assíncrona, preservação da conta e liberação da reserva |
| INDISPONIVEL | HTTP 503; retries limitados e FALHA_TECNICA |
| TIMEOUT | Primeiro aceite persiste, resposta atrasa; retry recupera o mesmo protocolo |
| RESPOSTA_INVALIDA | Protocolo inválido rejeitado pelo backend |
| ASSINATURA_INVALIDA | Assinatura adulterada rejeitada pelo backend |

A falha técnica mantém as reservas porque o aceite pode ter ocorrido. “Retomar com a mesma solicitação” reutiliza operação/chave após resolver a causa. Cenários de falha persistente continuam falhando ao retomar.

## Testes

~~~sh
./mvnw verify
cd frontend
npm test
npm run build
cd ..
python3 scripts/e2e.py
~~~

O e2e exige os JARs gerados. Usa portas livres e bancos temporários, inicia/reinicia/encerra somente seus processos e não apaga dados locais.

Cobertura: autorização obrigatória/idempotente; concorrência; chave com payload diferente; reserva; confirmação/rejeição; conta; auditoria; retries; retomada; timeout após aceite; indisponibilidade; protocolo/assinatura inválidos; reinício; HMAC; 204/Retry-After; elegibilidade e chave no frontend.

## Configuração

Propriedades Spring aceitam argumentos e variáveis padrão:
- SERVER_PORT: porta do serviço.
- SPRING_DATASOURCE_URL: banco de dados.
- INTEGRATION_CENTRAL_URL: endereço da Central no backend.
- INTEGRATION_SECRET: mesmo segredo em ambos. O padrão público serve somente ao demo.
- CENTRAL_CONFIRMATION_DELAY_MS: atraso do mock, padrão 5000.
- INTEGRATION_TIMEOUT_MS: timeout, padrão 1500.
- INTEGRATION_MAX_ATTEMPTS: limite, padrão 5.
- INTEGRATION_RETRY_BASE_MS: base do backoff, padrão 1000.

Para frontend com backend em outra porta:

~~~sh
cd frontend
API_URL=http://localhost:18080 npm run dev
~~~

Os dois bancos H2 em arquivo ficam em backend/data e central-mock/data, ignorados pelo Git. Execute a partir da raiz para manter os caminhos estáveis.

## Arquitetura e racional

React → API Spring MVC → CentralPort → HttpCentralAdapter → Central Spring MVC.
Cada serviço tem H2 e migrações Flyway independentes.

- api: fronteira HTTP, validação e ProblemDetail.
- operation: serviço de aplicação, persistência SQL e dispatcher.
- integration: porta, adaptador HTTP e classificação de falhas.
- shared: contratos e HMAC pequenos, duplicados para evitar acoplamento entre serviços.
- frontend/src: App coordena fluxos; api centraliza transporte; domain contém regras puras; types define contratos.

O backend grava operação, reservas e evento na mesma transação antes do 202. O worker envia, persiste o protocolo e consulta o resultado. A Central muda conta e estado atomicamente. Se a gravação local falhar depois da confirmação externa, a próxima consulta reconcilia sem repetir efeitos.

Estados: PENDENTE_ENVIO → PENDENTE → CONFIRMADA/REJEITADA. FALHA_TECNICA permite retomada explícita. Estados terminais não regridem.

### Por que estas decisões?

- Port/Adapter permite testar o serviço com uma Central falsa. HttpClient do JDK evita dependência HTTP extra.
- Constraints de chave/reserva e lock SQL protegem criação concorrente. Rede fica fora das transações do backend.
- Tabela de operações como fila durável elimina broker e recupera pendências após reinício. Worker de instância única.
- JDBC + Flyway deixam SQL e locks explícitos. O JPA do setup foi substituído por JDBC porque não há entidades ORM.
- H2 em arquivo elimina servidor externo; produção exigiria PostgreSQL e testes próprios de isolamento.
- React nativo atende o fluxo sem instalar Router, cache ou biblioteca de formulário. Polling é cancelável e não sobrepõe chamadas.
- Valores financeiros usam BigDecimal/DECIMAL, nunca double no Java; zeros à esquerda são preservados em contas/agências.

## API

Backend:
- POST /api/v1/authorizations — beneficiarioId e convenioId.
- GET /api/v1/beneficiaries/{id}/agenda.
- POST /api/v1/portabilities — Idempotency-Key obrigatório.
- GET /api/v1/portabilities.
- GET /api/v1/portabilities/{id}.
- GET /api/v1/portabilities/{id}/events.
- POST /api/v1/portabilities/{id}/retry.

~~~json
{
  "beneficiarioId": "BEN-001",
  "parcelas": ["BEN-001-CONV-001-1"],
  "credorCessionario": "Cielo",
  "contaRecebimento": {"banco": "237", "agencia": "0001", "conta": "12345-6"},
  "cenario": "CONFIRMAR"
}
~~~

Central: POST /autorizacoes; GET /beneficiarios/{id}/agenda; POST /operacoes-portabilidade; GET /operacoes-portabilidade/{protocolo}. Saúde: /actuator/health em ambos.

HMAC-SHA256 hexadecimal em X-Signature; X-Timestamp em segundos UTC.
Requisição: METHOD + newline + PATH + "|" + KEY_OR_EMPTY + newline + TIMESTAMP + newline + RAW_BODY.
Resposta: STATUS + newline + RETRY_AFTER_OR_EMPTY + newline + RAW_BODY.
Comparação em tempo constante, janela de cinco minutos. A chave de idempotência também é assinada. O cliente e2e demonstra o contrato exato. Corpo vazio em GET/204.

## Trade-offs e evolução

Lock global limita throughput; worker não possui lease para múltiplas réplicas. Retry exponencial determinístico (1, 2, 4 e 8 segundos entre cinco tentativas; teto 30 segundos). Adicionaria jitter em produção. Retry-After é limitado ao intervalo 1–30 segundos.

Auditoria append-only pela aplicação; administrador SQL ainda pode alterá-la. Produção exigiria segregação de permissões e retenção inviolável.

HMAC simplificado não substitui TLS, autenticação, rotação de segredo ou proteção por nonce dentro da janela. Histórico exibe todas as operações locais, sem segregação de usuário.

O mock conclui a operação quando consultado após ready_at; simula confirmação assíncrona observável sem outro scheduler. A Central real teria processamento independente.

Com mais tempo: PostgreSQL, leases, paginação, métricas de fila, OpenAPI, autenticação e suíte automatizada de navegador. Componentes React podem ser extraídos conforme crescerem responsabilidades.

## Bibliotecas

Spring MVC, JDBC/Hikari, Validation, Actuator, Flyway, H2, Jackson 3, Spring Test/JUnit/AssertJ. React/React DOM, TypeScript e Vite. Versões exatas no lockfile; reutilizadas as ferramentas existentes, sem atualização arbitrária para versões do planejamento antigo.

Veja docs/CASE_REVIEW.md e docs/AI_HARNESS.md.

## GitHub Actions

A configuração .github/workflows/ci.yml foi preparada localmente. O GitHub recusou seu envio porque a credencial atual não possui escopo workflow. Por isso o arquivo permanece fora do commit publicado. Para ativar CI, um usuário com a permissão correspondente deve adicioná-lo pelo GitHub ou por uma credencial autorizada. Nenhuma permissão da conta foi alterada pelo agente.
