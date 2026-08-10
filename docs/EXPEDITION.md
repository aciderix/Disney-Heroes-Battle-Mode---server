# EXPEDITION (#72 mode suivant) — mode « Expédition » — suivi d'implémentation

> Attaqué au pipeline industrialisé #73/#74 (`contract.sh --mode Expedition` + `ModeGraph --logic`), comme
> SURGE/CHALLENGES/FRIENDSHIPS. Chaque incrément : contrat → logique du jeu (§3) → test headless (WireCheck +
> ClientOracle) → **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon pipeline + bytecode)

Mode **SOLO de progression + combat**, gaté **`Unlockable.EXPEDITION` = TL 25** (`unlockables.tab`). Une **expédition**
= une suite de **nœuds** à une **difficulté** donnée : on choisit une difficulté, on affronte les nœuds l'un après
l'autre (combat client-autoritatif) OU on **raid** (saute le combat), en gagnant or/tickets/récompenses/epic chips, avec
des **wards hebdomadaires** (modificateurs de combat qui tournent chaque semaine) et une **remise à zéro** (reset).

Écran principal **`ExpeditionScreen`** (Destination **`EXPEDITION`** + **`EXPEDITION_DIFFICULTY`**). Fenêtres :
`ExpeditionDifficultyWindowV2`, `ExpeditionConfirmRaidWindow`, `ExpeditionCompleteWindow`, `ExpeditionDefeatWindow`,
`ExpeditionRewardsWindow`, `ExpeditionVictoryRetryWindow`. Hero choosers : `ExpeditionHeroChooserScreen`,
`RaidExpeditionHeroChooserScreen`. Combat : `ExpeditionAttackScreen`.

### Messages (client ↔ serveur)
- **`GetExpedition`** (requête, vide) → **`GetExpeditionResponse{ currentExpedition:ExpeditionRunData, expeditionID:long,
  wasReset:boolean, weeklyWardInfo:ExpeditionWeeklyInfo }`** — patron `GetSurge`. **[MANQUE] handler `LoginServer`.**
- **`ExpeditionAttack{ base:AttackBase, attackerHeroes, defenderHeroes, nodeIndex, epicChips:EpicChipDrop, acceptBattle }`**
  — issue d'un combat de nœud (client-autoritatif → serveur re-exécute, patron `CampaignAttack`/`FriendshipCampaignAttack`).
- **`ExpeditionRaid`** — raid (saute le combat, `doRaidFromClient`).
- **`ResetExpedition`** — remise à zéro du run (économie `chargeForReset`, resets hebdo).

### Données / état
- **`ExpeditionRunData`** (le run) : `difficulty`, `defenders` (lineups des nœuds), `nodesDefeated`, `chestsOpened`,
  `nodeRewards` (List<NodeReward{rewardDrops}>), `ticketsEarned`, `totalGoldEarned`, `droppedEpicChips`, `weeklyWards`.
- **`ExpeditionWeeklyInfo`** : `currentWards` (List<CombatModifier>), `currentWardExpiration`, `nextWards`,
  `nextWardStartTime` — **wards hebdomadaires** (modificateurs de combat rotatifs, calendrier à calculer serveur).
- **Persistance** : seul **`individualUserExtra.expeditionID`** (long) vit dans l'extra ; **le RUN (`ExpeditionRunData`)
  est un ÉTAT SERVEUR-AUTORITATIF** (généré/suivi côté serveur — le builder n'est PAS dans le jar client, comme
  `ArenaInfo`/Surge). ⇒ persistance dédiée à décider (blob par joueur type CHALLENGES, ou shard_state).

