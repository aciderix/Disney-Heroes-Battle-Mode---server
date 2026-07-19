# SIGNIN & SPECIAL EVENTS — feature serveur/admin (recon 2026-07-19)

Manque serveur trouvé **en progressant le tuto post-équip** (méthode « observer le protocole ») : le client
envoie `Action{REFRESH_SPECIAL_EVENTS}` **en boucle** (relevé 524×) et le bâtiment **SIGN IN** (récompense de
connexion quotidienne) reste vide. C'est une **REQUÊTE** attendant une réponse `SpecialEventsRaw` (évènements +
récompenses de sign-in), **pas** un fire-and-forget. Tout est **du code + des données du jeu** (zéro invention),
et c'est une **feature de configuration serveur** (un admin définit les évènements/récompenses). Voir
[`SERVER_PLAN.md`](SERVER_PLAN.md) §F, [`PROTOCOL.md`](PROTOCOL.md).

## 1. Le protocole (relevé au bytecode)

- Client → serveur : `Action{command=REFRESH_SPECIAL_EVENTS}` (périodique + à l'ouverture d'écrans dépendants).
- Serveur → client attendu : **`SpecialEventsRaw`** = `{ boolean changed, List<SpecialEventRaw> events,
  SigninRewards signinRewards }`.
- À la réception, le client applique :
  - `SpecialEventsHelper.setSpecialEvents(raw, user, shardID)` (les évènements) ;
  - `SigninHelper.setData(signinRewards)` (le sign-in ; aussi via un message `SigninRewards` direct).
- **Fait (2026-07-19)** : `LoginServer` répond désormais un `SpecialEventsRaw{changed=false, events=[]}` (aucun
  évènement hébergé) → **stoppe le "non appliquée" en boucle**. **MANQUE RESTANT** : `signinRewards` **vide** →
  l'écran SIGN IN n'a rien à afficher/réclamer. Il faut **construire un vrai `SigninRewards`**.

## 2. Modèle de données du sign-in (100 % jeu)

- **Récompenses définies par la donnée** : `game-data/stats/signin_rewards.tab` = une **DropTable**
  (`SigninStats.REWARDS_TABLE : DropTableStats`). Nœuds : `ROOT → V<SignInVersion>_DAY_<index>`. Ex. (V1) :
  `DAY_0=GOLD (fn niveau)`, `DAY_1=50 DIAMONDS`, `DAY_2=EXP_COLOSSAL`, `DAY_3=GEAR_JUICE (fn)`,
  `DAY_4=DOUBLE_CAMPAIGN_TEAM_XP`, `DAY_5=COSMETIC_CHEST_1X`, … (~30 jours). Quantités **scalées par le niveau**
  (`fn (L*…)`), donc **par joueur**.
- **Messages** :
  - `SigninReward = { long startTime, long endTime, List<RewardDrop> rewards, UnitType signinHero }`
    (les `rewards` = la **liste des récompenses journalières** du mois).
  - `SigninRewards = { SigninReward thisMonth, nextMonth, lastMonth; Map signinHeroesRev }`.
- **Logique cliente** (`SigninHelper`, tout lit `DATA` = le `SigninRewards` reçu) :
  - `setData(SigninRewards)` → stocke dans `DATA` (AtomicReference).
  - `getRewards(user)` → liste effective (basée `thisMonth.rewards` + `getCreationTime`/Calendar).
  - `getActiveRewardIndex(user)`, `isClaimable(user, i)`, `isSignedIn(user)`, `getCurrentServerEndTime()`
    (= `thisMonth.endTime`), `hasSigninHero(user)`.
  - **Réclamer** : `claim(user, index, retro) → RewardDrop` (donne la récompense du jour `index`).
- **Héros mensuel de sign-in** : `ContentStats.ContentColumn.getCurrentMonthlySigninHero()` /
  `getNextMonthlySigninHero()` (piloté par `content.1.tab`, daté).

