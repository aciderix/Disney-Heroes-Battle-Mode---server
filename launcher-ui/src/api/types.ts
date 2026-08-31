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

// Paramètres d'appel (côté front → daemon).
export type HostStartParams = { bundleDir?: string; contentPort?: number; gamePort?: number; authPort?: number; strict?: boolean };
export type BuildStartParams = { apkPath: string; target?: BuildTarget; outDir?: string; full?: boolean; pkg?: boolean };
export type PlayStartParams = { clientDir: string; serverId?: string; serverHost?: string; contentPort?: number; userID?: number; strict?: boolean };
