// État applicatif (contexte React) — s'appuie UNIQUEMENT sur daemonClient (endpoints réels). Aucune fonctionnalité
// affichée n'existe sans endpoint implémenté. La session est en mémoire (la persistance « se souvenir » = backend §7,
// non implémentée → non proposée dans l'UI).
import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { daemonClient } from "../api/daemonClient";
import type { Server, Session } from "../api/types";

type Ctx = {
  servers: Server[];
  reloadServers: () => Promise<void>;
  selectedId: string | null;
  select: (id: string | null) => void;
  selectedServer: Server | null;
  session: Session | null;
  setSession: (s: Session | null) => void;
};

const AppCtx = createContext<Ctx | null>(null);

export function AppStateProvider({ children }: { children: ReactNode }) {
  const [servers, setServers] = useState<Server[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [session, setSession] = useState<Session | null>(null);

  async function reloadServers() {
    const list = await daemonClient.serversList();
    setServers(list);
    // garde la sélection si toujours présente, sinon sélectionne le 1er
    setSelectedId((cur) => (cur && list.some((s) => s.id === cur) ? cur : (list[0]?.id ?? null)));
  }

  useEffect(() => { reloadServers().catch(() => { /* affiché par la vue */ }); }, []);

  const selectedServer = servers.find((s) => s.id === selectedId) ?? null;

  return (
    <AppCtx.Provider value={{ servers, reloadServers, selectedId, select: setSelectedId, selectedServer, session, setSession }}>
      {children}
    </AppCtx.Provider>
  );
}

export function useApp(): Ctx {
  const c = useContext(AppCtx);
  if (!c) throw new Error("useApp hors AppStateProvider");
  return c;
}