## 3. Ce que le serveur doit faire (plan — pure logique du jeu)

1. **Construire `SigninRewards`** pour le user (comme l'a fait le vrai serveur PerBlue) :
   - `thisMonth.startTime/endTime` = bornes du **mois courant** (Calendar sur `TimeUtil.serverTimeNow`/
     `getUserServerTime`) ; idem `lastMonth`/`nextMonth`.
   - `thisMonth.rewards` = pour chaque jour `i` du mois, **rouler** `SigninStats.REWARDS_TABLE` au nœud
     `V<version>_DAY_<i>` sur un `ChestContext`/`DropContext(user)` → `RewardDrop`. (Déterministe : la table
     est riggée par index, pas aléatoire.) → `List<RewardDrop>`.
   - `thisMonth.signinHero` = `ContentHelper.getCurrent(user).getCurrentMonthlySigninHero()`.
   - `signinHeroesRev` = map héros→révision (depuis le contenu).
2. **Envoyer** ce `SigninRewards` : soit dans `SpecialEventsRaw.signinRewards` (réponse à
   `REFRESH_SPECIAL_EVENTS`), soit comme message `SigninRewards` direct au login (à confirmer côté client :
   `lambda$setupPostClientInfoHandlers$31` gère un `SigninRewards` seul).
3. **Handler de RÉCLAMATION** : quand le client réclame (message/Action à identifier — probablement un
   `Action{CLAIM_SIGNIN}` ou un message dédié), exécuter `SigninHelper.claim(user, index, false)` (logique du
   jeu : donne le `RewardDrop`, incrémente `getMonthlySignins`/`getDailyChances("daily_signin")`), persister,
   répondre le `RewardDrop` (comme `openChest` répond `LootResults`).
4. **État joueur** : le compteur de sign-in vit dans l'état user (`getMonthlySignins`,
   `getDailyChances("daily_signin")`) → **auto-persisté** dans `this.extra` (comme la stamina).

## 4. Angle « feature admin serveur » (config)

C'est le point clé : **définir les récompenses = éditer `signin_rewards.tab`** (déjà extrait de l'APK = valeurs
authentiques PerBlue). Un opérateur de serveur (auto-hébergement, §5 PRINCIPLES) peut :
- **garder l'authentique** (la `.tab` du jeu) — comportement fidèle par défaut ;
- **personnaliser** ses récompenses (éditer la `.tab` / une surcharge) sans toucher au code — même canal que
  le reste de l'équilibrage (les `.tab` sont la couche de données).

Idem pour les **ÉVÈNEMENTS** (`SpecialEventRaw` : deals, contests, bannières) : c'est une **couche de config
serveur** (quels évènements sont actifs, quand). Aujourd'hui : aucun évènement hébergé (`events=[]`) = valeur
correcte pour un serveur sans évènements (§F). Une vraie **console admin** (définir/planifier évènements +
récompenses) serait la généralisation — hors périmètre immédiat, mais l'architecture (données `.tab` + logique
du jeu) la rend naturelle.

## 5. Autre manque vu sur la même capture

- **« CHOOSE NAME »** (bas-gauche du hub) : étape d'onboarding — le joueur doit **choisir son nom**. Interaction
  serveur à câbler (probablement un `Action`/message `SetName` → stocker dans `userInfo` → persister). À traiter
  après le sign-in (même méthode : observer le message émis, exécuter la logique du jeu, persister).

## 6. Statut

- ✅ `REFRESH_SPECIAL_EVENTS` : le serveur RÉPOND (`SpecialEventsRaw`, 0 évènement) → plus de spam « non gérée ».
- ⬜ `SigninRewards` : à **construire + envoyer** (§3) pour rendre le bâtiment SIGN IN fonctionnel.
- ⬜ Handler de **réclamation** de sign-in.
- ⬜ **CHOOSE NAME**.
