// Écran SERVEURS — favoris (liste/ajout/suppression) + ping. 100% adossé aux endpoints réels (/servers, /servers/ping,
// /servers/remove), testés (LauncherServersTest). Rien d'autre affiché.
import { useState } from "react";
import { daemonClient, DaemonError } from "../api/daemonClient";
import type { Ping, Server } from "../api/types";
import { useApp } from "../state/store";
import { Panel, Banner, StatusDot } from "../components";

export function Servers() {
  const { servers, reloadServers, selectedId, select } = useApp();
  const [err, setErr] = useState<string | null>(null);
  const [pings, setPings] = useState<Record<string, Ping | "loading">>({});

  const [name, setName] = useState("");
  const [host, setHost] = useState("");
  const [showPorts, setShowPorts] = useState(false);
  const [contentPort, setContentPort] = useState(8080);
  const [gamePort, setGamePort] = useState(8081);
  const [authPort, setAuthPort] = useState(8082);
  const [adding, setAdding] = useState(false);

  async function ping(s: Server) {
    setPings((p) => ({ ...p, [s.id]: "loading" }));
    try { const r = await daemonClient.serverPing(s.host, s.gamePort); setPings((p) => ({ ...p, [s.id]: r })); }
    catch { setPings((p) => ({ ...p, [s.id]: { reachable: false } })); }
  }

  async function add(prefillLocalhost = false) {
    setErr(null); setAdding(true);
    try {
      const h = prefillLocalhost ? "127.0.0.1" : host.trim();
      const n = prefillLocalhost ? (name.trim() || "Mon serveur local") : name.trim();
      await daemonClient.serverAdd(n, h, contentPort, gamePort, authPort);
      setName(""); setHost(""); await reloadServers();
    } catch (e) {
      setErr(e instanceof DaemonError ? `Ajout refusé (${e.status})` : "Ajout impossible");
    } finally { setAdding(false); }
  }

  async function remove(s: Server) {
    if (!window.confirm(`Supprimer « ${s.name} » ?`)) return;
    setErr(null);
    try { await daemonClient.serverRemove(s.id); await reloadServers(); }
    catch { setErr("Suppression impossible"); }
  }

  return (
    <div className="stack">
      <Panel title="Serveurs">
        {err && <Banner kind="error">{err}</Banner>}
        {servers.length === 0 && <div className="muted">Aucun serveur. Ajoute-en un ci-dessous (ou « localhost »).</div>}
        {servers.map((s) => {
          const p = pings[s.id];
          return (
            <div key={s.id} className="row" style={{ justifyContent: "space-between", borderBottom: "1px solid var(--border)", padding: "8px 0" }}>
              <label className="row" style={{ gap: 10 }}>
                <input type="radio" name="srv" style={{ width: "auto" }} checked={selectedId === s.id} onChange={() => select(s.id)} />
                <span>
                  <strong>{s.name}</strong>{" "}
                  <span className="muted">{s.host}:{s.contentPort}</span>
                </span>
              </label>
              <span className="row">
                {p === "loading" ? <span className="muted">…</span>
                  : p ? <span className="row"><StatusDot state={p.reachable ? "ok" : "err"} />
                          <span className="muted">{p.reachable ? `${p.latencyMs} ms` : "injoignable"}</span></span>
                  : null}
                <button onClick={() => ping(s)}>Tester</button>
                <button onClick={() => remove(s)}>Supprimer</button>
              </span>
            </div>
          );
        })}
      </Panel>

      <Panel title="Ajouter un serveur">
        <div className="row"><input placeholder="Nom" value={name} onChange={(e) => setName(e.target.value)} />
          <input placeholder="Adresse (host / IP)" value={host} onChange={(e) => setHost(e.target.value)} /></div>
        <button className="nav-item" style={{ alignSelf: "start" }} onClick={() => setShowPorts((v) => !v)}>
          {showPorts ? "▾" : "▸"} Ports avancés
        </button>
        {showPorts && (
          <div className="row">
            <label className="stack" style={{ gap: 4 }}>content<input type="number" value={contentPort} onChange={(e) => setContentPort(+e.target.value)} /></label>
            <label className="stack" style={{ gap: 4 }}>jeu<input type="number" value={gamePort} onChange={(e) => setGamePort(+e.target.value)} /></label>
            <label className="stack" style={{ gap: 4 }}>auth<input type="number" value={authPort} onChange={(e) => setAuthPort(+e.target.value)} /></label>
          </div>
        )}
        <div className="row">
          <button className="primary" disabled={adding || !name.trim() || !host.trim()} onClick={() => add(false)}>Ajouter</button>
          <button disabled={adding} onClick={() => add(true)}>+ localhost (127.0.0.1)</button>
        </div>
      </Panel>
    </div>
  );
}
