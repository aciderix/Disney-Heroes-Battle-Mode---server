// Racine : (1) attend le daemon local (health), (2) gate d'avertissement (1er lancement), (3) coquille de l'app.
// L'acceptation est mémorisée localement (localStorage) en attendant la persistance settings.json (backend §7).
import { useEffect, useState } from "react";
import { daemonClient } from "./api/daemonClient";
import { DISCLAIMER_VERSION } from "./content/disclaimer";
import { CenterScreen, Spinner, Banner } from "./components";
import { DisclaimerGate } from "./views/DisclaimerGate";
import { Shell } from "./views/Shell";
import { AppStateProvider } from "./state/store";
import { t, type Lang } from "./i18n";
import "./theme/base.css";

type Health = "waiting" | "ok" | "down";
const ACCEPT_KEY = "dh.disclaimer.accepted";

function readAccepted(): boolean {
  try { return Number(localStorage.getItem(ACCEPT_KEY)) >= DISCLAIMER_VERSION; } catch { return false; }
}

export default function App() {
  const [health, setHealth] = useState<Health>("waiting");
  const [accepted, setAccepted] = useState<boolean>(readAccepted());
  const [lang] = useState<Lang>("fr");

  useEffect(() => {
    let alive = true;
    const deadline = Date.now() + 30_000;
    (async function poll() {
      while (alive && Date.now() < deadline) {
        try {
          const h = await daemonClient.health();
          if (h.ok) { if (alive) setHealth("ok"); return; }
        } catch { /* daemon pas encore prêt */ }
        await new Promise((r) => setTimeout(r, 500));
      }
      if (alive) setHealth("down");
    })();
    return () => { alive = false; };
  }, []);

  if (health === "waiting") return <CenterScreen><Spinner /><div className="muted">{t(lang, "daemon.waiting")}</div></CenterScreen>;
  if (health === "down") return <CenterScreen><Banner kind="error">{t(lang, "daemon.down")}</Banner></CenterScreen>;

  if (!accepted) {
    return (
      <DisclaimerGate
        lang={lang}
        onAccept={() => { try { localStorage.setItem(ACCEPT_KEY, String(DISCLAIMER_VERSION)); } catch { /* ignore */ } setAccepted(true); }}
        onDecline={() => { window.close(); }}
      />
    );
  }
  return (
    <AppStateProvider>
      <Shell lang={lang} />
    </AppStateProvider>
  );
}
