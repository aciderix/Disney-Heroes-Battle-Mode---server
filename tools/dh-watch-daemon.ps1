# dh-watch-daemon.ps1 — surveille EN DIRECT le launcher + ses enfants pour CAPTURER le crash du daemon
# (bug #3 g252 : DisneyHeroesLauncher.exe disparaît de la mémoire en cours d'hébergement, laissant le serveur
# de jeu (java) et le contenu (python) orphelins → l'UI croit le serveur « arrêté »). But : choper le MOMENT et
# la CAUSE de la disparition, pas seulement des traces après-coup.
#
# Usage :  powershell -ExecutionPolicy Bypass -File tools\dh-watch-daemon.ps1 [-IntervalSec 2] [-Log <fichier>]
#   Lance l'app (via dh-debug-launch.ps1 ou double-clic), PUIS lance ce moniteur en parallèle, PUIS reproduis
#   l'hébergement. Quand le launcher disparaît, le script imprime + journalise l'instant exact et les erreurs
#   Windows (journal Application + Windows Error Reporting) autour de cet instant.

param(
  [int]$IntervalSec = 2,
  [string]$Log = "$env:TEMP\dh-daemon-watch.log"
)
$ErrorActionPreference = "Continue"
$names = @("DisneyHeroesLauncher.exe", "java.exe", "javaw.exe", "python.exe", "python3.exe")

function Now { (Get-Date).ToString("HH:mm:ss.fff") }
function Log([string]$m) { $line = "$(Now) $m"; Write-Host $line; Add-Content -Path $Log -Value $line }

Log "=== surveillance démarrée (intervalle ${IntervalSec}s, log: $Log) — Ctrl-C pour arrêter ==="
$launcherSeen = $false

while ($true) {
  $procs = Get-CimInstance Win32_Process -Filter ($names | ForEach-Object { "Name='$_'" }) -ErrorAction SilentlyContinue
  if (-not $procs) { $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $names -contains $_.Name } }

  $launcher = $procs | Where-Object { $_.Name -eq "DisneyHeroesLauncher.exe" }
  $java = @($procs | Where-Object { $_.Name -like "java*.exe" })
  $py = @($procs | Where-Object { $_.Name -like "python*.exe" })

  if ($launcher) {
    if (-not $launcherSeen) { Log "launcher PRÉSENT (pid $($launcher.ProcessId))  java=$($java.Count) python=$($py.Count)" ; $launcherSeen = $true }
  } else {
    if ($launcherSeen) {
      # TRANSITION présent → absent : c'est l'instant du crash. On dump tout ce qui peut expliquer.
      Log "!!! launcher DISPARU — java encore vivants=$($java.Count) python=$($py.Count) → ENFANTS ORPHELINS"
      Log "--- Journal Application (Erreurs/Avertissements, 3 dernières minutes) ---"
      try {
        Get-WinEvent -FilterHashtable @{ LogName = "Application"; StartTime = (Get-Date).AddMinutes(-3); Level = 1, 2, 3 } -ErrorAction SilentlyContinue |
          Select-Object -First 15 TimeCreated, ProviderName, Id, LevelDisplayName, @{n="Msg";e={ $_.Message -replace "\s+", " " }} |
          Format-List | Out-String | ForEach-Object { Add-Content -Path $Log -Value $_; Write-Host $_ }
      } catch { Log "(lecture journal Application impossible: $_)" }
      Log "--- Windows Error Reporting (crashs récents) ---"
      try {
        Get-WinEvent -FilterHashtable @{ LogName = "Application"; ProviderName = "Windows Error Reporting", "Application Error", ".NET Runtime" } -MaxEvents 10 -ErrorAction SilentlyContinue |
          Where-Object { $_.Message -match "DisneyHeroes|launcher|tauri|webview" } |
          Select-Object TimeCreated, Id, @{n="Msg";e={ $_.Message -replace "\s+", " " }} |
          Format-List | Out-String | ForEach-Object { Add-Content -Path $Log -Value $_; Write-Host $_ }
      } catch { Log "(lecture WER impossible: $_)" }
      Log "=== fin du dump post-crash (les enfants java/python tournent encore : à tuer manuellement si besoin) ==="
      $launcherSeen = $false
    }
  }
  Start-Sleep -Seconds $IntervalSec
}
