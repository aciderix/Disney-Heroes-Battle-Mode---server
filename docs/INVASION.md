# INVASION — état des lieux et implémentation (tâche #69)

> Mode de guilde. Audit du 2026-07-28 : **0 / 50 messages gérés** au départ. Ce document trace ce que le jeu
> fournit RÉELLEMENT (vérifié, pas supposé — consigne user : « double check plutôt que conclure trop vite »),
> et l'avancement.

## Ce que le jar contient (vérifié)

**Bonne nouvelle : l'essentiel de la logique est CÔTÉ CLIENT.** Contrairement aux dons de guilde (où le builder
de demande manquait), INVASION est très bien fourni :

| Élément | Présence | Détail |
|---|---|---|
| `InvasionHelper` | ✅ riche | `claimBossRewards`, `claimGuildRankRewards`, `claimUserRankRewards`, `chargeForBossAttack`, `chargeForBreakerAttack`, `recordBossFightOutcome`, `recordBreakerFightOutcome`, `rollBossRewardLoot`, `calculateEmpowerment`, `getBossHP`, `invasionDailyReset`… |
| `InvasionStats` | ✅ | plafonds, coûts, niveaux, récompenses de ligue, seuils de boss… |
| `ClientInvasion` / `ClientInvasionUser` | ✅ | implémentations concrètes de `IInvasion` / `IInvasionUser` → **le serveur n'a qu'à envoyer les bons messages**, comme pour l'arène |
| Données | ✅ **17 fichiers** | `invasion_constants`, `invasion_boss_rewards{,_guild,_solo}`, `invasion_boss_traits`, `invasion_league_rewards`, `invasion_guild_rank_league_rewards`, `invasion_user_rank_league_rewards`, `invasion_progress_rewards`, `invasion_breaker_*`, `invasion_point_multiplier`, `invasion_supply_rewards`, `invasion_stamina_buys`, `invasion_consumable_rewards`, `invasion_merchant_drops`, `boss_environments` |
| Combat | ✅ | toute la famille `game/buff/invasion/*` (augments, breakpoints) |

`invasion_constants.tab` contient **toutes les règles** : calendrier (`START_DAY=MONDAY`/`START_TIME=12h` →
`END_DAY=SATURDAY`/`END_TIME=12h`), énergie d'invasion (initiale 40, capacité 80, régén 12 min), ligues
(`LEAGUE_MIN_K=2`, `MAX_K=12`, `DESIRED_SIZE=50`, promo 5 / relégation 60), combats de breaker (coût 10 énergie,
niveau `(R<7 ? 5R : 12+3R)*5`, 3 étoiles, or `1000+10R`), boss (clés 1×/5× = 1/3, limite 24 h, niveau initial 450,
delta victoire +25, durée 60 s), plafonds quotidiens de guilde, etc.

## Implémenté

### ✅ Socle : calendrier + identité de l'invasion (`ServerInvasion`)
- **Fenêtre** : lundi 12:00Z → samedi 12:00Z, calculée depuis `START_DAY/START_TIME/END_DAY/END_TIME`.
- **Rotation** : `INVASION_BASE_ROTATION + semaines écoulées depuis INVASION_BASE_DATE` (base = 2022-02-27, 23).
  **Pourquoi c'est au serveur** : ces deux constantes sont déclarées dans les données mais référencées par
  **aucune classe cliente** (scan du pool de constantes de tout le jar) — exactement la signature de
  `MERCENARY_COST`, qui s'est avérée être une valeur à calculer côté serveur.
- **Équipe vedette** + ses héros via `UnitStats.getTeam` (94 RED / 99 BLUE / 93 YELLOW — données du jeu).
- Handler `GetInvasionInfo` → `InvasionInfo` (invasion courante si dans la fenêtre, sinon prochaine échéance).
- `InvasionScheduleTest` : constantes lues, fenêtre lundi→samedi, bascule du lundi 12:00, rotation +1/semaine et
  exacte à la date de base, équipe qui tourne, héros non vides, `InvasionInfo` cohérent.

> **Seule lecture structurelle assumée** : l'équipe tourne en cycle `RED → BLUE → YELLOW`. Le jar donne le champ
> `InvasionData.team` et le mapping héros→équipe, mais **aucune table n'énonce « rotation N ⇒ équipe X »**.
> Le cycle à 3 est le choix minimal cohérent ; il est isolé dans `ServerInvasion.teamForRotation()` pour être
> corrigé en un seul point si une preuve apparaît.

### ✅ État joueur (`UserInvasionData`, 34 champs)
- **Énergie d'invasion** : VÉRIFIÉ — `INVASION_STAMINA` (et `BREAKER`, `INVASION_POINTS`) sont de vraies
  ressources du jeu, **réglables** (contrairement à `GUILD_CONTEST_POINTS`) et déjà initialisées à leur capacité
  (80). La régénération est donc assurée par la mécanique de ressources existante — rien à réimplémenter.
- **Persistance** : nouvelle table `user_invasion` (shardID, userID → octets wire du `UserInvasionData`),
  séparée de `users` car remise à zéro chaque semaine.
