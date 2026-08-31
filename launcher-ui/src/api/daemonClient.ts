// daemonClient — LE SEUL module qui parle au launcher-core (daemon HTTP local, loopback). Tout le reste de l'UI
// passe par ici. Contrat = docs/LAUNCHER_UI.md §1 (source: LauncherDaemon.java). Aucune vue, aucun état ici.

import { getDaemonPort } from "./tauriBridge";
import type {
  Identity, AuthResult, Server, Ping, HostStatus, BuildStatus, PlayStatus,
  HostStartParams, BuildStartParams, PlayStartParams,
} from "./types";

let baseUrlCache: string | null = null;
async function base(): Promise<string> {
  if (!baseUrlCache) baseUrlCache = `http://127.0.0.1:${await getDaemonPort()}`;
  return baseUrlCache;
}

function form(params: Record<string, unknown>): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    u.append(k, String(v));
  }
  return u.toString();
}

async function get<T>(path: string): Promise<T> {
  const r = await fetch(`${await base()}${path}`);
  if (!r.ok) throw new DaemonError(path, r.status, await r.text().catch(() => ""));
  return r.json() as Promise<T>;
}

async function post<T>(path: string, params: Record<string, unknown> = {}): Promise<T> {
  const r = await fetch(`${await base()}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form(params),
  });
  const text = await r.text();
  const body = text ? (JSON.parse(text) as T) : ({} as T);
  if (!r.ok) throw new DaemonError(path, r.status, text);
  return body;
}

export class DaemonError extends Error {
  constructor(public path: string, public status: number, public body: string) {
    super(`daemon ${path} → HTTP ${status}`);
  }
}

export const daemonClient = {
  // --- santé ---
  health: () => get<{ ok: boolean }>("/health"),

  // --- identité (C2a-1) ---
  identityGenerate: () => post<Identity>("/identity/generate"),
  identityRegister: (phrase: string, serverAuthUrl: string) =>
    post<AuthResult>("/identity/register", { phrase, serverAuthUrl }),
  identityLogin: (phrase: string, serverAuthUrl: string) =>
    post<AuthResult>("/identity/login", { phrase, serverAuthUrl }),

  // --- serveurs (favoris) ---
  serversList: () => get<Server[]>("/servers"),
  serverAdd: (name: string, host: string, contentPort = 8080, gamePort = 8081, authPort = 8082) =>
    post<Server>("/servers", { name, host, contentPort, gamePort, authPort }),
  serverRemove: (id: string) => post<{ ok: boolean }>("/servers/remove", { id }),
  serverPing: (host: string, port = 8081) => post<Ping>("/servers/ping", { host, port }),

  // --- hébergement local ---
  hostStart: (p: HostStartParams) => post<HostStatus>("/host/start", p as Record<string, unknown>),
  hostStop: () => post<HostStatus>("/host/stop"),
  hostStatus: () => get<HostStatus>("/host/status"),

  // --- génération depuis l'APK ---
  buildStart: (p: BuildStartParams) => post<BuildStatus>("/build/start", p as Record<string, unknown>),
  buildStatus: () => get<BuildStatus>("/build/status"),

  // --- jouer (lancer le client) ---
  playStart: (p: PlayStartParams) => post<PlayStatus>("/play", p as Record<string, unknown>),
  playStop: () => post<PlayStatus>("/play/stop"),
  playStatus: () => get<PlayStatus>("/play/status"),
};

export type DaemonClient = typeof daemonClient;
