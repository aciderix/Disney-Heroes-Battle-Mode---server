// Composants pour Héberger/Générer (sans logique réseau). PathInput = champ + « Parcourir… » (natif Tauri, sinon
// saisie manuelle). LogConsole = tail monospace auto-scroll. StepProgress = étape courante + état (données du backend).
import { useEffect, useRef } from "react";
import { pickFile, pickDir } from "../api/tauriBridge";
import type { BuildState } from "../api/types";

export function PathInput({ value, onChange, placeholder, kind, browseLabel = "Parcourir…" }: {
  value: string; onChange: (v: string) => void; placeholder?: string; kind: "file" | "dir"; browseLabel?: string;
}) {
  async function browse() {
    const p = kind === "file" ? await pickFile(placeholder ?? "Choisir un fichier") : await pickDir(placeholder ?? "Choisir un dossier");
    if (p) onChange(p);
  }
  return (
    <div className="row">
      <input placeholder={placeholder} value={value} onChange={(e) => onChange(e.target.value)} />
      <button onClick={browse}>{browseLabel}</button>
    </div>
  );
}

export function StepProgress({ state, step }: { state: BuildState; step: string }) {
  const badge = state === "RUNNING" ? "⏳" : state === "DONE" ? "✓" : state === "FAILED" ? "✗" : "•";
  const color = state === "FAILED" ? "var(--err)" : state === "DONE" ? "var(--ok)" : "var(--text-dim)";
  return (
    <div className="row" style={{ color }}>
      <strong>{badge} {state}</strong>
      {step && <span className="muted">— étape : {step}</span>}
    </div>
  );
}

export function LogConsole({ text }: { text: string }) {
  const ref = useRef<HTMLPreElement>(null);
  useEffect(() => { const el = ref.current; if (el) el.scrollTop = el.scrollHeight; }, [text]);
  return (
    <pre ref={ref} style={{
      fontFamily: "var(--font-mono)", fontSize: 12, background: "var(--bg-elev)", color: "var(--text)",
      border: "1px solid var(--border)", borderRadius: "var(--radius-sm)", padding: 12,
      maxHeight: "34vh", overflow: "auto", whiteSpace: "pre-wrap", margin: 0,
    }}>{text || "(aucune sortie)"}</pre>
  );
}