- **Reset de rotation** : `ServerInvasion.loadOrResetUserData` renvoie un état neuf dès que l'`invasionID` change
  (équivalent de `InvasionHelper.resetUserInvasion`) ; sinon la progression est conservée. La guilde est
  rafraîchie à chaque lecture (un joueur peut changer de guilde en cours de semaine).
- Handler `GetInvasionInfo` : remplit `currentInvasion.yourData` et persiste.
- Couvert par `InvasionScheduleTest` (progression conservée sur la même rotation, remise à zéro à la suivante).

## Reste à faire

1. ~~État joueur~~ ✅ (ci-dessus)
2. **Breakers** — 🔨 boucle de récompense FAITE, composition à générer :
   - ✅ `ServerInvasion.resolveBreakerFight(user, userData, room, victoire, now)` : résolution autoritative avec
     les FORMULES DU JEU via `InvasionStats` — coût `BREAKER_FIGHT_STAMINA_COST`=10, niveau
     `BREAKER_FIGHT_LEVEL(room)`, or `BREAKER_FIGHT_GOLD_REWARD(room)`, points `BREAKER_FIGHT_POINT_REWARD`,
     gain `BREAKER_FIGHT_BREAKER_REWARD`. Débit d'énergie même en défaite, gains réservés à la victoire, refus
     hors fenêtre d'invasion. Vérifié : room 41 → niveau 675 = `(12+3·41)*5`, or 1410 = `1000+10·41`.
   - 🔨 **COMPOSITION : générée depuis la table de drop DU JEU** — `ServerInvasion.rollBreakerComposition(room,
     invasion, graine)` tire `InvasionStats.BREAKER_FIGHT_COMP` (`invasion_breaker_fight_comp.tab`) via
     `DropTable.roll` avec un `BreakerDTContext(room, IInvasion)`. Cela a nécessité **`ServerInvasionObject`**,
     implémentation SERVEUR de `IInvasion` (le contexte appelle `getStartTime()`) : 18 méthodes, adossée à
     l'`InvasionData` calculé et DÉLÉGUANT les récompenses aux tables du jeu.
     - **En processus isolé, le résultat est excellent et authentique** : room 1 → 6 unités niveau 25,
       room 20 → 9 niveau 360, room 45 → 14 niveau 735 — niveaux exactement conformes à `BREAKER_FIGHT_LEVEL`,
       et les conditions `RoomTest(16)`/`RoomTest(41)` débloquent bien des wards supplémentaires
       (`WARD_INCREASE_DAMAGE`, `WARD_INCREASE_SP`). Unités réelles (`SOULLESS_*`), rien d'inventé.
     - **🐛 Bug corrigé au passage** : `DropTable.roll` renvoie une liste RÉUTILISÉE d'un tirage à l'autre
       (tampon interne) → sans copie, trois compositions distinctes finissaient identiques. `rollBreakerComposition`
       en prend désormais une copie.
     - **✅ ANOMALIE ÉLUCIDÉE (le tirage dépend du JOUEUR)** : la divergence 6/9/14 vs 25/25/25 venait du
       CONTEXTE DE JEU. Bisection : le basculement se produit exactement à `ServerUser.newPlayer(...)`, qui LIE un
       utilisateur à `DH.app`. La table de composition consulte cet utilisateur courant :
         * **avec** joueur lié → de VRAIS héros du jeu (`BO_PEEP{level=25}`, `CHEF_SKINNER{level=735}`), 25 unités
           = le breaker + ses wards — cohérent avec `WARDS_PER_BREAKER=4` et avec
           `InvasionHelper.makeBreakerDefender(IInvasion, IHero)` qui prend bien un HÉROS ;
         * **sans** joueur lié → repli dégradé sur des mobs génériques `SOULLESS_*` (6/9/14).
       Autrement dit **c'est le cas « isolé » qui était faux**, pas le cas complet. Correction : la méthode prend
       désormais le `ServerUser` en paramètre et appelle `ServerUser.bindGameContext()` — contexte EXPLICITE au
       lieu de dépendre du dernier appel effectué.
     - **Assertions fortes rétablies** dans `InvasionScheduleTest` : niveau exact = `BREAKER_FIGHT_LEVEL(room)`
       (25 en room 1, 735 en room 45), compositions DIFFÉRENTES entre salles, tirage DÉTERMINISTE (même joueur +
       même graine), et le breaker (1ᵉ élément) est un vrai héros — pas un `SOULLESS_*` du mode dégradé.
       *(La composition MÉLANGE légitimement le breaker héros et ses wards `SOULLESS_*` : seule la tête compte.)*
   - ✅ **MESSAGES D'ATTAQUE CÂBLÉS** :
     * `InvasionBreakerAttackStart` → le serveur tire la composition adverse **dans le contexte du joueur**
       (graine dérivée de invasion+salle+joueur ⇒ composition STABLE tant que la salle et l'invasion ne changent
       pas) et répond `BreakerUserFightData`.
     * `InvasionBreakerAttack` → résolution AUTORITATIVE (`resolveBreakerFight`) : énergie débitée, or/points/
       BREAKER accordés selon les formules du jeu, `UserInvasionData` mis à jour et PERSISTÉ — même schéma que
       `CampaignAttack` (victoire = `CombatOutcome.WIN`).
     * `InvasionScheduleTest` couvre le flux complet : ouverture (composition stable) → victoire → relecture
       depuis la base (progression et or conformes).
   - ⏳ **Note** : rappel du fonctionnement de la table pour la suite —
     `invasion_breaker_fight_comp.tab` est une **table de drop** (`ROOT → <BREAKER>, <WARD_1..4>` avec
     `RoomTest(16)`/`RoomTest(41)` qui font varier les wards selon la room) et le jeu fournit le contexte
     `UserInvasionDTContext` + `InvasionHelper.makeBreakerDefender` → réutiliser la machinerie de drop-tables
     déjà employée pour le loot. Puis câbler `InvasionBreakerAttackStart` / `InvasionBreakerAttack`
     (mêmes formes que `CampaignAttack`, déjà géré).