### Logique du jeu (§3 — points d'entrée à EXÉCUTER, `ExpeditionHelper` statiques IUser)
`doRaidFromClient(user, difficulty, snap)` · `doRaid(...)` · `giveLoot(user, NodeReward, …)` · `createRewards(user)` ·
`openChest(user, snap, …)` · `chargeForReset(user, diff, snap, bool)` · `getResetsRemaining` · `enableDifficulty` ·
`getMaxDifficulty`/`getMaxEnabledDifficulty`/`isDifficultyAvailable`/`isDifficultyRaidable` · `getRaidCost` ·
`getEpicKeyCost` · `getMaxRaidTickets`/`getRaidTicketReward`/`giveRaidTickets` · `getMinHeroLevel` ·
`calculateNumChipsEpicRaid`/`rollEpicChipsForRound`/`selectRedHero`/`unitQualifiesForEpicReward` ·
`getNextWardsFor(weeklyInfo, diff)`. **Génération des nœuds/defenders** : à établir (ExpeditionStats +
`expedition_*.tab` ; probablement backend → à générer serveur comme Surge/Arena).

## ⚠️ DÉCOUVERTE EN JEU (g89) — le rendu passe par `ResetExpedition`, pas `GetExpedition`
La vérif en jeu de l'incr. 1 (`nav EXPEDITION`, compte TL100) a montré que le client **n'envoie PAS `GetExpedition`**
à l'ouverture d'un compte SANS run : il envoie directement **`ResetExpedition{difficulty, desiredWard:List<CombatModifier>,
firstEverReset:boolean, specialEvents}`** (= « démarre/relance une expédition à cette difficulté avec ce ward ») et
attend la réponse — l'écran reste sur **« SCANNING CITY MAP … »** tant que le serveur ne renvoie pas le run généré.
`GetExpedition` est le rafraîchissement d'un run DÉJÀ actif ; **`ResetExpedition` est le point d'entrée réel** (création
du run). ⇒ **le rendu en jeu est débloqué par l'incr. 2** (générer `ExpeditionRunData` sur `ResetExpedition`), pas par
l'incr. 1 seul. (`GetExpedition`→`GetExpeditionResponse` handler = livré incr. 1, headless `ExpeditionBootTest`, utile
pour un run actif ; à confirmer en jeu une fois qu'un run existe.)

## Plan d'incréments (révisé après découverte en jeu)
1. ✅ **Boot handler (headless)** : handler `GetExpedition`→`GetExpeditionResponse` (état frais = `ExpeditionRunData` vide
   non-null + `expeditionID` persisté + `weeklyWardInfo` non-null). `ExpeditionBootTest` (round-trip wire). **NB in-game :
   insuffisant seul — le client fraîchement ouvert envoie `ResetExpedition`, cf. découverte ci-dessus → incr. 2.**
2. ✅ **`ResetExpedition` + génération du run — LIVRÉ + VÉRIFIÉ EN JEU (g90)** : handler `ResetExpedition{difficulty,
   desiredWard, firstEverReset}` → **génération serveur de `ExpeditionRunData`** (nœuds/`defenders` depuis
   `ExpeditionStats`/`expedition_*.tab` à la difficulté choisie) + `enableDifficulty` + économie (`chargeForReset`,
   `getResetsRemaining` sauf `firstEverReset`) → réponse (run) → l'écran « SCANNING CITY MAP » se résout. Persistance du
   run (état serveur ; incr. 2 tranche : blob par joueur type CHALLENGES, ou champ dédié). **Vérif en jeu : la carte rend.**
   - **✅ VÉRIFIÉ EN JEU (g90, TL100)** : `nav EXPEDITION` → client `ResetExpedition1` → serveur `ResetExpedition(diff=1,
     firstEver=true) → run généré 15 nœuds [persisté]` → « SCANNING CITY MAP » se résout en la **carte d'expédition**
     rendue (titre **« CITY WATCH »** = nom en jeu de l'Expédition ; sélecteur **EASY**, SHOP, bouton reset, carte de la
     ville en 5 régions, nœuds 1-5 — nœud 1 actif/flèche, 2-5 verrouillés, tracker de manche). ⇒ génération + persistance
     (blob `expedition`, colonne ajoutée) OK bout-en-bout. **Calibration ennemis** (niveau/composition) à valider au 1ᵉʳ
     combat (incr. 3). NB : la rangée hub « CITY WATCH » = l'écran EXPEDITION (pas un vestige tuto).
