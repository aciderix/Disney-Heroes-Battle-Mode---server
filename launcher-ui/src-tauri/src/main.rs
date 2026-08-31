// Shell natif Tauri du launcher. Rôle STRICT : (1) démarrer le daemon Java local (launcher-core) sur un port libre,
// (2) exposer ce port au front (commande `get_daemon_port`), (3) fournir les dialogues fichiers natifs (plugin-dialog).
// AUCUNE logique métier ni code de jeu ici — tout passe par le daemon HTTP loopback.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::net::TcpListener;
use std::path::PathBuf;
use std::process::{Child, Command};
use std::sync::Mutex;
use tauri::Manager; // fournit Window::state (Tauri v2)

struct Daemon {
    port: u16,
    child: Mutex<Option<Child>>,
}

#[tauri::command]
fn get_daemon_port(state: tauri::State<Daemon>) -> u16 {
    state.port
}

/// Port libre sur loopback (bind :0 puis relâche → l'OS nous donne un port disponible).
fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .and_then(|l| l.local_addr())
        .map(|a| a.port())
        .unwrap_or(8090)
}

/// Racine du package launcher (contient dhlauncher.jar + runtime/jdk + tooling). Priorité à DH_LAUNCHER_HOME,
/// sinon le dossier de l'exécutable (cas du package produit par tools/build_launcher.sh).
fn launcher_home() -> PathBuf {
    if let Ok(h) = std::env::var("DH_LAUNCHER_HOME") {
        return PathBuf::from(h);
    }
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.to_path_buf()))
        .unwrap_or_else(|| PathBuf::from("."))
}

fn java_bin(home: &PathBuf) -> PathBuf {
    let exe = if cfg!(windows) { "java.exe" } else { "java" };
    let embedded = home.join("runtime").join("jdk").join("bin").join(exe);
    if embedded.is_file() { embedded } else { PathBuf::from(exe) } // repli PATH
}

fn start_daemon(port: u16) -> Option<Child> {
    let home = launcher_home();
    let java = java_bin(&home);
    let jar = home.join("dhlauncher.jar");
    let tooling = home.join("tooling");
    Command::new(java)
        .arg("-cp").arg(jar)
        .arg("dhlauncher.LauncherDaemon")
        .arg("--port").arg(port.to_string())
        .arg("--project").arg(tooling)
        .spawn()
        .ok()
}

fn main() {
    let port = free_port();
    let child = start_daemon(port);

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .manage(Daemon { port, child: Mutex::new(child) })
        .invoke_handler(tauri::generate_handler![get_daemon_port])
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::Destroyed = event {
                // arrête le daemon quand la fenêtre se ferme
                let state = window.state::<Daemon>();
                if let Ok(mut guard) = state.child.lock() {
                    if let Some(mut c) = guard.take() { let _ = c.kill(); }
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("erreur au lancement du shell Tauri");
}