3. **Boss** — ✅ FAIT (état partagé, attaque, récompenses, réclamation) :
   - **État partagé** `ServerGuild` **v7** : `invasionBossWire` (octets wire des `InvasionBossInfo` du jeu),
     `bossAttackLocks` (bossID → userID+expiration), `nextBossID`. Persisté.
   - `spawnBoss` : niveau `BOSS_FIGHT_INITAL_LEVEL`=450, échéance `BOSS_FIGHT_TIME_LIMIT`=24 h,
     `foundByUser`/`foundByGuild` renseignés. `activeBosses` purge les expirés.
   - `attackBoss` : VERROU exclusif (`ATTACK_LOCK_DURATION`=5 min, repris à expiration —
     `BOSS_SIMULTANEOUS_ATTACKS_COUNT`=1), clés `BOSS_FIGHT_1X/5X_KEY_COST`=1/3 débitées sur la ressource
     `BREAKER`, dégâts CUMULÉS par joueur, refus si clés insuffisantes / boss expiré / invasion inactive.
   - **Récompenses** : `ServerUser.rollInvasionBossRewards(...)` DÉLÈGUE à `InvasionHelper.rollBossRewardLoot`
     (tables `invasion_boss_rewards{,_guild,_solo}`). Conforme à la table pour PARTICIPANT/MEGA_VIRUS :
     5 INVASION_STAMINA, 20 BOSS_TECH, 90 INVASION_POINTS, 100 CODEBASE_CHEST_1X + 1 consommable aléatoire.
   - Handlers `GetInvasionBosses` et `ClaimInvasionBossRewards` (rôle FINDER/PARTICIPANT déduit de l'état
     partagé, refus sans participation, crédit via `RewardHelper.giveRewards`, persisté).
   - `InvasionBossTest` : 11 vérifications (apparition, cumul par joueur, coût 5×>1×, état partagé 2 membres,
     verrou exclusif puis expiré, refus sans clés, round-trip DB v7, expiration, récompenses = table, rôles).

   **🐛 Deux pièges résolus** :
   1. `InvasionBossInfo.damageDone` est un `Map<Long, InvasionBossDamageData>` — y mettre un `Long` brut fait
      DISPARAÎTRE SILENCIEUSEMENT les dégâts au passage wire (carte vide après round-trip, aucune erreur).
   2. **Diagnostic corrigé** : j'avais noté « récompenses vides pour un TL1, sans doute conditionnées par l'état
      du joueur ». **Faux — c'était mon bug** : `SpecialEventSnapshot` passé à `null`, `createDrop` appelle
      `getLootResourceMultiplier()` dessus → NPE **avalée par mon propre `catch`**, d'où une liste vide sans
      erreur. Le TL n'y était pour rien (identique à TL1/30/100). Corrigé par `SpecialEventSnapshot.NONE`.
      *Leçon : un `catch` trop large a transformé une NPE en « comportement métier » plausible.*

   - ⏳ **Reste** : handlers `StartInvasionBossAttack`/`InvasionBossAttack` (les dégâts se dérivent du combat
     rapporté par le client — pas de champ explicite dans le message, à établir factuellement). Le broadcast est
     prêt : `InvasionBossAttacked{attackerID, damageDone, mostDamageUser, guildPoints…}` via `pushToGuild`.