3. ✅ **Combat de nœud — LIVRÉ + VÉRIFIÉ EN JEU (g91)** : `ExpeditionAttack` → re-exécution autoritative → progression
   (`nodesDefeated`), récompense de nœud créditée par la méthode du jeu `ExpeditionHelper.giveLoot` (OR mis à l'échelle
   de la difficulté + objets), epic chips (client-reportés PARTIEL §4bis) ; persistance (blob).
   - **⚠️ 3 défauts trouvés & corrigés (g91, via sondes headless PROFONDES — `WireCheck` ne vérifie que le TYPE, pas la
     profondeur des `List` : angle mort)** :
     1. **Étoiles ennemies invalides (CRASH client).** `buildDefenders` passait `createAndAddHero(type, rarity, level, 1)`
        → l'ordre des 2 entiers est **(ÉTOILES, NIVEAU)** (bytecode `createUnitData` : `setStars(a)`/`setLevel(b)`) → ennemis
        à **140 étoiles**/niv.1. Étoiles > `getMaxStars` (=6 R102) font planter le client au rendu du combat. Corrigé :
        `stars = UnitStats.getMaxStars(user)`, `level = base`.
     2. **`nodeRewards` VIDE → `IndexOutOfBounds` au 1ᵉʳ nœud EN JEU.** `ExpeditionAttackScreen.createStageDefenders` lit
        `getExpeditionData().getData().nodeRewards.get(nodeIndex)` ET `defenders.get(nodeIndex)`. Le run n'avait pas de
        `nodeRewards` avant la 1ʳᵉ victoire. Corrigé : **pré-génération au reset** via `ExpeditionHelper.createRewards`
        (§3, 15 `NodeReward{OR}`).
     3. **Niveau ennemi DOUBLÉ.** Le serveur envoyait `base+getExtraEnemyLevels`, or le **client ajoute**
        `getExtraEnemyLevels(difficulty)` au combat. Corrigé : le serveur envoie la **BASE** (= TL du joueur ; EASY diff=1
        ajoute 0), le client ajoute l'extra.
   - **Réponse à `ResetExpedition`** = **`ResetExpeditionResponse`** (type DÉDIÉ → handler client `$55` :
     `clearModePersistentData`/`clearMercenaryHero`/`enableDifficulty`/`onExpeditionReset`), au lieu de
     `GetExpeditionResponse` qui sautait ce nettoyage (§3).
   - **✅ VÉRIFIÉ EN JEU (g91, compte TL100)** : `nav EXPEDITION` → `GetExpeditionResponse (15 nœuds)` → carte **CITY WATCH**
     → `expfight` → **CHOOSE YOUR HEROES** rend l'équipe (defenders=15, nodeRewards=15 côté client, **plus de crash**) →
     `expquick` → **combat RENDU joué** (`createStageDefenders` OK) → **DEFEAT** d'abord (roster de test niv.40-60 vs
     ennemis niv.100 corrects) → `ExpeditionAttack(LOSS)` → serveur « pas de progression [persisté] ». Puis roster porté
     à niv.100 (outil DEV `ExpAdminBoost`, état de compte légitime comme `SetTeamLevel`) → **VICTORY** → écran **REWARDS
     LOOT 5 157 or** → CONTINUE → `ExpeditionAttack(WIN)` → serveur **`nœud 0 VAINCU → nodesDefeated=1, or +5157 [persisté]`**
     → **DB confirmée** (`nodesDefeated=1`, `totalGoldEarned=5157`, GOLD crédité) → la carte avance au nœud suivant.
     Pilotes DEV : `expfight` (pousse `ExpeditionHeroChooserScreen(node, NONE)`), `expquick` (`quickFightPressed`) ;
     outils DEV `ExpAdminReset`/`ExpAdminBoost`.
