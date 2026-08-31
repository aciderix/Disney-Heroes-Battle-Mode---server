// Écran ADMIN — panneau opérateur, 5 domaines, 100% adossé à des endpoints RÉELS+testés (AdminService, proxy daemon) :
// Monitoring (/admin/monitor + /host/logs), Ère (/admin/releases,release,clock), Joueurs (/admin/player/*, journalisé),
// Events (/admin/events,enums), Modération (/admin/moderation/*). Le daemon injecte le jeton opérateur pour le serveur
// LOCAL hébergé ; sans hébergement, /admin/* renvoie 503 → cet écran invite à héberger (pas de faux OK). Admin d'un
// serveur DISTANT = ultérieur (chantier F). L'écran ne montre QUE des actions réellement câblées (principe util.).
import { useEffect, useRef, useState } from "react";
import { daemonClient, DaemonError } from "../api/daemonClient";
import type {
  AdminMonitor, AdminReleases, AdminClock, PlayerSummary, AdminEvents, AdminEnums, Moderation as ModerationState, AdminTarget,
} from "../api/types";
import { Panel, Banner, StatusDot } from "../components";

type Tab = "monitor" | "era" | "players" | "events" | "moderation";

function errText(e: unknown): string {
  if (e instanceof DaemonError) { try { return JSON.parse(e.body).error ?? e.message; } catch { return e.message; } }
  return e instanceof Error ? e.message : String(e);
}
function fmtDur(ms: number): string {
  const s = Math.floor(ms / 1000), m = Math.floor(s / 60), h = Math.floor(m / 60);
  return h > 0 ? `${h} h ${m % 60} min` : m > 0 ? `${m} min ${s % 60} s` : `${s} s`;
}

export function Admin() {
  const [available, setAvailable] = useState<boolean | null>(null); // null = en cours de sonde
  const [tab, setTab] = useState<Tab>("monitor");
  const [target, setTarget] = useState<AdminTarget | null>(null);

  async function probe() {
    try { await daemonClient.adminMonitor(); setAvailable(true); }
    catch (e) { setAvailable(e instanceof DaemonError && e.status === 503 ? false : true); }
  }
  async function refreshTarget() { try { setTarget(await daemonClient.adminTargetGet()); } catch { /* */ } }
  useEffect(() => {
    probe(); refreshTarget();
    const id = window.setInterval(probe, 3000);
    return () => window.clearInterval(id);
  }, []);
  const onTargetChange = () => { refreshTarget(); probe(); };

  if (available === null) return <Panel title="Admin"><div className="muted">…</div></Panel>;
  if (!available)
    return (
      <div className="stack">
        <TargetBar target={target} onChange={onTargetChange} />
        <Panel title="Admin — panneau opérateur">
          <Banner kind="info">
            Cible actuelle : <strong>serveur local</strong> — aucun serveur hébergé. Héberge un serveur (onglet « Héberger »),
            ou connecte un <strong>serveur distant</strong> ci-dessus (URL de son AdminService + jeton).
          </Banner>
        </Panel>
      </div>
    );

  const tabs: { id: Tab; label: string }[] = [
    { id: "monitor", label: "Monitoring" }, { id: "era", label: "Ère de contenu" },
    { id: "players", label: "Joueurs" }, { id: "events", label: "Events" }, { id: "moderation", label: "Modération" },
  ];
  return (
    <div className="stack">
      <TargetBar target={target} onChange={onTargetChange} />
      <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
        {tabs.map((tt) => (
          <button key={tt.id} className={tab === tt.id ? "primary" : ""} onClick={() => setTab(tt.id)}>{tt.label}</button>
        ))}
      </div>
      {tab === "monitor" && <MonitorTab />}
      {tab === "era" && <EraTab />}
      {tab === "players" && <PlayersTab />}
      {tab === "events" && <EventsTab />}
      {tab === "moderation" && <ModerationTab />}
    </div>
  );
}