4. **Ligues et rangs** — ✅ CLASSEMENTS FAITS :
   - `UserStore.listUserInvasions(shard)` : tous les états d'invasion du shard (base des classements).
   - `ServerInvasion.userRanking(...)` : joueurs triés par points d'invasion (rangs 1..N).
   - `ServerInvasion.guildRanking(...)` : score d'une guilde = SOMME des points de ses membres.
   - `leagueAfterRank(ligue, rang)` : promotion si rang ≤ `LEAGUE_PROMOTE_THRESHOLD`=5, relégation si
     rang ≥ `LEAGUE_DEMOTE_THRESHOLD`=60, sinon maintien ; ordre du jeu UNRANKED→BRONZE→SILVER→GOLD→
     PLATINUM→CHALLENGER, sans débordement aux extrémités.
   - Handlers `GetUserInvasionLeagueInfo` / `GetGuildInvasionLeagueInfo` → `UserInvasionLeagueInfo` /
     `GuildInvasionLeagueInfo` (taille de division = `LEAGUE_DESIRED_SIZE`=50).
   - **🐛 Bug corrigé** : `loadOrResetUserData` ÉCRASE le `guildID` persisté par son paramètre — correct dans le
     chemin « handler » (on connaît la guilde courante), mais faux pour une lecture EN MASSE : le classement des
     guildes ressortait VIDE. D'où `readUserData(bytes)`, lecture seule, utilisée par les classements.
   - Couvert par `InvasionBossTest` : ordre des joueurs (1500>900>700>500), score de guilde = somme des membres
     (joueur sans guilde exclu), seuils de promotion/relégation/maintien.
   - ✅ **`INVASION_CLAIM_GUILD_RANK_REWARD` CÂBLÉ** : le rang vient du classement SERVEUR (somme des points des
     membres) ; `ServerUser.claimInvasionRankRewards(...)` délègue à `InvasionHelper.claimGuildRankRewards` /
     `claimUserRankRewards` (tables `invasion_{guild,user}_rank_league_rewards`). L'anti-double-réclamation
     utilise les drapeaux DU JEU `UserInvasionData.hasGuildRankRewards`/`hasUserRankRewards` : la réclamation
     n'a lieu que si le drapeau est ARMÉ, la logique du jeu le DÉSARME, et l'état est re-persisté.
     Cela a nécessité **`ServerInvasionUser`** — implémentation SERVEUR de `IInvasionUser` (34 méthodes), simple
     adaptateur au-dessus du `UserInvasionData` persisté (le client a son `ClientInvasionUser`, adossé à `DH.app`).
   - Couvert par `InvasionBossTest` : refus sans drapeau, réclamation unique, drapeau désarmé, 2ᵉ tentative
     refusée, round-trip DB.
   - ✅ **REPORT DE LIGUE d'une semaine sur l'autre** : `carryOverLeague(précédent, neuf, rangFinal)`. À la
     bascule de rotation, l'état repart à zéro (comme `resetUserInvasion`) MAIS la ligue SURVIT, mise à jour par
     `leagueAfterRank` selon le rang final (promotion ≤5, relégation ≥60, sinon maintien), et les drapeaux de
     récompense de rang sont ARMÉS pour un joueur classé. Appelé automatiquement par `loadOrResetUserData`.
     Vérifié : points remis à zéro + SILVER conservé ; rang 2 → SILVER promu GOLD (récompenses armées) ;
     rang 90 → rétrogradé BRONZE.
5. **État partagé de guilde** : dégâts au boss par membre, plafonds quotidiens de guilde
   (`GUILD_DAILY_BOSS_LIMIT=100`, `BOSS_GUILD_DAILY_LIMIT=2400`) → table `shard_state` / `ServerGuild`.


---

## ✅ VÉRIFICATION EN JEU (2026-08-02) — l'écran s'ouvre enfin, et un manque de plus

Première vérification EN JEU du mode (PRINCIPLES §8). Compte BaronessDante, TL 100 (INVASION exige
`TEAM_LEVEL_REQ 60`).

### Le calendrier bloquait, et c'était FIDÈLE

`nav INVASION` répondait `canNavigateTo=false`. Sonde des prédicats DU JEU, un par un, plutôt que de
supposer : `Unlockables.isUnlocked(INVASION)` = **true** (TL 100 ≥ 60), mais `ServerInvasion.isActive` =
**false** — l'invasion va du **lundi 12 h au samedi 12 h** et on était un dimanche. Gate correct, rien à
corriger.

**Levier ajouté : `-Ddh.clock.offset.hours`** (`ServerContext.applyClockOffset`, exposé par
`DH_SERVER_OPTS` dans `run-online.sh`). Le serveur est la SOURCE DE L'HEURE — le client se cale dessus via
`TimeUtil.initClock(BootData.serverTime, deviceTime)` — donc décaler l'horloge serveur décale l'ensemble de
façon cohérente, et toutes les mécaniques datées suivent **leur propre logique**, inchangée. Ce n'est pas un
contournement (§2) : aucune vérification n'est court-circuitée, on avance la pendule. Vérifié de bout en
bout : à `+30 h`, le serveur ET le client affichent lundi. (Au passage, `BootData.serverTime` et l'écho
`Ping` envoyaient `System.currentTimeMillis()` en dur — corrigé en `serverTimeNow()`, sans quoi un décalage
désynchroniserait client et serveur.)

### 🐛 Manque RÉEL nº1 : l'invasion n'était jamais poussée au boot

Horloge dans la fenêtre, feature déverrouillée… et `canNavigateTo` restait **false**. Cause nommée par la
sonde : **`InvasionHelper.getActiveInvasion()` = null**. Le client ne connaît l'invasion que par le message
`InvasionInfo` — qu'il ne demandait jamais, puisqu'il faut déjà être sur l'écran pour l'envoyer. Poule et
œuf. Le vrai backend la pousse au login, comme `SocialHistory` pour le chat de guilde.

Correctif : `LoginServer.sendInvasionInfo(c, user, replyTo)`, appelée en réponse à `GetInvasionInfo` **et
spontanément au BOOT**. Résultat immédiat : `getActiveInvasion` rend un `ClientInvasion`,
`canNavigateTo(INVASION)` passe à **true**, et l'écran s'ouvre.

