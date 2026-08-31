// Écran COMPTE — identité mnémonique. Adossé aux endpoints réels /identity/generate|register|login (testés :
// LauncherLoginTest, AuthMintTest). Nécessite un serveur sélectionné (pour l'AuthService). Aucune option non
// implémentée n'est proposée (pas de « se souvenir de moi » : persistance = backend §7, absente).
import { useState } from "react";
import { daemonClient } from "../api/daemonClient";
import type { AuthResult } from "../api/types";
import { useApp } from "../state/store";
import { Panel, Banner } from "../components";
import { MnemonicWordGrid, Bip39Input } from "../components/mnemonic";

type Tab = "new" | "restore";

export function Account() {
  const { selectedServer, session, setSession } = useApp();
  const [tab, setTab] = useState<Tab>("new");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  // Nouveau
  const [genPhrase, setGenPhrase] = useState<string | null>(null);
  const [noted, setNoted] = useState(false);
  // Restaurer
  const [phrase, setPhrase] = useState("");
  const [phraseKnown, setPhraseKnown] = useState(false);

  const authUrl = selectedServer ? `http://${selectedServer.host}:${selectedServer.authPort}` : "";

  function applyAuth(r: AuthResult) {
    if (r.ok && r.userID && r.loginRequestID) {
      setSession({ userID: r.userID, loginRequestID: r.loginRequestID, serverId: selectedServer!.id });
    } else {
      setErr(`Échec (${r.error ?? "inconnu"})`);
    }
  }

  async function generate() {
    setErr(null); setBusy(true);
    try { const id = await daemonClient.identityGenerate(); setGenPhrase(id.phrase); setNoted(false); }
    catch { setErr("Génération impossible"); } finally { setBusy(false); }
  }
  async function register() {
    if (!genPhrase || !selectedServer) return;
    setErr(null); setBusy(true);
    try { applyAuth(await daemonClient.identityRegister(genPhrase, authUrl)); }
    catch { setErr("Serveur d'authentification injoignable"); } finally { setBusy(false); }
  }
  async function login() {
    if (!selectedServer) return;
    setErr(null); setBusy(true);
    try { applyAuth(await daemonClient.identityLogin(phrase, authUrl)); }
    catch { setErr("Serveur d'authentification injoignable"); } finally { setBusy(false); }
  }

  if (session) {
    return (
      <Panel title="Compte connecté">
        <div>Compte <strong>#{session.userID}</strong> — authentifié sur <strong>{selectedServer?.name ?? session.serverId}</strong>.</div>
        <div className="muted">Session en mémoire (non persistée). Le lancement du jeu arrive à un prochain incrément.</div>
        <button style={{ alignSelf: "start" }} onClick={() => setSession(null)}>Se déconnecter</button>
      </Panel>
    );
  }

  if (!selectedServer) {
    return <Panel title="Compte"><Banner kind="info">Sélectionne d'abord un serveur dans l'onglet « Serveurs ».</Banner></Panel>;
  }

  return (
    <div className="stack">
      <Panel title="Compte">
        <div className="muted">Serveur : <strong>{selectedServer.name}</strong> ({selectedServer.host}:{selectedServer.authPort})</div>
        <div className="row">
          <button className={tab === "new" ? "primary" : ""} onClick={() => { setTab("new"); setErr(null); }}>Nouveau</button>
          <button className={tab === "restore" ? "primary" : ""} onClick={() => { setTab("restore"); setErr(null); }}>Restaurer</button>
        </div>
        {err && <Banner kind="error">{err}</Banner>}

        {tab === "new" && (
          <div className="stack">
            {!genPhrase && <button className="primary" disabled={busy} onClick={generate}>Générer une phrase</button>}
            {genPhrase && (
              <>
                <MnemonicWordGrid phrase={genPhrase} />
                <Banner kind="info">Note ces 8 mots. C'est ta seule clé — aucune récupération possible en cas de perte.</Banner>
                <label className="row"><input type="checkbox" style={{ width: "auto" }} checked={noted} onChange={(e) => setNoted(e.target.checked)} />
                  <span>Je les ai notés en lieu sûr.</span></label>
                <button className="primary" disabled={busy || !noted} onClick={register}>Créer le compte et se connecter</button>
              </>
            )}
          </div>
        )}

        {tab === "restore" && (
          <div className="stack">
            <Bip39Input onChange={(p, known) => { setPhrase(p); setPhraseKnown(known); }} />
            <button className="primary" disabled={busy || !phraseKnown} onClick={login}>Se connecter</button>
          </div>
        )}
      </Panel>
    </div>
  );
}
