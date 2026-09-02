# dh-debug-launch.ps1 — lance le launcher packagé avec le port de DÉBOGAGE CDP ouvert (WebView2), pour le
# piloter à 100% via tools/cdp_drive.mjs (DOM réel, vrais clics, console) — sur le VRAI exe, pas un proxy de dev.
#
# WebView2 (le moteur de l'app Tauri sous Windows) est du Chromium : la variable d'environnement
# WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS lui passe des arguments Chromium. "--remote-debugging-port=N" ouvre
# alors l'endpoint DevTools (CDP) sur http://127.0.0.1:N/json. RIEN n'est modifié dans l'app : le port ne
# s'ouvre QUE si cette variable est posée → aucun impact pour un joueur normal (qui double-clique l'exe nu).
#
# Usage :  powershell -ExecutionPolicy Bypass -File tools\dh-debug-launch.ps1 [-Exe <chemin.exe>] [-Port 9222]
#   Sans -Exe, cherche DisneyHeroesLauncher.exe à côté du script puis en remontant les dossiers parents.

param(
  [string]$Exe,
  [int]$Port = 9222
)
$ErrorActionPreference = "Stop"

function Find-Exe {
  param([string]$start)
  $dir = $start
  for ($i = 0; $i -lt 5 -and $dir; $i++) {
    $c = Join-Path $dir "DisneyHeroesLauncher.exe"
    if (Test-Path $c) { return $c }
    $dir = Split-Path $dir -Parent
  }
  return $null
}

if (-not $Exe) { $Exe = Find-Exe $PSScriptRoot }
if (-not $Exe -or -not (Test-Path $Exe)) {
  Write-Error "DisneyHeroesLauncher.exe introuvable. Passe -Exe `"C:\chemin\vers\DisneyHeroesLauncher.exe`"."
  exit 1
}

$env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = "--remote-debugging-port=$Port"
Write-Host "[dh-debug] WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS = $($env:WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS)"
Write-Host "[dh-debug] CDP prêt sur : http://127.0.0.1:$Port/json"
Write-Host "[dh-debug] Pilotage :    node tools\cdp_drive.mjs targets --port $Port"
Write-Host "[dh-debug] Lancement :   $Exe"
Write-Host ""

# Lancement au premier plan → la console reste ouverte, on voit les logs [launcher]/[content] de l'app.
& $Exe
$code = $LASTEXITCODE
Write-Host ""
Write-Host "[dh-debug] l'app s'est terminée (code $code)."
