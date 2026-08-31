import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Front web pur (aucun code de jeu). En prod il est empaqueté par Tauri ; en dev, Vite sert sur :1420.
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: { port: 1420, strictPort: true },
  build: { target: "es2020", outDir: "dist" },
});
