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
     - **⚠️ ANOMALIE OUVERTE** : dans le test complet (après DB + autres appels invasion), les trois rooms
       rendent la même taille (25) au lieu de 6/9/14. Un état partagé ou un cache du moteur de drop-tables est
       probablement en cause (`InvasionStats.clearProgressRewardCache()` existe → il y a bien du cache).
       **Tant que ce n'est pas élucidé, le test n'affirme que le stable** (tirage non vide + déterministe) ;
       les assertions de niveau/ward/progression ont été retirées plutôt que rendues « chanceuses ».
       *Prochaine action* : isoler la cause (ordre d'appel ? cache ? `getProgressRewards` ?) avant de câbler
       `InvasionBreakerAttackStart`/`InvasionBreakerAttack`.
   - ⏳ **Reste ensuite** : câbler les messages d'attaque. Rappel :
     `invasion_breaker_fight_comp.tab` est une **table de drop** (`ROOT → <BREAKER>, <WARD_1..4>` avec
     `RoomTest(16)`/`RoomTest(41)` qui font varier les wards selon la room) et le jeu fournit le contexte
     `UserInvasionDTContext` + `InvasionHelper.makeBreakerDefender` → réutiliser la machinerie de drop-tables
     déjà employée pour le loot. Puis câbler `InvasionBreakerAttackStart` / `InvasionBreakerAttack`
     (mêmes formes que `CampaignAttack`, déjà géré).
3. **Boss** : `GetInvasionBosses` → `InvasionBosses`, `StartInvasionBossAttack`, `InvasionBossAttack`,
   HP partagés de guilde, verrou d'attaque (`ATTACK_LOCK_DURATION=5m`), `ClaimInvasionBossRewards`
   (via `rollBossRewardLoot` + tables `invasion_boss_rewards*`).
4. **Ligues et rangs** : `GetUserInvasionLeagueInfo` / `GetGuildInvasionLeagueInfo`, classements, et
   `INVASION_CLAIM_GUILD_RANK_REWARD` (via `claimGuildRankRewards` / `claimUserRankRewards` + tables de ligue).
5. **État partagé de guilde** : dégâts au boss par membre, plafonds quotidiens de guilde
   (`GUILD_DAILY_BOSS_LIMIT=100`, `BOSS_GUILD_DAILY_LIMIT=2400`) → table `shard_state` / `ServerGuild`.
