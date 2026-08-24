# AUDIT A2 — câblage des handlers (messages envoyés sans route serveur)

> AUTO-GÉNÉRÉ par `tools/audit/audit.sh a2` — 2026-08-24T21:13Z. Section C de `ScreenContract` agrégée sur tous les packages UI.
> Un `[MANQUE]` = un message que l'écran ENVOIE (client→serveur) mais que `LoginServer` ne route pas (instanceof) →
> risque « écran vide / bouton inerte ». À trancher : implémenter le handler, ou justifier `[OK-connu]` (faux positif :
> message construit localement, non envoyé ; ou handler via un chemin non détecté par l'instanceof).

**14 manque(s) potentiel(s).**

| Package | Message sans handler |
|---|---|
| `com/perblue/heroes/ui/heist/` | `GetHeist ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/heist/` | `KickHeistParticipant ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/heist/` | `StartHeist ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/invasion/` | `GetGMemInvasionRankInfo ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/powerpromote/` | `RequestResync ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/prizewall/` | `GetPrizeWallData ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/pvp/` | `RequestResync ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/screens/` | `GetChestConsumableHistory ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/screens/` | `GetCodebaseAttackLogs ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/screens/` | `GetPrizeWallData ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/windows/` | `GetBlockedList ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/windows/` | `GetServers ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/windows/` | `GetUserSaveData ? requ?te sans handler LoginServer (? impl?menter)` |
| `com/perblue/heroes/ui/windows/` | `RequestResync ? requ?te sans handler LoginServer (? impl?menter)` |
