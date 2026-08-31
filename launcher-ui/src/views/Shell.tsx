// Coquille : barre latérale + zone principale. PRINCIPE : on n'affiche QUE les sections dont les endpoints sont
// implémentés ET testés (pas de bouton « à venir » qui ferait croire qu'une fonction existe). Les sections suivantes
// (Héberger, Générer, Jouer, Réglages, Admin) apparaîtront quand leurs écrans seront livrés.
import { useState, type ReactNode } from "react";
import { useApp } from "../state/store";
import { StatusDot } from "../components";
import { Servers } from "./Servers";
import { Account } from "./Account";
import { Host } from "./Host";
import { Generate } from "./Generate";
import { Play } from "./Play";
import { Admin } from "./Admin";
import { SettingsView } from "./Settings";
import { t, type Lang, type MsgKey } from "../i18n";

type Section = { id: string; label: MsgKey; view: ReactNode };

export function Shell({ lang }: { lang: Lang }) {
  const { session, selectedServer } = useApp();
  const sections: Section[] = [
    { id: "play", label: "nav.play", view: <Play /> },
    { id: "servers", label: "nav.servers", view: <Servers /> },
    { id: "account", label: "nav.account", view: <Account /> },
    { id: "host", label: "nav.host", view: <Host /> },
    { id: "generate", label: "nav.generate", view: <Generate /> },
    { id: "admin", label: "nav.admin", view: <Admin /> },
    { id: "settings", label: "nav.settings", view: <SettingsView /> },
  ];
  const [active, setActive] = useState("servers");
  const current = sections.find((s) => s.id === active) ?? sections[0];

  return (
    <div className="app">
      <nav className="sidebar">
        <div className="brand">{t(lang, "app.title")}</div>
        {sections.map((s) => (
          <button key={s.id} className={`nav-item ${active === s.id ? "active" : ""}`} onClick={() => setActive(s.id)}>
            {t(lang, s.label)}
          </button>
        ))}
        <div style={{ flex: 1 }} />
        <div className="brand">
          {session ? `Compte #${session.userID}` : "Non connecté"}
          {selectedServer && <><br />▸ {selectedServer.name}</>}
        </div>
      </nav>
      <main className="main">
        <div className="topbar">
          <h1 style={{ margin: 0, fontSize: 18 }}>{t(lang, current.label)}</h1>
          <span className="row"><StatusDot state={session ? "ok" : "warn"} /><span className="muted">{session ? "connecté" : "hors ligne"}</span></span>
        </div>
        {current.view}
      </main>
    </div>
  );
}
