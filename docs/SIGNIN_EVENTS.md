# SIGN-IN & ÉVÈNEMENTS SPÉCIAUX — feature serveur (recon + implémentation 2026-07-19)

Manque serveur trouvé **en progressant le tuto post-équip** (méthode « observer le protocole ») : le client
envoie `Action{REFRESH_SPECIAL_EVENTS}` **en boucle** (relevé 524×) et le bâtiment **SIGN IN** (récompense de
connexion quotidienne) restait vide. C'est une **REQUÊTE** attendant une réponse `SpecialEventsRaw` (évènements +
récompenses de sign-in), **pas** un fire-and-forget. Tout est **du code + des données du jeu** (zéro invention).
Voir [`SERVER_PLAN.md`](SERVER_PLAN.md) §F, [`PROTOCOL.md`](PROTOCOL.md).

> **Note de cadrage (corrigée) : le sign-in N'EST PAS une « feature admin ».** Les récompenses sont **définies
> par la donnée** (`game-data/stats/signin_rewards.tab`, extraite de l'APK = valeurs authentiques PerBlue) et la
> logique de réclamation est **du code du jeu** (`SigninHelper.claim`). C'est donc une **feature de jeu standard**
> que le serveur **exécute** (construire `SigninRewards` en roulant la `.tab`, puis appeler `claim`). Le seul
> « angle admin » est trivial et **commun à tout le jeu** : un opérateur auto-hébergé peut éditer n'importe quel
> `.tab` (couche donnée) — rien de spécifique au sign-in. Pas de console/feature d'administration à concevoir.

## 1. Le protocole (relevé au bytecode)

- Client → serveur : `Action{command=REFRESH_SPECIAL_EVENTS}` (périodique + à l'ouverture d'écrans dépendants ;
  `ClientActionHelper.refreshSpecialEvents`, appelé notamment par `SignInScreen`).
- Serveur → client : **`SpecialEventsRaw`** = `{ boolean changed, List<SpecialEventRaw> events,
  SigninRewards signinRewards }` (champ `signinRewards` confirmé au bytecode).
- À la réception, le client applique :
  - `SpecialEventsHelper.setSpecialEvents(raw, user, shardID)` (les évènements) ;
  - `SigninHelper.setData(raw.signinRewards)` (le sign-in).
- **Réclamation** : `ClientActionHelper.claimSignInReward(index)` → `Action{CLAIM_SIGNIN_REWARD, extra={INDEX=i}}`
  (et `CLAIM_SIGNIN_WITH_VIDEO` pour le x2). Le client applique `claim` de son côté (optimiste) et envoie
  l'Action ; le serveur AUTORITATIF ré-exécute la même logique.

## 2. Modèle de données du sign-in (100 % jeu)

- **Récompenses définies par la donnée** : `game-data/stats/signin_rewards.tab` = une **DropTable**
  (`SigninStats.REWARDS_TABLE : DropTableStats`). Nœuds : `ROOT → V<SignInVersion>_DAY_<index>`. Variables de la
  table (`SigninStats.SigninDTCode`) : `SignInVersion` = `ContentColumn(signinStart).getSigninVersion()`,
  `SignInIndex` = `SigninContext.index` (le jour), **`L` = `ContentColumn(signinStart).getMaxTeamLevel()`**
  (⇒ quantités **indexées sur l'ère de contenu**, pas sur le joueur — cohérent avec le reste du daté, cf. la
  stamina). Ex. (V1) : `DAY_0=GOLD fn (L*400000)+250000`, `DAY_1=50 DIAMONDS`, `DAY_2=1563 EXP_COLOSSAL`,
  `DAY_3=GEAR_JUICE fn max((L*67)-2550,1)`, … jusqu'à `DAY_30` (31 nœuds).
- **Messages** :
  - `SigninReward = { long startTime, long endTime, List<RewardDrop> rewards, UnitType signinHero }`
    (`rewards` = la **liste des récompenses journalières** du mois, une `RewardDrop` par jour).
  - `SigninRewards = { SigninReward thisMonth, nextMonth, lastMonth; Map signinHeroesRev }`.
- **Logique cliente** (`SigninHelper`, tout lit `DATA` = le `SigninRewards` reçu via `setData`) :
  - `getCurrentSigninReward(user)` : sélectionne `thisMonth`/`lastMonth`/`nextMonth` en comparant
    `TimeUtil.getUserServerTime(user)` aux bornes `startTime`/`endTime`.
  - `getRewards(user)` → sous-liste effective (jours écoulés) de `thisMonth.rewards`.
  - `getReward(user, i)` = `getRewards(user).get(i)` ; `getActiveRewardIndex`, `isClaimable(user, i)`.
  - **Réclamer** : `claim(user, index, retro) → RewardDrop` : `isClaimable` → `getReward(i)` →
    `RewardHelper.giveReward` (donne l'objet) + `incMonthlySignins`/`decDailyChances("daily_signin")`/
    `setLastSigninTime`/`setTime(LAST_MONTHLY_SERVER_SIGNIN)` (état joueur, **auto-persisté dans `this.extra`**).
- **Héros mensuel** : `ContentColumn.getCurrentMonthlySigninHero()` (piloté par `content.<shard>.tab`, daté).

## 3. Implémentation serveur ✅ (2026-07-19)

- **Construction** `ServerUser.buildSigninRewards()` → `signinRewardsFor(user)` :
  - `thisMonth`/`lastMonth`/`nextMonth` = `buildSigninMonth(user, start, end)` avec les **bornes de mois**
    calculées par `Calendar` sur `TimeUtil.getUserServerTime(user)` (premier/dernier instant du mois).
  - `thisMonth.rewards` = pour chaque jour `i`, **rouler** `SigninStats.REWARDS_TABLE.getTable().rollNode("ROOT",
    new SigninContext(i, start), rng)` → `DropConverter(user).convert(...)` → une `RewardDrop`. Boucle jusqu'à ce
    qu'un jour ne produise plus rien (nœud absent → fin des jours de la version). **Déterministe** (table riggée
    par index). `SigninContext` est instancié **par réflexion** (classe imbriquée `protected` hors package).
  - `signinHero` = `ContentHelper.getRawStats().getColumn(start).getCurrentMonthlySigninHero()`.
- **Envoi** : `LoginServer` (handler `REFRESH_SPECIAL_EVENTS`) pose `raw.signinRewards = user.buildSigninRewards()`.
- **Réclamation** : `ServerUser.applyCommand` cases `CLAIM_SIGNIN_REWARD`/`CLAIM_SIGNIN_WITH_VIDEO` →
  `SigninHelper.setData(signinRewardsFor(user))` (claim lit `DATA`) → `getActiveRewardIndex` (défaut) ou
  `extra[INDEX]` → `isClaimable` → `SigninHelper.claim(user, index, withVideo)`. L'état (compteur de sign-in,
  objet donné) persiste via `this.extra` (comme la stamina).
