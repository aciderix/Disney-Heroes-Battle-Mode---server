// daemonClient — LE SEUL module qui parle au launcher-core (daemon HTTP local, loopback). Tout le reste de l'UI
// passe par ici. Contrat = docs/LAUNCHER_UI.md §1 (source: LauncherDaemon.java). Aucune vue, aucun état ici.

import { getDaemonPort, isTauri } from "./tauriBridge";
import type {
  Identity, AuthResult, Server, Ping, HostStatus, BuildStatus, PlayStatus, Settings,
  HostStartParams, BuildStartParams, PlayStartParams,
  AdminMonitor, HostLogs, AdminReleases, AdminEraStatus, AdminClock, PlayerSummary,
  AdminEvents, AdminEnums, Moderation, AuditLog, AdminTarget,
  DirectoryServer, VerifiedServer,
} from "./types";

let baseUrlCache: string | null = null;
async function base(): Promise<string> {
  if (baseUrlCache !== null) return baseUrlCache;
  // Prod Tauri / hors dev → URL directe du daemon local. En DEV navigateur → même origine (proxy Vite, pas de CORS).
  if (!isTauri() && (import.meta as any).env?.DEV) baseUrlCache = "";
  else baseUrlCache = `http://127.0.0.1:${await getDaemonPort()}`;
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
  // ANNUAIRE (brique 3) — navigateur de serveurs communautaires
  directoryList: () => get<DirectoryServer[]>("/directory"),
  directoryVerify: (infoUrl: string) => post<VerifiedServer>("/directory/verify", { infoUrl }),

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

  // --- réglages locaux (persistés) ---
  getSettings: () => get<Settings>("/settings"),
  updateSettings: (patch: Partial<Settings>) => post<Settings>("/settings", patch as Record<string, unknown>),

  // --- ADMIN (chantier D) — le daemon PROXIFIE /admin/* vers le serveur LOCAL hébergé en injectant le jeton opérateur.
  //     503 si aucun serveur n'est hébergé (l'écran Admin invite alors à héberger). Admin distant = ultérieur (chantier F).
  adminMonitor: () => get<AdminMonitor>("/admin/monitor"),
  hostLogs: (which: "server" | "content", tail = 200) => get<HostLogs>(`/host/logs?which=${which}&tail=${tail}`),
  // Cible d'administration : serveur LOCAL hébergé (défaut) ou DISTANT (cloud, URL + jeton, validé par ping)
  adminTargetGet: () => get<AdminTarget>("/admin/target"),
  adminTargetSet: (adminUrl: string, token: string, caFingerprint?: string) =>
    post<AdminTarget>("/admin/target", caFingerprint ? { adminUrl, token, caFingerprint } : { adminUrl, token }),
  adminTargetClear: () => post<AdminTarget>("/admin/target/clear"),
  // Ère de contenu
  adminReleases: () => get<AdminReleases>("/admin/releases"),
  adminSetRelease: (name: string) => post<AdminEraStatus>("/admin/release", { name }),
  adminClockGet: () => get<AdminClock>("/admin/clock"),
  adminClockSet: (offsetHours: number) => post<AdminClock>("/admin/clock", { offsetHours }),
  // Joueurs (autoritatif, journalisé côté serveur)
  adminPlayerLookup: (userID: number) => post<PlayerSummary>("/admin/player/lookup", { userID }),
  adminGiveResource: (userID: number, type: string, amount: number) =>
    post<PlayerSummary>("/admin/player/giveResource", { userID, type, amount }),
  adminGrantHero: (userID: number, hero: string, rarity?: string, level?: number, stars?: number) =>
    post<PlayerSummary>("/admin/player/grantHero", { userID, hero, rarity, level, stars }),
  adminSetTeamLevel: (userID: number, level: number) =>
    post<PlayerSummary>("/admin/player/setTeamLevel", { userID, level }),
  adminGrantCampaign: (userID: number, chapter: number, level: number, stars?: number, campaignType?: string) =>
    post<PlayerSummary>("/admin/player/grantCampaign", { userID, chapter, level, stars, campaignType }),
  adminCompleteTutorials: (userID: number) => post<PlayerSummary>("/admin/player/completeTutorials", { userID }),
  adminUnlock: (userID: number) => post<PlayerSummary>("/admin/player/unlock", { userID }),
  adminAudit: (tail = 100) => get<AuditLog>(`/admin/audit?tail=${tail}`),
  // Events live-ops
  adminEvents: () => get<AdminEvents>("/admin/events"),
  adminEventAdd: (spec: string) => post<AdminEvents>("/admin/events", { spec }),
  adminEventRemove: (index: number) => post<AdminEvents>("/admin/events/remove", { index }),
  adminEventClear: () => post<AdminEvents>("/admin/events/clear"),
  adminEnums: () => get<AdminEnums>("/admin/enums"),
  // Modération
  adminModeration: () => get<Moderation>("/admin/moderation"),
  adminBan: (userID: number) => post<Moderation>("/admin/moderation/ban", { userID }),
  adminUnban: (userID: number) => post<Moderation>("/admin/moderation/unban", { userID }),
  adminMute: (userID: number) => post<Moderation>("/admin/moderation/mute", { userID }),
  adminUnmute: (userID: number) => post<Moderation>("/admin/moderation/unmute", { userID }),
  adminKick: (userID: number) => post<{ kicked: boolean }>("/admin/moderation/kick", { userID }),
};

export type DaemonClient = typeof daemonClient;
