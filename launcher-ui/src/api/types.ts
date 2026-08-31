// Types du contrat daemon (miroir de docs/LAUNCHER_UI.md §1 — source: server/java/dhlauncher/LauncherDaemon.java).
// AUCUNE logique ici : uniquement les formes de données échangées avec le launcher-core local.

export type Identity = { phrase: string; userID: number; publicKey: string };
export type AuthResult = { ok: boolean; userID?: number; loginRequestID?: string; error?: string };
export type Session = { userID: number; loginRequestID: string; serverId: string };

export type Server = {
  id: string; name: string; host: string;
  contentPort: number; gamePort: number; authPort: number;
};
export type Ping = { reachable: boolean; latencyMs?: number };

// ANNUAIRE (brique 3) — une fiche BRUTE de la table Supabase (à re-vérifier via /info avant de faire confiance).
export type DirectoryServer = {
  pub_key: string; name: string; mode: string; game_version: string; server_version: string;
  address: string; info_url: string; online: number; max_online: number; open_time: number; updated_at: string;
};
// ANNUAIRE — fiche VÉRIFIÉE en direct (signature prouvée + serveur vivant).
export type VerifiedServer = {
  serverId: number; name: string; mode: string; gameVersion: string; serverVersion: string;
  online: number; maxOnline: number; full: boolean; openTime: number; pubKey: string; verified: boolean;
};

export type HostStatus = {
  running: boolean; gamePortListening: boolean;
  contentPort: number; gamePort: number; authPort: number;
  strict: boolean; serverPid: number; contentPid: number; uptimeMs: number;
};

export type BuildTarget = "server" | "client" | "apk";
export type BuildState = "IDLE" | "RUNNING" | "DONE" | "FAILED";
export type BuildStatus = { state: BuildState; target: "SERVER" | "CLIENT" | "APK"; step: string; outDir: string; log: string };

export type PlayStatus = {
  running: boolean; pid: number; server: string; userID: number; strict: boolean; uptimeMs: number;
};

export type Settings = {
  language: "fr" | "en";
  disclaimerAcceptedVersion: number;
  apkPath: string; outDir: string; clientDir: string; bundleDir: string;
};

// --- ADMIN (chantier D — via le proxy /admin/* ; 503 si aucun serveur hébergé localement) ---
export type AdminMonitor = {
  onlineCount: number; connectionsAccepted: number; uptimeMs: number; strict: boolean;
  online: { userID: number; sinceMs: number }[];
};
export type HostLogs = { which: string; lines: string[] };
export type Release = { index: number; name: string; date: string; maxTeamLevel: number; current: boolean };
export type AdminReleases = { offsetMs: number; releases: Release[] };
export type AdminEraStatus = { offsetMs: number; timersDate: string; eraName: string; eraDate: string; maxTeamLevel: number };
export type AdminClock = { offsetMs: number; gameDate: string; realDate: string };
export type PlayerSummary = {
  userID: number; name: string; teamLevel: number;
  gold: number; diamonds: number; stamina: number; heroCount: number; guildID: number;
};
export type AdminEvents = { count: number; events: Record<string, unknown>[] };
export type AdminEnums = Record<string, string[]>;
export type Moderation = { bans: number[]; mutes: number[] };
export type AuditLog = { lines: string[] };
export type AdminTarget = { mode: "local" | "remote"; baseUrl?: string; tls?: boolean };

// Paramètres d'appel (côté front → daemon).
export type HostStartParams = { bundleDir?: string; contentPort?: number; gamePort?: number; authPort?: number; strict?: boolean };
export type BuildStartParams = { apkPath: string; target?: BuildTarget; outDir?: string; full?: boolean; pkg?: boolean };
export type PlayStartParams = { clientDir: string; serverId?: string; serverHost?: string; contentPort?: number; userID?: number; strict?: boolean };
