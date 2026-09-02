// Pont NATIF (Tauri) — le SEUL endroit qui touche à l'API Tauri. En dehors de Tauri (dev navigateur), tout retombe
// sur des valeurs par défaut / stubs, pour développer l'UI sans le shell natif. Aucune logique métier ici.
//
// Imports STATIQUES (pas d'indirection par variable) : @tauri-apps/api et @tauri-apps/plugin-dialog sont déjà des
// dépendances RÉELLES (package.json), donc l'import est résolu par le bundler à la compilation — statique ou
// dynamique littéral, peu importe. ⚠️ Un `import(variable)` (specifier INDIRECT, non littéral) N'EST PAS bundlé par
// Vite/Rollup (qui n'analyse que les chaînes littérales pour le code-splitting) → à l'exécution le navigateur tente
// de résoudre le bare specifier "@tauri-apps/api/core" tel quel et échoue avec `TypeError: Failed to resolve module
// specifier` — silencieusement rattrapé par le try/catch de getDaemonPort() → repli sur le port 8090 (personne
// n'écoute dessus, le VRAI port est aléatoire) → « Launcher local injoignable » en PROD, à coup sûr, sur TOUT OS.
// Reproduit ET confirmé en jeu (Windows, CDP : invoke direct OK, import indirect KO avec ce message exact).
import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { open as tauriOpenDialog } from "@tauri-apps/plugin-dialog";

function inTauri(): boolean {
  const w = window as unknown as { __TAURI_INTERNALS__?: unknown };
  return !!w.__TAURI_INTERNALS__;
}

export const isTauri = (): boolean => inTauri();

/** Port du daemon Java local, fourni par le shell Tauri (`get_daemon_port`). Dev navigateur → VITE_DAEMON_PORT ou 8090. */
export async function getDaemonPort(): Promise<number> {
  if (!inTauri()) return Number((import.meta as any).env?.VITE_DAEMON_PORT ?? 8090);
  return tauriInvoke<number>("get_daemon_port");
}

async function pick(title: string, directory: boolean): Promise<string | null> {
  if (!inTauri()) return window.prompt(title) || null;
  const res = await tauriOpenDialog({ title, multiple: false, directory });
  return typeof res === "string" ? res : null;
}

/** Sélecteur de FICHIER natif (APK). */
export const pickFile = (title: string): Promise<string | null> => pick(title, false);
/** Sélecteur de DOSSIER natif (bundle / sortie). */
export const pickDir = (title: string): Promise<string | null> => pick(title, true);
