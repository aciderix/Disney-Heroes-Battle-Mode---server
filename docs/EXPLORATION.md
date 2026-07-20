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
| 1 | **BATTLE PASS** (onglet QUESTS) | 11 | ✅ | **serveur complet** (claim/collect/buyout/rollover/premium + handler `BattlePassV2GetData`) + **ère de contenu résolue** (stat-sync `BootData.statDataTxt`) → **onglet ACTIF EN JEU**, saison courante, PREMIUM « Activated », paliers réclamables (voir note ↓) |
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
- **2026-07-20 — GAP `REFRESH_TRADER` (marchand/trader) — trouvé à TL65.** Au TL65 le marchand (TRADER, TL0)
  est actif → le client **spamme `REFRESH_TRADER{TYPE=merchantType, REASON}`** (non géré → boucle, comme jadis
  `REFRESH_SPECIAL_EVENTS`). **Réponse attendue** = `MerchantUpdate{type, reason, data:MerchantData}` (le client
  fait `IndividualUser.initMerchantData(type, data)`). C'est la **feature MARCHAND** (écran MERCHANT + achats en
  monnaie du jeu) : construire un `MerchantData` valide = **rouler l'inventaire** du marchand (`MerchantHelper`
  + `merchant_*.tab`). → **feature à part** (pas un simple ack ; un `MerchantUpdate` vide stopperait la boucle
  mais l'écran serait vide). Documenté ; à implémenter quand on fera le marchand.
- **2026-07-20 — FIGHT_PIT / RANKINGS (arène) — non ouverts proprement.** Nav en état de session encombré
  (modale CHANGE NAME résiduelle d'un mis-tap) → l'écran n'a pas basculé. À re-tester en **session propre** ;
  l'arène a probablement besoin de **données d'arène/inscription** côté serveur (à établir).
- **BILAN À TL65** : l'exploration confirme que les écrans mid-game déverrouillés déclenchent chacun une
  **feature serveur à part entière** (marchand `MerchantUpdate`, arène, etc.) ET/OU butent sur l'**ère de
  contenu** (battle pass). Prochaine grosse décision (à valider avec l'utilisateur) : **(A)** traiter l'**ère
  de contenu** (le plus gros levier — débloque le battle pass EN JEU + fiabilise tout le contenu daté), ou
  **(B)** implémenter les features mid-game une par une (marchand, arène, guildes…).

- **2026-07-20 — BATTLE PASS ⚠️ (bloqué EN JEU par l'ÈRE DE CONTENU, pas par le serveur).** À TL65 l'onglet
  `PASS_BUTTON` (QUESTS) est **présent mais inerte** (`listeners=[]`, tap sans effet). **Cause établie par les
  faits** : `QuestsScreen` n'active l'onglet & n'appelle `requestBattlePassV2Data()` que si
  `Unlockables.isUnlocked(BATTLE_PASS)` **ET** `!BattlePassV2Helper.battlePassHidden()`. Or `battlePassHidden()`
  = `now ≥ getBattlePassHiddenTime()` = **constante STATIQUE** `HIDE_BATTLE_PASS_AFTER` des stats
  (`battle_pass_v2_constants.tab` = **2026-04-30**, déjà passée à notre date 2026-07) — elle **ignore** le
  `battlePassV2Data.endTime` qu'on envoie dans BootData. Le client se base sur SES stats embarquées
  (`BootData.statDataTxt/Bin` **non peuplé par le serveur** → le client garde ses stats de l'APK). Donc le
  client croit la saison **terminée** → onglet grisé + **aucun `BattlePassV2GetData` envoyé**. **Mon override
  de saison (roulante mensuelle) est côté SERVEUR seulement** → n'atteint pas le client. **Fait côté serveur** :
  handler `BattlePassV2GetData` ajouté (`LoginServer` → répond `BattlePassV2Data`) — correct, il activera
  l'onglet dès que le client verra une saison active. **CE QUI RESTE (tâche « ère de contenu »)** : faire voir
  au client une saison active — soit **peupler `BootData.statDataTxt` avec un `battle_pass_v2_constants` à
  saison courante** (mécanisme stat-sync du jeu, override opérateur ; propre), soit l'alignement global de
  l'ère de contenu. C'est un chantier à part (transverse), pas un simple handler. **⇒ battle pass serveur =
  OK & testé ; affichage EN JEU reporté à la résolution de l'ère de contenu.** On continue avec les autres écrans.

- **2026-07-20 — BATTLE PASS ✅ RÉSOLU EN JEU (ère de contenu via STAT-SYNC).** Approche (A) retenue et
  implémentée : `ServerUser.bootData` peuple `BootData.statDataTxt["battle_pass_v2_constants.tab"]` avec un
  fichier à **saison courante**. **Mécanisme du jeu (prouvé au bytecode)** : au boot le client applique
  `statDataTxt` via `SyncStatDataClientHelper.updateStats` → `GeneralStats.updateStats(map)` →
  `parseStats(nom, contenu)` pour chaque `parsedFiles` présent (clé = `battle_pass_v2_constants.tab`, « .tab »
  inclus). `BattlePassV2Stats.getStatClasses()` est enregistré dans `GENERAL_STAT_FILE_CLASSES` → `CONSTANT_STATS`
  est re-parsé. On **réutilise le vrai fichier** (game-data/stats) et on ne remplace QUE les 2 lignes datées
  (`SEASON_START_TIME`/`HIDE_BATTLE_PASS_AFTER`, **mêmes bornes que l'ancre serveur** — juillet→août 2026 à notre
  date). Le `TIME` converter (`StatUtil`) parse le format ISO d'origine → on formate en `yyyy-MM-dd'T'HH:mm:ss.SSSXXX`.
  **VÉRIFIÉ EN JEU (TL65, `nav QUESTS`)** : client log `Updated …DHConstantStats from text battle_pass_v2_constants.tab`
  (override appliqué) → **onglet `PASS_BUTTON` désormais `touch=enabled`** (avant : inerte `listeners=[]`) → client
  envoie **`BattlePassV2GetData`** → serveur répond **`BattlePassV2Data(type=QUEST, premium=true, progress=0)`** →
  l'écran BATTLE PASS **rend** : « SEASON 1 », countdown ~11 j (jusqu'au 1er août), **PREMIUM « Activated »**,
  paliers R102 avec pistes gratuite + premium et boutons **Collect** (stamina/or/diamants/chips/coffres). Régression
  **21/21 verte** + `server/smoke/StatSyncProbe` (le client applique l'override → `battlePassHidden()`=false, saison
  active). **⇒ BATTLE PASS entièrement fonctionnel client↔serveur↔affichage.** Fichiers : `server/java/dhserver/ServerUser.java`,
  `server/smoke/StatSyncProbe.java`. **Portée** : ce même levier stat-sync pourra fiabiliser d'autres contenus datés.
