import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Front web pur (aucun code de jeu). En prod, empaqueté par Tauri (transport = pont natif, pas de CORS).
// En DEV navigateur, on PROXIE les chemins du daemon (même origine → pas de CORS) vers le daemon local
// (VITE_DAEMON_PORT, défaut 8090). Le daemon reste loopback-only sans CORS permissif (sécurité).
const daemon = `http://127.0.0.1:${process.env.VITE_DAEMON_PORT ?? "8090"}`;
const proxy = Object.fromEntries(
  ["/health", "/identity", "/servers", "/host", "/build", "/play", "/settings", "/admin", "/directory"].map((p) => [p, { target: daemon, changeOrigin: true }]),
);

export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: { port: 1420, strictPort: true, proxy },
  build: { target: "es2020", outDir: "dist" },
});