### Ce que l'écran affiche, et ce que ça prouve

`==> InvasionInfo : rotation #254 équipe YELLOW du Mon Aug 03 12:00 au Sat Aug 08 12:00 [EN COURS]`

* popup **HOW INVASION WORKS** (et `VIEW_INVASION_EXPLANATION` reçu côté serveur) ;
* écran **INVASION!** avec le compte à rebours **4j 18h 59m** — exactement la fenêtre envoyée, relue par le
  client depuis notre message ;
* **BREAKER QUEST (SOLO)** avec l'**énergie d'invasion 80/80** (ressource `INVASION_STAMINA` du jeu, à son
  cap) ; **BOSS BATTLES (GUILD)** avec 0 breaker ;
* **MY PROGRESS: TIER 1**, barre **0/100** points d'invasion ; MY SCORE 0 / GUILD SCORE 0, rangs « - ».

### 🐛 Manque RÉEL nº2 : `GetBreakerQuest` n'est pas géré

Taper **GO** sur BREAKER QUEST envoie `GetBreakerQuest1` — que le serveur **journalise sans y répondre**
(aucun handler). L'écran reste **entièrement vide**, le client attendant un `BreakerQuest`
{`activeBreakerFight`, `basicBreakerFights`} qui ne vient jamais.

Le mode n'était donc **pas complet**, contrairement à ce que le statut laissait croire : les handlers
existants (`GetInvasionInfo`, `GetInvasionBosses`, `InvasionBreakerAttackStart`, `InvasionBreakerAttack`,
`INVASION_CLAIM_GUILD_RANK_REWARD`) ne couvrent pas l'entrée du mode SOLO. **Non corrigé à ce stade** —
c'est le prochain chantier, avec sa propre étude (composition des combats de breaker depuis
`invasion_*.tab`).

### Restent NON vérifiés en jeu

BREAKER QUEST (bloqué par le manque nº2), BOSS BATTLES, les récompenses de rang de guilde, le report de
ligue d'une invasion à la suivante.


## BREAKER QUEST — handler écrit, composition servie, rendu à finir (2026-08-02, suite)

Le manque nº2 ci-dessus est traité côté serveur : **`GetBreakerQuest` a un handler**
(`ServerInvasionBreaker.buildQuest`) et le client reçoit désormais **10 combats** au lieu de rien
(`==> BreakerQuest (10 combat(s), à partir de la salle 0)`).

**La composition vient des données, pas d'une invention.** Un tirage de
`InvasionStats.BREAKER_FIGHT_COMP` (`invasion_breaker_fight_comp.tab`) rend **25 `DropItem`** — relevé à
l'exécution, pas supposé : cinq groupes de cinq unités, chacune portant `level`/`stars`/`rarity`, plus deux
marqueurs qui structurent le tout — `ward=WARD_xxx` (le garde auquel l'unité appartient ; la ligne ROOT de
la table est `<BREAKER>, <WARD_1>, <WARD_2>, <WARD_3>, <WARD_4>`) et `boss=true` (la vedette du groupe).
La taille de page vient de `BREAKER_PAGE_SIZE`/`BREAKER_FIRST_PAGE_SIZE`, et l'indice de départ est
`UserInvasionData.breakerBattlesWon` — le `R` des formules `BREAKER_FIGHT_LEVEL/GOLD/POINT_REWARD`.

**`InvasionBreakerAttackStart` ne répondait plus avec des listes vides** : `breakerDefenders` et
`wardLineups` sont désormais remplis depuis cette même composition.

### Trois faits mesurés en route (aucun deviné)

1. **`DropItem.getType()` rend une CHAÎNE**, pas un `UnitType` — le cast direct levait
   `ClassCastException` sur chaque unité, et la quête sortait à 0 combat.
2. **Le niveau d'équipe indexe des tables du jeu** : poser 999 sur l'atelier de fabrication levait
   `ArrayIndexOutOfBoundsException: Index 999 out of bounds for length 751` dans `TeamLevelStats`. On prend
   désormais le **maximum réel** des données (dichotomie sur `getMaxHeroLevel`), pas un chiffre rond.
3. **`ServerContext.bind` échoue avec un `IndividualUser` d'identifiant 0** — l'atelier utilise l'identifiant
   et le shard du joueur, comme les bots d'arène.

### ⚠️ Ce qui reste : le RENDU

