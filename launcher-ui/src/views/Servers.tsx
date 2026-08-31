// Écran SERVEURS — favoris (liste/ajout/suppression) + ping. 100% adossé aux endpoints réels (/servers, /servers/ping,
// /servers/remove), testés (LauncherServersTest). Rien d'autre affiché.
import { useEffect, useState } from "react";
import { daemonClient, DaemonError } from "../api/daemonClient";
import type { Ping, Server, DirectoryServer, VerifiedServer } from "../api/types";
import { useApp } from "../state/store";
import { Panel, Banner, StatusDot } from "../components";

type VerifyState = VerifiedServer | "loading" | { error: string };

export function Servers() {
  const { servers, reloadServers, selectedId, select } = useApp();
  const [err, setErr] = useState<string | null>(null);
  const [pings, setPings] = useState<Record<string, Ping | "loading">>({});

  // ANNUAIRE (brique 3) — serveurs communautaires (lus depuis l'annuaire, re-vérifiés en direct via /info).
  const [dir, setDir] = useState<DirectoryServer[] | null>(null);
  const [dirErr, setDirErr] = useState<string | null>(null);
  const [dirLoading, setDirLoading] = useState(false);
  const [verifs, setVerifs] = useState<Record<string, VerifyState>>({});

  useEffect(() => { void loadDirectory(); }, []);

  async function loadDirectory() {
    setDirErr(null); setDirLoading(true);
    try { setDir(await daemonClient.directoryList()); }
    catch (e) {
      setDir([]);
      setDirErr(e instanceof DaemonError && e.status === 503
        ? "Annuaire non configuré sur ce launcher (aucune URL d'annuaire)."
        : "Annuaire injoignable.");
    } finally { setDirLoading(false); }
  }

  async function verifyOne(s: DirectoryServer) {
    setVerifs((v) => ({ ...v, [s.pub_key]: "loading" }));
    try {
      const r = await daemonClient.directoryVerify(s.info_url);
      setVerifs((v) => ({ ...v, [s.pub_key]: r }));
    } catch (e) {
      setVerifs((v) => ({ ...v, [s.pub_key]: { error: e instanceof DaemonError ? `injoignable/invalide (${e.status})` : "injoignable" } }));
    }
  }

  async function addFromDirectory(s: DirectoryServer) {
    setErr(null);
    const [h, cpStr] = s.address.split(":");
    const cp = parseInt(cpStr, 10) || 8080;
    let ap = 8082; try { const u = new URL(s.info_url); if (u.port) ap = parseInt(u.port, 10) || 8082; } catch { /* défaut */ }
    const existing = servers.find((x) => x.host === h && x.contentPort === cp);
    if (existing) { select(existing.id); return; }
    try {
      const added = await daemonClient.serverAdd(s.name, h, cp, 8081, ap);
      await reloadServers();
      select(added.id);
    } catch (e) {
      setErr(e instanceof DaemonError ? `Ajout refusé (${e.status})` : "Ajout impossible");
    }
  }

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

      <Panel title="Serveurs communautaires">
        {dirErr && <Banner kind="info">{dirErr}</Banner>}
        <div className="row" style={{ justifyContent: "space-between" }}>
          <span className="muted" style={{ fontSize: 12 }}>
            Serveurs publiés dans l'annuaire. Chaque fiche est <strong>re-vérifiée en direct</strong> (signature + serveur vivant)
            avant que tu t'y fies — la table seule ne fait pas foi.
          </span>
          <button onClick={loadDirectory} disabled={dirLoading}>{dirLoading ? "…" : "Rafraîchir"}</button>
        </div>
        {dir && dir.length === 0 && !dirErr && <div className="muted">Aucun serveur communautaire pour l'instant.</div>}
        {dir && dir.map((s) => {
          const v = verifs[s.pub_key];
          const isOk = v && v !== "loading" && "verified" in v && v.verified;
          const isBad = v && v !== "loading" && "error" in v;
          const already = servers.some((x) => `${x.host}:${x.contentPort}` === s.address);
          return (
            <div key={s.pub_key} className="row" style={{ justifyContent: "space-between", borderBottom: "1px solid var(--border)", padding: "8px 0" }}>
              <span className="stack" style={{ gap: 2 }}>
                <span>
                  <strong>{s.name}</strong>{" "}
                  <span className="muted">{s.mode === "strict" ? "🔒 strict" : "ouvert"} · v{s.game_version} · {s.address}</span>
                </span>
                <span className="muted" style={{ fontSize: 12 }}>
                  {isOk
                    ? <>✅ vérifié · {(v as VerifiedServer).online}{(v as VerifiedServer).maxOnline ? `/${(v as VerifiedServer).maxOnline}` : ""} en ligne{(v as VerifiedServer).full ? " · COMPLET" : ""}</>
                    : isBad ? <>⚠️ {(v as { error: string }).error}</>
                    : <>~{s.online} en ligne (non vérifié)</>}
                </span>
              </span>
              <span className="row">
                {v === "loading" ? <span className="muted">…</span>
                  : <StatusDot state={isOk ? "ok" : isBad ? "err" : "warn"} />}
                <button onClick={() => verifyOne(s)}>Vérifier</button>
                <button className="primary" onClick={() => addFromDirectory(s)} disabled={already}>
                  {already ? "Ajouté" : "Ajouter & sélectionner"}
                </button>
              </span>
            </div>
          );
        })}
        <div className="muted" style={{ fontSize: 12 }}>
          Après « Ajouter & sélectionner », va dans <strong>Jouer</strong> pour lancer le jeu sur ce serveur.
        </div>
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
