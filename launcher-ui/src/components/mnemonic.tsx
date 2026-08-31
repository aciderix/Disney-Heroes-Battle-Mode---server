// Composants d'identité mnémonique (sans réseau). MnemonicWordGrid affiche les 8 mots générés ; Bip39Input saisit
// une phrase avec autocomplétion (datalist BIP39) + validation d'APPARTENANCE (chaque mot ∈ wordlist). La validation
// finale (checksum + auth) est faite par le SERVEUR (register/login) — on ne prétend rien valider qu'on ne valide pas.
import { useState } from "react";
import { BIP39_WORDS, BIP39_SET } from "../content/bip39";

export const WORD_COUNT = 8;

export function MnemonicWordGrid({ phrase }: { phrase: string }) {
  const [copied, setCopied] = useState(false);
  const words = phrase.split(/\s+/).filter(Boolean);
  async function copy() {
    try { await navigator.clipboard.writeText(phrase); setCopied(true); setTimeout(() => setCopied(false), 1500); }
    catch { /* ignore */ }
  }
  return (
    <div className="stack">
      <div style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: 8 }}>
        {words.map((w, i) => (
          <div key={i} className="row" style={{ background: "var(--bg-elev)", borderRadius: "var(--radius-sm)", padding: "8px 12px" }}>
            <span className="muted" style={{ width: 20 }}>{i + 1}.</span>
            <strong style={{ fontFamily: "var(--font-mono)", fontSize: 16 }}>{w}</strong>
          </div>
        ))}
      </div>
      <button onClick={copy}>{copied ? "Copié ✓" : "Copier les 8 mots"}</button>
    </div>
  );
}

/** Saisie de la phrase (WORD_COUNT champs). onChange(phrase, allKnown). allKnown = tous les mots ∈ wordlist. */
export function Bip39Input({ onChange }: { onChange: (phrase: string, allKnown: boolean) => void }) {
  const [words, setWords] = useState<string[]>(Array(WORD_COUNT).fill(""));
  function update(i: number, v: string) {
    const next = words.slice();
    next[i] = v.trim().toLowerCase();
    setWords(next);
    const phrase = next.join(" ").trim();
    const allKnown = next.every((w) => BIP39_SET.has(w));
    onChange(phrase, allKnown && next.every((w) => w.length > 0));
  }
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(2, 1fr)", gap: 8 }}>
      <datalist id="bip39">{BIP39_WORDS.map((w) => <option key={w} value={w} />)}</datalist>
      {words.map((w, i) => {
        const known = w.length === 0 || BIP39_SET.has(w);
        return (
          <div key={i} className="row">
            <span className="muted" style={{ width: 20 }}>{i + 1}.</span>
            <input list="bip39" value={w} onChange={(e) => update(i, e.target.value)}
                   style={{ borderColor: known ? undefined : "var(--err)" }}
                   autoComplete="off" spellCheck={false} />
          </div>
        );
      })}
    </div>
  );
}
