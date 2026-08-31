// Pont NATIF (Tauri) — le SEUL endroit qui touche à l'API Tauri. En dehors de Tauri (dev navigateur), tout retombe
// sur des valeurs par défaut / stubs, pour développer l'UI sans le shell natif. Aucune logique métier ici.
//
// Note technique : on charge l'API Tauri par des specifiers INDIRECTS (variables) → au type-check, l'import est `any`
// et ne requiert pas les paquets @tauri-apps/* installés (utile pour `tsc --noEmit` en CI front). À l'exécution dans
// le shell, les modules sont résolus par le bundler.

const TAURI_CORE = "@tauri-apps/api/core";
const TAURI_DIALOG = "@tauri-apps/plugin-dialog";

function inTauri(): boolean {
  const w = window as unknown as { __TAURI_INTERNALS__?: unknown };
  return !!w.__TAURI_INTERNALS__;
}

async function invoke<T>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  const mod: any = await import(/* @vite-ignore */ TAURI_CORE);
  return mod.invoke(cmd, args) as Promise<T>;
}

export const isTauri = (): boolean => inTauri();

/** Port du daemon Java local, fourni par le shell Tauri (`get_daemon_port`). Dev navigateur → VITE_DAEMON_PORT ou 8090. */
export async function getDaemonPort(): Promise<number> {
  if (!inTauri()) return Number((import.meta as any).env?.VITE_DAEMON_PORT ?? 8090);
  try { return await invoke<number>("get_daemon_port"); } catch { return 8090; }
}

async function pick(title: string, directory: boolean): Promise<string | null> {
  if (!inTauri()) return window.prompt(title) || null;
  const dlg: any = await import(/* @vite-ignore */ TAURI_DIALOG);
  const res = await dlg.open({ title, multiple: false, directory });
  return typeof res === "string" ? res : null;
}

/** Sélecteur de FICHIER natif (APK). */
export const pickFile = (title: string): Promise<string | null> => pick(title, false);
/** Sélecteur de DOSSIER natif (bundle / sortie). */
export const pickDir = (title: string): Promise<string | null> => pick(title, true);