Le client accepte le message **sans lever**, mais l'écran BREAKER QUEST reste **visuellement vide**. Le
serveur envoie donc quelque chose de structurellement valide mais d'incomplet pour l'affichage. Piste la
plus probable, par analogie avec l'arène : les `HeroSummary` fabriqués partagent tous le même `heroId`
(l'atelier réutilise un seul `User`), et le client indexe ses widgets par `heroId` — c'est exactement le
problème que `ServerArena.offsetHeroIds` résout pour les lignes de classement. À vérifier et corriger à la
prochaine session, avec une capture à l'appui.

### Enquête sur l'écran vide — ce qui est ÉLIMINÉ, et le prochain pas

Sonde ajoutée (`breakerdump` : dump côté CLIENT du champ `BreakerQuest` de l'écran). Verdict :
**`BreakerQuest = null` sur `InvasionBreakerScreen`**, alors que le serveur journalise bien
`==> BreakerQuest (10 combat(s))`. Le message part donc, mais le client ne le range nulle part.

Chemin du client, relevé au bytecode (`GameMain.lambda$setupPostClientInfoHandlers$120`) :

```java
InvasionHolder h = this.currentInvasion;
if (h != null) h.setBreakerQuest(bq);
if (screenManager.checkStackForScreen(InvasionBreakerScreen.class)) handleScreenMessage(bq);
```

**Éliminé par la mesure** : ce n'est pas l'appariement requête/réponse. Les deux formes ont été essayées EN
JEU — réponse appariée (`setAsReplyTo`) **et** poussée spontanée — et l'écran garde `null` dans les deux cas.
Ce n'est pas non plus une exception : ni le client ni le serveur ne journalisent quoi que ce soit. Et ce
n'est pas l'hypothèse `heroId` avancée plus haut : elle expliquerait un rendu fautif, pas un champ `null`.

**Prochain pas, précis** : instrumenter le CLIENT pour savoir si le message est seulement *décodé* — enregistrer
un listener temporaire sur `BreakerQuest` depuis le pilote, et dumper `GameMain.currentInvasion` (si ce champ
est `null`, le handler ci-dessus n'appelle ni `setBreakerQuest` ni `handleScreenMessage`, ce qui produit
exactement le symptôme observé, y compris l'absence totale de trace).

**Statut honnête** : `GetBreakerQuest` n'est plus un trou noir côté serveur (10 combats servis, composition
réelle tirée des données), mais la BREAKER QUEST n'est **pas jouable en jeu** — l'écran reste vide, et la
cause est cernée sans être encore établie.

## RÉSOLU (2026-08-02, g46) — la BREAKER QUEST s'affiche, se joue et persiste EN JEU

L'énigme de « l'écran vide » avait UNE cause de plus, invisible au dump précédent (qui ne regardait que
`basicBreakerFights`). La sonde `breakerdump` a été étendue à **`activeBreakerFight`** et a tranché :

```
[breakerdump] holder.getBreakerQuest() = combats=10
[breakerdump] activeBreakerFight = null        ← AVANT le correctif
[breakerdump] activeBreakerFight = index=1 breakerLineup=5 wardLineups=4   ← APRÈS
```

**Défaut RÉEL nº1 — `BreakerQuest.activeBreakerFight` jamais renseigné.** Le client
(`InvasionBreakerScreen`, relevé au bytecode : `this.activeBreakerFight = holder.getBreakerQuest().activeBreakerFight`)
lit CE champ pour activer l'aperçu et démarrer le combat de la salle active. Le `onClicked` de la vedette
n'ouvre l'aperçu **que si `activeBreakerFight != null`**. Notre `buildQuest` ne remplissait que la LISTE
(`basicBreakerFights`) → champ `null` → **taper la vedette n'ouvrait rien**. Le combat de la salle 0 ne
« marchait » que parce que le **tutoriel** forçait le démarrage ; hors tutoriel (salle ≥ 1), la quête était
**injouable**. Correctif : `buildQuest` pose `bq.activeBreakerFight = toFightInfo(salleActive, groupes)`.

**Défaut RÉEL nº2 — points d'invasion calculés avec des arguments inventés.** `resolveBreakerFight` appelait
`getBreakerFightPoints(room, 1, 1)`. Le jeu (relevé au bytecode de `InvasionHelper.recordBreakerFightOutcome`)
appelle `getBreakerFightPoints(room, userLevelSnapshot, invasionMaxTeamLevel)`, l'expression étant
`BREAKER_FIGHT_POINT_REWARD = 1R*M` avec `M = getInvasionPointsMultiplier(userLevelSnapshot, invasionMaxTeamLevel)`
(et `userLevelSnapshot=0` retombe fidèlement sur `invasionMaxTeamLevel`). Le `(1,1)` inventé divergeait du
client dès que le niveau d'équipe fait passer le multiplicateur au-dessus de 1 → **score serveur ≠ score
attendu** (triche/incohérence multijoueur). Correctif : mêmes arguments que le jeu, plus le facteur
d'évènement `SpecialEventSnapshot.NONE.getLootResourceMultiplier(INVASION_BREAKER, INVASION_POINTS)` (= 1,
PARTIEL headless, cf. SHIMS). **`R=0` (salle 0) ⇒ 0 point : c'est le comportement DU JEU**, pas un bug.

### Vérifié EN JEU (client réel → serveur → persistance → affichage)

- **Salle 0, VICTOIRE** (déjà couvert) : `InvasionBreakerAttack room=0 outcome=WIN → −10 énergie, +1000 or,
  +1 BREAKER, +0 pts (niveau 0)`, **persisté**. À la réouverture de l'écran INVASION : énergie 71/80,
  **BREAKER 1**, TIER 1 0/100 — l'état vient bien du serveur.
- **La BREAKER QUEST s'affiche et se joue** : `nav INVASION` → GO → 10 salles rendues (niveaux 25/50/75… =
  `(R<7?5R:12+3R)*5`), aperçu **BREAKER FIGHT 1** ouvert (5 vedettes niv. 25), CHOOSE YOUR HEROES →
  `InvasionBreakerAttackStart room=1 → BreakerUserFightInfo`.
