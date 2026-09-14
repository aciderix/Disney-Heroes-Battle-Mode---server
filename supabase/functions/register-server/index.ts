import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

// ANNUAIRE (brique 2) — inscription/rafraîchissement d'un serveur, AUTHENTIFIÉ PAR SIGNATURE Ed25519,
// ET VÉRIFIÉ JOIGNABLE DEPUIS INTERNET avant écriture.
// verify_jwt=false : l'auth n'est PAS un JWT Supabase mais la SIGNATURE du serveur (auth custom). La fonction
// VÉRIFIE la signature avant d'écrire (service role, bypass RLS) → un tiers ne peut ni usurper ni polluer la table.
// La chaîne canonique DOIT être identique bit à bit à celle du serveur Java (dhserver.directory.ServerRegistration).
//
// CONTRÔLE D'ACCESSIBILITÉ : un serveur derrière un NAT sans redirection s'inscrivait avec succès alors qu'AUCUN
// joueur ne pouvait le joindre (le transport de jeu est du TCP brut vers l'adresse annoncée, cf. docs/PROTOCOL.md §1).
// Pire, il se rafraîchissait toutes les 10 min → jamais purgé par le job pg_cron, et l'annuaire se remplissait de
// fiches inutilisables. On exige donc que la fiche soit CONFIRMÉE DEPUIS L'EXTÉRIEUR : cette fonction tourne dans le
// cloud Supabase (= vrai point de vue Internet) et va chercher le /info SIGNÉ de l'hébergeur avec un nonce frais.
// Pas joignable = pas d'écriture (422 + raison), et l'hébergeur voit immédiatement quoi corriger.
//
// ⚠️ Déploiement : `verify_jwt=false` (auth = signature, pas JWT). Toute évolution de canonical()/infoCanonical()
// doit être faite EN MÊME TEMPS que le Java correspondant (ServerRegistration / ServerInfo), sinon plus aucune
// inscription ne passe.

// ⚠ La ligne ci-dessous contient un caractere de CONTROLE BRUT (US, 0x1F) entre les guillemets.
// Ne pas le "nettoyer" : il doit rester identique au SEP de ServerInfo.java / ServerRegistration.java,
// sinon PLUS AUCUNE inscription ne passe (signature calculee sur une chaine differente).
const SEP = "";
const REG = "REG1";
const MAX_SKEW_MS = 5 * 60 * 1000;
const INFO_PROTOCOL = 1;        // ServerInfo.PROTOCOL (Java) — incrémenter ensemble si canonical() change
const PROBE_TIMEOUT_MS = 6000;

