import { useEffect, useRef, useState } from "react";
import type { Dispatch, SetStateAction } from "react";
import { api } from "../api";
import { eligible } from "../domain";
import type { Operation, AuditEvent, Installment } from "../types";

export function useOperations(
    authorized: string,
    setAgenda: Dispatch<SetStateAction<Installment[]>>,
    setSelected: Dispatch<SetStateAction<string[]>>,
) {
    const [operations, setOperations] = useState<Operation[]>([]);
    const [current, setCurrent] = useState<Operation | null>(null);
    const [events, setEvents] = useState<AuditEvent[]>([]);
    const [pollError, setPollError] = useState("");
    const agendaVersion = useRef("");
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
                    const operation = rows.find((row) => row.id === current.id);
                    const history = await api<AuditEvent[]>(
                        "/portabilities/" + current.id + "/events",
                        { signal: controller.signal },
                    );
                    if (controller.signal.aborted) return;
                    if (operation) setCurrent(operation);
                    setEvents(history);
                }
                const terminal = rows
                    .filter(
                        (row) =>
                            row.beneficiarioId === authorized &&
                            (row.status === "CONFIRMADA" || row.status === "REJEITADA"),
                    )
                    .map((row) => row.id + row.status)
                    .sort()
                    .join("|");
                const version = authorized + ":" + terminal;
                if (authorized && version !== agendaVersion.current) {
                    const agenda = await api<Installment[]>(
                        "/beneficiaries/" + encodeURIComponent(authorized) + "/agenda",
                        { signal: controller.signal },
                    );
                    if (controller.signal.aborted) return;
                    setAgenda(agenda);
                    setSelected((ids) =>
                        ids.filter((id) =>
                            agenda.some((parcel) => parcel.parcelaId === id && eligible(parcel)),
                        ),
                    );
                    agendaVersion.current = version;
                }
            } catch (error) {
                if (!controller.signal.aborted)
                    setPollError(
                        error instanceof Error
                            ? error.message
                            : "Não foi possível atualizar o histórico",
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
    }, [current?.id, authorized, setAgenda, setSelected]);
    return { operations, current, setCurrent, events, setEvents, pollError };
}