4. ✅ **Raid — LIVRÉ + VÉRIFIÉ EN JEU (g92)** : `ExpeditionRaid` (saute le combat, complète TOUTE l'expédition d'un
   coup). Client-autoritatif : le client exécute `doRaidFromClient` (→ `doRaid` local) puis envoie `ExpeditionRaid{
   rewards, difficulty}` ; le serveur RÉ-EXÉCUTE l'autorité via la MÊME méthode du jeu (§3) `ExpeditionHelper.doRaid(
   user, difficulty, nodesDefeated, snap, finisher, null)`.
   - **Gate `isDifficultyRaidable`** (anti-triche) : refuse tant que la difficulté n'a pas été CLEARÉE une fois
     (`getRawMaxEnabledDifficulty > diff` ; sinon `COMPLETE_PREVIOUS_EXPEDITION_FIRST`). ⇒ **`recordAttack` active
     désormais la difficulté suivante au clear complet** (`nodesDefeated == 15 → enableDifficulty(diff+1)`, mirroir
     EXACT du client `ExpeditionAttackScreen`).
   - **Débit du coût en tickets de raid** : `getRaidCost(user, diff)` × `getRaidTicketType(diff)` (diff 1 → 1 ×
     `EXPEDITION_RAID_1` ; lève `DONT_HAVE_ITEM` si insuffisant → anti-triche).
   - **Crédit de TOUS les nœuds** (`createRewards` + drops/epic chips roulés → `giveRewards`), `chargeForReset`,
     `incDailyUses` ; le `finisher` marque le run persisté COMPLET.
   - **6ᵉ arg = `null`** (comme le client : `aload 5 ifnull → saute compareDrops`) → le serveur ROULE et CRÉDITE son
     PROPRE butin, sans faux rejet `INVALID_LOOT` sur divergence RNG (même choix que le loot campagne #25/§4bis).
   - **✅ VÉRIFIÉ EN JEU (g92, TL100)** : compte rendu raidable (outil DEV `ExpAdminRaidable` : clear réel des 15 nœuds
     → `enableDifficulty(2)` → diff 1 raidable ; + 5 tickets) → `nav EXPEDITION` → `expraid` (`doRaidFromClient` réel) →
     `ExpeditionRaid` → serveur **`RAID diff=1 → expédition complète (nodesDefeated=15), or +370531 [persisté]`** →
     **DB confirmée** (`nodesDefeated=15`, `totalGoldEarned=370531`, tickets `EXPEDITION_RAID_1` 5→4, GOLD crédité).
     Pilote DEV `expraid` ; outil DEV `ExpAdminRaidable`. Tests headless `ExpeditionRaidTest`.
5. 🟢 **Wards hebdomadaires — LIVRÉ (headless) + délivrance vérifiée EN JEU ; EFFET/affichage HARD+ différés (g93)** :
   `GetExpeditionResponse.weeklyWardInfo` est désormais PEUPLÉ (au lieu d'un objet vide). Les wards (`CombatModifier`)
   sont des modificateurs de combat qui tournent chaque semaine et ne s'appliquent qu'aux **difficultés ≥ 3**
   (`getWardsFor` renvoie EMPTY pour diff < 3 ; EASY/NORMAL n'ont PAS de ward — fidélité).
   - **POOL = donnée du jeu** (`ExpeditionStats$WardStats.wardsByDifficulty` : diff 3 & 4 → 13 wards chacun ; §4, lu
     par réflexion). **ROTATION = serveur, déterministe par l'indice de semaine DU JEU** (`TimeUtil.getServerWeek`) — le
     backend de sélection est absent du jar (comme `ArenaInfo`/Surge) → calibration serveur documentée (patron incr. 2).
     2 wards exposés : HARD (pool diff 3) + EPIC additionnel différent (pool diff 4) ; `getWardsFor(info, diff)` tranche
     `subList(0, diff-2)`. **BORNES** : `currentWardExpiration`/`nextWardStartTime` = prochaine frontière hebdo
     (`MILLIS_PER_WEEK`).
   - `ExpeditionWardTest` : structure via les accesseurs DU JEU `getWardsFor`/`getNextWardsFor`, rotation déterministe
     (`nextWards[s] == currentWards[s+1]`), bornes, round-trip wire.
   - **EN JEU (g93)** : `nav EXPEDITION` → serveur délivre `GetExpeditionResponse` avec `weeklyWardInfo` PEUPLÉ (2 wards
     réels) → le client l'ACCEPTE et rend CITY WATCH sans erreur (régression : avant vide, maintenant peuplé, toujours OK).
   - **DIFFÉRÉ (§8, gate de progression, documenté)** : l'**EFFET** des wards en combat (diff ≥ 3 HARD/EPIC) et leur
     **affichage** dans `ExpeditionDifficultyWindowV2` ne sont pas atteignables sur le compte de test (EASY complété ;
     le sélecteur de difficulté est grisé sur un run terminé ; HARD requiert de clearer NORMAL). À vérifier sur un
     compte plus avancé. La rotation EXACTE du backend reste à OBSERVER en jeu (comme le protocole de raid SURGE avant
     câblage) ; notre rotation déterministe est un stand-in fidèle (pool = donnée du jeu).
6. ✅ **Reset (économie) — LIVRÉ + VÉRIFIÉ EN JEU (g94)** : `chargeForReset`/`getResetsRemaining`. Relancer une
   expédition (hors 1ᵉʳ run) consomme un RESET GRATUIT (`CITY_WATCH_RESETS`) ; épuisé, il coûte `getEpicKeyCost(diff)`
   clés epic (`CITY_WATCH_EPIC_KEYS`) ; à défaut refusé (`EXPEDITION_CHANCES_USED`).
   - **Barème DU JEU (§4, bytecode)** : `getResetsRemaining = max(CITY_WATCH_RESETS, quota quotidien)` ;
     `getEpicKeyCost(diff)` = **0 pour diff 1-3**, **35 pour diff 4 (EPIC)**. ⇒ EASY (coût 0) : resets **limités** au
     quota gratuit puis **refusés** (pas d'option payante) ; EPIC : payable en clés epic.
   - `resetResponse.resetsDone` = `DailyActivityHelper.getDailyUses(user, "EXPEDITION RESET", …)` (compteur d'activité
     quotidienne DU JEU ; ne compte pas les resets-ressource → peut être 0). `ServerExpedition.resetsDoneToday`.
   - `ExpeditionResetTest` : barème epic (0 EASY / 35 EPIC) ; firstEver ne consomme rien ; reset EASY consomme le
     gratuit puis refusé ; reset EPIC refusé sans clé puis payant (35→0) ; persistance DB.
   - **✅ VÉRIFIÉ EN JEU (g94, TL100)** : run frais (`ExpAdminReset`, `CITY_WATCH_RESETS=1`) → `nav EXPEDITION` →
     `expreset 1` (`ClientExpeditionHelper.resetExpedition` réel) → client `ResetExpedition(firstEver=false)` → serveur
     `run généré 15 nœuds [persisté]` → réponse `ResetExpeditionResponse` → **carte fraîche rendue** (nœud 1 actif, 2-5
     verrouillés) → **compteur de reset (coin sup. droit) 1 → 0** (reset gratuit consommé, visible en jeu) ; DB
     `CITY_WATCH_RESETS 1→0`, persisté. Pilote DEV `expreset [diff]`.
7. ⬜ **Récompenses / coffres** : `createRewards`/`openChest` (coffres d'expédition, epic chips → héros).
8. ⬜ **Vérif EN JEU complète** (solo, compte TL100) : difficulté → combat → récompenses → raid → reset.

## Notes §3/§4
- Combat client-autoritatif (comme campagne/amitié) : loot d'objets = client-reporté (PARTIEL §4bis/#25) ; or/tickets/
  progression/epic chips = calculés SERVEUR via `ExpeditionHelper`. Zéro invention (§4) : tout des `expedition_*.tab`.
- Le run est un état serveur (pas dans l'extra) → persistance dédiée (à trancher en incr. 2).
