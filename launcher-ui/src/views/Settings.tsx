// Écran RÉGLAGES — adossé à /settings (persistance réelle, testée SettingsLifecycleTest). On n'expose QUE des réglages
// réellement CÂBLÉS : langue (i18n), chemins par défaut (préremplissent Générer/Jouer/Héberger), et « relire
// l'avertissement ». Résolution / qualité spine ne sont pas encore transmis au lancement → NON proposés (principe).
import { useState } from "react";
import { useApp } from "../state/store";
import { Panel } from "../components";
import { PathInput } from "../components/build";
import { DISCLAIMER_AUTHOR, DISCLAIMER_CONTACT } from "../content/disclaimer";
import type { Lang } from "../i18n";

export function SettingsView() {
  const { settings, saveSettings } = useApp();
  const [apkPath, setApkPath] = useState(settings.apkPath);
  const [outDir, setOutDir] = useState(settings.outDir);
  const [clientDir, setClientDir] = useState(settings.clientDir);
  const [bundleDir, setBundleDir] = useState(settings.bundleDir);
  const [saved, setSaved] = useState(false);

  async function savePaths() {
    await saveSettings({ apkPath, outDir, clientDir, bundleDir });
    setSaved(true); setTimeout(() => setSaved(false), 1500);
  }

  return (
    <div className="stack">
      <Panel title="Langue">
        <select value={settings.language} onChange={(e) => saveSettings({ language: e.target.value as Lang })} style={{ maxWidth: 200 }}>
          <option value="fr">Français</option>
          <option value="en">English</option>
        </select>
      </Panel>

      <Panel title="Chemins par défaut">
        <div className="muted">Préremplissent les écrans Générer / Jouer / Héberger.</div>
        <label className="stack" style={{ gap: 4 }}>APK<PathInput kind="file" value={apkPath} onChange={setApkPath} placeholder="…/disney-heroes.apk" /></label>
        <label className="stack" style={{ gap: 4 }}>Dossier de sortie des builds<PathInput kind="dir" value={outDir} onChange={setOutDir} /></label>
        <label className="stack" style={{ gap: 4 }}>Bundle CLIENT (port PC)<PathInput kind="dir" value={clientDir} onChange={setClientDir} /></label>
        <label className="stack" style={{ gap: 4 }}>Bundle SERVEUR<PathInput kind="dir" value={bundleDir} onChange={setBundleDir} /></label>
        <div className="row">
          <button className="primary" onClick={savePaths}>Enregistrer les chemins</button>
          {saved && <span className="muted">enregistré ✓</span>}
        </div>
      </Panel>

      <Panel title="À propos">
        <div className="muted">Launcher Disney Heroes (port privé) — projet amateur, gratuit, non affilié à Disney/PerBlue.</div>
        <div className="muted">Auteur : {DISCLAIMER_AUTHOR} — {DISCLAIMER_CONTACT}</div>
        <button style={{ alignSelf: "start" }} onClick={() => saveSettings({ disclaimerAcceptedVersion: 0 })}>Relire l'avertissement</button>
      </Panel>
    </div>
  );
}