- **Salle 1, DÉFAITE** : `InvasionBreakerAttack room=1 outcome=LOSS → −10 énergie, +0 or, +0 BREAKER,
  +0 pts (niveau 25)`, **persisté** (débit d'énergie même en défaite = fidèle).
- **Voie VICTOIRE salle ≥ 1 (points > 0)** : prouvée **headless** (`BreakerQuestTest`, `BreakerWinProbe`) —
  `room=1 victory → −10 énergie, +1010 or, +1 BREAKER, +1 pt (niveau 25)`, le NOUVEAU code de points
  (`bindGameContext` + `ContentHelper.getCurrent(...).getInvasionMaxTeamLevel()` + `getBreakerFightPoints`)
  **s'exécute sans exception** et crédite `ud.points`.

### Observation à suivre — difficulté/puissance des vedettes de breaker

En jeu, l'aperçu de la salle 1 affiche une puissance ennemie de **866,3 M** face à notre équipe (~10 505),
d'où la défaite en QUICK FIGHT. Deux facteurs se cumulent : (a) le breaker est **conçu pour être dur** — on
doit d'abord défaire les **4 gardes** (qui l'affaiblissent) et jouer les **héros vedettes** (empouvoirés) ;
(b) l'**inflation de stats de l'ère de contenu** (R102/2026, même racine que « 39,96 M » d'énergie, cf.
SHIMS) gonfle la puissance affichée. La chaîne technique (aperçu → START → OUTCOME → persistance) est
**prouvée** ; gagner une salle ≥ 1 EN JEU avec ce compte de test suppose de jouer gardes+vedettes (ou
d'ancrer l'ère de contenu) — à confirmer ultérieurement. **Nouvelle commande pilote** `breakerfight` : ouvre
l'aperçu du combat actif sans viser la vedette au pixel près (reproduit le `onClicked`).

## BOSS BATTLES — rendu vérifié EN JEU + boss attaquable (2026-08-02, g47)

