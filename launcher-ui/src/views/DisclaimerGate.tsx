// Écran 0 (docs/LAUNCHER_UI.md §2) : avertissement à lire EN ENTIER puis accepter. Le bouton « accepter » n'est
// actif qu'après défilement jusqu'en bas + case cochée. Aucune logique réseau.
import { useRef, useState } from "react";
import { DISCLAIMER_PARAGRAPHS } from "../content/disclaimer";
import { t, type Lang } from "../i18n";

export function DisclaimerGate({ lang, onAccept, onDecline }: {
  lang: Lang; onAccept: () => void; onDecline: () => void;
}) {
  const [scrolledToEnd, setScrolledToEnd] = useState(false);
  const [checked, setChecked] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  function onScroll() {
    const el = ref.current;
    if (!el) return;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 8) setScrolledToEnd(true);
  }

  const canAccept = scrolledToEnd && checked;
  return (
    <div className="gate panel stack">
      <h1 style={{ margin: 0, fontSize: 20 }}>{t(lang, "gate.title")}</h1>
      <div className="scroll" ref={ref} onScroll={onScroll}>
        {DISCLAIMER_PARAGRAPHS.map((p, i) => <p key={i}>{p}</p>)}
      </div>
      {!scrolledToEnd && <div className="muted">{t(lang, "gate.scrollHint")}</div>}
      <label className="row" style={{ opacity: scrolledToEnd ? 1 : 0.5 }}>
        <input type="checkbox" style={{ width: "auto" }} disabled={!scrolledToEnd}
               checked={checked} onChange={(e) => setChecked(e.target.checked)} />
        <span>{t(lang, "gate.checkbox")}</span>
      </label>
      <div className="actions">
        <button onClick={onDecline}>{t(lang, "gate.decline")}</button>
        <button className="primary" disabled={!canAccept} onClick={onAccept}>{t(lang, "gate.accept")}</button>
      </div>
    </div>
  );
}