function b64urlToBytes(s: string): Uint8Array {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function canonical(p: Record<string, unknown>): Uint8Array {
  const parts = [REG, str(p.pubKey), str(p.name), str(p.mode), str(p.gameVersion), str(p.serverVersion),
    str(p.address), str(p.infoUrl), String(int(p.online)), String(int(p.maxOnline)),
    String(int(p.openTime)), String(int(p.issuedAt))];
  return new TextEncoder().encode(parts.join(SEP));
}
function str(v: unknown): string { return v == null ? "" : String(v); }
function int(v: unknown): number { const n = Math.trunc(Number(v)); return Number.isFinite(n) ? n : 0; }
function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Adresse non routable depuis Internet → un joueur distant ne pourra JAMAIS s'y connecter. Rejet immédiat.
 *  Couvre aussi 100.64/10 (espace CGNAT) : une machine derrière CGNAT n'a pas d'adresse publique a publier.
 *  Sert accessoirement de garde anti-SSRF (on ne fait pas sonder le reseau interne de Supabase) — la preuve
 *  de fond restant que la reponse doit porter une signature valide de la MEME cle. */
function isUnroutable(host: string): boolean {
  const s = host.toLowerCase().replace(/^\[/, "").replace(/\]$/, "");
  if (s === "localhost" || s.endsWith(".localhost") || s === "::1" || s === "0.0.0.0") return true;
  const m = s.match(/^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  if (m) {
    const a = +m[1], b = +m[2];
    if (a === 0 || a === 127 || a === 10) return true;
    if (a === 192 && b === 168) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 169 && b === 254) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;   // CGNAT
    return false;
  }
  if (s.startsWith("fc") || s.startsWith("fd") || s.startsWith("fe80")) return true;   // IPv6 ULA / link-local
  return false;
}

/** Chaîne canonique de la FICHE /info — doit être identique à ServerInfo.canonical(nonce) côté Java. */
function infoCanonical(b: Record<string, unknown>, nonce: string): Uint8Array {
  const parts = [String(INFO_PROTOCOL), str(b.name), str(b.mode), str(b.gameVersion), str(b.serverVersion),
    String(int(b.online)), String(int(b.maxOnline)), String(int(b.openTime)), nonce];
  return new TextEncoder().encode(parts.join(SEP));
}

/** Va chercher le /info SIGNÉ de l'hébergeur avec un nonce frais et le vérifie avec la clé publique ANNONCÉE.
 *  Succès ⇒ le serveur est joignable depuis Internet ET détient bien la clé privée (nonce frais = anti-rejeu). */
async function probeReachable(infoUrl: string, expectedPubKey: string): Promise<{ ok: boolean; reason?: string }> {
  let u: URL;
  try { u = new URL(infoUrl); } catch { return { ok: false, reason: "infoUrl invalide" }; }
  if (u.protocol !== "http:" && u.protocol !== "https:") return { ok: false, reason: "protocole non supporté" };
  if (isUnroutable(u.hostname)) return { ok: false, reason: `adresse non routable depuis Internet (${u.hostname}) — redirection de ports absente, ou adresse privee/CGNAT` };

  const nb = new Uint8Array(16);
  crypto.getRandomValues(nb);
  const nonce = btoa(String.fromCharCode(...nb)).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const target = infoUrl.replace(/\/+$/, "") + "/info?nonce=" + nonce;

  let body: Record<string, unknown>;
  try {
    const r = await fetch(target, { signal: AbortSignal.timeout(PROBE_TIMEOUT_MS), redirect: "error" });
    if (r.status !== 200) return { ok: false, reason: `/info → HTTP ${r.status}` };
    body = await r.json();
  } catch (e) {
    return { ok: false, reason: `serveur injoignable depuis Internet (${e instanceof Error ? e.name : "erreur"}) — verifie que les ports sont ouverts/rediriges` };
  }

  if (int(body.protocol) !== INFO_PROTOCOL) return { ok: false, reason: `protocole de fiche inconnu (${str(body.protocol)})` };
  if (str(body.nonce) !== nonce) return { ok: false, reason: "nonce non renvoyé (rejeu ?)" };
  if (str(body.pubKey) !== expectedPubKey) return { ok: false, reason: "le /info ne présente pas la même clé publique que l'inscription" };
  try {
    const key = await crypto.subtle.importKey("spki", b64urlToBytes(str(body.pubKey)), { name: "Ed25519" }, false, ["verify"]);
    const good = await crypto.subtle.verify({ name: "Ed25519" }, key, b64urlToBytes(str(body.sig)), infoCanonical(body, nonce));
    if (!good) return { ok: false, reason: "signature de fiche /info invalide" };
  } catch { return { ok: false, reason: "crypto /info" }; }
  return { ok: true };
}

Deno.serve(async (req: Request) => {
  if (req.method !== "POST") return json(405, { error: "method" });
  let p: Record<string, unknown>;
  try { p = await req.json(); } catch { return json(400, { error: "json" }); }

  const name = str(p.name).slice(0, 60).trim();
  const mode = str(p.mode);
  const infoUrl = str(p.infoUrl);
  const address = str(p.address);
  const issuedAt = int(p.issuedAt);
  if (!name) return json(400, { error: "name" });
  if (mode !== "strict" && mode !== "open") return json(400, { error: "mode" });
  if (!/^https?:\/\//.test(infoUrl) || infoUrl.length > 200) return json(400, { error: "infoUrl" });
  if (!address || address.length > 200) return json(400, { error: "address" });
  if (!issuedAt || Math.abs(Date.now() - issuedAt) > MAX_SKEW_MS) return json(400, { error: "issuedAt" });

  let ok = false;
  try {
    const pub = b64urlToBytes(str(p.pubKey));
    const sig = b64urlToBytes(str(p.signature));
    const key = await crypto.subtle.importKey("spki", pub, { name: "Ed25519" }, false, ["verify"]);
    ok = await crypto.subtle.verify({ name: "Ed25519" }, key, sig, canonical(p));
  } catch { return json(400, { error: "crypto" }); }
  if (!ok) return json(401, { error: "signature" });

  // L'adresse de JEU annoncée doit elle aussi être routable (c'est elle que le client utilise en TCP brut).
  const gameHost = address.replace(/:\d+$/, "");
  if (isUnroutable(gameHost)) {
    return json(422, { error: "unreachable", reason: `adresse de jeu non routable depuis Internet (${gameHost})` });
  }
  // Sonde RÉELLE depuis le cloud : pas joignable ⇒ on n'écrit RIEN (l'hébergeur voit l'erreur et sait quoi corriger).
  const probe = await probeReachable(infoUrl, str(p.pubKey));
  if (!probe.ok) return json(422, { error: "unreachable", reason: probe.reason });

  const sb = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const row = {
    pub_key: str(p.pubKey), name, mode,
    game_version: str(p.gameVersion).slice(0, 32), server_version: str(p.serverVersion).slice(0, 32),
    address, info_url: infoUrl, online: int(p.online), max_online: int(p.maxOnline),
    open_time: int(p.openTime), issued_at: issuedAt, signature: str(p.signature),
    updated_at: new Date().toISOString(),
  };
  const { error } = await sb.from("servers").upsert(row, { onConflict: "pub_key" });
  if (error) return json(500, { error: "db" });
  return json(200, { ok: true });
});