- **Vérifié** : `server/smoke/SigninTest` — 31 jours roulés, valeurs = la `.tab` scalée à l'ère R102 (L=565 :
  jour 0 GOLD 226 250 000, jour 1 50 DIAMONDS, jour 3 GEAR_JUICE 35305…), bornes cohérentes (last<this<next),
  réclamation du jour actif crédite la récompense. Régressions `ResourceTest`/`EquipTest`/`ViewedChestsTest` OK.

## 4. Évènements spéciaux (`events`)

`SpecialEventRaw` (deals, contests, bannières) = une **couche de config serveur** (quels évènements sont actifs,
quand). Aujourd'hui : aucun évènement hébergé (`events=[]`, `changed=false`) = valeur **correcte** pour un serveur
sans évènement (cf. SERVER_PLAN §F). Un système d'hébergement d'évènements (planification + snapshots temporels)
serait une feature à part entière, hors périmètre immédiat.

## 5. « CHOOSE NAME » ✅ (2026-07-19)

Étape d'onboarding : le joueur choisit son nom. Relevé au bytecode (`ChangeNamePrompt.changeNameInner`) : le
client applique `UserHelper.changeName(user, name)` en local **puis** envoie **`SetPlayerName{name}`**
fire-and-forget. **Serveur** (`LoginServer` branche `SetPlayerName` → `ServerUser.setPlayerName`) : ré-exécute
`UserHelper.changeName` (légalité + coût — 1ᵉʳ gratuit via `FREE_NAME_CHANGE`), **re-sync** le nom vers le wire
(`userInfo.basicInfo.name`/`previousName`, car `User.userName` vit hors `this.extra`), persiste. **Ext serveur**
`isNameLegalExt = s -> true` posé dans `ServerContext.init` (le cœur de légalité s'exécute ; la vérif de POLICE
cliente — `Gdx.app.getPreferences` — n'a pas d'objet serveur et est déjà faite par le client ; cf. SHIMS).
Vérifié `server/smoke/SetNameTest` (nom appliqué + survit au round-trip wire).

