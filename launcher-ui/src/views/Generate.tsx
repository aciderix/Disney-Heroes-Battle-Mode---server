// Écran GÉNÉRER — build depuis l'APK. Endpoints réels /build/start|status (testés BuildDataGenTest, ServerBundleTest,
// ClientBundleTest, ApkBuildProbe). Cibles : SERVER (héberger), CLIENT (port PC), APK (patch mobile → redirige vers un
// serveur choisi + re-signe). Polling du statut + étapes + console.
import { useEffect, useRef, useState } from "react";
import { daemonClient } from "../api/daemonClient";
import type { BuildStatus, BuildTarget } from "../api/types";
import { Panel, Banner } from "../components";
import { PathInput, StepProgress, LogConsole } from "../components/build";
import { useApp } from "../state/store";

export function Generate() {
  const { settings } = useApp();
  const [apkPath, setApkPath] = useState(settings.apkPath);
  const [target, setTarget] = useState<BuildTarget>("server");
  const [outDir, setOutDir] = useState(settings.outDir);
  const [full, setFull] = useState(false);
  const [pkg, setPkg] = useState(true);
  const [serverHost, setServerHost] = useState("");
  const [serverPort, setServerPort] = useState(8080);
  const [apkMode, setApkMode] = useState<"redirect" | "picker">("picker");
  const isApk = target === "apk";
  const isPicker = isApk && apkMode === "picker";
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
      const s = isPicker
        ? await daemonClient.buildStart({ apkPath, target, outDir: outDir || undefined, apkMode: "picker" })
        : isApk
        ? await daemonClient.buildStart({ apkPath, target, outDir: outDir || undefined, apkMode: "redirect", serverHost: serverHost.trim(), serverPort })
        : await daemonClient.buildStart({ apkPath, target, outDir: outDir || undefined, full, pkg });
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
          <select value={target} onChange={(e) => setTarget(e.target.value as BuildTarget)}>
            <option value="server">Serveur (héberger)</option>
            <option value="client">Port PC (jouable)</option>
            <option value="apk">APK mobile (rediriger vers un serveur)</option>
          </select>
        </label>
        <label className="stack" style={{ gap: 4 }}>Dossier de sortie
          <PathInput kind="dir" value={outDir} onChange={setOutDir} placeholder="…/sortie" />
        </label>
        {isApk ? (
          <>
            <label className="stack" style={{ gap: 4 }}>Mode
              <select value={apkMode} onChange={(e) => setApkMode(e.target.value as "redirect" | "picker")}>
                <option value="picker">Écran de choix au lancement (annuaire)</option>
                <option value="redirect">Serveur fixe (une seule adresse)</option>
              </select>
            </label>
            <div className="muted" style={{ fontSize: 12 }}>
              Patche TON APK, puis le re-signe. À installer <strong>hors Play Store</strong> (sources inconnues).
              L'APK d'origine n'est pas redistribué.
            </div>
            {isPicker ? (
              <div className="muted" style={{ fontSize: 12 }}>
                Au lancement, l'appli affichera un <strong>écran de choix de serveur</strong> (liste de l'annuaire + saisie
                manuelle). Nécessite un annuaire configuré sur ce launcher.
              </div>
            ) : (
              <div className="row">
                <label className="stack" style={{ gap: 4, flex: 2 }}>Serveur (hôte / IP)
                  <input value={serverHost} onChange={(e) => setServerHost(e.target.value)} placeholder="192.168.1.20" /></label>
                <label className="stack" style={{ gap: 4, flex: 1 }}>Port
                  <input type="number" value={serverPort} onChange={(e) => setServerPort(+e.target.value)} /></label>
              </div>
            )}
          </>
        ) : (
          <div className="row">
            <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={full} onChange={(e) => setFull(e.target.checked)} /><span>complet (decompile+reframe)</span></label>
            <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={pkg} onChange={(e) => setPkg(e.target.checked)} /><span>packaging autonome</span></label>
          </div>
        )}
        <button className="primary" disabled={busy || !apkPath || (isApk && !isPicker && !serverHost.trim())} onClick={start}>Générer</button>
      </Panel>

      {status && (
        <Panel title="Progression">
          <StepProgress state={status.state} step={status.step} />
          {done && status.outDir && (
            <Banner kind="success">Terminé : <span style={{ fontFamily: "var(--font-mono)" }}>{status.outDir}</span>
              {target === "server" && <> — copie ce dossier dans « Héberger » pour lancer le serveur.</>}
              {target === "apk" && <> — l'APK patché est dans ce dossier ; installe-le sur le téléphone (sources inconnues){isPicker && <>, il affichera l'écran de choix au lancement</>}.</>}</Banner>
          )}
          <LogConsole text={status.log} />
        </Panel>
      )}
    </div>
  );
}
