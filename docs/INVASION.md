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
4. **Ligues et rangs** : `GetUserInvasionLeagueInfo` / `GetGuildInvasionLeagueInfo`, classements, et
   `INVASION_CLAIM_GUILD_RANK_REWARD` (via `claimGuildRankRewards` / `claimUserRankRewards` + tables de ligue).
5. **État partagé de guilde** : dégâts au boss par membre, plafonds quotidiens de guilde
   (`GUILD_DAILY_BOSS_LIMIT=100`, `BOSS_GUILD_DAILY_LIMIT=2400`) → table `shard_state` / `ServerGuild`.
