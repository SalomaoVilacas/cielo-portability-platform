import type { Installment, Submission } from "./types.ts";
export function eligible(p: Installment, today = new Date().toLocaleDateString("en-CA")): boolean {
    return p.status === "ABERTA" && p.dataDesconto > today;
}
export function canonical(request: Submission): string {
    return JSON.stringify({
        ...request,
        parcelas: [...new Set(request.parcelas)].sort(),
    });
}
export function isActive(status: string): boolean {
    return status === "PENDENTE" || status === "PENDENTE_ENVIO";
}
export function submissionKey(
    storage: Pick<Storage, "getItem" | "setItem">,
    payload: string,
): string {
    const saved = storage.getItem("portability-submission");
    if (saved) {
        try {
            const previous = JSON.parse(saved);
            if (previous.payload === payload) return previous.key;
        } catch {
            /* Replace invalid local session data. */
        }
    }
    const key = crypto.randomUUID();
    storage.setItem("portability-submission", JSON.stringify({ key, payload }));
    return key;
}
