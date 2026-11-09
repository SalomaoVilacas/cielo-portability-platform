import { useEffect, useRef, useState } from "react";
import type { FormEvent } from "react";
import { api } from "./api";
import { canonical, eligible, isActive, submissionKey } from "./domain";
import type { Account, AuditEvent, Installment, Operation, Submission } from "./types";

const money = (n: number) => n.toLocaleString("pt-BR", { style: "currency", currency: "BRL" });
const date = (s: string) =>
    new Date(s.length === 10 ? s + "T12:00:00" : s).toLocaleString(
        "pt-BR",
        s.length === 10 ? { dateStyle: "short" } : { dateStyle: "short", timeStyle: "medium" },
    );
const labels: Record<string, string> = {
    PENDENTE_ENVIO: "Aguardando envio",
    PENDENTE: "Em processamento",
    CONFIRMADA: "Confirmada",
    REJEITADA: "Rejeitada",
    FALHA_TECNICA: "Falha de integração",
    ABERTA: "Disponível",
    RESERVADA: "Reservada",
    PORTADA: "Portada",
    PAGA: "Paga",
};
export default function App() {
    const [tab, setTab] = useState<"agenda" | "history">("agenda");
    const [beneficiary, setBeneficiary] = useState("BEN-001");
    const [agreement, setAgreement] = useState("CONV-001");
    const [consent, setConsent] = useState(false);
    const [authorized, setAuthorized] = useState("");
    const [agenda, setAgenda] = useState<Installment[]>([]);
    const [selected, setSelected] = useState<string[]>([]);
    const [creditor, setCreditor] = useState("Cielo");
    const [account, setAccount] = useState<Account>({
        banco: "",
        agencia: "",
        conta: "",
    });
    const [scenario, setScenario] = useState("CONFIRMAR");
    const [operations, setOperations] = useState<Operation[]>([]);
    const [current, setCurrent] = useState<Operation | null>(null);
    const [events, setEvents] = useState<AuditEvent[]>([]);
    const [busy, setBusy] = useState(false);
    const lock = useRef(false);
    const [error, setError] = useState("");
    const [notice, setNotice] = useState("");
    const [pollError, setPollError] = useState("");
    async function action(work: () => Promise<void>) {
        if (lock.current) return;
        lock.current = true;
        setBusy(true);
        setError("");
        setNotice("");
        try {
            await work();
        } catch (e) {
            setError(e instanceof Error ? e.message : "Falha inesperada");
        } finally {
            lock.current = false;
            setBusy(false);
        }
    }
    async function refreshAgenda(id = beneficiary) {
        const rows = await api<Installment[]>(
            "/beneficiaries/" + encodeURIComponent(id) + "/agenda",
        );
        setAgenda(rows);
        setSelected((ids) =>
            ids.filter((id) => rows.some((p) => p.parcelaId === id && eligible(p))),
        );
    }
    async function authorize(e: FormEvent) {
        e.preventDefault();
        await action(async () => {
            await api("/authorizations", {
                method: "POST",
                body: JSON.stringify({
                    beneficiarioId: beneficiary,
                    convenioId: agreement,
                }),
            });
            setAuthorized(beneficiary);
            await refreshAgenda();
            setNotice("Autorização registrada. Selecione as parcelas disponíveis.");
        });
    }
    async function openOperation(op: Operation) {
        await action(async () => {
            const history = await api<AuditEvent[]>("/portabilities/" + op.id + "/events");
            setCurrent(op);
            setEvents(history);
        });
    }
    async function submit(e: FormEvent) {
        e.preventDefault();
        await action(async () => {
            const request: Submission = {
                beneficiarioId: beneficiary,
                parcelas: selected,
                credorCessionario: creditor,
                contaRecebimento: account,
                cenario: scenario,
            };
            const payload = canonical(request);
            const key = submissionKey(sessionStorage, payload);
            const result = await api<Operation>("/portabilities", {
                method: "POST",
                headers: { "Idempotency-Key": key },
                body: payload,
            });
            setCurrent(result);
            setEvents(await api<AuditEvent[]>("/portabilities/" + result.id + "/events"));
            setTab("history");
            setSelected([]);
            setNotice("Solicitação registrada. O acompanhamento é automático.");
        });
    }
    useEffect(() => {
        const controller = new AbortController();
        let timer: ReturnType<typeof setTimeout>;
        async function poll() {
            try {
                const rows = await api<Operation[]>("/portabilities", {
                    signal: controller.signal,
                });
                if (controller.signal.aborted) return;
                setOperations(rows);
                setPollError("");
                if (current) {
                    const op = rows.find((o) => o.id === current.id);
                    const history = await api<AuditEvent[]>(
                        "/portabilities/" + current.id + "/events",
                        { signal: controller.signal },
                    );
                    if (controller.signal.aborted) return;
                    if (op) setCurrent(op);
                    setEvents(history);
                }
            } catch (e) {
                if (!controller.signal.aborted)
                    setPollError(
                        e instanceof Error ? e.message : "Não foi possível atualizar o histórico",
                    );
            } finally {
                if (!controller.signal.aborted) timer = setTimeout(poll, 2000);
            }
        }
        void poll();
        return () => {
            controller.abort();
            clearTimeout(timer);
        };
    }, [current?.id]);
    const total =
        agenda
            .filter((p) => selected.includes(p.parcelaId))
            .reduce((sum, p) => sum + Math.round(p.valor * 100), 0) / 100;
    return (
        <div className="app">
            <aside>
                <a className="brand" href="/">
                    porta<span>●</span>
                </a>
                <p className="brand-note">CONVÊNIOS · PORTABILIDADE</p>
                <nav aria-label="Navegação principal">
                    <button
                        className={tab === "agenda" ? "active" : ""}
                        onClick={() => setTab("agenda")}
                    >
                        ↗ Nova portabilidade
                    </button>
                    <button
                        className={tab === "history" ? "active" : ""}
                        onClick={() => setTab("history")}
                    >
                        ◷ Histórico e auditoria
                    </button>
                </nav>
                <div className="aside-foot">
                    <span className="dot" /> Ambiente de demonstração
                    <p>
                        Dados fictícios
                        <br />
                        Case Full Stack · Java + React
                    </p>
                </div>
            </aside>
            <main>
                <header>
                    <div>
                        <p className="eyebrow">PLATAFORMA DE CONVÊNIOS</p>
                        <h1>
                            {tab === "agenda"
                                ? "Uma nova rota para seus recebimentos."
                                : "Cada operação, do início ao fim."}
                        </h1>
                        <p>
                            {tab === "agenda"
                                ? "Autorize a consulta, escolha as parcelas e acompanhe a transferência."
                                : "Acompanhe os retornos da Central e a trilha de cada solicitação."}
                        </p>
                    </div>
                    <span className="environment">SANDBOX</span>
                </header>
                {error && (
                    <div role="alert" className="message error">
                        {error}
                    </div>
                )}
                {pollError && (
                    <div role="alert" className="message error">
                        Atualização automática: {pollError}
                    </div>
                )}
                {notice && (
                    <div role="status" className="message success">
                        {notice}
                    </div>
                )}
                {tab === "agenda" ? (
                    <>
                        <section className="card">
                            <div className="section-heading">
                                <span className="step">01</span>
                                <div>
                                    <h2>Autorização do beneficiário</h2>
                                    <p>
                                        O consentimento libera a consulta das parcelas do convênio.
                                    </p>
                                </div>
                            </div>
                            <form onSubmit={authorize}>
                                <div className="fields">
                                    <label>
                                        Identificador do beneficiário
                                        <input
                                            required
                                            pattern="[A-Za-z0-9-]{1,60}"
                                            value={beneficiary}
                                            disabled={busy}
                                            onChange={(e) => {
                                                setBeneficiary(e.target.value);
                                                setAuthorized("");
                                                setAgenda([]);
                                                setSelected([]);
                                                setConsent(false);
                                            }}
                                        />
                                    </label>
                                    <label>
                                        Convênio
                                        <input
                                            required
                                            pattern="[A-Za-z0-9-]{1,60}"
                                            disabled={busy}
                                            value={agreement}
                                            onChange={(e) => {
                                                setAgreement(e.target.value);
                                                setConsent(false);
                                                setAuthorized("");
                                                setAgenda([]);
                                                setSelected([]);
                                            }}
                                        />
                                    </label>
                                </div>
                                <label className="check">
                                    <input
                                        type="checkbox"
                                        checked={consent}
                                        onChange={(e) => setConsent(e.target.checked)}
                                        required
                                        disabled={busy}
                                    />{" "}
                                    Autorizo a consulta da agenda de descontos deste beneficiário.
                                </label>
                                <button className="primary" disabled={busy || !consent}>
                                    {busy ? "Processando…" : "Autorizar e consultar agenda"}
                                </button>
                            </form>
                        </section>
                        {authorized && (
                            <>
                                <section className="card">
                                    <div className="section-heading">
                                        <span className="step">02</span>
                                        <div>
                                            <h2>Agenda de descontos</h2>
                                            <p>
                                                Somente parcelas abertas e futuras podem ser
                                                transferidas.
                                            </p>
                                        </div>
                                        <button
                                            className="secondary push"
                                            disabled={busy}
                                            onClick={() => void action(() => refreshAgenda())}
                                        >
                                            Atualizar agenda
                                        </button>
                                    </div>
                                    <div className="table-wrap">
                                        <table>
                                            <thead>
                                                <tr>
                                                    <th>Selecionar</th>
                                                    <th>Parcela / convênio</th>
                                                    <th>Desconto</th>
                                                    <th>Valor</th>
                                                    <th>Situação</th>
                                                    <th>Conta atual</th>
                                                </tr>
                                            </thead>
                                            <tbody>
                                                {agenda.map((p) => (
                                                    <tr key={p.parcelaId}>
                                                        <td>
                                                            <input
                                                                aria-label={
                                                                    "Selecionar " + p.parcelaId
                                                                }
                                                                type="checkbox"
                                                                disabled={busy || !eligible(p)}
                                                                checked={selected.includes(
                                                                    p.parcelaId,
                                                                )}
                                                                onChange={(e) =>
                                                                    setSelected((ids) =>
                                                                        e.target.checked
                                                                            ? [...ids, p.parcelaId]
                                                                            : ids.filter(
                                                                                  (id) =>
                                                                                      id !==
                                                                                      p.parcelaId,
                                                                              ),
                                                                    )
                                                                }
                                                            />
                                                        </td>
                                                        <td>
                                                            <strong>{p.parcelaId}</strong>
                                                            <small>{p.convenioId}</small>
                                                        </td>
                                                        <td>{date(p.dataDesconto)}</td>
                                                        <td className="amount">{money(p.valor)}</td>
                                                        <td>
                                                            <span className={"badge " + p.status}>
                                                                {labels[p.status] || p.status}
                                                            </span>
                                                        </td>
                                                        <td>
                                                            <small>
                                                                Banco {p.contaRecebimento.banco} ·
                                                                Ag. {p.contaRecebimento.agencia}
                                                                <br />
                                                                Conta {p.contaRecebimento.conta}
                                                            </small>
                                                        </td>
                                                    </tr>
                                                ))}
                                            </tbody>
                                        </table>
                                    </div>
                                    {!agenda.length && (
                                        <p className="empty">
                                            Nenhuma parcela encontrada para este beneficiário.
                                        </p>
                                    )}
                                    <div className="selection">
                                        <span>{selected.length} parcela(s) selecionada(s)</span>
                                        <strong>{money(total)}</strong>
                                    </div>
                                </section>
                                <section className="card">
                                    <div className="section-heading">
                                        <span className="step">03</span>
                                        <div>
                                            <h2>Destino dos recebimentos</h2>
                                            <p>
                                                A conta será atualizada somente após a confirmação
                                                da Central.
                                            </p>
                                        </div>
                                    </div>
                                    <form onSubmit={submit}>
                                        <div className="fields">
                                            <label>
                                                Novo credor
                                                <input
                                                    required
                                                    maxLength={100}
                                                    value={creditor}
                                                    disabled={busy}
                                                    onChange={(e) => setCreditor(e.target.value)}
                                                />
                                            </label>
                                            {(["banco", "agencia", "conta"] as const).map(
                                                (field) => (
                                                    <label key={field}>
                                                        {
                                                            {
                                                                banco: "Banco (3 dígitos)",
                                                                agencia: "Agência",
                                                                conta: "Conta com dígito",
                                                            }[field]
                                                        }
                                                        <input
                                                            required
                                                            disabled={busy}
                                                            inputMode="numeric"
                                                            pattern={
                                                                field === "banco"
                                                                    ? "[0-9]{3}"
                                                                    : field === "agencia"
                                                                      ? "[0-9]{1,6}"
                                                                      : "[0-9-]{1,20}"
                                                            }
                                                            value={account[field]}
                                                            onChange={(e) =>
                                                                setAccount({
                                                                    ...account,
                                                                    [field]: e.target.value,
                                                                })
                                                            }
                                                        />
                                                    </label>
                                                ),
                                            )}
                                        </div>
                                        <details>
                                            <summary>Cenários de demonstração</summary>
                                            <label>
                                                Comportamento da Central
                                                <select
                                                    disabled={busy}
                                                    value={scenario}
                                                    onChange={(e) => setScenario(e.target.value)}
                                                >
                                                    <option value="CONFIRMAR">
                                                        Confirmação após alguns segundos
                                                    </option>
                                                    <option value="REJEITAR">
                                                        Rejeição de negócio
                                                    </option>
                                                    <option value="INDISPONIVEL">
                                                        Central indisponível
                                                    </option>
                                                    <option value="TIMEOUT">
                                                        Timeout após aceitar a operação
                                                    </option>
                                                    <option value="RESPOSTA_INVALIDA">
                                                        Protocolo inválido
                                                    </option>
                                                    <option value="ASSINATURA_INVALIDA">
                                                        Assinatura de resposta inválida
                                                    </option>
                                                </select>
                                            </label>
                                        </details>
                                        <button
                                            className="primary"
                                            disabled={busy || !selected.length}
                                        >
                                            {busy ? "Registrando…" : "Solicitar portabilidade →"}
                                        </button>
                                    </form>
                                </section>
                            </>
                        )}
                    </>
                ) : (
                    <>
                        <div className="metrics">
                            <div>
                                <span>Total de operações</span>
                                <strong>{operations.length}</strong>
                            </div>
                            <div>
                                <span>Em processamento</span>
                                <strong>
                                    {operations.filter((o) => isActive(o.status)).length}
                                </strong>
                            </div>
                            <div>
                                <span>Confirmadas</span>
                                <strong>
                                    {operations.filter((o) => o.status === "CONFIRMADA").length}
                                </strong>
                            </div>
                        </div>
                        <section className="card">
                            <h2>Operações</h2>
                            <p>Atualização automática a cada 2 segundos.</p>
                            <div className="table-wrap">
                                <table>
                                    <thead>
                                        <tr>
                                            <th>Solicitada em</th>
                                            <th>Beneficiário</th>
                                            <th>Situação</th>
                                            <th>Protocolo</th>
                                            <th>Detalhes</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {operations.map((o) => (
                                            <tr key={o.id}>
                                                <td>{date(o.criadaEm)}</td>
                                                <td>{o.beneficiarioId}</td>
                                                <td>
                                                    <span className={"badge " + o.status}>
                                                        {labels[o.status]}
                                                    </span>
                                                </td>
                                                <td>
                                                    <code>
                                                        {o.protocolo || "Aguardando Central"}
                                                    </code>
                                                </td>
                                                <td>
                                                    <button
                                                        className="secondary"
                                                        disabled={busy}
                                                        onClick={() => void openOperation(o)}
                                                    >
                                                        Ver trilha
                                                    </button>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                            {!operations.length && (
                                <p className="empty">Você ainda não registrou uma portabilidade.</p>
                            )}
                        </section>
                        {current && (
                            <section className="card">
                                <h2>Trilha da operação</h2>
                                <p>
                                    <code>{current.id}</code>
                                </p>
                                {current.motivo && (
                                    <div className="message error">{current.motivo}</div>
                                )}
                                {current.status === "FALHA_TECNICA" && (
                                    <>
                                        <p>
                                            A reserva das parcelas foi mantida para evitar
                                            duplicidade. Retome após resolver a falha de integração.
                                        </p>
                                        <button
                                            className="secondary"
                                            disabled={busy}
                                            onClick={() =>
                                                void action(async () => {
                                                    setCurrent(
                                                        await api<Operation>(
                                                            "/portabilities/" +
                                                                current.id +
                                                                "/retry",
                                                            { method: "POST" },
                                                        ),
                                                    );
                                                })
                                            }
                                        >
                                            Retomar com a mesma solicitação
                                        </button>
                                    </>
                                )}
                                <ol className="timeline">
                                    {events.map((e, i) => (
                                        <li key={i}>
                                            <span className="timeline-dot" />
                                            <div>
                                                <strong>{labels[e.tipo] || e.tipo}</strong>
                                                <time>{date(e.instante)}</time>
                                                <p>{e.detalhe}</p>
                                            </div>
                                        </li>
                                    ))}
                                </ol>
                            </section>
                        )}
                    </>
                )}
                <footer>
                    Porta · Simulador de portabilidade de convênios · Dados persistidos localmente
                </footer>
            </main>
        </div>
    );
}
