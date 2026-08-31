// Écran JOUER — lance le CLIENT (port PC) sur le serveur sélectionné, compte authentifié. Endpoints réels
// /play|/play/stop|/play/status (PlayManager, testés PlayLifecycleTest + vérif bout-en-bout). Lancement en mode
// PERMISSIF (DH_USERID = compte). Le strict vers un serveur distant (hook loginRequestID) n'est pas encore livré →
// non proposé (principe « pas de bouton futur »). Nécessite : session + serveur + dossier du bundle CLIENT.
import { useEffect, useRef, useState } from "react";
import { daemonClient } from "../api/daemonClient";
import type { PlayStatus } from "../api/types";
import { useApp } from "../state/store";
import { Panel, Banner, StatusDot } from "../components";
import { PathInput } from "../components/build";

function fmt(ms: number): string { const s = Math.floor(ms / 1000); return s < 60 ? `${s} s` : `${Math.floor(s / 60)} min ${s % 60} s`; }

export function Play() {
  const { session, selectedServer, settings } = useApp();
  const [clientDir, setClientDir] = useState(settings.clientDir);
  const [status, setStatus] = useState<PlayStatus | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const timer = useRef<number | null>(null);

  async function refresh() { try { setStatus(await daemonClient.playStatus()); } catch { /* transitoire */ } }
  useEffect(() => { refresh(); timer.current = window.setInterval(refresh, 1500); return () => { if (timer.current) window.clearInterval(timer.current); }; }, []);

  if (!session) return <Panel title="Jouer"><Banner kind="info">Connecte-toi d'abord dans l'onglet « Compte ».</Banner></Panel>;
  if (!selectedServer) return <Panel title="Jouer"><Banner kind="info">Sélectionne un serveur dans « Serveurs ».</Banner></Panel>;

  async function play() {
    setErr(null); setBusy(true);
    try { setStatus(await daemonClient.playStart({ clientDir, serverId: selectedServer!.id, userID: session!.userID })); }
    catch { setErr("Lancement impossible (bundle client introuvable ?)"); } finally { setBusy(false); }
  }
  async function stop() {
    setErr(null); setBusy(true);
    try { setStatus(await daemonClient.playStop()); } catch { setErr("Arrêt impossible"); } finally { setBusy(false); }
  }

  const running = !!status?.running;
  return (
    <div className="stack">
      <Panel title="Jouer">
        {err && <Banner kind="error">{err}</Banner>}
        <div className="muted">Compte <strong>#{session.userID}</strong> · serveur <strong>{selectedServer.name}</strong> ({selectedServer.host}:{selectedServer.contentPort})</div>
        <label className="stack" style={{ gap: 4 }}>
          Dossier du bundle CLIENT (port PC généré)
          <PathInput kind="dir" value={clientDir} onChange={setClientDir} placeholder="…/bundle-client" />
        </label>
        <div className="row">
          <button className="primary" disabled={busy || running || !clientDir} onClick={play}>JOUER</button>
          <button disabled={busy || !running} onClick={stop}>Arrêter</button>
        </div>
      </Panel>

      {status && (
        <Panel title="État du jeu">
          <div className="row"><StatusDot state={running ? "ok" : "warn"} /><strong>{running ? "en cours" : "arrêté"}</strong>
            {running && <span className="muted">PID {status.pid} · {status.server} · {fmt(status.uptimeMs)}</span>}</div>
        </Panel>
      )}
    </div>
  );
}
