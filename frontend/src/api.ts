export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
    const response = await fetch("/api/v1" + path, {
        ...options,
        headers: { "Content-Type": "application/json", ...options.headers },
    });
    if (!response.ok) {
        const problem = await response.json().catch(() => ({}));
        throw new Error(
            problem.detail || "Não foi possível completar a solicitação. Tente novamente.",
        );
    }
    return response.json() as Promise<T>;
}