## 6. Statut

- ✅ `REFRESH_SPECIAL_EVENTS` : le serveur RÉPOND (`SpecialEventsRaw`, 0 évènement).
- ✅ `SigninRewards` **construit + envoyé** (§3) → bâtiment SIGN IN alimenté.
- ✅ Handler de **réclamation** (`CLAIM_SIGNIN_REWARD` / `_WITH_VIDEO`) → `SigninHelper.claim`.
- ✅ **Progression MULTI-JOUR (j2/j3) vérifiée** (`server/smoke/SigninMultiDayTest`) : jour actif piloté par
  `getMonthlySignins()` + chance quotidienne `getDailyChances("daily_signin")` (reset LAZY par le jeu,
  `DailyActivityHelper.checkAndUpdateDailyValues`, à chaque lecture, sur `this.extra.dailyChances` persisté).
  j1 → GOLD crédité, j2 (après reset quotidien) → DIAMONDS crédité, `monthlySignins` 1→2, tout persiste.
- ✅ **TOUS les types de récompense** (pas que GOLD/DIAMONDS) vérifiés (`server/smoke/SigninAllRewardsTest`) :
  les 31 jours du mois = **15 types distincts** (RES: GOLD/DIAMONDS/GEAR_JUICE ; ITEM: EXP_COLOSSAL,
  DOUBLE_CAMPAIGN_TEAM_XP/GOLD/HERO_XP, DOUBLE_ELITE/NORMAL_CAMPAIGN_DROPS, COSMETIC_CHEST_1X,
  GURANTEE_COSMETIC_CHEST_1X, GOLD_CHEST_ROLL_X1/X10, RED_SKILL_CHEST_1X, BADGE_CHEST_1X) se donnent **sans
  exception** via le chemin du jeu `RewardHelper.giveReward` (celui qu'emprunte `claim`) et sont crédités :
  GOLD/GEAR_JUICE + 12 types d'objets dans `individualUserExtra.items` (this.extra, auto-persisté), DIAMONDS
  via `resyncDiamonds`. NB : la réclamation jour-à-jour reste gatée par le CALENDRIER du jeu (`getRewards`
  débloque 1 jour/jour réel) — c'est le comportement d'origine, pas un blocage serveur.
- ✅ **Gap trouvé & corrigé — crédit des DIAMONDS** : les diamants vivent dans un champ dédié
  `IndividualUser.diamonds` (initialisé depuis `userInfo.diamonds`, HORS `this.extra`) → non auto-persisté ;
  une récompense en diamants (sign-in j1/j7/j16/j23…, mais aussi tout gain de diamants) était **perdue au
  round-trip wire**. Corrigé par `ServerUser.resyncDiamonds` (`userInfo.diamonds = user.getResource(DIAMONDS)`,
  appelé après action/coffre/campagne — même schéma que team-level/nom). Cf. SHIMS.
- ✅ **CHOOSE NAME** (`SetPlayerName` → `UserHelper.changeName`, §5).
- ✅ **Pilote DEV `dh.gosignin`** : ouvre le bâtiment SIGN IN au hub via `UINavHelper.navigateTo(SIGN_IN)` —
  **respecte le verrou de navigation du tuto** (`canNavigateTo=false` mid-tuto = comportement fidèle, non
  contourné). ⬜ **Vérif live du SIGN IN affiché/réclamé** : nécessite d'atteindre le point sign-in du tuto (la
  navigation libre est verrouillée pendant le tuto — c'est le jeu, pas un manque serveur).
