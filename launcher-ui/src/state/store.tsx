// État applicatif (contexte React) — s'appuie UNIQUEMENT sur daemonClient (endpoints réels). Les réglages sont
// chargés/persistés côté daemon (/settings) et passés par App ; la session est en mémoire.
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { daemonClient } from "../api/daemonClient";
import type { Server, Session, Settings } from "../api/types";

type Ctx = {
  servers: Server[];
  reloadServers: () => Promise<void>;
  selectedId: string | null;
  select: (id: string | null) => void;
  selectedServer: Server | null;
  session: Session | null;
  setSession: (s: Session | null) => void;
  settings: Settings;
  saveSettings: (patch: Partial<Settings>) => Promise<void>;
};

const AppCtx = createContext<Ctx | null>(null);

export function AppStateProvider({ settings, onSettings, children }: {
  settings: Settings; onSettings: (s: Settings) => void; children: ReactNode;
}) {
  const [servers, setServers] = useState<Server[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [session, setSession] = useState<Session | null>(null);

  async function reloadServers() {
    const list = await daemonClient.serversList();
    setServers(list);
    setSelectedId((cur) => (cur && list.some((s) => s.id === cur) ? cur : (list[0]?.id ?? null)));
  }
  useEffect(() => { reloadServers().catch(() => { /* affiché par la vue */ }); }, []);

  async function saveSettings(patch: Partial<Settings>) { onSettings(await daemonClient.updateSettings(patch)); }

  const selectedServer = servers.find((s) => s.id === selectedId) ?? null;
  return (
    <AppCtx.Provider value={{ servers, reloadServers, selectedId, select: setSelectedId, selectedServer, session, setSession, settings, saveSettings }}>
      {children}
    </AppCtx.Provider>
  );
}

export function useApp(): Ctx {
  const c = useContext(AppCtx);
  if (!c) throw new Error("useApp hors AppStateProvider");
  return c;
}
