# EXPLORATION — checklist vivante des écrans (hub + menu ☰)

> **Document de suivi.** On coche/commente **au fur et à mesure** de l'exploration : statut par écran +
> **problèmes rencontrés et fixés**. But : toujours savoir où on en est. Voir la liste canonique + gating dans
> [`HUB_NAV.md §7`](HUB_NAV.md). Méthode par écran : **capturer → observer → trouver+combler le gap serveur →
> vérifier en jeu + persistance** (cf. `MEMORY.md §6ter`, règle PRINCIPLES §8 « travailler sur les faits »).

## Contexte du compte de test
- **userID=1, shard=1**, `server/data/dh-server.db`.
- **Team level = 65** (fixé via `server/smoke/SetTeamLevel.java` le 2026-07-20 pour débloquer les écrans
  mid/late — `unlockables.tab` : ouvre tout jusqu'à INVASION TL60 ; PATCHED_HEROES TL185 reste fermé).
- Snapshot pré-TL60 : `server/data/dh-snapshot-pretl60-0720.db` (Vanellope, 500 diamants, items, TL2).
- Héros : Ralph/Elastigirl/Frozone/Vanellope (WHITE niv.1-2) — **pas montés** (le TL est fixé, pas la puissance
  réelle) → certains écrans PvP/contenu peuvent se comporter différemment ; on le note au cas par cas.

## Légende statut
✅ OK (vérifié en jeu + persistance) · 🟢 serveur OK (en jeu à faire) · 🔧 en cours · ⚠️ problème ouvert ·
⬜ à explorer · 🔒 verrouillé (raison) · 💤 hors scope (store fermé / feature retirée)

---

## A. Écrans EARLY-GAME — déjà validés (rappel)

| Écran | TL | Statut | Notes / handlers |
|---|---|---|---|
| CAMPAIGN | 1 | ✅ | `recordOutcome` (stamina/loot/gold/XP/progression) |
| HEROES (HERO_MANAGEMENT) | 1 | ✅ | `UNLOCK_HERO`, `EQUIP_ITEM` |
| CHESTS | 1 | ✅ | coffres gratuit + payant (anti-triche `validateChestPurchase`) |
| ITEMS | 1 | ✅ | `SELL_ITEM`/`USE_ITEM`/`VIEWED_CONSUMABLE_ITEM` |
| QUESTS | 1 | ✅ | complete/view/redeem + boîtes weekly |
| MEDALS (prize wall) | 1 | ✅ | `COMPLETE_QUEST` |
| MAILBOX | 1 | ✅ | livraison + open/take/delete + `ActionGroup` |
| EVENTS | 1 | ✅ | rend + `UPDATE_TIME` (events live-ops vides) |
| SIGN_IN | 1 | ✅ | `CLAIM_SIGNIN_REWARD` |

---

## B. Écrans à EXPLORER (débloqués à TL65) — checklist

Ordre de traitement (modifiable). On commence par **BATTLE PASS**.

| # | Écran | TL | Statut | Problèmes rencontrés / fixés · notes |
|---|---|---|---|---|
| 1 | **BATTLE PASS** (onglet QUESTS) | 11 | 🔧 | serveur déjà fait (claim/collect/buyout/rollover/premium) → **vérif EN JEU** |
| 2 | RANKINGS (arena league) | 10 | ⬜ | |
| 3 | FIGHT_PIT (arène PvP) | 10 | ⬜ | |
| 4 | ELITE_CAMPAIGN | 11 | ⬜ | |
| 5 | ALCHEMY (achat d'or) | 12 | ⬜ | |
| 6 | SKILL_UPGRADE (compétences héros) | 8 | ⬜ | montée de skills |
| 7 | GUILDS | 15 | ⬜ | guilde, chest social, dons, mercenaires |
| 8 | CHALLENGES | 20 | ⬜ | |
| 9 | SAVED_LINEUPS | 20 | ⬜ | |
| 10 | FRIENDSHIPS / MISSIONS | 24 | ⬜ | campagnes d'amitié, disks |
| 11 | EXPEDITION | 25 | ⬜ | |
| 12 | COLLECTIONS | 26 | ⬜ | cosmétiques |
| 13 | SURGE (crypt raid) | 30 | ⬜ | |
| 14 | BLACK_MARKET | 31 | ⬜ | store (partiel — monnaie de jeu ?) |
| 15 | ENCHANTING | 35 | ⬜ | enchantement d'équipement |
| 16 | COLISEUM (PvP) | 40 | ⬜ | |
| 17 | MEGA_MART / GEAR_MARKET / MEMORY_MARKET | 41-42 | 💤 | stores (fermés) |
| 18 | WAR (guild war) | 45 | ⬜ | |
| 19 | FRANCHISE_TRIALS / TEAM_TRIALS | 55 | ⬜ | |
| 20 | INVASION | 60 | ⬜ | |
| 21 | PORT | ? | ⬜ | à mapper |
| 22 | WISHING_WELL | 30 | ⬜ | |
| 23 | PATCHED_HEROES | 185 | 🔒 | TL insuffisant (185) |
| 24 | HEIST | 9999 | 💤 | désactivé (retiré du jeu) |

---

## C. Journal d'exploration (chronologique — problèmes & fixes)

_(rempli au fur et à mesure)_

- **2026-07-20** — Compte passé à TL65 (`SetTeamLevel`). Début de l'exploration par le **battle pass**.