// ─── CIBLE (local / distant) ─────────────────────────────────────────────────────────────────────────────────────
function TargetBar({ target, onChange }: { target: AdminTarget | null; onChange: () => void }) {
  const [open, setOpen] = useState(false);
  const [url, setUrl] = useState("");
  const [token, setToken] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const remote = target?.mode === "remote";

  async function connect() {
    setErr(null); setBusy(true);
    try { await daemonClient.adminTargetSet(url.trim(), token.trim()); setOpen(false); setToken(""); onChange(); }
    catch (e) { setErr(errText(e)); } finally { setBusy(false); }
  }
  async function backToLocal() { setErr(null); try { await daemonClient.adminTargetClear(); onChange(); } catch (e) { setErr(errText(e)); } }

  return (
    <Panel title="Serveur à administrer">
      {err && <Banner kind="error">{err}</Banner>}
      <div className="row" style={{ justifyContent: "space-between", flexWrap: "wrap", gap: 8 }}>
        <div className="row">
          <StatusDot state={remote ? "ok" : "warn"} />
          <span>{remote ? <>Distant : <strong>{target?.baseUrl}</strong></> : <>Serveur <strong>local</strong> hébergé</>}</span>
        </div>
        <div className="row" style={{ gap: 6 }}>
          {remote && <button onClick={backToLocal}>Revenir au local</button>}
          <button onClick={() => setOpen((o) => !o)}>{open ? "Annuler" : "Connecter un serveur distant"}</button>
        </div>
      </div>
      {open && (
        <div className="stack" style={{ gap: 6 }}>
          <div className="muted" style={{ fontSize: 12 }}>
            URL de l'AdminService du serveur distant (ex. <code>http://mon-serveur:8083</code>) + jeton opérateur.
            Le serveur doit être lancé avec <code>DH_ADMIN_BIND=0.0.0.0</code> + <code>DH_ADMIN_TOKEN=…</code>.
            ⚠ Le jeton transite en clair : pour un serveur exposé sur Internet, passe par un tunnel SSH/VPN (TLS à venir).
          </div>
          <label className="stack" style={{ gap: 4 }}>URL<input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="http://mon-serveur:8083" /></label>
          <label className="stack" style={{ gap: 4 }}>Jeton<input value={token} onChange={(e) => setToken(e.target.value)} type="password" /></label>
          <div className="row"><button className="primary" disabled={busy || !url.trim() || !token.trim()} onClick={connect}>Connecter</button></div>
        </div>
      )}
    </Panel>
  );
}

