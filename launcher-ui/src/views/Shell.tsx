// Coquille de l'app après acceptation : barre latérale (sections) + zone principale. Les écrans réels (Compte,
// Serveurs, Héberger, Générer, Jouer, Admin, Réglages) arrivent aux incréments 2→6 ; ici des placeholders honnêtes.
import { useState } from "react";
import { Panel } from "../components";
import { t, type Lang, type MsgKey } from "../i18n";

type Section = { id: string; label: MsgKey; ready: boolean };
const SECTIONS: Section[] = [
  { id: "play", label: "nav.play", ready: false },
  { id: "servers", label: "nav.servers", ready: false },
  { id: "host", label: "nav.host", ready: false },
  { id: "generate", label: "nav.generate", ready: false },
  { id: "admin", label: "nav.admin", ready: false },
  { id: "settings", label: "nav.settings", ready: false },
];

export function Shell({ lang }: { lang: Lang }) {
  const [active, setActive] = useState("servers");
  return (
    <div className="app">
      <nav className="sidebar">
        <div className="brand">{t(lang, "app.title")}</div>
        {SECTIONS.map((s) => (
          <button key={s.id} className={`nav-item ${active === s.id ? "active" : ""}`} onClick={() => setActive(s.id)}>
            {t(lang, s.label)}
          </button>
        ))}
      </nav>
      <main className="main">
        <Panel title={t(lang, SECTIONS.find((s) => s.id === active)!.label)}>
          <div className="muted">
            Écran « {active} » — {t(lang, "common.soon")}. Livré aux incréments suivants (voir docs/LAUNCHER_UI.md §9).
          </div>
        </Panel>
      </main>
    </div>
  );
}
