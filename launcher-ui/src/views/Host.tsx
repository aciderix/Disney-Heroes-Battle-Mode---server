// Écran HÉBERGER — cycle de vie d'un serveur local. Endpoints réels /host/start|stop|status (testés HostLifecycleTest,
// ServerBundleTest). Polling du statut. Rien d'affiché qui ne soit backé.
import { useEffect, useRef, useState } from "react";
import { daemonClient } from "../api/daemonClient";
import type { HostStatus } from "../api/types";
import { Panel, Banner, StatusDot } from "../components";
import { PathInput } from "../components/build";
import { useApp } from "../state/store";

// Après /host/start, le serveur local n'apparaît nulle part (Serveurs/Compte/Jouer dépendent tous du
// favori sélectionné dans le store) tant qu'il n'est pas enregistré via /servers — donc on l'auto-enregistre
// et le sélectionne ici, une fois par passage à "en cours" (idempotent : réutilise le favori existant si présent).

function fmtUptime(ms: number): string {
  if (!ms) return "—";
  const s = Math.floor(ms / 1000), m = Math.floor(s / 60), h = Math.floor(m / 60);
  return h > 0 ? `${h} h ${m % 60} min` : m > 0 ? `${m} min ${s % 60} s` : `${s} s`;
}

export function Host() {
  const { settings, servers, reloadServers, select } = useApp();
  const [status, setStatus] = useState<HostStatus | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [bundleDir, setBundleDir] = useState(settings.bundleDir);
  const [contentPort, setContentPort] = useState(8080);
  const [gamePort, setGamePort] = useState(8081);
  const [authPort, setAuthPort] = useState(8082);
  const [strict, setStrict] = useState(false);
  const timer = useRef<number | null>(null);
  const registeredForRunRef = useRef(false);

  async function refresh() { try { setStatus(await daemonClient.hostStatus()); } catch { /* daemon momentanément indispo */ } }
  useEffect(() => {
    refresh();
    timer.current = window.setInterval(refresh, 1500);
    return () => { if (timer.current) window.clearInterval(timer.current); };
  }, []);

  useEffect(() => {
    if (!status?.running) { registeredForRunRef.current = false; return; }
    if (registeredForRunRef.current) return;
    registeredForRunRef.current = true;
    void registerLocal(status);
  }, [status?.running]);

  async function registerLocal(s: HostStatus) {
    const existing = servers.find((x) => x.host === "127.0.0.1" && x.contentPort === s.contentPort);
    if (existing) { select(existing.id); return; }
    try {
      const added = await daemonClient.serverAdd("Serveur local hébergé", "127.0.0.1", s.contentPort, s.gamePort, s.authPort);
      await reloadServers();
      select(added.id);
    } catch { /* pas bloquant : ajoutable manuellement depuis « Serveurs » */ }
  }

  async function start() {
    setErr(null); setBusy(true);
    try { setStatus(await daemonClient.hostStart({ bundleDir: bundleDir || undefined, contentPort, gamePort, authPort, strict })); }
    catch { setErr("Démarrage impossible (voir les logs du daemon)"); } finally { setBusy(false); }
  }
  async function stop() {
    setErr(null); setBusy(true);
    try { setStatus(await daemonClient.hostStop()); } catch { setErr("Arrêt impossible"); } finally { setBusy(false); }
  }

  const running = !!status?.running;
  return (
    <div className="stack">
      <Panel title="Héberger un serveur (local)">
        {err && <Banner kind="error">{err}</Banner>}
        <label className="stack" style={{ gap: 4 }}>
          Bundle serveur (dossier généré) — laisser vide = mode dev (classpath courant)
          <PathInput kind="dir" value={bundleDir} onChange={setBundleDir} placeholder="…/bundle-serveur" />
        </label>
        <div className="row">
          <label className="stack" style={{ gap: 4 }}>content<input type="number" value={contentPort} onChange={(e) => setContentPort(+e.target.value)} /></label>
          <label className="stack" style={{ gap: 4 }}>jeu<input type="number" value={gamePort} onChange={(e) => setGamePort(+e.target.value)} /></label>
          <label className="stack" style={{ gap: 4 }}>auth<input type="number" value={authPort} onChange={(e) => setAuthPort(+e.target.value)} /></label>
        </div>
        <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={strict} onChange={(e) => setStrict(e.target.checked)} disabled={running} />
          <span>Mode strict (auth mnémonique obligatoire)</span></label>
        <div className="row">
          <button className="primary" disabled={busy || running} onClick={start}>Héberger</button>
          <button disabled={busy || !running} onClick={stop}>Arrêter</button>
        </div>
      </Panel>

      <Panel title="État du serveur">
        {!status ? <div className="muted">…</div> : (
          <div className="stack">
            <div className="row"><StatusDot state={running ? "ok" : "warn"} /><strong>{running ? "en cours" : "arrêté"}</strong>
              {running && <span className="row"><StatusDot state={status.gamePortListening ? "ok" : "warn"} /><span className="muted">{status.gamePortListening ? "port de jeu en écoute" : "démarrage…"}</span></span>}</div>
            <div className="muted">ports : content {status.contentPort} · jeu {status.gamePort} · auth {status.authPort} · {status.strict ? "STRICT" : "permissif"}</div>
            {running && <div className="muted">PID serveur {status.serverPid} {status.contentPid > 0 ? `· PID content ${status.contentPid}` : ""} · uptime {fmtUptime(status.uptimeMs)}</div>}
            {running && <Banner kind="info">Enregistré et sélectionné comme serveur favori — va dans « Compte » pour créer un compte, puis « Jouer ».</Banner>}
          </div>
        )}
      </Panel>
    </div>
  );
}