// ─── MONITORING ────────────────────────────────────────────────────────────────────────────────────────────────────
function MonitorTab() {
  const [mon, setMon] = useState<AdminMonitor | null>(null);
  const [logs, setLogs] = useState<string[]>([]);
  const [which, setWhich] = useState<"server" | "content">("server");
  const timer = useRef<number | null>(null);

  async function refresh() {
    try { setMon(await daemonClient.adminMonitor()); } catch { /* transitoire */ }
    try { setLogs((await daemonClient.hostLogs(which, 200)).lines); } catch { /* idem */ }
  }
  useEffect(() => { refresh(); timer.current = window.setInterval(refresh, 2000); return () => { if (timer.current) window.clearInterval(timer.current); }; }, [which]);

  return (
    <div className="stack">
      <Panel title="Serveur">
        {!mon ? <div className="muted">…</div> : (
          <div className="stack">
            <div className="row"><StatusDot state="ok" /><strong>{mon.onlineCount} en ligne</strong>
              <span className="muted">· {mon.connectionsAccepted} connexions acceptées · uptime {fmtDur(mon.uptimeMs)} · {mon.strict ? "STRICT" : "permissif"}</span></div>
            {mon.online.length > 0 && (
              <table className="tbl"><thead><tr><th>userID</th><th>connecté depuis</th></tr></thead>
                <tbody>{mon.online.map((o) => <tr key={o.userID}><td>#{o.userID}</td><td>{fmtDur(o.sinceMs)}</td></tr>)}</tbody></table>
            )}
          </div>
        )}
      </Panel>
      <Panel title="Logs">
        <div className="row">
          <select value={which} onChange={(e) => setWhich(e.target.value as "server" | "content")} style={{ maxWidth: 160 }}>
            <option value="server">serveur de jeu</option><option value="content">content_server</option>
          </select>
        </div>
        <pre className="logconsole">{logs.length ? logs.join("\n") : "(aucune ligne)"}</pre>
      </Panel>
    </div>
  );
}

// ─── ÈRE DE CONTENU ────────────────────────────────────────────────────────────────────────────────────────────────
function EraTab() {
  const [rel, setRel] = useState<AdminReleases | null>(null);
  const [clock, setClock] = useState<AdminClock | null>(null);
  const [hours, setHours] = useState(0);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  async function refresh() {
    try { setRel(await daemonClient.adminReleases()); } catch (e) { setErr(errText(e)); }
    try { setClock(await daemonClient.adminClockGet()); } catch { /* */ }
  }
  useEffect(() => { refresh(); }, []);

  async function setRelease(name: string) {
    setErr(null); setMsg(null);
    try { const s = await daemonClient.adminSetRelease(name); setMsg(`Ère → ${s.eraName} (${s.eraDate}, Max TL ${s.maxTeamLevel})`); await refresh(); }
    catch (e) { setErr(errText(e)); }
  }
  async function applyClock() {
    setErr(null); setMsg(null);
    try { setClock(await daemonClient.adminClockSet(hours)); setMsg(`Horloge : offset ${hours} h`); } catch (e) { setErr(errText(e)); }
  }

  return (
    <div className="stack">
      {err && <Banner kind="error">{err}</Banner>}
      {msg && <Banner kind="success">{msg}</Banner>}
      <Panel title="Ère de contenu (release)">
        <div className="muted">Choisir une release change l'ère servie (héros/objets/caps disponibles) SANS toucher les sauvegardes ni les timers des joueurs.</div>
        <div className="row"><button onClick={() => setRelease("reset")}>Réinitialiser (date réelle)</button></div>
        <div className="tblwrap">
          <table className="tbl"><thead><tr><th>#</th><th>Release</th><th>Date</th><th>Max TL</th><th></th></tr></thead>
            <tbody>{(rel?.releases ?? []).map((r) => (
              <tr key={r.index} style={r.current ? { fontWeight: 600 } : undefined}>
                <td>{r.index}</td><td>{r.name}{r.current ? " ◀" : ""}</td><td>{r.date}</td><td>{r.maxTeamLevel}</td>
                <td><button onClick={() => setRelease(r.name)} disabled={r.current}>servir</button></td>
              </tr>))}</tbody></table>
        </div>
      </Panel>
      <Panel title="Horloge (mode test — décale TOUT : ère + timers)">
        {clock && <div className="muted">jeu : {clock.gameDate} · réel : {clock.realDate} · offset {clock.offsetMs} ms</div>}
        <div className="row">
          <label className="row" style={{ gap: 4 }}>offset (heures, + = avancer)
            <input type="number" value={hours} onChange={(e) => setHours(+e.target.value)} style={{ width: 100 }} /></label>
          <button onClick={applyClock}>Appliquer</button>
        </div>
      </Panel>
    </div>
  );
}

// ─── JOUEURS ───────────────────────────────────────────────────────────────────────────────────────────────────────
function PlayersTab() {
  const [uid, setUid] = useState("1");
  const [p, setP] = useState<PlayerSummary | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [audit, setAudit] = useState<string[]>([]);
  // formulaires
  const [resType, setResType] = useState("GOLD"); const [resAmt, setResAmt] = useState(1000);
  const [hero, setHero] = useState(""); const [tl, setTl] = useState(50);

  const id = () => Number(uid) || 0;
  async function reloadAudit() { try { setAudit((await daemonClient.adminAudit(30)).lines); } catch { /* */ } }
  async function run(fn: () => Promise<PlayerSummary>, confirmMsg?: string) {
    if (confirmMsg && !window.confirm(confirmMsg)) return;
    setErr(null);
    try { setP(await fn()); await reloadAudit(); } catch (e) { setErr(errText(e)); }
  }
  async function lookup() { setErr(null); try { setP(await daemonClient.adminPlayerLookup(id())); await reloadAudit(); } catch (e) { setErr(errText(e)); } }

  return (
    <div className="stack">
      {err && <Banner kind="error">{err}</Banner>}
      <Panel title="Compte">
        <div className="row">
          <label className="row" style={{ gap: 4 }}>userID <input value={uid} onChange={(e) => setUid(e.target.value)} style={{ width: 120 }} /></label>
          <button className="primary" onClick={lookup}>Rechercher</button>
        </div>
        {p && (
          <div className="muted">#{p.userID} « {p.name || "(sans nom)"} » · TL {p.teamLevel} · or {p.gold} · diamants {p.diamonds} ·
            énergie {p.stamina} · {p.heroCount} héros · guilde {p.guildID || "—"}</div>
        )}
      </Panel>
      {p && (
        <Panel title="Actions (autoritatives — journalisées)">
          <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
            <select value={resType} onChange={(e) => setResType(e.target.value)}>
              {["GOLD", "DIAMONDS", "STAMINA"].map((r) => <option key={r} value={r}>{r}</option>)}
            </select>
            <input type="number" value={resAmt} onChange={(e) => setResAmt(+e.target.value)} style={{ width: 120 }} />
            <button onClick={() => run(() => daemonClient.adminGiveResource(id(), resType, resAmt), `Donner ${resAmt} ${resType} à #${id()} ?`)}>Donner ressource</button>
          </div>
          <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
            <input placeholder="héros (ex. STITCH)" value={hero} onChange={(e) => setHero(e.target.value.toUpperCase())} style={{ width: 180 }} />
            <button disabled={!hero} onClick={() => run(() => daemonClient.adminGrantHero(id(), hero), `Accorder ${hero} à #${id()} ?`)}>Accorder héros</button>
          </div>
          <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
            <input type="number" value={tl} onChange={(e) => setTl(+e.target.value)} style={{ width: 100 }} />
            <button onClick={() => run(() => daemonClient.adminSetTeamLevel(id(), tl), `Fixer le TL de #${id()} à ${tl} ?`)}>Fixer TL</button>
            <button onClick={() => run(() => daemonClient.adminCompleteTutorials(id()), `Terminer tous les tutos de #${id()} ?`)}>Terminer tutos</button>
            <button onClick={() => run(() => daemonClient.adminUnlock(id()), `DÉBLOCAGE global de #${id()} (TL300 + chapitre + roster) ?`)}>Déblocage global</button>
          </div>
        </Panel>
      )}
      <Panel title="Journal d'audit (dernières actions)">
        <pre className="logconsole">{audit.length ? audit.join("\n") : "(aucune action)"}</pre>
      </Panel>
    </div>
  );
}

// ─── EVENTS ────────────────────────────────────────────────────────────────────────────────────────────────────────
function EventsTab() {
  const [ev, setEv] = useState<AdminEvents | null>(null);
  const [enums, setEnums] = useState<AdminEnums | null>(null);
  const [spec, setSpec] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  async function refresh() { try { setEv(await daemonClient.adminEvents()); } catch (e) { setErr(errText(e)); } }
  useEffect(() => { refresh(); daemonClient.adminEnums().then(setEnums).catch(() => {}); }, []);

  async function add() {
    setErr(null); setMsg(null);
    try { setEv(await daemonClient.adminEventAdd(spec)); setSpec(""); setMsg("Event ajouté."); } catch (e) { setErr(errText(e)); }
  }
  async function remove(i: number) { setErr(null); try { setEv(await daemonClient.adminEventRemove(i)); } catch (e) { setErr(errText(e)); } }
  async function clear() { if (!window.confirm("Retirer TOUS les events (rotation par défaut) ?")) return; setErr(null); try { setEv(await daemonClient.adminEventClear()); } catch (e) { setErr(errText(e)); } }

  return (
    <div className="stack">
      {err && <Banner kind="error">{err}</Banner>}
      {msg && <Banner kind="success">{msg}</Banner>}
      <Panel title={`Events actifs (${ev?.count ?? 0})`}>
        {(ev?.events ?? []).length === 0 && <div className="muted">Aucun override — rotation par défaut du jeu.</div>}
        {(ev?.events ?? []).map((e, i) => (
          <div key={i} className="row" style={{ justifyContent: "space-between", alignItems: "start", gap: 8 }}>
            <code style={{ fontSize: 12, wordBreak: "break-all" }}>{JSON.stringify(e)}</code>
            <button onClick={() => remove(i)}>retirer</button>
          </div>
        ))}
        {(ev?.count ?? 0) > 0 && <button onClick={clear}>Tout retirer</button>}
      </Panel>
      <Panel title="Ajouter un event (spec JSON {kind,…})">
        <div className="muted">La spec est validée côté serveur (kind reconnu + reconstruit) ; une spec invalide est refusée.</div>
        <textarea value={spec} onChange={(e) => setSpec(e.target.value)} rows={4} placeholder='{"kind":"MODES_OPEN","modes":["PORT_DOCKS"],"start":...,"end":...}'
          style={{ fontFamily: "monospace", fontSize: 12, width: "100%" }} />
        <div className="row"><button className="primary" disabled={!spec.trim()} onClick={add}>Ajouter</button></div>
      </Panel>
      {enums && (
        <Panel title="Enums disponibles (référence pour construire une spec)">
          <div className="muted" style={{ fontSize: 12 }}>kinds : {(enums.kinds ?? []).join(", ")}</div>
          <details><summary className="muted">GameMode ({(enums.GameMode ?? []).length})</summary>
            <div style={{ fontSize: 12 }}>{(enums.GameMode ?? []).join(", ")}</div></details>
          <details><summary className="muted">ChestType ({(enums.ChestType ?? []).length})</summary>
            <div style={{ fontSize: 12 }}>{(enums.ChestType ?? []).join(", ")}</div></details>
        </Panel>
      )}
    </div>
  );
}

// ─── MODÉRATION ────────────────────────────────────────────────────────────────────────────────────────────────────
function ModerationTab() {
  const [mod, setMod] = useState<ModerationState | null>(null);
  const [uid, setUid] = useState("");
  const [err, setErr] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);

  async function refresh() { try { setMod(await daemonClient.adminModeration()); } catch (e) { setErr(errText(e)); } }
  useEffect(() => { refresh(); }, []);
  const id = () => Number(uid) || 0;

  async function act(fn: () => Promise<unknown>, note: string, confirmMsg?: string) {
    if (confirmMsg && !window.confirm(confirmMsg)) return;
    setErr(null); setMsg(null);
    try { await fn(); setMsg(note); await refresh(); } catch (e) { setErr(errText(e)); }
  }

  return (
    <div className="stack">
      {err && <Banner kind="error">{err}</Banner>}
      {msg && <Banner kind="success">{msg}</Banner>}
      <Panel title="Action">
        <div className="row" style={{ gap: 6, flexWrap: "wrap" }}>
          <label className="row" style={{ gap: 4 }}>userID <input value={uid} onChange={(e) => setUid(e.target.value)} style={{ width: 120 }} /></label>
          <button disabled={!id()} onClick={() => act(() => daemonClient.adminBan(id()), `#${id()} banni`, `Bannir #${id()} (rejeté au login + déconnecté) ?`)}>Bannir</button>
          <button disabled={!id()} onClick={() => act(() => daemonClient.adminMute(id()), `#${id()} muté`)}>Mute</button>
          <button disabled={!id()} onClick={() => act(() => daemonClient.adminKick(id()), `#${id()} kické (si en ligne)`)}>Kick</button>
        </div>
      </Panel>
      <Panel title="Bannis">
        {(mod?.bans ?? []).length === 0 ? <div className="muted">aucun</div> :
          (mod?.bans ?? []).map((b) => (
            <div key={b} className="row" style={{ justifyContent: "space-between" }}><span>#{b}</span>
              <button onClick={() => act(() => daemonClient.adminUnban(b), `#${b} débanni`)}>débannir</button></div>))}
      </Panel>
      <Panel title="Mutés">
        {(mod?.mutes ?? []).length === 0 ? <div className="muted">aucun</div> :
          (mod?.mutes ?? []).map((mm) => (
            <div key={mm} className="row" style={{ justifyContent: "space-between" }}><span>#{mm}</span>
              <button onClick={() => act(() => daemonClient.adminUnmute(mm), `#${mm} démuté`)}>démuter</button></div>))}
      </Panel>
    </div>
  );
}
