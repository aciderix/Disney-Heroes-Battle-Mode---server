// Racine : (1) attend le daemon (health), (2) charge les réglages (/settings), (3) gate d'avertissement (persisté
// côté daemon : disclaimerAcceptedVersion), (4) coquille. La langue vient des réglages.
import { useEffect, useState } from "react";
import { daemonClient } from "./api/daemonClient";
import { DISCLAIMER_VERSION } from "./content/disclaimer";
import type { Settings } from "./api/types";
import { CenterScreen, Spinner, Banner } from "./components";
import { DisclaimerGate } from "./views/DisclaimerGate";
import { Shell } from "./views/Shell";
import { AppStateProvider } from "./state/store";
import { type Lang } from "./i18n";
import "./theme/base.css";

type Health = "waiting" | "ok" | "down";
const DEFAULTS: Settings = { language: "fr", disclaimerAcceptedVersion: 0, apkPath: "", outDir: "", clientDir: "", bundleDir: "" };

export default function App() {
  const [health, setHealth] = useState<Health>("waiting");
  const [settings, setSettings] = useState<Settings | null>(null);

  useEffect(() => {
    let alive = true;
    const deadline = Date.now() + 30_000;
    (async function poll() {
      while (alive && Date.now() < deadline) {
        try { if ((await daemonClient.health()).ok) { if (alive) setHealth("ok"); return; } } catch { /* pas prêt */ }
        await new Promise((r) => setTimeout(r, 500));
      }
      if (alive) setHealth("down");
    })();
    return () => { alive = false; };
  }, []);

  useEffect(() => {
    if (health !== "ok") return;
    daemonClient.getSettings().then(setSettings).catch(() => setSettings(DEFAULTS));
  }, [health]);

  if (health === "waiting") return <CenterScreen><Spinner /><div className="muted">Connexion au launcher local…</div></CenterScreen>;
  if (health === "down") return <CenterScreen><Banner kind="error">Launcher local injoignable. Relance l'application.</Banner></CenterScreen>;
  if (!settings) return <CenterScreen><Spinner /><div className="muted">Chargement des réglages…</div></CenterScreen>;

  const lang: Lang = settings.language;

  if (settings.disclaimerAcceptedVersion < DISCLAIMER_VERSION) {
    return (
      <DisclaimerGate
        lang={lang}
        onAccept={() => { daemonClient.updateSettings({ disclaimerAcceptedVersion: DISCLAIMER_VERSION }).then(setSettings).catch(() => {}); }}
        onDecline={() => { window.close(); }}
      />
    );
  }
  return (
    <AppStateProvider settings={settings} onSettings={setSettings}>
      <Shell lang={lang} />
    </AppStateProvider>
  );
}
