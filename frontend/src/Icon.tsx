import type { ReactNode } from "react";

type IconName =
    | "transfer"
    | "history"
    | "shield"
    | "chevron"
    | "arrow"
    | "refresh"
    | "check"
    | "alert"
    | "layers"
    | "clock";
const shapes: Record<IconName, ReactNode> = {
    transfer: (
        <>
            <path d="M4 8h15m-4-4 4 4-4 4M20 16H5m4-4-4 4 4 4" />
        </>
    ),
    history: (
        <>
            <path d="M4 10a8 8 0 1 1 1 7M4 4v6h6M12 7v5l3 2" />
        </>
    ),
    shield: (
        <>
            <path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Z" />
            <path d="m8.5 12 2.5 2.5 4.5-5" />
        </>
    ),
    chevron: <path d="m9 5 7 7-7 7" />,
    arrow: <path d="M4 12h16m-6-6 6 6-6 6" />,
    refresh: (
        <>
            <path d="M20 7v5h-5M4 17v-5h5" />
            <path d="M6.1 7a7 7 0 0 1 11.5-1L20 9M4 15l2.4 3A7 7 0 0 0 18 17" />
        </>
    ),
    check: <path d="m5 12 4.5 4.5L19 7" />,
    alert: (
        <>
            <path d="M12 8v5m0 4h.01M10.3 4.5 2.6 18a1.5 1.5 0 0 0 1.3 2.2h16.2a1.5 1.5 0 0 0 1.3-2.2L13.7 4.5a2 2 0 0 0-3.4 0Z" />
        </>
    ),
    layers: (
        <>
            <path d="m12 3 9 5-9 5-9-5 9-5Zm-9 9 9 5 9-5M3 16l9 5 9-5" />
        </>
    ),
    clock: (
        <>
            <circle cx="12" cy="12" r="8.5" />
            <path d="M12 7v5l3 2" />
        </>
    ),
};
export default function Icon({ name, className = "" }: { name: IconName; className?: string }) {
    return (
        <svg
            className={"icon " + className}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={1.75}
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            focusable="false"
        >
            {shapes[name]}
        </svg>
    );
}
