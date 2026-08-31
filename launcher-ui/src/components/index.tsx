// Briques réutilisables — AUCUNE logique réseau : données + callbacks en props. Restylables via les classes CSS.
import type { ReactNode } from "react";

export function Spinner() { return <span className="spinner" aria-label="chargement" />; }

export function StatusDot({ state }: { state: "ok" | "err" | "warn" }) {
  return <span className={`dot ${state}`} />;
}

export function Banner({ kind, children }: { kind: "info" | "error" | "success"; children: ReactNode }) {
  return <div className={`banner ${kind}`}>{children}</div>;
}

export function Panel({ title, children }: { title?: string; children: ReactNode }) {
  return (
    <div className="panel stack">
      {title && <h2 style={{ margin: 0, fontSize: 18 }}>{title}</h2>}
      {children}
    </div>
  );
}

export function CenterScreen({ children }: { children: ReactNode }) {
  return <div className="center-screen">{children}</div>;
}
