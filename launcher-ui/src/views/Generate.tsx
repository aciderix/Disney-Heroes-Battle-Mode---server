// Écran GÉNÉRER — build depuis l'APK. Endpoints réels /build/start|status (testés BuildDataGenTest, ServerBundleTest,
// ClientBundleTest). Cibles proposées : SERVER et CLIENT UNIQUEMENT (APK = non implémenté → NON proposé, principe
// « pas de bouton futur »). Polling du statut + étapes + console.
import { useEffect, useRef, useState } from "react";
import { daemonClient } from "../api/daemonClient";
import type { BuildStatus, BuildTarget } from "../api/types";
import { Panel, Banner } from "../components";
import { PathInput, StepProgress, LogConsole } from "../components/build";
import { useApp } from "../state/store";

export function Generate() {
  const { settings } = useApp();
  const [apkPath, setApkPath] = useState(settings.apkPath);
  const [target, setTarget] = useState<Exclude<BuildTarget, "apk">>("server");
  const [outDir, setOutDir] = useState(settings.outDir);
  const [full, setFull] = useState(false);
  const [pkg, setPkg] = useState(true);
  const [status, setStatus] = useState<BuildStatus | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const timer = useRef<number | null>(null);

  function stopPolling() { if (timer.current) { window.clearInterval(timer.current); timer.current = null; } }
  useEffect(() => stopPolling, []);

  async function poll() {
    try {
      const s = await daemonClient.buildStatus();
      setStatus(s);
      if (s.state === "DONE" || s.state === "FAILED") { stopPolling(); setBusy(false); }
    } catch { /* transitoire */ }
  }

  async function start() {
    setErr(null); setBusy(true);
    try {
      const s = await daemonClient.buildStart({ apkPath, target, outDir: outDir || undefined, full, pkg });
      setStatus(s);
      stopPolling();
      timer.current = window.setInterval(poll, 1000);
    } catch { setErr("Lancement du build impossible"); setBusy(false); }
  }

  const done = status?.state === "DONE";
  return (
    <div className="stack">
      <Panel title="Générer depuis l'APK">
        {err && <Banner kind="error">{err}</Banner>}
        <label className="stack" style={{ gap: 4 }}>
          APK Disney Heroes (votre propre copie)
          <PathInput kind="file" value={apkPath} onChange={setApkPath} placeholder="…/disney-heroes.apk" />
        </label>
        <label className="stack" style={{ gap: 4 }}>Cible
          <select value={target} onChange={(e) => setTarget(e.target.value as "server" | "client")}>
            <option value="server">Serveur (héberger)</option>
            <option value="client">Port PC (jouable)</option>
          </select>
        </label>
        <label className="stack" style={{ gap: 4 }}>Dossier de sortie
          <PathInput kind="dir" value={outDir} onChange={setOutDir} placeholder="…/sortie" />
        </label>
        <div className="row">
          <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={full} onChange={(e) => setFull(e.target.checked)} /><span>complet (decompile+reframe)</span></label>
          <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={pkg} onChange={(e) => setPkg(e.target.checked)} /><span>packaging autonome</span></label>
        </div>
        <button className="primary" disabled={busy || !apkPath} onClick={start}>Générer</button>
      </Panel>

      {status && (
        <Panel title="Progression">
          <StepProgress state={status.state} step={status.step} />
          {done && status.outDir && (
            <Banner kind="success">Terminé : <span style={{ fontFamily: "var(--font-mono)" }}>{status.outDir}</span>
              {target === "server" && <> — copie ce dossier dans « Héberger » pour lancer le serveur.</>}</Banner>
          )}
          <LogConsole text={status.log} />
        </Panel>
      )}
    </div>
  );
}