**Le boss est SERVEUR-autoritatif** : le jar CLIENT n'a aucune méthode pour le créer (il ne fait que le LIRE
et l'attaquer). Le backend le « fait apparaître » pour la guilde — ici décision OPÉRATEUR (cohérent avec
« ADMIN = source »). Nouvel outil **`server/smoke/AdminInvasion.java`** (pendant d'`AdminWar`) :
`--spawn-boss [--guild <id>] [--finder <userID>] [--level <n>]` appelle `ServerInvasion.spawnBoss` (niveau
`BOSS_FIGHT_INITAL_LEVEL`=450 et échéance `BOSS_FIGHT_TIME_LIMIT` tirés des données) et persiste ; `--status`
liste les boss actifs par guilde. Vérifié en CLI : boss #1 niv 450 apparu pour « Baroness Legion », persisté.

**Vérifié EN JEU** : `nav INVASION` → BOSS BATTLES (GO) → `GetInvasionBosses → InvasionBosses (1 boss actif)`
→ l'écran **rend le boss** : « Boss found today: 1/100 », **« Found By: You »** (le finder), la vedette
(crâne), le compteur de clés BREAKER (1) en tête. Le boss partagé de guilde s'affiche donc de bout en bout.

**Défaut RÉEL nº3 corrigé — `InvasionBossInfo.actionState` jamais renseigné.** Comme `activeBreakerFight`,
c'est un champ wire que le client lit : `InvasionBossCard.onCardPressed` n'ouvre l'aperçu de combat QUE si
`actionState == FIGHT` (et ne propose la réclamation que si `CLAIM`). Sans lui, taper le boss ne fait RIEN.
Nouveau `ServerInvasion.applyBossActionState(boss, user, ud)` calcule la vue PAR JOUEUR (FIGHT si actif/non
vaincu ; CLAIM si vaincu et part du joueur non réclamée ; sinon DEFAULT) ; le handler `GetInvasionBosses`
l'applique à chaque boss servi. [`InvasionBossTest` : boss neuf ⇒ FIGHT]. Régression 77/77.

### ✅ Boucle d'ATTAQUE du boss — VÉRIFIÉE EN JEU (2026-08-03, g49)

Chaîne complète exercée par le VRAI client (compte de test, boss MAMA_BOT niveau 1 spawné par
`AdminInvasion --level 1`) : `nav INVASION` → BOSS BATTLES (le boss rend en **MAMA_BOT** avec barre de vie
**274 714/274 714**, « Reset in … » — la chaîne manquante est résolue) → tap du boss (`tut=
INVASION_BOSS_LIST_FIGHTABLE_BOSS`, `actionState=FIGHT`) → **InvasionBossPreviewScreen** (MAMA BOT, Orange+2,
Lvl 1, boutons 1X=1 clé / 5X=3 clés) → CHOOSE HEROES → FIGHT → `StartInvasionBossAttack bossID=3 →
StartBossAttackResponse (lineup 1, verrou acquis)` → **combat réel joué** (5 héros vs MAMA_BOT) →
`InvasionBossAttack bossID=3 ×1 outcome=LOSS → −1 clé, 88803 dégâts (cumul 88803) [persisté]`.

**Preuve de fidélité des dégâts** : l'écran de résultat affiche **DAMAGE DONE : 88 803 (32,33 %)** et
**BOSS HP : 185 911 / 274 714** — soit **exactement** le chiffre enregistré côté serveur (274 714 − 88 803 =
185 911). Le serveur a donc lu la BONNE valeur (`base.defenders[*].units[*].damageTaken`). Après CONTINUE, la
carte du boss montre **185 911/274 714** et le compteur de clés BREAKER **1 → 0** — débit et cumul **persistés
et relus** à l'écran. La boucle d'attaque de boss est donc **complète et fidèle de bout en bout EN JEU**.

**RESTE (gated par l'état du compte, pas le code)** : tuer le boss (il faut cumuler 274 714 dégâts ≈ 4 clés ;
le compte de test n'a plus de clés) → passage `actionState` FIGHT→CLAIM → réclamation `ClaimInvasionBossRewards`
(`rollBossRewardLoot`). Le boss réel niveau 450 reste réservé à un compte fort (ou ancrage d'ère de contenu).

### (historique) Boucle d'ATTAQUE du boss — CÂBLÉE (2026-08-02, g48)

**Source FIDÈLE des dégâts — ÉTABLIE au bytecode.** Le client calcule `InvasionBossAttackScreen.getBossDamage`
= `UnitCombatStats.totalDamageTaken` de la vedette et l'applique LOCALEMENT ; le message `InvasionBossAttack`
ne porte pas de champ « damage ». MAIS : ce MÊME compteur est sérialisé dans
`AttackUnitSummary.damageTaken` — dans `Scene`, sur le MÊME `entityDamageEvent`, on incrémente à la fois
`summary.damageTaken += ev.getDamage()` ET `stats.totalDamageTaken += ev.getDamage()` (mesuré). Donc
**`InvasionBossAttack.base.defenders[*].units[*].damageTaken` de la vedette = exactement le chiffre du
client** (`ClientNetworkStateConverter.applyAttackBase` remplit `base.defenders` avec
`getDefenderLineupSummaries`). `defenderHeroes`, lui, n'est que le `lineup` STATIQUE (aucune info de dégâts) —
piège écarté. Combat **client-autoritatif** comme campagne/arène/breaker : on LIT ce chiffre, on ne re-simule
pas → `ServerInvasion.extractBossDamage(ba, bossType)`.

**Handlers (LoginServer)** :
1. **`StartInvasionBossAttack`** → répond `StartBossAttackResponse{bossID, damageMultiplier, bossLineup=
   boss.lineup, selectedBoosts}` (exigé par le ctor `InvasionBossAttackScreen`) et acquiert le VERROU exclusif
   (`ServerGuild.lockBoss`, `ATTACK_LOCK_DURATION` — un attaquant à la fois).
2. **`InvasionBossAttack`** (issue) → `extractBossDamage(base.defenders)` puis `ServerInvasion.attackBoss`
   (débit clés `BOSS_FIGHT_{1X,5X}_KEY_COST` sur `BREAKER`, cumul des dégâts par joueur dans `damageDone`,
   persistance de l'état partagé, libération du verrou). `attackBoss` était déjà prêt et testé.

**LINEUP du boss corrigé** : `spawnBoss` ne posait pas `InvasionBossInfo.lineup`, or `getBossUnitData` le lit
(cherche le HeroData marqué `INVASION_BOSS`/BOSS=true). Sans lui, carte dégradée + combat impossible. Nouveau
`ServerInvasionBreaker.bossLineup` construit la vedette **MAMA_BOT** (unité boss par défaut du jeu,
`getBossUnitType(DEFAULT)` — le robot de l'écran) au niveau du boss, rareté = `getEnemyRarity` du niveau
d'équipe du découvreur (comme `InvasionBossCard.getScaledBossRarity`). [`InvasionBossTest` asserte lineup +
`extractBossDamage`=damageTaken].

### Boss niveau 450 — comment TESTER la boucle EN JEU

Le boss réel est niveau `BOSS_FIGHT_INITAL_LEVEL`=450 (donnée du jeu) : **invaincable** par le compte de test
TL100 (même inflation d'ère de contenu que le breaker). Pour exercer la boucle complète en jeu (aperçu →
combat → dégâts → cumul → mort → réclamation) sans compte « endgame », l'opérateur **spawn un boss de faible
niveau** — LEVIER d'opérateur légitime (comme `-Ddh.clock.offset.hours`), pas un contournement de règle :

```
AdminInvasion --shard 1 --spawn-boss --guild <id> --finder <userID> --level 1
```

Un boss niveau 1 (MAMA_BOT rareté basse) est battable par l'équipe de test → on voit le débit de clés, le
cumul de dégâts (`base.defenders.damageTaken`), la mort du boss et le passage `actionState` FIGHT → CLAIM.
La `réclamation` des récompenses de boss (`ClaimInvasionBossRewards` + `rollBossRewardLoot`) reste le dernier
maillon (récompense dépendante du palier de contenu — à rejouer avec le boss faible).
