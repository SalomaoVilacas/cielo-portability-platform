# Relatório final — Porta / Cielo
Data: 9 de novembro de 2026.

Implementação funcional dos fluxos do PDF: opt-in, agenda, seleção, portabilidade, confirmação/rejeição assíncrona, atualização da conta, auditoria e falhas explícitas. Backend Java/Spring, frontend React/TypeScript e Central independente. H2 persistente, migrações, HMAC e idempotência nos dois serviços.

## Validação
- 11 testes Java: 7 backend, 4 Central, sem falhas.
- 4 testes frontend, sem falhas.
- Compilação TypeScript e build Vite aprovados.
- Testes ponta a ponta com dois processos reais: confirmação/conta, concorrência e replay, conflito de payload, rejeição, reserva, falhas de protocolo e assinatura, indisponibilidade, timeout após aceite, persistência após reinício e 204/Retry-After.
- Fluxo de autorização → seleção → envio → confirmação → auditoria validado no navegador.
- Nenhuma instalação global nem download de bibliotecas durante a implementação local.
- CI preparada localmente, mas não enviada: a credencial GitHub não possui escopo workflow. A suíte local foi validada; não houve execução remota de CI.

## Mudanças técnicas em relação ao planejamento
JDBC substituiu JPA sem uso para deixar transações, locks e constraints explícitos. HttpClient do JDK implementa o adaptador da Central. Frontend usa React nativo, estado local e polling cancelável, sem adicionar as bibliotecas opcionais do planejamento. Os padrões efetivamente aplicados são documentados abaixo.

## Limites
Worker de instância única; HMAC simplificado e segredo de demonstração; sem autenticação de usuário; auditoria imutável pela aplicação, sem proteção contra administrador SQL; sem validação bancária real. Esses limites são escolhas de case, não garantias de produção.
