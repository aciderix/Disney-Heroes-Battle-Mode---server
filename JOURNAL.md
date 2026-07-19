# JOURNAL — journal détaillé des modifications

## 2026-07-19 (g2) — Coffres PAYANTS : débit DÉMONTRÉ end-to-end (bug 4ᵉ param) + correction « TL1 »

### Demande & découverte
User : « vérifier les coffres payants avec des team levels plus hauts pour vérifier tes dires ». Mon test
précédent montrait qu'à TL40, une fois le coffre GOLD gratuit consommé, `openChest` REFUSAIT le payant avec un
`ERROR` générique — le débit n'était donc PAS démontré. Cause trouvée en décompilant (CFR) la chaîne cliente
`SilverChestDetailScreen` → `ChestHelper.openChest` → `openChestInner` :
```
if (validateChestPurchase(user, type, count, n2 /* = COÛT */, item, snapshot)) break;
...
buyChests.cost = n2;   // le coût est ré-émis dans le message
```
⇒ **le 4ᵉ paramètre de `validateChestPurchase` == `BuyChests.cost`** (0 pour un gratuit, le coût réel pour un
payant). La branche PAYANTE de `validateChestPurchase` termine par `if (coûtRecalculéServeur > coûtDéclaréClient)
throw ERROR` = un **contrôle ANTI-TAMPER** (le client ne peut pas déclarer un coût inférieur au vrai). Le serveur
passait **`0` en dur** → pour un payant `288 > 0` → `ERROR` systématique → le débit était INATTEIGNABLE.

### Fix (RÉEL, miroir du client)
`ServerUser.openChest` : `validateChestPurchase(user, type, count, m.cost, usedItem, NONE)` (au lieu de `0`).
Gratuit → `m.cost=0` → branche gratuite → OK ; payant → `m.cost=coût` → `coût==coût` faux → OK ; coût
sous-déclaré → `coûtRecalculé > m.cost` → ERROR (anti-triche renforcée).

### Vérifié — débit end-to-end (server/smoke/ChestPaidDebitTest, TL40)
1) coffre GOLD gratuit consommé (`wasFree=true`) ; 2) ouverture GOLD **PAYANTE** avec coût correct déclaré
(`m.cost=288`) → **-288 DIAMONDS** (100000→99712), **persiste au round-trip wire** (99712) ; 3) coût
**sous-déclaré (287)** → **REFUSÉ** (anti-tamper), aucun débit. Régression 8/8 verte.

### Correction d'une affirmation antérieure FAUSSE
« À team level 1 (tuto) le jeu REFUSE tout achat payant » était **inexact**. `validateChestPurchase` n'a **aucun
gate de team level** pour SILVER/GOLD (seuls VIDEO=`TEAM_LEVEL_LOCK`, SOUL=`VIP`, EVENT/WISH ont des gates). Le
verrou du tuto `REBLOCK_SILVER_BUY_ONE` est **côté CLIENT** (`SilverChestDetailScreen`), pas dans la validation
serveur. Le vrai blocage du débit était le bug du 4ᵉ param, pas le niveau d'équipe. `ChestChargeTest` recadré
(le refus qu'il teste = coût sous-déclaré à 0, pas un « verrou TL1 »).


## 2026-07-19 (c) — SIGN-IN multi-jour (j2/j3) vérifié + GAP diamants corrigé (resyncDiamonds)

### Question (user) & vérification
« Les deux sont-ils testés in-game ? À j2/j3 le joueur aura-t-il les récompenses correspondantes, bien
créditées et disponibles ? L'ext headless (isNameLegalExt) impacte-t-il les vrais joueurs desktop/android ? »

**Mécanique jour-par-jour (relevée au bytecode)** : `SigninHelper.getActiveRewardIndex` = fonction de
`getMonthlySignins()` (nb de réclamations) et `getDailyChances("daily_signin")` ; `isClaimable` exige une
chance quotidienne > 0 (une claim/jour) dans la fenêtre du mois. La chance quotidienne se **réinitialise
LAZY** : `IndividualUser.getDailyChances` appelle `DailyActivityHelper.checkAndUpdateDailyValues` qui compare
`serverTimeNow()` à `LAST_USER_DAILY_RESET` (`isSameUserDay`) et, à un nouveau jour, `resetDailyChances(key,
max)` — le tout sur `individualUserExtra.dailyChances` (this.extra, persisté). Donc j2/j3 = logique du jeu
automatique, pas de code serveur spécial.

### Vérification multi-jour (server/smoke/SigninMultiDayTest)
j1 : réclame le jour actif → GOLD (226 250 000) crédité. Simulation j2 : recul de `LAST_USER_DAILY_RESET` de
2 jours → le reset lazy remet `daily_signin` à 1. j2 : jour actif AVANCE (0→1), réclamable, claim → DIAMONDS
(50). `monthlySignins` 1→2. Tout persiste au round-trip wire.

### VRAI GAP trouvé : crédit des DIAMANTS perdu au reload
Au 1er jet, j2 donnait DIAMONDS 0→0 (récompense NON créditée). Cause (diagnostiquée) : les diamants vivent
dans un **champ dédié `IndividualUser.diamonds`** (init depuis `userInfo.diamonds` au `getIndividualUser`,
lu/écrit par `get/setResource(DIAMONDS)`) — **HORS `this.extra`**. `getResource(DIAMONDS)` lit bien ce champ
(vérifié : param 777 → 777), mais `applyAction` ne re-syncait que les héros → le gain de diamants restait en
mémoire et était **perdu au round-trip wire** (comme l'étaient team-level et le nom avant leurs resyncs).
**Correctif** : `ServerUser.resyncDiamonds(user)` = `userInfo.diamonds = user.getResource(DIAMONDS)`, appelé
après `applyAction`/`openChest`/`recordCampaignAttack`. Impact plus large que le sign-in : **tout** gain de
diamants (loot, quêtes…) est désormais persisté. Après correctif : DIAMONDS 0→50, persiste. Régression 7/7
(`Resource/Signin/SetName/SigninMultiDay/CampaignAttack/ChestWire/CampaignPersist`) verte.

### Réponses aux 3 questions
1. **In-game via le client complet** : NON encore — le tuto verrouille la navigation libre (`canNavigateTo=
   false`) jusqu'à l'étape sign-in ; côté serveur tout est prouvé par tests. À confirmer en progressant le tuto.
2. **j2/j3 crédités & disponibles** : OUI (mécanique du jeu + fix diamants), vérifié par test multi-jour.
3. **Impact de `isNameLegalExt = s->true` sur les vrais joueurs** : AUCUN. Il est posé dans
   `ServerContext.init` = **process JVM du SERVEUR uniquement** (dhserver, non chargé par le client). Le client
   desktop/android garde SA propre vérif de police (`Gdx.app` présent côté client). Le serveur ne reçoit que
   des noms déjà validés par le client (changeName local avant l'envoi) ; il en refait le CŒUR de légalité
   (noms interdits, codepoints), seule la vérif POLICE (sans objet serveur) est omise.

## 2026-07-19 (b) — CHOOSE NAME (SetPlayerName) + pilote dh.gosignin (ouvrir SIGN IN)

### CHOOSE NAME — message + handler
Relevé au bytecode (`ChangeNamePrompt.changeNameInner`) : le choix/changement de nom passe par
`UserHelper.changeName(user, newName)` (logique du jeu : légalité `NameChangeHelper.isNameLegal`, coût — 1ᵉʳ
changement gratuit via `FREE_NAME_CHANGE`, sinon item/diamants —, `setPreviousName`+`setName`) PUIS l'envoi
**fire-and-forget** d'un `SetPlayerName{name}` (`getNetworkProvider().sendMessage`). Serveur : `LoginServer`
branche `SetPlayerName` → `ServerUser.setPlayerName` ré-exécute `UserHelper.changeName`, **re-sync** le nom vers
le wire (`userInfo.basicInfo.name`/`previousName`, car `User.userName` est un champ hors `this.extra`, comme le
team-level) et persiste.

### Blocage headless (Gdx.app) résolu par un ext serveur
`UserHelper.changeName` → `NameChangeHelper.isNameLegal` fait le CŒUR (noms interdits, codepoints valides) PUIS
délègue à un champ statique `isNameLegalExt` (Predicate CLIENTE) = `isNameLegalClientExt` qui vérifie le rendu
POLICE (`DisplayStringUtil.containsUnsupportedCharacters` → `LanguageHelper.getPreferredLanguage` →
`Gdx.app.getPreferences`) → NPE headless (`Gdx.app` null). Le rendu police est une préoccupation CLIENTE, déjà
validée par le client avant l'envoi. `ServerContext.init` pose donc par réflexion un **ext SERVEUR** `s -> true`
(même patron que `ServerSpecialEventsExt` : le cœur de légalité s'exécute, seule la vérif police — sans objet
serveur — est omise ; pas une rustine, §2). Vérifié `server/smoke/SetNameTest` : nom « HeroTester » appliqué,
`basicInfo.name` peuplé, survit au round-trip wire. Régressions `SigninTest`/`ResourceTest` vertes.

### Pilote DEV dh.gosignin (vérif live du SIGN IN)
`TutorialDriver` : flag `GO_SIGNIN` → au hub, ouvre le bâtiment SIGN IN via l'API du jeu
`UINavHelper.navigateTo(Destination.SIGN_IN)` pour déclencher `REFRESH_SPECIAL_EVENTS` (→ le serveur répond
`SpecialEventsRaw{signinRewards}`). **Constat live** : `canNavigateTo(SIGN_IN)=false` pendant le tuto
(`mainScreenTutorialBlocked` → « TUTORIAL_CANT_DO_THAT_YET ») — la navigation libre est **verrouillée par le
jeu** tant que le tuto n'est pas au point sign-in. Le hook **RESPECTE ce verrou** (n'ouvre que si
`canNavigateTo=true` ; forcer serait une rustine, §2). La construction serveur `SigninRewards` est déjà prouvée
(`SigninTest`) ; la vérif live du SIGN IN affiché s'obtiendra en progressant le tuto jusqu'au sign-in.
Câblage `DH_GOSIGNIN` → `dh.gosignin` dans `run-online.sh`/`run-desktop.sh`.

## 2026-07-19 — SIGN-IN (récompense de connexion quotidienne) : construction serveur + réclamation

### Contexte & correction de cadrage
En progressant le tuto post-équip, le client spammait `Action{REFRESH_SPECIAL_EVENTS}` (relevé 524×) et le
bâtiment **SIGN IN** restait vide. J'avais initialement cadré ça comme une « feature admin » (définir les
récompenses). **Correction (user) : c'en est pas une.** Les récompenses sont **définies par la donnée**
(`game-data/stats/signin_rewards.tab`, extraite de l'APK = authentique PerBlue) et la réclamation est **du code du
jeu** (`SigninHelper.claim`). Le serveur ne fait qu'**exécuter** (PRINCIPLES §3/§4). Le seul angle « admin » =
éditer un `.tab`, commun à TOUT le jeu — rien de spécifique.

### Modèle relevé au bytecode
- `SpecialEventsRaw{changed, List events, SigninRewards signinRewards}` (champ confirmé).
- `SigninRewards{thisMonth,nextMonth,lastMonth : SigninReward, Map signinHeroesRev}` ;
  `SigninReward{startTime,endTime, List<RewardDrop> rewards, UnitType signinHero}`.
- Table `SigninStats.REWARDS_TABLE : DropTableStats`, nœuds `ROOT → V<SignInVersion>_DAY_<index>`. Variables
  (`SigninDTCode`) : `SignInVersion`=`ContentColumn(signinStart).getSigninVersion()`, `SignInIndex`=`ctx.index`,
  **`L`=`ContentColumn(signinStart).getMaxTeamLevel()`** → quantités indexées sur l'ère de contenu (pas le joueur).
- `SigninHelper` (client) lit tout dans `DATA` (posé par `setData`) : `getRewards`/`getReward(i)`/
  `getActiveRewardIndex`/`isClaimable`/`claim(user,index,retro)` (donne l'objet + `incMonthlySignins`/
  `decDailyChances("daily_signin")`/`setLastSigninTime`). Réclamation client = `Action{CLAIM_SIGNIN_REWARD,
  extra={INDEX=i}}` (et `CLAIM_SIGNIN_WITH_VIDEO` pour le x2).

### Implémentation (`ServerUser`, `LoginServer`)
- `buildSigninRewards()` → `signinRewardsFor(user)` : construit thisMonth/lastMonth/nextMonth via
  `buildSigninMonth(user, start, end)`. Bornes de mois = `Calendar` (premier/dernier instant) sur
  `TimeUtil.getUserServerTime(user)`. `rewards` = pour chaque jour i, `REWARDS_TABLE.getTable().rollNode("ROOT",
  SigninContext(i, start), Random)` → `DropConverter(user).convert(...)` → 1 `RewardDrop` ; boucle jusqu'à ce
  qu'un jour ne produise plus rien (nœud absent = fin des jours de la version). `SigninContext(int,long)` =
  classe imbriquée **protected** hors package → instanciée par **réflexion** (constructeur mis en cache).
  `signinHero` = `ContentHelper.getRawStats().getColumn(start).getCurrentMonthlySigninHero()`.
- `LoginServer` handler `REFRESH_SPECIAL_EVENTS` : `raw.signinRewards = user.buildSigninRewards()`.
- `applyCommand` cases `CLAIM_SIGNIN_REWARD`/`CLAIM_SIGNIN_WITH_VIDEO` : `SigninHelper.setData(signinRewardsFor(
  user))` (claim lit `DATA`), index depuis `extra[INDEX]` (défaut `getActiveRewardIndex`), garde `isClaimable`,
  puis `SigninHelper.claim(user, index, withVideo)`. État auto-persisté dans `this.extra`.

### Vérification
`server/smoke/SigninTest` : `buildSigninRewards()` rend **31 jours** dont les valeurs matchent la `.tab` scalée à
l'ère R102 (L=565) — jour 0 GOLD 226 250 000 (`(565*400000)+250000`), jour 1 50 DIAMONDS, jour 2 1563
EXP_COLOSSAL, jour 3 GEAR_JUICE 35305 (`565*67-2550`), jour 4 DOUBLE_CAMPAIGN_TEAM_XP. Bornes last<this<next.
Réclamation du jour actif → RewardDrop GOLD crédité. Régressions `ResourceTest`/`EquipTest`/`ViewedChestsTest`
vertes. **Reste** : vérif EN JEU (le SIGN IN affiche/réclame réellement), puis CHOOSE NAME.

## 2026-07-17 (quater) — #25 LOOT AUTORITAIRE : le serveur roule le butin, certifié == client, SANS simuler le combat

### Décision (user) & principe
« Rendre autoritaire tout ce qui ne demande pas de simuler le combat. » Décomposition du combat de campagne :
**seuls `outcome`/`stars` exigent une simulation** ; le loot, l'XP, l'or, l'énergie sont **déterministes** une
fois ces valeurs connues. L'XP/or/énergie sont **déjà** autoritaires (`recordOutcome`). Restait le **loot** :
le serveur faisait confiance à `m.lootEarned` (client). #25 le rend autoritaire.

### Fait établi (relevé au bytecode)
Le loot est un **flux RNG SÉPARÉ du combat** : `CampaignAttackScreen` (2ᵉ ctor) fait `user.resetRandom(LOOT)`
puis `user.resetRandom(COMBAT)` — deux graines distinctes (`RandomSeedType.LOOT` ≠ `COMBAT`). Le combat consomme
le flux COMBAT ; le loot consomme le flux LOOT. Donc **le butin est une fonction déterministe de la SEULE graine
LOOT**, indépendante du déroulé du combat. Séquence client exacte :
```
user.resetRandom(LOOT) ;
CampaignLootHelper.getLoot(user, campaignType, 0, chapter, level, NONE, guildPerks /*GuildInfoPerkProvider*/, true)
  → CampaignLoot.combinedLoot   // List<RewardDrop> = butin d'une victoire complète
```
`getCampaignAttack` ne fait que **recopier** cette liste dans `m.lootEarned` (elle ne roule rien). Le mécanisme
RNG : `IndividualUser.getRandom(type)` = `InstrumentedRandom.newRandom(getSeed(type), logMode)` ; `getSeed` lit
`individualUserExtra.storedSeeds` (ou `SeedHelper.getDefaultSeed` par défaut). Le client annonce sa graine au
serveur via `Action{SET_SEED, TYPE=LOOT, ID=<seed>}` (capturée en #23, `getPendingSeed`).

### Implémentation (`ServerUser`)
- `rollAuthoritativeLoot(user, iu, type, m)` : `iu.setSeed(LOOT, pendingSeed)` + `user.resetRandom(LOOT)` +
  l'appel `getLoot(...)` EXACT ci-dessus → `combinedLoot`. `null` si pas de graine.
- `recordCampaignAttack` : sur **VICTOIRE**, `lootEarned = serverLoot` (crédite le tirage SERVEUR) ; sinon repli
  sur le loot client (pas de graine, ou non-WIN = loot partiel dépendant de la progression, hors pure-logique).
  `logLootValidation` LOGue serveur↔client (divergence = **signal anti-triche** ; on crédite le serveur quand même).
- `computeAuthoritativeLoot(m)` : wrapper public (test + bascule).

### Prérequis résolu — `BuildOptions.SERVER_TYPE = ServerType.NONE`
1er run : `NullPointerException … getNetworkProvider().sendMessage(...)`. Cause : `InstrumentedRandom` (via
`resetRandom`/`getRandom`) **envoie** les `RandomEvent` client→serveur pour l'anti-triche — sauf si
`SERVER_TYPE == NONE` (commutateur **offline du jeu**). Headless, `getNetworkProvider()` est null → NPE. Correctif
fidèle : poser `SERVER_TYPE = NONE` dans `ServerContext.init` (chemin offline **prévu par PerBlue**). N'affecte
**que l'envoi d'événements**, PAS les valeurs RNG (même graine → même séquence) — cf. SHIMS.

### Certification (`server/smoke/LootAuthoritativeTest`) — patron oracle
Réf CLIENT (séquence bytecode exacte) vs SERVEUR (via `Action SET_SEED` + `computeAuthoritativeLoot`), multiset
(item/ressource → quantité), sur 5 graines : **5/5 MATCH**, **5 butins distincts** (sensible à la graine → le
test discrimine). Ex. graine 999 → `{ACE_OF_SPADES×0, CLEVER_FOX×1, EXP_VIAL×1, HEARTY_BREAKFAST×1, RAID_TICKET×1,
SUGAR_RUSH×1}` serveur==client. Régression **OK** : `CampaignAttack/LootPersist/CampaignPersist/Resource/Seed/
TeamLevel/Equip/ViewedChests` (les tests sans graine LOOT retombent sur le loot client, inchangés).

### Verdict
Le serveur est **AUTORITAIRE sur le loot** (la principale surface de triche : objets/or) **pour un coût nul,
sans re-simuler le combat**. Combiné à l'XP/or/énergie déjà autoritaires : **tout ce qui a de la valeur est
autoritaire**. Ne reste client que `outcome`/`stars` (§D) — qui, eux, exigent une re-sim (unidbg, échantillonnable).
Fichiers : `server/java/dhserver/{ServerUser,ServerContext}.java`, `server/smoke/LootAuthoritativeTest.java`,
`docs/{SERVER_PLAN,SHIMS}.md`.

## 2026-07-17 (ter) — Harnais différentiel de certification (compare) : bâti, tourne, 1er rapport

### Outil
`CompareBackend` (`-Ddh.spinebackend=compare`) : pour CHAQUE appel cspine, exécute unidbg (ORACLE = binaire ARM
PerBlue = mobile) ET le JNI natif (spine-c recompilé x86, HostSpine) en parallèle, avec table de correspondance
de handles (u2j), et DIFFE automatiquement. Le jeu utilise les résultats unidbg (boote normalement → contourne le
blocage handle du JNI). Gate `active` : ne compare QUE le combat (boot = unidbg seul, rapide). Rapport auto :
diffs/méthode + distance ULP (arrondi FP ARM↔x86 vs écart de logique). Reproductible (régression).

### Bug RÉEL trouvé (par le harnais) + corrigé
`cspine_jni.c` `getBoneTransform(s)` : stride 7 + boucle `idOff..len` → le vrai JNI **borne** et throw AIOOBE
(unidbg tolérait). Corrigé : stride 6 + boucle `0..count` (contrat du jeu, = binaire PerBlue). C'est un bug de
NOTRE glue (tâche #5), latent sous unidbg. Rebuild via `native/build-hostspine.sh`.

### 1er rapport (1-1, seed 123456789) — À AFFINER
TOTAL 5343 diffs. Mais **incohérence interne** : `getBoneNames`=0 diff (listes d'os IDENTIQUES) alors que
`getBoneID`=410 diffs (u=71 j=70). Si les noms sont identiques et ordonnés pareil, getBoneID devrait matcher →
une partie des diffs = **artefacts du harnais** (état non propagé au skeleton JNI : ex. position monde de l'unité
non appliquée côté JNI → getBoneTransform diverge en masse ; ou mapping de handle imparfait sur certains appels).
`setAnimation` u=1 j=12 = différence de sémantique de RETOUR de notre glue (retourne animId) vs PerBlue (autre).
`nextEvent` u=true j=false = PerBlue FIRE des events, notre stub non. `getAnimationID/durations/names/BoneNames`
= 0 diff ✅ (structure de base identique).

### Lecture
Le harnais fonctionne et donne des données riches, mais nécessite **1 passe d'affinage** (propager tout l'état au
JNI, corriger le mapping, aligner les sémantiques de retour de la glue) avant de pouvoir conclure « 100% fidèle ou
non ». Signal préliminaire : la structure de base (noms/ids d'anim, durées, noms d'os) MATCHE ; les écarts
restants sont soit des artefacts du harnais, soit de vraies différences (events, retours) à isoler.

> Journal **détaillé** relié à l'historique court de [`MEMORY.md`](MEMORY.md#7-état-courant--prochaines-étapes).
> But : permettre à n'importe quel agent de **retrouver facilement n'importe quelle
> information** (décision, découverte, commande, fichier). Mis à jour **à chaque étape**.
> Ordre : le plus récent en haut. Chaque entrée = date + résumé + détails + fichiers touchés.

---

## 2026-07-17 (bis) — Optimisation Opt.2 : dynarmic (JIT ARM) testé → PAS un gain ; les vraies pistes sont architecturales

### Contexte / malentendu levé
Opt.2 = validation anti-triche **côté SERVEUR, en fond** (rejoue le combat pour vérifier l'issue). Ça **ne touche
PAS** la fluidité du joueur (le vrai jeu mobile utilise le spine natif de l'appareil). Les ~9 s unidbg = coût
serveur hors-ligne. « Optimiser » = débit serveur (combats/s), pas l'expérience en jeu.

### dynarmic (JIT ARM) — testé, NON concluant
Le backend `dynarmic` (JIT ARM, natif `linux_64/libdynarmic.so` embarqué dans unidbg-dynarmic-0.9.8) remplace
l'interpréteur Unicorn : **mêmes instructions ARM → mêmes résultats** (fidélité OK), censé plus rapide. Ajouté en
**opt-in** (`-Ddh.dynarmic`, off par défaut, `AndroidEmulatorBuilder.addBackendFactory(new DynarmicFactory(true))`).
**Résultat** : le combat spike n'a PAS démarré en 250 s (le boot — rendu MainScreen via spine unidbg à chaque
frame — est devenu PLUS LENT : ~200 frames jamais atteints). ⇒ pour le motif de spine (**beaucoup d'appels ARM
COURTS** : getBoneTransform/apply/update), l'**overhead JIT/warmup ne se rentabilise pas** ; l'interpréteur
Unicorn est plus efficace. **dynarmic n'est pas un gain ici.** Flag conservé (opt-in) pour ré-évaluation future,
sans effet par défaut.

### Les VRAIES optimisations sûres (fidélité intacte) = architecturales
1. **Validation ASYNC/en fond** : ne pas bloquer sur les ~9 s ; valider hors du chemin de réponse.
2. **VMs unidbg en PARALLÈLE** (une par cœur) → débit linéaire (chaque VM exécute le vrai binaire).
3. **Échantillonner / cibler** les combats suspects plutôt que tout revalider.
4. **Cache des assets chargés** (SkeletonData/atlas par unité) → amortit le setup spine entre combats.
Ces leviers ne touchent PAS la sim (mêmes résultats), contrairement à un changement de runtime (JNI natif /
spine-libgdx) qui, lui, doit être certifié.

### Fichiers
`desktop-port/src/main/java/dhbackend/unidbg/UnidbgVM.java` (flag dynarmic opt-in), `build.gradle` (dep
unidbg-dynarmic), `run-desktop.sh`/`run-online.sh` (flag). Docs : JOURNAL.

---

## 2026-07-17 — Opt.3 PIVOT « JNI natif » : le vrai spine-c compilé hôte (pas de réécriture) — bâti, bloqué au boot

### Insight (question user : réécriture main vs fichiers du jeu ?)
La délégation fidèle n'a PAS à être réécrite à la main : **la colle d'origine existe** = `native/src/cspine_jni.c`
(écrite sur le VRAI spine-c officiel 3.6). ⇒ la « bonne » Opt.3 = **compiler spine-c + cspine_jni.c pour l'HÔTE
x86-64 et l'appeler en JNI RÉEL** (« l'Opt.2 sans unidbg » : même code natif, sans émulation ARM). Fidélité PAR
CONSTRUCTION (mixing/clear de track corrects d'office), rapide, **zéro patch à la main** → supersede le
`JavaSpineBackend` (spine-libgdx divergent, patché main → dérive §4, abandonné).

### Fait
- `native/build/spine-native64.so` existait déjà (spine-c 3.6 + cspine_jni.c compilés x86-64, 65 symboles JNI).
- Classe `dhbackend.jnispine.HostSpine` (déclarations `native`, sous-ensemble combat) + `libhostspine64.so`
  (rename mécanique des symboles JNI `...cspine_Native_*` → `...HostSpine_*` + **unification des tables de
  handles** en un espace global — script `native/build-hostspine.sh`). Routage 3-voies dans `cspine.Native`
  (`-Ddh.spinebackend=jni|java|` défaut unidbg). Compile + charge en JNI réel. **Défaut = unidbg → 0 régression.**

### Bloqué (intégration boot, pas fond)
Au boot (chargement d'une particule UI `hero_chooser_add.np` → `hero_chooser.atlas@native`) :
`[main]E/: Bad handle type: Wanted ATLAS but is actually NONE for handle 1` → `Dependency not found` (fatal).
Le **registre de handles Java du jeu** rejette le schéma de handles de NOTRE `cspine_jni.c` (≠ binaire ARM de
PerBlue). L'unification des tables (handles globalement uniques) n'a PAS suffi → le registre attend autre chose
(à investiguer : comment le jeu enregistre le type d'un handle natif ; peut-être un appel natif spécifique, ou
cparticle qui doit passer sur le même backend/espace). **C'est exactement le genre d'incompat que le projet a
CONTOURNÉ en passant à unidbg** (faire tourner le VRAI binaire de PerBlue évite tout ça).

### Synthèse stratégique (convergence)
Les DEUX voies « rapides » (spine-libgdx Java ; spine-c recompilé natif) utilisent les **données** du jeu mais un
**runtime ≠** de celui livré → nécessitent des **patchs de compat** (patch anim côté Java ; schéma de handles /
banding getVertices côté natif recompilé) → dérive §4bis tant que non certifié bit-à-bit. La voie **pleinement
fidèle par construction = Opt.2 (unidbg, binaire ARM d'origine)**, au prix de la lourdeur (~9 s). Le « JNI natif »
reste la meilleure piste rapide SI on finit l'intégration boot (handles) + certification — sinon Opt.2 async est
le choix conforme.

### Fichiers
`desktop-port/src/main/java/dhbackend/jnispine/HostSpine.java` (nouveau), `.../cspine/Native.java` (routage
3-voies), `native/build-hostspine.sh` (nouveau, build reproductible), `run-desktop.sh` (flag jni). Docs : JOURNAL.

---

## 2026-07-16 (nuit 4 bis) — Opt.3 certification : divergences d'ANIMATION diagnostiquées (dont 1 vérifiée sur source spine-c)

### Résumé
Débogage de la divergence de cadence (java stagne : 3/5 kills vs oracle 5/5). Méthode : instrumentation diff
(maxAnimTime, setAnimId, histogramme d'anim courante par NOM, dump getBoneTransform) java vs oracle unidbg.
**Positions d'os : cohérentes** (valeurs caractéristiques identiques). **Bug d'animation trouvé & VÉRIFIÉ sur la
source spine-c**, mais ≥1 couche résiduelle subsiste → combat pas encore fidèle.

### Diagnostics (java vs oracle unidbg, 1-1 seed 123456789)
- **Histogramme d'anim courante par nom** : java bloqué en `attack`(14488)+`hit`(7170), **0 skill** ; oracle
  étalé. ⇒ les unités attaquent mais n'enchaînent jamais les skills (pas d'énergie → dégâts lents).
- **`getBoneTransform`** : java `[worldX,worldY,rot,sx,sy]` = mêmes valeurs caractéristiques que l'oracle →
  positions d'os OK (pas la cause).

### Bug VÉRIFIÉ sur source spine-c (`spine-c/src/spine/AnimationState.c:296-300`)
`spAnimationState_update` **CLEAR le track** (`self->tracks[i] = 0`) quand « pas de next, `trackTime>=trackEnd`,
pas de mixingFrom » → `getCurrent`→null→`getCurrentAnimID`=0. **spine-libgdx NE clear PAS** : l'entry non-bouclée
TERMINÉE persiste → `getCurrent` la renvoie → le jeu croit l'unité toujours en `attack` → n'enchaîne jamais le
skill (bloqué). **Fix partiel** : `getCurrentAnimationID` renvoie 0 si `!getLoop() && isComplete()` (mime le
clear). Effet : distribution redevenue normale (attack 14488→288, walk/idle dominants). **MAIS** : (a) fix
INCOMPLET (ne corrige que l'ID, pas `getCurrentAnimationTime`/`apply`/bones → incohérent avec un vrai clear qui
remet en pose de repos) ; (b) le combat **stagne toujours** (3/5 kills) → ≥1 divergence de plus.

### Bilan certification
Le combat tourne (9× plus vite) et l'oracle a permis de trouver/vérifier des bugs réels (stride 6, getAnimationTime
wrappé, clear de track spine-c). **La fidélité complète reste à atteindre** : c'est un chantier multi-bugs (la
prochaine couche = probablement rendre le clear de track COMPLET — vraiment clear l'entry après update comme
spine-c, pour que `apply`/bones/temps soient cohérents — puis re-diff). Le fix `isComplete` actuel est une
**approximation NON entièrement vérifiée** (§4bis) → à compléter ou à valider strictement avant tout déploiement.

### Fichiers
`.../dhbackend/spine/JavaSpineBackend.java` (fix isComplete + histo noms), `.../cspine/Native.java` (diag
histo/bt). Docs : JOURNAL. DEV-gated, backend unidbg par défaut → aucune régression.

---

## 2026-07-16 (nuit 4) — Opt.3 Phase 1 : backend Java-spine — le combat TOURNE (9× plus vite), certification en cours

### Résumé
`JavaSpineBackend` écrit (~22 méthodes cspine du combat via `spine-libgdx-perblue`, contrat répliqué du JNI),
flag `-Ddh.spinebackend=java` route l'animation vers lui au lieu d'unidbg (atlas reste unidbg). **Le vrai
`HeadlessCombat` tourne intégralement sur Java-spine** (`state=DONE`) et **~9× plus vite** (work ~0,9 s vs
~9 s unidbg). La **certification contre l'oracle DÉTECTE des divergences** (rôle de l'oracle) : 2 bugs corrigés,
≥1 résiduel (cadence d'animation). PAR DÉFAUT le backend reste unidbg (flag off) — aucune régression.

### Obstacles franchis (build)
- **Deps compile** : ajout `spine-libgdx-perblue.jar` (uniquement `com.esotericsoftware.spine`, aucun conflit gdx).
  Casts explicites : l'`Array`/`IntMap` PerBlue (dex2jar) a des génériques ÉRASÉS → `.get()` renvoie `Object`.
- **libGDX PerBlue STRIPPÉ par ProGuard** : `spine-libgdx-perblue` exige des méthodes retirées du `game-logic.jar`
  (`DataInput.readString`, `IntSet.clear`). Fix : déposer les classes COMPLÈTES de gdx-1.9.7 (cache gradle) dans
  le dir de classes (1ᵉʳ sur le CP → ombrage). **Uniquement des classes STRIPPÉES** (superset sûr), JAMAIS une
  classe MODIFIÉE par PerBlue (ex. `Array.add→boolean`). Vérifié par diff de signatures (sous-ensemble).

### Bugs de fidélité (trouvés par l'oracle)
1. **`getBoneTransform` stride 6, pas 7.** Le JNI écrit 7 floats (toléré par unidbg sans bounds-check) mais le
   jeu alloue/lit **6** (`NativeSkeleton.tmpTF=float[6]`, `getBoneTransforms` dimensionne `n*6`). En JVM strict le
   7ᵉ déborde → AIOOBE. Corrigé : écrire `[worldX, worldY, worldRotationX, worldScaleX, worldScaleY, 0]`.
   `getBoneTransforms` (pluriel, ShadowRenderable/ombres cosmétiques) : rendu défensif (garde de bornes ;
   sémantique JNI = no-op quand `ids.length==nbOs`).
2. **`getCurrentAnimationTime` = `animationTime` WRAPPÉ, pas `trackTime` brut.** Oracle : maxAnimTime **1.833 s**
   (= durée pleine de skill1) = borné. Java avec `getTrackTime()` : **89.9 s** (accumule) → le jeu croit l'anim
   au-delà de tous les keyframes → n'attaque plus qu'une fois → stagnation. `getAnimationTime()` reproduit le
   borné natif (java → 1.733 s).

### État certification (1-1, seed 123456789, snapshot loot-1to5)
| | ticks | maxAnimTime | morts déf. | issue |
|---|---|---|---|---|
| **oracle unidbg** | 973 | 1.833 s | 5/5 | **WIN** (~8,5 s réel) |
| **java (après fix 1+2)** | 3876 (cap) | 1.733 s | **3/5** | stagne → LOSS (~0,9 s réel) |

⇒ Java **progresse** (3 kills, ~9× plus rapide) mais **tue ~15× plus lentement** → **divergence résiduelle de
cadence** (les attaques/skills atterrissent moins souvent ; skill1 semble interrompue à 1.733 vs 1.833 → keyframe
de dégâts tardif manqué). **Prochain pas certification** : diff **tick-par-tick** de la séquence
`getCurrentAnimationID`/`Time` d'une unité entre java et unidbg pour localiser la 1ʳᵉ divergence (mixing ? ordre
update/apply ? bornes de wrap ?). Piste : comportement du **mixing** (`setMix`/crossfade) ou `getCurrentAnimationID`
pendant un mix.

### Fichiers
`desktop-port/src/main/java/dhbackend/spine/JavaSpineBackend.java` (nouveau, backend), `.../cspine/Native.java`
(routage flag + diag), `.../CombatSpikeDriver.java` (compteur morts), `build.gradle` (dep spine), `run-desktop.sh`
(flag + shadow classes gdx), `run-online.sh` (forward `DH_SPINEBACKEND`). Docs : SERVER_PLAN §D.

---

## 2026-07-16 (nuit 3 quater) — Opt.3 Phase 0 : surface cspine EXACTE du combat headless mesurée (profilage)

### Résumé
Instrumentation DEV du shim `cspine.Native` (`-Ddh.cspineprofile`, compteur par méthode ; reset/report autour du
combat dans `CombatSpikeDriver`). Run du spike Opt.2 → **surface cspine EXACTE que le combat headless exerce**.
Résultat : **~22 méthodes** (animation/skeleton/os), **0 rendu**, mais **les positions d'os SONT lues**.

### Mesures (1-1, 3 héros vs 3 vagues, 973 ticks, 9 unités) — 37 méthodes appelées
**Hot path (par tick) :** `AnimationState_nextEvent` 8611 (polling events, VIDE car .skel sans event → trivial),
`getCurrentAnimationTime` 8008, **`Skeleton_getBoneTransform` 4376** (~4.5/tick — **positions d'os monde LUES**),
`getCurrentAnimationID` 4299, `AnimationState_apply` 4091, `Skeleton_updateWorldTransform` 4013, `Skeleton_update`
+ `AnimationState_update` 4004 chacun. **Setup/occasionnel :** `getAnimationID` 445, `getBoneID` 410,
`setToSetupPose` 96, `setAnimation` 87, `setColor`/`setTintBlack`/`setSlotEyeState` 47/38/36 (cosmétique),
`setMix` 36, `setSkin` 36, `Atlas_getTexture` 24, `getBoneTransforms` 15, create/dispose ×9 (9 unités),
`getAnimationDurations`/`getSlot/Anim/Bone/SkinNames`/`create` ×8 (skeletons uniques).

### Findings décisifs
1. **0 appel `getVertices*`** → le combat headless **NE REND RIEN** → toutes les méthodes de rendu (getVertices,
   setColor/tintBlack) → **no-op** en Opt.3. ✅
2. **`getBoneTransform` massif (4376)** → l'inconnu de la Phase 0 est **tranché : OUI le combat lit les positions
   d'os monde** → le backend Java-spine doit **appliquer l'anim + `updateWorldTransform` + exposer `Bone.getWorldX/
   Y/rotation`** (fourni nativement par spine-libgdx-perblue).
3. **`setMix` 36** → mixing/crossfade d'animations configuré → **à certifier** (timing).
4. **`nextEvent` 8611 mais 0 event dans les .skel** → poll toujours vide → implémentation triviale (retourne
   « rien »). Les keyframes de combat viennent du prefab (déjà établi nuit 3 ter).

### Conséquence pour l'Opt.3 (chantier CADRÉ)
Surface à implémenter en Java-spine ≈ **22 méthodes** : `SkeletonData_{create,dispose,getAnimation{Durations,ID,
Names},getBone{ID,Names},get{Slot,Skin}Names}`, `Skeleton_{create,dispose,update,updateWorldTransform,
setToSetupPose,setSkin,getBoneTransform(s)}`, `AnimationState{,Data}_{create,dispose,update,apply,setAnimation,
clearTracks,getCurrentAnimation{ID,Time},setMix,nextEvent}`. Toutes en **délégation directe au runtime Java**
(pas les 47 de cspine, PAS de rendu). Risques de fidélité à certifier vs oracle : valeurs de `getBoneTransform`
(monde) + timing `setMix`. Gain attendu : le hot-path (~29 appels unidbg/tick, ~9 s) → JVM natif (~ns) → <100 ms.

### Fichiers
`desktop-port/src/main/java/com/perblue/heroes/cspine/Native.java` (profilage DEV gated), `.../CombatSpikeDriver.java`
(reset/report), `run-desktop.sh`/`run-online.sh` (`DH_CSPINEPROFILE`). Docs : SERVER_PLAN §D.

---

## 2026-07-16 (nuit 3 ter) — Opt.3 TEST : données de timing ACCESSIBLES en Java pur (spine-libgdx-perblue) → faisable, verdict

### Résumé
Test de faisabilité de l'Opt.3 (timelines d'animation via runtime **Java spine** au lieu d'unidbg). **Résultat
POSITIF sur la data-reachability** : le `spine-libgdx-perblue.jar` (runtime Java `com.esotericsoftware.spine`)
**lit les `.skel` du jeu** et en extrait les animations + durées, **sans unidbg ni GL**. ⇒ fondation de l'Opt.3
existante. Reste un **coût de câblage** (backer le natif cspine par le Java-spine) à certifier contre l'oracle.

### Ce qui a été testé (probe `SpineProbe`, scratchpad)
- `SkeletonBinary.readSkeletonData(FileHandle)` sur `ralph/elastigirl/frozone.skel` → **parse complet** :
  ralph **12** anims (attack 1.0s, hit 0.633s, skill1 1.833s, skill2/3 1.367s…), elastigirl **14**, frozone **10**,
  avec **durées**. AttachmentLoader stub (attachments vides — pas besoin des textures pour le timing), FileHandle
  direct (sans Gdx.files).
- **Conflit de libGDX résolu (probe)** : `spine-libgdx-perblue` veut `DataInput.readString()` (absent : le
  `DataInput` de `game(-logic).jar` est un **stub 215 o**) ET `Array.add→boolean` (libGDX **PerBlue**). Or
  gdx-1.9.7 a readString mais `Array.add→void`. Même package, classes différentes → on prend le `DataInput`
  complet de gdx-1.9.7 (prioritaire) + le reste de `game-logic-framed` (Array→boolean). (= le sujet de la
  tâche #3 « DataInput.readString », shadow retiré au passage à unidbg.)

### Découverte structurante
- **Les `.skel` n'ont AUCUN event spine** (`eventDatas=0` pour les 3 héros). Les **keyframes de combat**
  (déclenchement dégâts/hit/projectile) NE sont donc PAS des events spine, mais des **composants du prefab de
  scène** (`.treeb` : `HitKeyframeData`/`ProjectileKeyframeData`), authored à part. Ces prefabs sont **aussi
  chargeables en Java** (PrefabLoader du jeu, données pures, sans GL/unidbg).
- **`AnimationElement`** (classe concrète) encapsule le **natif cspine** (`nativeSkel`/`nativeAS`, ctor
  `(NativeSkeleton, NativeAnimationState)`) pour la playback (`setAnimation`/`getAnimationLength` = horloge +
  durées), MAIS ses **keyframes sont une `HashMap` settable** (`setKeyframes`, source = prefab). Le combat ne
  semble pas lire de **positions d'os** (0 `findBone` dans `simulation/`).

### Verdict Opt.3
- **Data reachable en Java pur : OUI** (durées via .skel/Java-spine + keyframes via prefab/PrefabLoader).
- **Câblage** : pour retirer unidbg, backer la **sous-surface cspine du combat** (`NativeSkeleton`/
  `NativeAnimationState` : durées + horloge d'animation ; pas de rendu/vertices/os apparemment) par le
  Java-spine. **Réimplémentation bornée** (précédent : le projet avait des shadows cspine Java avant unidbg),
  **à CERTIFIER contre l'oracle Opt.2** (même spine 3.6 sur les mêmes .skel → forte proba de match, non garanti).
- **Gain** : supprime l'émulation ARM du combat → ~9 s → probablement <100 ms → **validation synchrone viable**.
- **Reco** : **Opt.2 async MAINTENANT** (autorité immédiate : brancher l'oracle sur `recordCampaignAttack` en
  validation de fond, ~9 s/combat hors ligne) ; **Opt.3 ensuite** comme upgrade perf certifiable (fondation +
  oracle déjà en place). Choisir Opt.3 en priorité seulement si le synchrone temps-réel est requis d'emblée.

### Fichiers
Probe `scratchpad/SpineProbe.java` (throwaway). Docs : SERVER_PLAN §D, MEMORY. Aucune modif jeu/serveur.

---

## 2026-07-17 — #28 CERTIFICATION Opt.3 : spine-c recompilé certifié FIDÈLE contre l'oracle unidbg (verdict desktop)

### Résumé — VERDICT
Le harnais différentiel (`CompareBackend` : le jeu tourne sur unidbg=oracle=binaire PerBlue mobile, le JNI
spine-c recompilé tourne **en parallèle** sur les **mêmes handles**, on diffe chaque appel) a servi à
**certifier** notre spine-c hôte (x86-64) contre le binaire ARM d'origine, **automatiquement**, sur un vrai
combat (NORMAL 1-1, 973 ticks, 5 morts, WIN — identique côté unidbg). Résultat après fermeture des écarts :

| catégorie | méthodes | diffs | verdict |
|---|---|---|---|
| **structure** (noms/ids/durées/counts d'anims, os, slots, skins ; atlas) | getAnimation{ID,Names,Durations}, getBoneNames, getSlotNames, getSkinNames, setSkin, Atlas_* | **0** | **identique bit-à-bit** |
| **événements d'animation** | nextEvent (8611 appels), setAnimation (87) | **0** | **identique bit-à-bit** |
| **transforms d'os** | getBoneTransform (4376), getBoneTransforms (15) | ~818 | **dérive flottante seule** : matrice 1.8e-7, position 6.1e-5 (sub-pixel, bornée) |
| ordre interne des os | getBoneID (410) | 410 | **artefact** : PerBlue réordonne les os en interne ; chaque backend est auto-cohérent (les transforms matchent une fois traduits par NOM) — aucun impact fonctionnel |
| extension proprio | setSlotEyeState (36) | 36 | **seul vrai manque** : extension PerBlue (expression des yeux), absente de spine-c vanilla — **purement cosmétique** |

### Bugs de fidélité RÉELS trouvés par le harnais (et corrigés)
1. **Layout matrice transposé** (`getBoneTransform`) : le contrat `NativeSkeleton` = matrice affine monde
   `[a, b, c, d, worldX, worldY]` (stride 6). Notre colle écrivait d'abord `[worldX,worldY,rot,sx,sy,0]` (faux),
   corrigé en `[a,b,c,d,x,y]` — mais le split mat/pos du harnais a révélé `mat=2.00` (∈±1 → catastrophique) avec
   un ulp de signe : signature d'une **transposition b↔c** (`b=-sin`, `c=+sin` → écart `2|sin|`, nul quand
   `sin≈0`, position intacte). Ordre correct de l'oracle = **`[a, c, b, d, x, y]`**. Après fix : `mat` tombe à
   **1.79e-7** (dérive flottante pure). *(Ces 2 bugs étaient invisibles sous unidbg — qui exécute le binaire
   PerBlue, pas notre colle — d'où jamais vus avant le harnais.)*
2. **`nextEvent` non branché** (renvoyait toujours `false`) → aucun callback `complete/end/start/…` → machines
   d'état d'animation du jeu muettes en backend JNI autonome. Implémenté la **file d'événements spine-c** :
   listener global sur `spAnimationState` (`rendererObject` porte une FIFO), empilée dans l'ordre de drain
   interne de spine (`_spEventQueue`) pendant `apply/update`, dépilée par `nextEvent` → `out[0]=type PerBlue`
   (`spEventType+1` : 1=start…6=event), `out[1]=trackIndex`. Certifié **0 diff / 8611 appels** (dont 423 events
   réels). *(Piège : `dispose` doit appeler `spAnimationState_dispose` AVANT de libérer la FIFO — spine émet un
   DISPOSE via le listener → use-after-free sinon, corrigé.)*
3. **`setAnimation` mauvais retour** : le jeu fait `return natif + eventIDOffset`. Relevé contre l'oracle : le
   natif renvoie un **compteur de trackEntry par animState** (1-based, ++ à chaque set/addAnimation), pas
   l'animId ni le trackIndex. Implémenté (`EvQueue.seq`). Certifié **0 diff / 87**.

### Verdict pour la décision « desktop dev-only vs production »
- **Rendu / animation : FIDÈLE.** Structure et événements **identiques bit-à-bit** ; poses d'os fidèles à la
  précision flottante (écart max **6e-5** sur des positions de l'ordre de la centaine = relatif ~1e-7, **borné**
  car les transforms monde sont **recalculés à neuf chaque frame** depuis les keyframes — pas d'intégration, donc
  **pas d'accumulation/chaos**). Sub-pixel → **invisible à l'écran**. ⇒ le spine-c recompilé est **jouable en
  production pour le rendu** (reste : régler le blocage handle-registry du boot JNI autonome, + l'extension
  cosmétique `setSlotEyeState` si on veut les expressions des yeux).
- **Autorité de combat : reste sur unidbg (serveur).** La dérive flottante ARM↔x86 (même minime) rend le JNI
  **non bit-identique** → un combat rejoué sur JNI pourrait diverger sur le long terme (sensibilité au chaos).
  Donc la **validation autoritative** (#24) garde le binaire PerBlue via unidbg — ce qui **coïncide** avec le
  principe §3 (serveur autoritatif) : c'est le serveur qui certifie, pas le client.
- En clair : **desktop = production pour jouer/afficher** (fidèle à l'œil), **serveur = unidbg pour l'autorité**
  (fidèle au bit). Les deux voies restent du **vrai code/données PerBlue** exécutés (§4), rien de réécrit (§2).

### Perf (FPS) — pourquoi le backend décide de la fluidité
Le harnais chronomètre les DEUX backends sur le **même mix d'appels** (fenêtre combat, hors `getVertices`) :
**unidbg (ARM émulé) = 16 900 ms vs JNI (natif x86) = 337 ms → le natif est ~50× plus rapide.** Sur 2919 ticks
(3 combats × 973) : ~**5,8 ms/frame** de travail squelettique côté unidbg vs ~**0,12 ms/frame** côté natif.
- **Aujourd'hui (défaut = unidbg)** : ~5,8 ms/frame RIEN QUE pour l'animation squelettique, AVANT le meshing
  (`getVertices`, aussi émulé, l'appel spine le plus lourd) et le GL → budget 60fps (16,6 ms) explosé en combat
  → **saccadé (FPS à un chiffre / bas). C'est un outil de dev, pas jouable fluide.**
- **Backend natif JNI (certifié)** : ~0,12 ms/frame → spine **n'est plus le goulot**, 60fps atteignable ; le
  coût résiduel devient le GL (ici llvmpipe logiciel headless ; sur une vraie machine = GPU). Le 50× est
  **conservateur** (exclut `getVertices`, où la pénalité d'émulation est encore plus forte).
- **Bloquant restant avant de mesurer le FPS natif bout-en-bout** : le boot JNI-autonome bute sur le
  handle-registry (aujourd'hui contourné par le mode compare, où le jeu boote sur unidbg). À lever pour un
  desktop natif de production. Le ratio par-appel, lui, est **mesuré** (pas estimé).

Fichiers : `native/src/cspine_jni.c` (layout `[a,c,b,d,x,y]`, FIFO d'événements + `seq`), `desktop-port/src/
main/java/dhbackend/spine/CompareBackend.java` (traduction d'os par nom, split diff matrice/position, chrono
par backend, rapport).

## 2026-07-16 (nuit 3 bis) — Opt.2 PROUVÉE : le vrai HeadlessCombat tourne headless via unidbg (ORACLE établi) + fix bytecode itf

### Résumé
Spike Opt.2 (#27) **concluant** : le **vrai `HeadlessCombat` du jeu** tourne **headless dans le client**
(`desktop-port` : GL Xvfb/llvmpipe + vrai `GameMain` + unidbg + assets) jusqu'à `DONE` et produit une **issue
autoritative**. C'est l'**oracle** pour certifier l'Opt.3. Lourdeur mesurée : **~9 s/combat** (unidbg-dominé).

### Ce qui a été fait
- **`CombatSpikeDriver`** (DEV, `-Ddh.combatspike`, off par défaut) : après boot+login, construit un
  `HeadlessCombat` de campagne (héros du user en `CoreAttackScreen$CombatUnitData` + `CampaignAttackScreen.
  createStageDefenders`), boucle `work()` jusqu'à `DONE`, imprime issue + timing. Câblé dans `DesktopLauncher`
  (hook après `game.render()`), flags forwardés par `run-desktop.sh`/`run-online.sh` (`DH_COMBATSPIKE*`).
- **Fix bytecode `itf` (ReframeJar)** : le 1ᵉʳ run a buté sur `IncompatibleClassChangeError: FXHandle.
  $r8$lambda$…() must be InterfaceMethodref constant`. Cause : dex2jar encode un `INVOKESTATIC` vers une méthode
  statique d'**interface** en `Methodref` au lieu d'`InterfaceMethodref`. `ReframeJar` corrige désormais
  `itf = isInterface(owner)` pour les `INVOKESTATIC` (non-sémantique §1). ⚠️ **Pas** pour `INVOKESPECIAL` (un
  1ᵉʳ essai trop large a cassé les défauts de super-interface indirecte → `VerifyError` sur
  `PegasusSkill3.onRampageStartAnimationEnd` ; restreint à INVOKESTATIC). Régénère `game-framed.jar` (serveur)
  ET `game-logic-framed.jar` (client).

### Résultats (mesures)
- **1-1, 3 héros progressés (snapshot loot-1to5) vs 3 vagues** → `state=DONE ticks=973 → WIN` (attackersLeft=
  true, defendersLeft=false). Le vrai moteur (skills/IA/keyframes) tourne headless.
- **Déterminisme** : 3 runs → **ticks=973 identiques** (graines 123456789 ×2 et 999). Sim bit-déterministe
  (temps réel varie 8,9–9,5 s = variance wall-clock, pas de sim). ⇒ **qualité oracle**.
- **Lourdeur** : ctor ~5-7 ms + `work()` ~9 s (973 ticks × 25 ms = ~24 s de combat in-game émulés). Dominé par
  unidbg spine (keyframes). **Trop lent pour du synchrone, OK pour de l'anti-triche async.**
- **Limite du cas de test** : stomp trivial → **seed-insensible** (même issue/ticks pour graines différentes,
  tout one-shot). La certification #28 devra utiliser des combats **serrés** (RNG discriminant).

### Fichiers
`tools/reframe/src/ReframeJar.java` (normalisation itf), `desktop-port/src/main/java/dhdesktop/
CombatSpikeDriver.java` (nouveau), `.../DesktopLauncher.java` (hook), `desktop-port/run-desktop.sh` +
`run-online.sh` (flags `DH_COMBATSPIKE*`). Docs : SERVER_PLAN §D (résultats+lourdeur), SHIMS (fix itf).

---

## 2026-07-16 (nuit 3) — #24 RE-SIM COMBAT : investigation à fond → combat KEYFRAME-DRIVEN → plan « oracle-certification »

### Résumé
Investigation approfondie de #24 (re-simulation serveur du combat pour outcome/stars **autoritatifs**). Conclusion
ferme : **`HeadlessCombat` n'est pas « pure logique »** et **le combat de DH est piloté par les keyframes
d'animation** → une sim serveur SANS données d'animation n'est pas fidèle. Décision user : approche
**oracle-certification** (mesurer l'Opt.2 unidbg comme oracle, puis certifier une Opt.3 plus légère contre lui).

### Chaîne de preuves (désassemblage + probes headless)
1. **`HeadlessCombat` = simulateur in-process du CLIENT** (utilisé par les écrans invasion/surge/hero-chooser
   pour **prévoir** l'issue via `startQuickCombat`). Son **ctor** bâtit un `RepresentationManager` →
   `initCommonVFX()` charge `world/common/common.treeb` via `RPGAssetManager`, dont le ctor fait
   `new Texture(Pixmap)` (**upload GL → exige un contexte GL**) et enregistre les loaders **natifs**
   (`NativeAtlasLoader`/`NativeSkeletonDataLoader`/`NativeParticlePoolLoader` = cspine/cparticle **unidbg**).
   `work()` pilote `loadScene(LOAD_ONLY)`/`startScene()` → charge+anime les représentations.
2. **Voie logique-only explorée** (piloter `Scene` directement) : le jeu a un mode headless
   `BuildOptions.TOOL_MODE=COMBAT_AUTOMATOR` → `RenderContext2D.getEnvSpacingInfo()`=`AUTOMATOR_BOUNDS` (pas de
   renderContext) ; renderer no-op du jeu `HeadlessSceneRenderer.INSTANCE` (méthodes vides). **Le SETUP tourne
   headless sans GL** : `new Scene(random,true)` + `CombatSetupHelper.createUnits/initPositions/
   initializeAIAndSkills` → unités créées (1-1 : 3 vs 1), `fixedTimestep=25ms`. `Scene.update` ne touche ni
   rendu ni représentation ; `Unit` a **0** ref `getRepresentation()`.
3. **MAIS `scene.update` enregistre des `AnimationKeyframeListener` sur l'`AnimationElement` de chaque unité**
   (`getAnimationElement()` null headless → NPE). C'est **le mécanisme par lequel les effets d'ability
   (dégâts/projectiles) se déclenchent à des frames précises**. Sans `AnimationElement` (créé par le
   `RepresentationManager` depuis les `.skel`), les unités **n'infligeraient jamais de dégâts**. Les entrées
   walk-in exigent aussi un `entranceKey` posé par le repManager (durées d'anim=0 sans `AnimationElement`).
   ⇒ **combat = keyframe/animation-driven → pure logique NON fidèle.**

### Options + principes (cf. SERVER_PLAN §D)
- **Opt.1** loot autoritatif (#25, pure logique prouvée) + garde-fous, combat = PARTIEL client documenté (§2 OK).
- **Opt.2** worker combat via **unidbg** (données-spine, sans rendu pixel) = **§3 complet + §4** (vrai code+data),
  mais lourd. Le plus fidèle à nos principes pour l'autorité totale.
- **Opt.3** timelines via runtime **spine Java** (`spine-libgdx-perblue.jar`) = plus léger, **conforme seulement
  si prouvé bit-fidèle au natif** (sinon rustine §4/§4bis).

### Décision (user) — oracle-certification (patron du rebuild natif)
Mesurer/monter l'**Opt.2 comme ORACLE** (elle réutilise le **client headless déjà fonctionnel** du
`desktop-port` : GL Xvfb/llvmpipe + vrai `GameMain` → assetManager/renderContext peuplés + unidbg + assets), puis
**certifier l'Opt.3 contre elle** (RNG/HP-tick/timing sur matrice large). Jamais shipper l'Opt.3 non certifiée.
**Prochain pas** : hook lanceur `dh.combatspike` (après boot+login → `HeadlessCombat` de campagne → `work()`
jusqu'à `DONE` → outcome/stars + timing). Aucune modif jeu/serveur committée dans cette phase d'investigation
(exploration en scratchpad). Fichiers docs : `docs/SERVER_PLAN.md` §D, MEMORY.

---

## 2026-07-16 (soir) — PIPELINE COMPLET VALIDÉ EN JEU (nouveau joueur → 1-1 GAGNÉ) + sélection des héros (pilote)

### Résumé
Run complet **nouveau joueur** (DB + prefs wipés, assets en cache) : intro → coffre GOLD (Frozone, 3 héros)
→ FAST_FORWARD → AUTO_FIGHT → SKILL_USE → **entrée campagne → choix des héros → 1-1 GAGNÉ → `CampaignAttack`
→ `recordOutcome` persisté**. Le serveur (framed jar, sans `-Xverify:none`) est resté **stable, 0 fatal JVM**
sur tout le run — le fix reframe validé de bout en bout en conditions réelles.

### Fix pilote : sélection des héros sur l'écran de choix
Le pilote atteignait `CampaignHeroChooserScreen` (« CHOOSE YOUR HEROES! ») mais **tapait FIGHT sans
sélectionner d'équipe** (TEAM POWER=0 → popup « select at least one hero » → aucun combat). Correctif
(technique **capturer→cliquer→monitorer→câbler**) : `selectHeroesIfNeeded` sélectionne les héros dispo via
l'API du jeu qu'un tap de portrait déclenche — **`HeroChooserScreen.unitSelected(UnitData, provider, x, y)`**
(cœur `HeroChooserHelper.selectUnitPressed` ; provider/coords inutilisés hors SURGE, vérifié au bytecode).
N'ajoute que les héros pas déjà dans l'équipe (`unitSelected` TOGGLE) et sélectionnables (`canSelectUnit`).
Vérifié EN JEU : `[tutodrive] CampaignHeroChooserScreen → héros sélectionnés via unitSelected → équipe prête
pour FIGHT`, puis `CampaignAttack NORMAL 1-1 WIN`.

### Persistance vérifiée sur la DB live (après 6× 1-1 WIN) — TOUT persiste, AUCUN 39M
Probe `server/data/dh-server.db` après le run :
- **STAMINA stored=122 / effective=120** ✅ — **aucun 39M même après 6 combats** (réponse à la question user :
  120 à l'écran — bouton FIGHT coût 6 — ET 120 en DB, au first launch). Le 122 stored (>cap) est le même
  artefact bénin R102, clampé à 120 à l'affichage/dépense.
- **GOLD = 2040** ✅ (6× ~340 = loot de campagne cumulé et persisté).
- **1-1 = 3★, totalWins=6** ✅ ; **1-2 débloqué = true** ✅ (déblocage persisté) ; `getLatestCompletedLevel=1-1`.
- **teamXP/teamLevel** cohérents (niv.1).
⇒ Pipeline **entrée / choix héros / skills (AUTO) / vagues / loot-gold / récompenses / XP / conso énergie /
progression / déblocage niveau suivant** : **tout validé et persisté**.

### BUG PERSISTANCE trouvé & corrigé : NIVEAU D'ÉQUIPE (révélé par la question stamina de l'utilisateur)
L'utilisateur a tiqué : « stamina 122/120 (au-dessus de 120) et 6 combats sans consommation ? ». Investigation
(`StamTrace`, 6× 1-1 sur joueur neuf) : la stamina EST consommée (−6/combat : 120→114→108…) MAIS **remonte
tous les 3 combats** à 122 (stored) / 120 (effective), corrélé à `TEAM_XP` qui reset 12→0. `team_levels.tab` :
niv.1→2 = **18 XP** (=3×6), montée = **+20 stamina** (`STAMINA_GAIN_ON_LEVEL`) ⇒ 122 = 102 (après −6) + 20.
MAIS `getTeamLevel()` restait **1** même après reload → **le niveau d'équipe ne montait/persistait pas**.
**Cause** : `User.teamLevel` est un **champ de `User`** (pas dans `this.extra`) ; `getUser` le lit depuis
`userInfo.basicInfo.teamLevel`, mais `setTeamLevel` (appelé à la montée par `giveTeamXP`) n'écrit QUE le
champ `User`. Sans re-sync, le wire garde 1 → l'équipe « re-monte 1→2 » à chaque palier de 18 XP et
**ré-accorde +20 stamina EN BOUCLE** (d'où la stamina qui ne descend jamais — le symptôme repéré par l'user).
**Correctif** (même schéma que resyncHeroes/resyncCampaign, §6) : `recordCampaignAttack` fait
`userInfo.basicInfo.teamLevel = user.getTeamLevel()`. Vérifié `StamTrace` (le niveau monte 1→2 au 3ᵉ combat
et **RESTE** à 2 ; teamXP progresse ensuite vers 25 = seuil 2→3 ; plus de refill +20 ; stamina se consomme
vraiment : …114→108→102) + **`server/smoke/TeamLevelPersistTest`** (niv.2 après 18 XP, survit au reload
SQLite). Régression `Resource/CampaignAttack/CampaignPersist` toujours verte.

### Réponse à « 122/120 et 6 combats sans consommation ? »
OUI la stamina est consommée (−6/combat). Le « 122>120 » = bonus `STAMINA_GAIN_ON_LEVEL=20` du jeu à la
montée de niveau d'équipe (stored peut dépasser le cap d'affichage 120 → clampé `min(122,120)=120`, même
artefact bénin R102). Le « pas de consommation apparente sur 6 combats » = le **bug ci-dessus** (re-level en
boucle refillant +20 tous les 3 combats) — désormais corrigé : la stamina descend réellement.

### BUG trouvé & corrigé : LOOT D'OBJETS de campagne non crédité (question user « objets équipables ? »)
L'utilisateur a demandé si les objets ramassés en combat sont persistés et dispo à l'équipement. Investigation :
inventaire (`individualUserExtra.items`) VIDE après 5 victoires (`InvProbe`), même EN MÉMOIRE (`LootProbe`) →
pas un souci de persistance, `recordOutcome` ne crédite RIEN. Cause : le combat est CLIENT-autoritatif → le
client ROULE le loot pendant le combat et l'envoie dans `CampaignAttack.lootEarned` (List<RewardDrop>) +
`memoryChanges`. `recordOutcome` **n'en roule pas** (vérifié `LootRoll` : lootEarned vide en entrée → vide en
sortie) : il **applique** la liste reçue (`giveLoot → RewardHelper.giveRewards → addItem` → items, auto-persisté).
`recordCampaignAttack` passait des listes **vides** → objets jamais crédités. **Correctif** : passer
`m.lootEarned` comme **1ᵉʳ paramètre List** (le loot à donner). ⚠️ **Piège** : le **2ᵉ paramètre List** de
`recordOutcome` est un **delta de RewardDrop** (déjà-affiché) que `giveLoot` passe à `removeDelta` → y mettre
`m.memoryChanges` (`UserLootMemoryChange`) **plante** (`ClassCastException` dans `removeDelta`) au 1ᵉʳ
`CampaignAttack`, qui n'est alors jamais enregistré → **cascade `CAMPAIGN_LEVEL_LOCKED`** sur tous les suivants
(révélé par un run réel). On laisse donc ce delta **VIDE**. Vérifié `LootApply` (RewardDrop → getItemAmount
0→1) + `server/smoke/LootPersistTest` (BADGE_OF_FRIENDSHIP x2 crédité, `m.memoryChanges` peuplé SANS planter,
survit au round-trip SQLite → dispo à l'équipement). **PARTIEL** : `m.memoryChanges` (mémoire de loot/pity) +
graine RNG client (`Action SET_SEED` TYPE=LOOT/COMBAT, loguée « non appliquée ») non appliqués → le serveur ne
re-roule pas, il fait **confiance au loot client** (cohérent avec le combat client-autoritatif).
Régression (Resource/CampaignAttack/CampaignPersist/TeamLevelPersist) verte.

### ENCHAÎNEMENT 1-1→1-5 EN JEU ✅ (nav post-victoire + fenêtre d'équipement)
D'abord le pilote rejouait 1-1 (une seule entrée carte, 6 combats) : après victoire il revenait sur
l'aperçu du MÊME niveau et re-tapait FIGHT au lieu de retourner à la carte. **Fix nav post-victoire** : flag
`justFoughtCampaign` posé en combat → au retour sur CampaignPreview/HeroChooser, RETOUR (BACK) à la carte →
`enterCampaignLevel` prend `nextPlayableLevel` = niveau débloqué suivant (remis à false à chaque entrée
fraîche pour ne pas sortir du 1ᵉʳ choix après le combat d'intro). **Fix étape équipement** : le pilote bouclait
sur `HeroDetailScreen` (~7000 frames) en FERMANT la `CraftingWindow` (prise pour un résidu ; cible tuto =
HERO_GEAR_SLOT_SIX derrière) → cas (a-bis) : une CraftingWindow est une **fenêtre de FLUX** → taper son bouton
EQUIP (`CRAFTING_WINDOW_EQUIP_BUTTON`) au lieu de fermer. **Résultat EN JEU** (run fresh, 3 fixes cumulés) :
`CampaignAttack NORMAL 1-1,1-2,1-3,1-4,1-5 WIN` — le pilote enchaîne
`normalOrEliteNodeSelected(1-1)→RETOUR→(1-2)→(1-3)→(1-4)→RETOUR→(1-5)→(1-6)`. **État persisté** (DB) :
teamLevel=**2**, 1-1..1-5 à **3★**, 1-6 débloqué, gold=**2316**, stamina stored=108/eff=108 (consommée
correctement — plus de refill en boucle grâce au fix team-level), serveur **0 fatal**. Pipeline complet
(entrée/choix héros/skills/vagues/loot/récompenses/XP/énergie/progression/**enchaînement**) validé en jeu.

### Run resume « coincé tôt » (élucidé)
Un run *resume* (300s) avait repris DANS le tuto (progression persistée seulement à HERO_FILTERS), rejoué
intro+coffres, atteint HERO_FILTERS puis s'était mis en **veille** (captures + pings keepalive) sans franchir
l'étape avant timeout. Pas une boucle de reconnexion (mauvaise lecture : `dh_game.log` écrasé par le run
suivant). Le run *fresh* (du début) atteint la campagne de façon fiable.

### Fichiers
`desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (`selectHeroesIfNeeded`). Aucune modif jeu/serveur.

---

## 2026-07-16 — Enquête « crash addHeroEXP » : RÉSOLU (artefact pré-fix) + vraie cause = SIGABRT oop-map JVM → reframe game.jar (serveur)

### Résumé
Reprise de l'enquête « crash `addHeroEXP` » (demande user « investigue ça et corrige »). Trois conclusions
fermes, toutes vérifiées :
1. **Le crash `addHeroEXP` (`ClientErrorCodeException: ERROR []`) NE se reproduit PLUS** — c'était un
   **artefact d'un état compilé pré-fix** (ChargeTest tournait contre une ancienne compilation). Sur la DB de
   chaînage courante, recompilée, `ChargeTest`/`ChainProbe`/`DiscTest` passent tous.
2. **Le VRAI incident intermittent était un crash JVM** (SIGABRT, pas une exception de jeu) :
   `fatal error: Illegal class file … in method getDefaultStats` dans `GenerateOopMap::error_work`
   pendant un **GC** (G1 root scan). Cause : le bytecode **dex2jar** de `game.jar` n'a pas de
   `StackMapTable` → sous `-Xverify:none` la JVM infère les oop-maps au GC via l'ancien
   `generateOopMap.cpp`, qui **plante** sur certains motifs (`ConstantStats.getDefaultStats`, perks de guilde).
   **Non déterministe** (dépend du timing GC) : ~1 run/8 en test. **Le serveur AUTORITATIF y était exposé
   aussi** (`run-online.sh:67` tournait `-Xverify:none`), pas seulement les smoke tests.
3. **La stamina « 39,96 M » N'EST PAS un bug** (hypothèse user « timestamp mal enregistré » **réfutée**).

### Fix durable : reframe game.jar → game-framed.jar (comme le client), retrait de -Xverify:none
`tools/reframe/ReframeJar` (ASM 9.7, `COMPUTE_FRAMES`) réécrit les **64 196** classes de `game.jar` avec des
frames valides → la JVM utilise le vérificateur rapide par table (plus de `generateOopMap`) et on **retire
`-Xverify:none`**. Appliqué à **`desktop-port/run-online.sh` (serveur)** ET **`server/smoke/run.sh`** :
reframe à la demande vers `libs/game-framed.jar` (gitignoré/régénérable, comme `game-logic-framed.jar` côté
client), classpath serveur basculé dessus, `-Xverify:none` retiré, `-XX:TieredStopAtLevel=1` conservé
(prudence C2). **Sémantique inchangée** (métadonnées de vérif). Vérifié :
- **`ChargeTest` 15/15 sans abort** sous vérification par défaut (vs abort intermittent avant).
- **Boot serveur OK** (framed jar, vérif par défaut) : process en écoute, port ouvert, **0 fatal**
  (aucun `VerifyError`/`Illegal class`/`GenerateOopMap`).
- **Régression identique** : `ResourceTest`, `CampaignAttackTest`, `CampaignPersistTest` verts (framed jar).
- Smoke `run.sh` : `CodecRoundTrip`/`MessageRoundTrip` OK ; `HandshakeRoundTrip` **retiré** (obsolète, ancien
  ctor `LoginServer` — SHIMS TODO #4).

### Stamina 39,96 M — cause RÉELLE établie (pas un bug), timestamp CORRECT
Mesures directes (probes) sur la DB de chaînage :
- **STORED stamina = 114** (correct), `lastResourceGenerationTime(STAMINA)` **correctement sauvé** (≈31 min
  avant `serverTimeNow`). ⇒ **hypothèse « timestamp mal enregistré » réfutée**.
- `getResourceCap(STAMINA)=**120**` (cap DÉPENSABLE), content update **R102**, `getRegenAmount=39 965 650`,
  `getHardCap=79,46 Md`, intervalle 6 min. Un joueur **neuf** lit **120** (aucun temps écoulé).
- Le « 39,96 M » n'apparaît QUE quand `stamina<cap` **et** ≥1 intervalle écoulé : `updateAndGetResource`
  (branche NON-capée pour STAMINA) ajoute **un** tick de 39,96 M puis sort → `getResource`=brut. Le jeu
  affiche/dépense la valeur **effective = `min(getResource, cap)` = 120**. **Fidèle R102 end-game** : un tick
  d'inactivité (6 min) = recharge pleine.
- `ChainProbe` (3 combats enchaînés) : **effective toujours ≤120** (120→114→120→114), **gold chaîne**
  340→680→1088→1632, **aucun crash de logique**. Le fix `applyEffectiveResourceCap` (commit 8f84ef5) applique
  la MÊME règle du jeu (`min(getResource, cap)`) → DB propre, dépense visible. **PAS de « figer »**
  (recalcule via `getResource`, régén incluse).

### Fichiers
`desktop-port/run-online.sh` (reframe + retrait `-Xverify:none`), `server/smoke/run.sh` (framed jar auto +
retrait Handshake obsolète), `.gitignore` (`libs/game-framed.jar`), `docs/SHIMS.md` (row reframe),
`MEMORY.md`. Aucune modif de logique de jeu ni de serveur (le handler campagne du commit 8f84ef5 est inchangé).

---

## 2026-07-13 (soir) — EQUIP_ITEM VÉRIFIÉ IN-GAME (wire) + enregistreur pas-à-pas + fixes pilote

### Résumé
Grâce à un **enregistreur pas-à-pas** (`dh.tutorec` : dump exhaustif des pointeurs du tuto + captures
numérotées par tick) et à un pilote **discipliné** (plus de tap central hors-script), on a capturé la
**séquence exacte de l'équipement** et **vérifié `EQUIP_ITEM` en jeu sur le wire** : le vrai client envoie
`Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP, extra={SLOT=SIX}}`, le serveur répond
`action EQUIP_ITEM appliquée [persisté]`, et le tuto INTRO_FEATURES avance. Nouvelle micro-frontière :
post-équip, le tuto n'émet plus de pointeur sur `HeroDetailScreen` (il faut en sortir vers le hub).

### Enregistreur `dh.tutorec` (outil DEV)
À chaque tick d'autotap : (1) `[tutorec]` dump SANS dédup — écran, fenêtres, **TOUS** les pointeurs du tuto
(`getPointAt` + `getActorTutorialName`), acteurs actionnables ; (2) capture **numérotée**
`build/rec/step_NNN.ppm` (après rendu/swap). Câblé `DH_TUTOREC`. Sert à savoir **exactement** ce que le tuto
désigne à chaque étape (au lieu de deviner). Off par défaut.

### Pilote discipliné (anti-vagabondage)
1. Le lanceur ne tape au **centre** que si le tuto n'a **aucun pointeur actif** (`hadActiveTarget()`=false,
   dialogue « tap to continue »). Avant, le tap central partait hors-script quand un pointeur était actif mais
   non résolu → écran coffre Diamant → « Follow the tutorial arrow! », tuto figé.
2. Quand la cible désignée est **absente de l'écran courant** (élément du hub alors qu'on est sur un écran de
   détail), le pilote frappe **BACK_BUTTON** pour se rapprocher du hub.

### Séquence de l'équipement (ENREGISTRÉE, source de vérité)
`HeroDetailScreen` pointeur **`HERO_GEAR_SLOT_SIX`** (slot 6) → ouvre `CraftingWindow` → pointeur
**`CRAFTING_WINDOW_EQUIP_BUTTON`** → le client émet `Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP,
SLOT=SIX}`. **Le serveur applique + persiste** (handler `ServerUser.applyCommand` EQUIP_ITEM) → tuto avance
(INTRO_FEATURES step 17→29/21). ⇒ `EQUIP_ITEM` **vérifié bout-en-bout en jeu**, plus seulement au smoke test.
NB : le client envoie le slot dans `extra={SLOT=SIX}` ; le handler le recalcule via `getSlotThatCanEquip`
(concordant) — on pourra préférer l'`extra` SLOT quand présent.

### Back-out « libre » post-équip → PREMIER COMBAT DE CAMPAGNE atteint
Indice utilisateur : sur `HeroDetailScreen` post-équip, le **bouton retour est mis en avant** mais
`getPointers` (rafraîchi) renvoie **vide** → le jeu attend que le joueur **sorte** de lui-même (pas de
pointeur formel). Ajout au pilote : après ~120 frames d'inactivité (aucun pointeur) sur un écran NON-hub,
taper **BACK_BUTTON** (seuil en frames, robuste à l'autotap ; un dialogue avance au tap central avant le
seuil). **Résultat vérifié en jeu** : le pilote sort `HeroDetailScreen`→`HeroListScreen`→…→**hub**, puis le
tuto reprend ses pointeurs (`MAIN_SCREEN_CAMPAIGN`→`CAMPAIGN_CHAPTER_ONE_NAME`→`CAMPAIGN_PREVIEW_FIGHT_BUTTON`
→`HERO_CHOOSER_*`) et lance le **1ᵉʳ combat de campagne** (`CampaignAttackScreen`, Frozone équipé, rendu
unidbg). Le tuto atteint l'acte **`FAST_FORWARD`** (post-INTRO_FEATURES). Capture
`desktop-port/build/campaign.png`. ⇒ frontière équipement **entièrement franchie**, on est dans le monde/combat.

### Roster de départ : Ralph + Elastigirl (fidélité vidéo)
Constat utilisateur (vidéo de gameplay) : un compte neuf possède **Ralph + Elastigirl** AVANT Frozone — or
notre nouveau joueur n'avait que Frozone (du coffre). L'intro combat les crée en SYNTHÉTIQUE
(`createUnitDatas`→`new User()`+`CombatSimHelper.createUnitData`) et ne les ajoute PAS au roster ; le roster
de départ est une décision de **création de compte** (serveur, qu'on contrôle). Ajout dans
`ServerUser.initNewPlayerResources` : `user.createAndAddHero(RALPH/ELASTIGIRL, WHITE, 1, 1, "new_user")`
(méthode du jeu ; état = défaut « nouveau héros » = celui de Frozone-coffre) + resync wire. Frozone reste
donné ENSUITE par le coffre GOLD. Vérifié (`server/smoke/RosterTest`) : {Ralph,Elastigirl} WHITE niv.1 →
+Frozone → persiste au wire ; 0 régression (Equip/Resource/ViewedChests/ChestWire OK). **Confirmé (vidéo)** : Vanellope n'est que dans le 1ᵉʳ combat tuto (synthétique), PAS possédée ensuite →
roster = Ralph + Elastigirl ; rang/niveau WHITE niv.1 validé.

### Piège découvert : reprise POLLUÉE
Reprendre depuis un `dh-server.db` d'une run **tuée** en plein coffre laisse un état incohérent (coffre Gold
déjà ouvert → bouton « gratuit » en cooldown, mais step de tuto non avancé) → deadlock (le tuto pointe un
bouton mort, `LootResults=0`). ⇒ tester depuis un état **propre** (snapshot post-coffres, ou nouveau joueur).

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` : `dh.tutorec` (dump exhaustif) + `hadActiveTarget()` + RETOUR BACK_BUTTON.
- `desktop-port/src/main/java/dhdesktop/DesktopLauncher.java` : tap central conditionné + capture par tick + capture périodique.
- `desktop-port/run-desktop.sh`, `run-online.sh` : `DH_TUTOREC`, `DH_SHOTEVERY` ; garde-fou (client+Xvfb).
- `MEMORY.md`, `JOURNAL.md`, `docs/SHIMS.md`.

## 2026-07-13 — Pilote : pop-ups empilées drainées (hub atteint) + Actions de bookkeeping REAL/NO-OP

### Résumé
Le correctif du pilote DEV pour les **pop-ups modales EMPILÉES** est **validé en jeu** : le tuto franchit la
frontière de l'équipement et atteint le **hub principal propre** d'un nouveau joueur ; frontière suivante =
tuto `HERO_FILTERS`. Les deux Actions de bookkeeping que le serveur loguait « non appliquée (PARTIEL) » sont
désormais traitées **fidèlement** : `VIEWED_CHESTS` (RÉEL) et `RECORD_SERVER_ROLL_FINISHED` (NO-OP fidèle).

### Pilote — drainage des modales empilées (`TutorialDriver.driveOnce`)
- **Bug** : quand le tuto pointe une cible (ex. bouton EQUIP de `CraftingWindow`) mais qu'une modale
  résiduelle (`ChestReadyWindow` « CRATE READY ») est empilée par-dessus, l'ancien code faisait `collect()`
  sur **toutes** les fenêtres, trouvait la cible dans la fenêtre inférieure et « tapait » ses coordonnées —
  mais le tap est **absorbé par la modale du dessus** (seule elle reçoit l'entrée) → faux « tapé »
  (`return true`) → **blocage infini**.
- **Correctif** (guidé par le tuto, sans coordonnée devinée) : on raisonne sur la fenêtre du **dessus**.
  (a) le tuto pointe DEDANS → taper le bouton désigné ; (b) le tuto pointe **AILLEURS** → la modale du dessus
  est un **résidu bloquant** → la fermer via l'API du jeu (`BaseModalWindow.hide()` = bouton X), ce qui
  **draine la pile une fenêtre/frame** jusqu'à révéler la cible ; (c) aucune cible active → attendre
  (récompense=`hide()`, interactive=bouton VIEW).
- **Vérifié en jeu** (reprise persistée depuis le snapshot post-coffres) : sur `HeroListScreen` une
  `ChestReadyWindow` résiduelle (coffre Gold) apparaît empilée → **drainée** (VIEW → `ChestResultsWindow` →
  fermeture). Le tuto progresse **au-delà** de l'équipement (INTRO_FEATURES step 29 → `HERO_FILTERS`) ; le
  jeu atteint le **hub principal rendu** (menu HEROES/ITEMS/…, CHOOSE NAME, CAMPAIGN!/CRATES!), session
  stable (Ping échangés). Capture `desktop-port/build/herofilters.png`.
- **Nouvelle frontière** (`HERO_FILTERS`) : `getPointers` n'émet le pointeur d'un step (`DIALOG_1` →
  `UIComponentName.FILTER_BUTTON`) que si `Step.logic().matches()` est vrai (sinon `cibles=[]`, attente).
  À la reprise, le client repart du hub (`MainScreen`) alors que `HERO_FILTERS` attend `HeroListScreen` →
  le pilote devra naviguer vers HEROES (chantier suivant).

### Actions de bookkeeping — `VIEWED_CHESTS` (RÉEL) + `RECORD_SERVER_ROLL_FINISHED` (NO-OP fidèle)
- **`VIEWED_CHESTS`** : branche extraite au bytecode de `ActionHelper.doAction` =
  `user.setTime(TimeType.LAST_CHESTS_VIEW_TIME, Long.parseLong((String) extra.get(ActionExtraType.TIME)))`.
  `User.setTime` écrit dans `this.extra.times` (`UserExtra` partagé) → **persiste** via `this.extra` (§3).
  Marque « coffres vus » (efface la pastille « nouveau »).
- **`RECORD_SERVER_ROLL_FINISHED`** : `ClientActionHelper.recordServerRollFinished` ne fait que construire
  l'extra (`ID/TYPE/COUNT/TIME`) et appeler `ActionHelper.doAction(RECORD_SERVER_ROLL_FINISHED, …)` — or
  `doAction` **n'a AUCUNE branche** pour ce `CommandType` (vérifié) → le code **client** du jeu ne mute rien.
  Pure notification client→serveur ; le comptage **autoritatif** des rolls est déjà fait par `openChest`
  (`ChestHelper.updateChestRollCounters`). ⇒ **NO-OP fidèle** (pas une rustine : rien n'est simulé ;
  inventer un registre de `rollId` violerait §4).
- **Vérifié** (`server/smoke/ViewedChestsTest`) : nouveau joueur → `applyAction(VIEWED_CHESTS, extra{TIME})`
  puis `applyAction(RECORD_SERVER_ROLL_FINISHED, extra{ID,TYPE,COUNT,TIME})` → round-trip wire →
  `getTime(LAST_CHESTS_VIEW_TIME)` == la valeur envoyée ; les deux `applyAction` renvoient `true`.

### Ressources du nouveau joueur — énergie « 39,96 M / 120 » CORRIGÉE
- **Bug** (repéré à la capture du hub) : l'énergie affichait **des millions** (« 39,96 M / 120 »). Cause :
  un `new IndividualUserExtra()` laisse `getLastResourceGenerationTime(STAMINA)=0` ; le jeu calcule la
  stamina courante = `UserHelper.updateAndGetResource(STAMINA, …, serverTimeNow())` = régénération **depuis
  l'époque 1970** (≈ 56 ans / intervalle 6 min ≈ des millions).
- **Correctif fidèle** (`ServerUser.initNewPlayerResources`, appelé par `newPlayer`) : comme un serveur à la
  création d'un compte — **ancre l'horloge de génération** de chaque ressource régénérée
  (`UserHelper.resourceGenerates(rt)` → `iu.setLastResourceGenerationTime(rt, creationTime)`) puis met la
  **stamina au cap du jeu** (`UserHelper.getResourceCap(STAMINA, user)` = `MAX_STAMINA` de `team_levels.tab`,
  **120** au niveau d'équipe 1). Valeurs issues de la logique/données du jeu (pas inventées). `setResource`
  écrit `individualUserExtra.resources` (partagé → persiste) ; sa branche `battlePassV2` ne concerne QUE les
  diamants → sûr headless.
- **Gestion/valeurs vérifiées** (`server/smoke/ResourceTest`) : compte neuf → **STAMINA=120/120**,
  **GOLD=0**, **DIAMONDS=0**, et la stamina **ne se re-gonfle pas** au round-trip wire (gen-time persisté).
  Note : GOLD/DIAMONDS=0 = valeur du constructeur du jeu pour un compte neuf (le jeu/tuto les accorde en
  jouant) ; à revoir si une dotation de départ (diamants) doit être seedée.

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` : drainage des modales empilées (logique (a)/(b)/(c)).
- `server/java/dhserver/ServerUser.java` : `applyCommand` += `VIEWED_CHESTS` (RÉEL) + `RECORD_SERVER_ROLL_FINISHED` (NO-OP) ; `newPlayer`/`initNewPlayerResources` (ancrage gen-time + stamina au cap).
- `server/smoke/ViewedChestsTest.java` (NEW), `server/smoke/ResourceTest.java` (NEW).
- `docs/SHIMS.md` #5, `MEMORY.md` (date + §7), `JOURNAL.md`.

---

## 2026-07-12 — Handler `Action` : investigation + architecture « logique cœur par commande »

### Résumé
Démarrage du **handler `Action`** (commandes génériques du jeu : équiper/voir/promouvoir…). Investigation
riche → **correction d'un diagnostic**, **1 vrai shim**, et une **conclusion d'architecture**. Handler
**frameworké et démarré**, **pas encore fonctionnel** pour l'équipement (à finaliser).

### Faits établis (corrigent/précisent)
- **`GuildStats` n'est PAS un bloqueur** (mon 1ᵉʳ diagnostic était FAUX — « illusion de crash », comme
  EVIL_QUEEN). `guild_perk_levels.tab` a des `CONTENT_TL` vides (lignes `TIMED_*`) → `parseInt("")` lève,
  mais `GeneralStats.parseStats` l'**attrape** (`onStatError` LOGue + saute la ligne). Vérifié en isolation
  (`GuildProbe`) : **`GuildStats` se charge OK**. La stack imprimée était **loguée**, pas fatale.
- **`DH.app.guildInfo` requis** : `getYourGuildInfo()` → `GuildPerkHelper.updateGuildInfoTimedPerks` lit
  `guildInfo.perkEndTimes`. **Corrigé** : `ServerContext.bind` pose un `new GuildInfo()` (nouveau joueur).
- **`ActionHelper.doAction` = chemin CLIENT « appliquer + UI »** : appelle `getScreenManager().getScreen()`
  **×4** → NPE headless (pas d'écran). ⇒ **on N'UTILISE PAS `doAction`** côté serveur. Comme `openChest`
  (qui exécute `ChestStats`/`DropTable`, pas un flux « acheter » client), on route **par commande vers la
  logique CŒUR** (`HeroHelper.equipItem`, `RealGearHelper.equipGear`…). Aiguillage écrit, règle exécutée.
- **Le badge « Badge of Friendship » n'est PAS du real gear** (`ItemStats.getRealGearType=DEFAULT`) → la
  commande d'équipement du tuto est vraisemblablement **`EQUIP_ITEM`**, pas `EQUIP_REAL_GEAR`.
- **Commandes `Action` réellement observées** (log serveur) : `VIEWED_CHESTS`, `RECORD_SERVER_ROLL_FINISHED`
  (mises à jour d'état légères). L'`Action` d'équipement reste **à capturer** (repro in-game NON déterministe :
  le pilote n'atteint pas toujours l'onglet gear).

### Ce qui est fait (committé)
- `ServerContext` : shim `guildInfo` (`new GuildInfo()`), en plus de user/individualUser/évènements.
- `ServerUser.applyAction`/`applyCommand` : aiguillage **par commande** vers la logique cœur ; routes
  `EQUIP_ITEM` (`user.getHero` + `HeroHelper.getSlotThatCanEquip` + `HeroHelper.equipItem`) et
  `EQUIP_REAL_GEAR` (`RealGearHelper.equipGear`) ; **log des commandes non gérées** (= cartographie).
- `LoginServer` (branche `Action`) : **log du contenu exact** (command/hero/item/extra) + `applyAction` + persist.

### `EQUIP_ITEM` ✅ RÉSOLU & VÉRIFIÉ (débogage déterministe par probes)
Diagnostic pas à pas (sans le client flaky) : (a) `openChest(SILVER)` **persiste bien** le badge
(`individualUserExtra.items={BADGE_OF_FRIENDSHIP=1}`) — le « no slot » venait d'un **snapshot périmé** ;
(b) le badge est le gear **requis du slot 6** de Frozone (`NormalGearStats.getItem(FROZONE, WHITE, SIX)=
BADGE_OF_FRIENDSHIP`) ; (c) `getSlotThatCanEquip` renvoie null car `ItemStats.isItemReleased(badge,
ContentHelper.getCurrent(user))=false` **headless** (colonne de contenu mal résolue — bug à corriger à part) ;
(d) `HeroHelper.equipItem(FROZONE, badge, SIX, user)` **équipe correctement** (slot 6 rempli, badge consommé).
⇒ **fix** : `applyCommand` route `EQUIP_ITEM` via `HeroHelper.getSlotThatCanEquip` + `equipItem`. **Vérifié**
(`server/smoke/EquipTest`) : équipe le badge en slot 6 + **persiste au round-trip wire**.

### Couche CONTENU (colonnes de release) ✅ RÉSOLU — unlock générique
Cause racine du `isItemReleased=false` headless : `ContentHelper` démarre **vide** (`ContentStats
.getColumns()=0` → `getColumn(now)=DEFAULT`). Le jeu charge le contenu du shard via
`ShardStats.setShardID(shard, map)` → `parseStats("content.<shard>.tab", opener)` — jamais appelé headless.
⇒ **fix** : `ServerContext.bind` appelle `ContentHelper.get().setShardID(user.getShardID(), new HashMap())`
→ charge `content.<shard>.tab` (372 colonnes pour shard 1) → `isItemReleased`=true. **Générique** (débloque
toute logique gatée « contenu released », pas que l'équipement). `EQUIP_ITEM` repasse alors sur la logique
d'origine `getSlotThatCanEquip` (plus de contournement). Reste : autres commandes (`VIEWED_CHESTS`…).
Détail : `docs/SHIMS.md` #5.

### Fichiers touchés
- `server/java/dhserver/ServerContext.java` (shim guildInfo), `ServerUser.java` (applyAction/applyCommand),
  `LoginServer.java` (branche Action + log), `docs/SHIMS.md` (#5 réécrit avec les faits).

---

## 2026-07-12 — Traversée du tuto en autonomie (pilote DEV) : intro→coffres→héros→équipement

### Résumé
Mise au point du **pilote DEV** (`TutorialDriver`) + méthodologie **reprise persistée + capture/inspection**
pour traverser le tuto in-game et faire une **passe de features**. Franchi : intro+combat → coffre GOLD
(Frozone) → coffre SILVER (Badge of Friendship) → CRATE READY → menu HÉROS → liste → détail héros →
onglet GEAR → **équipement du badge**. **0 exception serveur** partout. Frontière atteinte : l'équipement
passe par `Action1` non traité côté serveur → prochain handler.

### Corrections du pilote (chacune trouvée par capture d'écran + dump des acteurs)
- **Popups de récompense** (`ChestResultsWindow` = « CRATE REWARDS ») : fermées via l'API du jeu
  `BaseModalWindow.hide()` (le tap central ratait le X). Distinction affichage-récompense vs interactif.
- **Popups interactives** (`ChestReadyWindow` = « CRATE READY ») : frappe du **bouton-texte principal**
  (`DFTextButton` « VIEW », à stage(640,180)≠centre) — trouvé via `dumpActionable` (dump classe/tag/texte/pos).
- **Recherche dans TOUTE la scène** (`stage.getRoot`, pas seulement `getRootStack`) : le menu latéral
  (HEROES/ITEMS…) était hors du rootStack → `BASE_MENU_HERO_BUTTON` introuvable (trouvés=0) alors que le jeu
  y pointe. Corrigé → enchaîne HeroList→HeroDetail→GEAR→EQUIP.

### Méthodologie (documentée dans MEMORY §6ter)
- **Reprise RAPIDE** : ne pas supprimer `server/data/dh-server.db` → le client **reprend au hub** (saute
  l'intro), **~20 s** au lieu de ~4 min (0 rejeu de combat). **Snapshots** DB aux points sûrs
  (`dh-snapshot-postchests.db`, `dh-snapshot-postequip.db`) pour restaurer une frontière.
- **Boucle** : au blocage → screenshot (voir l'écran) + `dumpActionable` (quoi taper) → faire frapper le
  **bon acteur du jeu** (jamais une coordonnée devinée) → recompiler → relancer (reprise rapide).
- **Lire les logs avec `grep -a`** (le log serveur peut avoir du bourrage NUL).

### Finding (prochain handler serveur)
L'**équipement de gear** (et sans doute d'autres actions) part en **`Action1`** (fire-and-forget) que le
serveur **journalise mais ne TRAITE pas** → l'état autoritatif ne reflète pas l'équipement → au **reload**
le tuto ne peut pas avancer (idle sur MainScreen, INTRO_FEATURES step 29). ⇒ prochain : **traiter `Action`
côté serveur** (équipement…), comme on a fait pour `BuyChests`.

### Infra
- **Garde-fou serveurs** (`run-online.sh`) : détecte/kill les anciens serveurs (zombies) + refuse si port pris.
- Email d'auteur des commits corrigé → `noreply@anthropic.com` (GitHub vérifié).

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (fermeture popups, bouton-texte, recherche
  scène, dump), `desktop-port/run-online.sh` (garde-fou + DH_TUTODBG/DH_FPS + DH_FRAMES vide=non plafonné),
  `desktop-port/run-desktop.sh` (LC_ALL=C.utf8), `MEMORY.md` (§6bis lancement + §6ter méthodologie).

---

## 2026-07-12 — Diagnostic signal 16/exit 144 (strace) + couche évènements spéciaux (2ᵉ coffre débloqué)

### Résumé
Deux résultats liés, obtenus en poussant le run client réel jusqu'au coffre : (1) **diagnostic définitif**
du `exit 144` (SIGSTKFLT) via `strace -f` ; (2) un **bug réel** révélé par ce run — le **2ᵉ** coffre plantait
côté serveur — **corrigé fidèlement** en initialisant la couche évènements spéciaux du jeu (tâche #14).

### (1) signal 16 / exit 144 — `strace -f` = kill EXTERNE, pas de crash natif
Client lancé sous `strace -f -e trace=kill,tgkill,tkill,rt_sigqueueinfo,rt_tgsigqueueinfo,exit_group,exit
-e signal=all`. Sur **6 runs** (30→1500 frames, hors-ligne ET en ligne atteignant le combat) :
- **Aucun `SIGSTKFLT`**, aucun `tgkill`/`rt_sigqueueinfo` **interne** (rien ne s'auto-tue), `exit_group(0)`
  **propre** par le thread leader de la JVM. Seulement **2 `SIGSEGV`/run**, mais **normaux** (HotSpot les
  utilise pour ses null-checks implicites + safepoint polling, les rattrape, continue).
- Le kill **disparaît sous strace** (ralentissement ptrace) et n'est pas revenu ensuite → réponse au doute
  « crash natif JNI/.so » (unidbg/unicorn/LWJGL) : **NON**. Un crash natif serait déterministe sur le même
  chemin (ne disparaît pas juste parce qu'on ralentit) et apparaîtrait dans strace (signal reçu ou auto-kill).
  `SIGSTKFLT` (16) est quasi inutilisé par le noyau/glibc/JVM → **kill externe transitoire du superviseur**,
  non bloquant, non lié à notre code (un simple serveur Python en a été victime aussi). Confirme la
  conclusion précédente (le test `4× yes` avait déjà écarté un budget CPU).

### (2) Couche évènements spéciaux — 2ᵉ coffre (tâche #14)
Le run client réel a d'abord **prouvé le 1ᵉʳ coffre de bout en bout** (`[login] ==> LootResults : coffre
GOLD -> 1 héros débloqué`), puis a envoyé un **2ᵉ** `BuyChests` → `openChest` **NPE** :
`SpecialEventsHelper.helper` null via `giveChestRewards` → `RewardHelper.giveReward` → `UserHelper.giveUser`
→ `ContestHelper.onItemEarn` → `getActiveContestsWithTask` (les coffres à **récompense d'objet** enregistrent
des tâches de contest). C'est la dette #14, sur le chemin critique.
- **Fidélité** : `GameMain.create()` fait `SpecialEventsHelper.init(new ClientEventUserProvider(), new
  ClientSpecialEventsHelperExt())` (vérifié au bytecode, offsets 589-603), puis `handleBootData` appelle
  `setSpecialEvents(SpecialEventsRaw, user, shardID)`. L'extension **cliente** touche libGDX (`Gdx.app not
  available` headless) car elle **pousse au serveur** les temps de visionnage (`UpdateEventViewTimes`).
- **`dhserver.ServerSpecialEventsExt`** (NEW) = équivalent **serveur** de l'interface (RÉEL) :
  `sendEventRewards` reproduit la logique d'état cliente à l'identique (PREMIUM_STAMINA_CONSUMABLE →
  `ItemHelper.convertTimeLimitedItems` + `setTime(LAST_AMPED_STAMINA_BUY)`, aucun libGDX) ; `trySetEventViewed`
  conserve l'inscription **autoritative** (`user.getIndividual().getEventViewTimes().put`) et **omet** la
  poussée réseau client→serveur (le serveur EST le destinataire → sans objet). Zéro donnée falsifiée.
- **`ServerContext`** : `init()` appelle `SpecialEventsHelper.init(new ClientEventUserProvider(), new
  ServerSpecialEventsExt())` (une fois, comme `create()`) ; `bind()` appelle `setSpecialEvents(new
  SpecialEventsRaw(), user, shardID)` (par joueur, comme `handleBootData`). Nouveau joueur sans évènement
  live = raw vide → `getActiveContestsWithTask` renvoie une liste **vide** (au lieu de NPE).
- **`ServerUser.openChest`** : `updateChestCounters` **réactivé** (plus de PARTIEL).

### Vérifications
- **Unitaire** : nouveau joueur → **3 coffres GOLD d'affilée** : coffre 1 = Frozone (héros 0→1), coffre 2 =
  **récompense d'objet** (`heroesUnlocked=0`, le chemin qui plantait) **sans NPE**, coffre 3 OK.
- **Wire** (`ChestWireTest`) : `BuyChests(GOLD)` → `LootResults{Frozone}` en ~746 ms, **aucune régression**.
- **En jeu** : run client réel relançé (serveurs recompilés) pour rejouer les 2 coffres du tuto.

### Confirmation IN-GAME du 2ᵉ coffre (2026-07-12, après coup)
Un run client **non plafonné** (`dh.frames` absent → tourne jusqu'à ce que le tuto se joue) a traversé
l'intro + les DEUX coffres : GOLD → Frozone, puis **SILVER → `LootResults` avec 0 héros = récompense
d'OBJET** (exactement le chemin `onItemEarn` qui plantait). Résultat serveur : **0 exception** de tout le
run (`helper is null`/`NullPointer` = 0), tuto poursuivi jusqu'à **INTRO_FEATURES step 29**, `Action1`
(claims de coffre) acceptés. ⇒ le 2ᵉ coffre est **corrigé et vérifié in-game**. (Piège d'outillage noté :
tronquer le log serveur avec `: >` laisse du bourrage NUL → `grep` le prend pour un binaire et n'affiche
rien ; utiliser `grep -a`. Ne pas tronquer un log tenu ouvert par le serveur.)

### `sendEventRewards` : plus aucune recopie (2026-07-12)
Suite à la demande utilisateur (« éviter la recopie manuelle, exécuter le code du jeu »), `ServerSpecialEventsExt`
alloue l'instance du jeu `ClientSpecialEventsHelperExt` **sans constructeur** (Unsafe, le ctor touche libGDX)
et **délègue `sendEventRewards`** à la vraie méthode (elle ne dépend pas de libGDX). `trySetEventViewed` reste
de la glue serveur (enregistre le temps de visionnage, sans la poussée réseau client→serveur sans cible).
Zéro ligne de logique de jeu recopiée. Vérifié : 3 coffres d'affilée inchangés.

### Fichiers touchés
- `server/java/dhserver/ServerSpecialEventsExt.java` (NEW puis délégation), `ServerContext.java` (init/bind
  couche évènements), `ServerUser.java` (`updateChestCounters` réactivé).
- `desktop-port/run-desktop.sh` (`LC_ALL=C.utf8` — extraction d'assets aux noms Unicode).
- `docs/SHIMS.md` (TODO #1 → RÉSOLU, entrées `ServerSpecialEventsExt` + locale plateforme),
  `docs/SERVER_PLAN.md` §6, `MEMORY.md`.

---

## 2026-07-12 — Handler `BuyChests` complet : le serveur exécute la logique du jeu (Frozone, vérifié wire)

### Résumé
Étape 6 démarrée avec un **handler `BuyChests` fully functional** (option B) : le serveur **exécute le code
du jeu** sur l'état autoritatif → roule la vraie table, donne Frozone, répond `LootResults`, persiste.

### Architecture (option B, choisie avec l'utilisateur)
- **`ServerContext`** : `ServerStats.install()` (données du jeu) + **shim `DH.app`** — beaucoup de classes
  passent par le singleton client `GameMain` (ex. `User.getIndividual()` = `DH.app.getYourIndividualUser()`).
  On alloue un `GameMain` **sans constructeur** (`Unsafe.allocateInstance`), pose `user`/`individualUser`,
  affecte `DH.app`. Couche plateforme (§4), pas de logique de jeu.
- **`ServerUser.openChest(BuyChests)`** : construit `User`/`IndividualUser` de jeu **sur nos objets wire**
  (`getUser` fait `this.extra = userExtra` → **les mutations via `this.extra` persistent d'elles-mêmes** :
  `setResource`/`setChannelRollCount` écrivent dans `this.extra`). Roule `ChestStats.getDropTable(GOLD)` +
  `DropTable.rollNode("ROOT")`, `DropConverter.convert`, `ChestHelper.giveChestRewards(bl=true)` (donne +
  remplit `heroesUnlocked`), `updateChestRollCounters`. **Resync** des champs hors `this.extra` (héros via
  `getHeroData`, `chestUpgradeXP`). Renvoie `LootResults`.
- **`LoginServer`** : `BuyChests` → `openChest` → répond `LootResults` + persiste (SQLite).

### Sérialisation inverse (le point clé résolu)
Le jeu n'a pas de sérialiseur `User→wire` complet, MAIS ses setters écrivent dans `this.extra` (l'objet
wire qu'on lui passe). En construisant le `User` **sur nos propres objets wire**, la plupart des mutations
persistent automatiquement ; seul un **ensemble fermé** (héros, `chestUpgradeXP`) est resynchronisé.
Validé par round-trip.

### Vérifications
- **Unitaire** : nouveau joueur (0 héros) → `openChest(GOLD)` → `LootResults{lootDrops=1, heroesUnlocked=1}`,
  joueur possède Frozone, **persiste au reload** (SQLite).
- **Sur le wire** (`server/smoke/ChestWireTest`) : client `ClientInfo→BootData` puis `BuyChests(GOLD)` →
  **`LootResults{Frozone}` reçu en ~630 ms**. Handler prouvé sur le protocole réel.
- **En jeu** : le client atteint le coffre et envoie `BuyChests1` à notre serveur (confirmé). Le run client
  complet meurt parfois (exit 144, signal d'environnement sur runs longs) avant d'afficher la réponse —
  d'où la vérification par `ChestWireTest` (rapide, déterministe).

### PARTIEL noté (§2, avec risque)
- `updateChestCounters` (compteurs QUOTIDIENS, limites d'achat) passe par `SpecialEventsHelper.helper`
  (couche évènements non initialisée headless) → différé. Non requis pour le tuto (coffre gratuit).
- Coffres **payants** (charge diamants via `setResource`→`DH.app.getUserBattlePassV2`) : nécessitent
  d'étoffer le shim (battlePassV2). Le coffre **gratuit** du tuto est complet.

### Fichiers touchés
- `server/java/dhserver/ServerContext.java` (NEW), `ServerUser.java` (openChest + resync), `LoginServer.java`
  (handler BuyChests), `server/smoke/ChestWireTest.java` (NEW), `desktop-port/run-online.sh` (-Ddh.stats),
  `docs/PRINCIPLES.md` §3 (shim DH.app + sérialisation inverse), `docs/SERVER_PLAN.md` §6, `MEMORY.md`.

---

## 2026-07-12 — Enquête coffres/héros + fondation « serveur exécute le code+données du jeu » (spike Frozone)

### Résumé
Investigation (question utilisateur) sur le 1ᵉʳ coffre du tuto, puis **fondation de l'étape 6** : le serveur
**charge les données du jeu et exécute sa logique** (règle affinée §3). **Spike concluant** : le vrai code
de coffre du jeu roule côté serveur → **Frozone** pour un nouveau joueur.

### Enquête (extraite du code/données)
- **Héros avant le 1ᵉʳ coffre = 0.** Combat d'intro = synthétique (`CombatSimHelper.createUnitData(new
  User(),…)`). 5 « héros tuto » du jeu = `RemoveIf(SpecificHeroes, VANELLOPE, RALPH, YAX, ELASTIGIRL,
  FROZONE)` (`black_market_merchant_drops.tab`), acquis progressivement (Frozone→Vanellope `UnlockHeroActV1`
  →Yax campagne 1-13…). `starter_deal_heroes.tab` = pack **payant**, pas le roster gratuit.
- **1ᵉʳ coffre = FROZONE prédéfini** : `IntroFeaturesActV2.getChestUnitType()=FROZONE` **et** rig de la table
  `gold_chest_drops.tab` (`ROOT_1X_FIRST ? PreviousRolls(0) ? ROOT_1X_RIG_1 ? CJK ? HERO_BUZZ ? HERO_FROZONE`).
- **Coffres hors tuto** : `BuyChests{chestType,count,roll:ServerRollRequest}` → serveur roule
  `<type>_chest_drops.tab` (`DropTable`) : rigs 1ᵉʳ/2ᵉ, pitié 10× `Try NoneAre(YourHero)`, payant/gratuit,
  VIP, locale, pools `@NON_EXCLUSIVE/GOLD_CHEST_EXCLUSIVE_HEROES`. `channelRollCount` alimente `PreviousRolls`.
- Réponse au client = **`LootResults`** (`heroesUnlocked`/`lootDrops`/`costs`/`roll`).

### Fondation « lire & exécuter » (spike)
- **`ServerStats`** (NEW) installe l'ouvreur de stats du jeu (`StatFileHelper.setExt`) lisant
  `game-data/stats/` → 274 `.tab` chargés headless (SEVERE = quirks `.tab` tolérés, comme en jeu).
- **Dépendance joda-time** : `game.jar` a les classes joda (dex2jar) mais **pas** la donnée fuseaux
  `org/joda/time/tz/data/*` (requise par `TimeUtil.<clinit>`, appelé via `ContentStats`/`CampaignStats` lors
  de `IndividualUser.setExtra`). Fournie par le jar standard joda-time-2.12.2 (classes ombrées par game.jar,
  seule la ressource tz utilisée) — donnée du jeu, pas une réécriture. Récupéré à la demande par `run-online.sh`.
- **Spike** (`ChestSpike`) : install stats → `ChestStats.getDropTable(GOLD)` (table 38 nœuds, parsée) →
  `User`/`IndividualUser` construits depuis l'état wire (`ClientNetworkStateConverter`) → `ChestContext(user)`
  avec `setChestType(GOLD)`+`setCount(1)` → `DropTable.rollNode("ROOT")` = **`HERO_FROZONE`**. (count=0 →
  `RetainCount(0)` = 0 drop : d'où l'importance de `setCount`.) **Zéro donnée écrite à la main.**

### Fichiers touchés
- `server/java/dhserver/ServerStats.java` (NEW), `desktop-port/run-online.sh` (classpath+fetch joda).
- `docs/PRINCIPLES.md` §3 (règle affinée « lire & exécuter »), `docs/SERVER_PLAN.md` §6 (archi + faits coffres),
  `MEMORY.md`, `JOURNAL.md`.
- Reste : handler `BuyChests` complet (roll→`LootResults`→`applyChestResults`→re-sérialiser→répondre).

---

## 2026-07-12 — Tuto d'intro joué DE BOUT EN BOUT (harnais DEV) + FPS combat + frontière du hub

### Résumé
Ajout d'un **harnais de DEV** (drapeaux lanceur, off par défaut, **aucune modif jeu/serveur**, **rien en
prod**) qui pilote le jeu headless via **les outils/API du jeu** : le tuto d'intro se joue **de bout en
bout jusqu'à `DONE`**, puis atteint sa 1ᵉʳ action serveur (**coffre de départ → `BuyChests1`**) = frontière
de la phase hub. Mesure FPS/profilage du combat.

### Outils de DEV ajoutés (côté lanceur `desktop-port`)
- **`TutorialDriver`** (piloté par `dh.autotap`) : interroge `TutorialHelper.getPointers(user)` → cible
  (`TutorialPointerInfo.getActorTutorialName()` = nom+index), retrouve l'acteur via `getTutorialName()`
  (comme `Group.findTutorialActor`) dans `getRootStack()`, convertit stage→écran (viewport plein cadre) et
  tape via `DhInput`. Sans pointeur → tap central (dialogues). **Zéro coordonnée devinée** : tout vient de
  l'acteur désigné par le jeu. C'est du **contrôle headless**, pas une modif.
- **`dh.autofight`** : appelle l'API publique **`CoreAttackScreen.setAutoAttack(true)`** (bouton AUTO
  d'origine) → auto-combat du jeu (utile hors tuto ; le tuto d'intro **met le combat en pause** et exige un
  tap manuel sur le héros désigné → assuré par le driver).
- **`dh.fps`** : FPS glissants + **profilage** (chrono des appels unidbg vs reste). Compteurs statiques dans
  `UnidbgVM` autour du dispatch `si/sv/so/pi/pv/po`.
- **Inventaire des outils d'auto du jeu** (pour info) : `automation/{TouchRecorder,TouchPlayback}` (rejoue un
  enregistrement), `automation/crawler/*` (scripts de clics FIXES : Example/Chest/Market/Purchase),
  message serveur `TriggerCrawler`, `TutorialHelper.finishIntroForced/finishAllTutorials` (saut). **Aucun**
  n'est un « joueur de tuto » clé en main ; le driver ci-dessus s'appuie sur le **système de pointeurs** du jeu.

### FPS combat (sur cette machine, headless llvmpipe SANS GPU)
- **~9 fps** en combat (`TutorialAttackScreen`). Répartition par frame : **unidbg (spine+particules) ~50 ms
  (combat léger) → ~80 ms (combat plein, DOMINANT)** ; rendu logiciel+logique ~40-60 ms. Deux pire-cas :
  pas de GPU (le « reste » s'effondrerait avec une carte) + émulation ARM (plancher dur d'unidbg). ⇒ perf
  combat = futur chantier d'optim (moins d'appels unidbg/frame, JIT dynarmic cassé à réparer, etc.).

### Déroulé vérifié (run-online.sh + DH_AUTOTAP + DH_AUTOFIGHT)
- Intro : GATE_DIALOG → TRANSFORM → **COMBAT1** (ACTIVE_1/2/3 franchis via taps guidés) → POST_COMBAT →
  **COMBAT_2** → **`DONE`**. Serveur = 0 réponse requise pour tout l'intro (client-side).
- Puis **INTRO_FEATURES** : chargement des VFX `reward_boxes`, **`BuyChests1`** + `Action1` envoyés →
  écran **« CRATE REWARDS / Waiting for results… »** (capture `native/reference/shots/
  tutorial-intro-done-crate.png`). Le serveur journalise mais **ne répond pas** → le client attend.

### Conclusion / prochaine phase
Réponse à « gagne-t-on des héros de départ, le serveur gère-t-il ? » : **OUI**, le tuto donne les héros de
départ via un **coffre** (`BuyChests1`) juste après l'intro, et **le serveur doit le gérer** (contenu du
coffre autoritatif = héros de départ, réponse). ⇒ **étape 6 (hub)** démarre par le handler **`BuyChests`**.

### Fichiers touchés
- `desktop-port/src/main/java/dhdesktop/TutorialDriver.java` (NEW), `DesktopLauncher.java` (autotap→driver,
  `dh.autofight`, `dh.fps`+profilage), `dhbackend/unidbg/UnidbgVM.java` (chrono), `run-desktop.sh` (drapeaux).
- `docs/SERVER_PLAN.md` (étape 6 amorcée + section Outils de DEV), `MEMORY.md`, `JOURNAL.md`.
- Captures : `native/reference/shots/{tutorial-combat1-unidbg,tutorial-intro-done-crate}.png`.

---

## 2026-07-11 — Serveur autoritaire étape 5 : persistance SQLite (octets wire des objets du jeu)

### Résumé
L'état joueur autoritaire est **persisté en SQLite** sous forme d'**octets wire** produits par les
classes du jeu (aucun schéma inventé pour les données du jeu, cf. PRINCIPLES §6). La progression du
tutoriel survit à un redémarrage du serveur.

### Détails
- **`ServerUser` refactoré** : détient l'état comme **objets du jeu** — `UserInfo` (identité),
  `UserExtra` (héros/ressources/réglages), `IndividualUserExtra` (tutoriels/quêtes). `bootData()` branche
  ces objets dans un `new BootData()` (complet par ses constructeurs). Sérialisation :
  `GruntMessage.writeAll` (en-tête + données = wire exact) ↔ `MessageFactory.readMessage` (round-trip
  symétrique prouvé par les smoke tests).
- **`UserStore` (SQLite, `sqlite-jdbc`)** : table `users(userID, shardID, userInfo BLOB, userExtra BLOB,
  individualUserExtra BLOB, updatedAt)`, clé `(userID, shardID)`, upsert `ON CONFLICT`. **Un objet du jeu
  = un BLOB** → ajouter un champ de jeu persisté = ajouter un BLOB, sans recopier une seule valeur.
- **`LoginServer`** : `loadOrCreate(1,1)` au démarrage ; `store.save(user)` à chaque `ChangeTutorialStep`.
- **Dépendances** : `sqlite-jdbc` 3.45 + `slf4j-api` récupérés à la demande par `run-online.sh` (non
  committés, régénérables — comme ASM). DB sous `server/data/` (gitignore). Le serveur reste « rien à
  installer » côté utilisateur.

### Vérifications
- **Unitaire** : session 1 crée le compte (122 actes), avance INTRO au pas 40, `save` ; **session 2**
  rouvre la DB → état **restauré à l'identique** (122 actes, INTRO step 40), BootData revalide sur le wire.
- **En jeu** : `LoginServer` démarre, `loadOrCreate` OK (DB `server/data/dh-server.db`), persiste les
  `ChangeTutorialStep` réels sans erreur.

### Fichiers touchés
- `server/java/dhserver/UserStore.java` (NEW) : persistance SQLite (BLOB wire).
- `server/java/dhserver/ServerUser.java` : détient les objets du jeu + sérialisation wire.
- `server/java/dhserver/LoginServer.java` : load-or-create + save sur ChangeTutorialStep.
- `desktop-port/run-online.sh` : classpath + fetch sqlite/slf4j ; `.gitignore` : DB runtime.

---

## 2026-07-11 — Serveur autoritaire étape 4 : handlers du tuto (intro) + pilote headless (vérifié en jeu)

### Résumé
Le serveur **applique/persiste la progression du tutoriel** (`ChangeTutorialStep`) dans un état autoritaire
(`ServerUser`). Fait extrait : le **tutoriel d'intro est 100% piloté par le client** (aucun aller-retour
serveur), donc le serveur n'a qu'à **suivre la progression**. Ajout d'un **pilote headless** (`dh.autotap`)
qui traverse les dialogues « tap to continue » : le tuto se joue **de bout en bout jusqu'au 1ᵉʳ combat**.

### Détails (extraction = source de vérité)
- **`IntroTutorialActV2` n'émet AUCUN message réseau** (aucun `sendMessage`/`networkProvider`) ; son combat
  est **local** : `CombatSimHelper.createUnitData((IUser)new User(), …)`, `pauseCombat/resumeCombat`,
  `PauseCombatEvent/ResumeCombatEvent`, `TutorialHelper.startCombatTimerEvent`. Les étapes (`Step` enum) sont
  toutes des états de dialogue/combat côté client (GATE_DIALOG_*, TRANSFORM_ANIMATION, COMBAT1_*, COMBAT_2_*,
  POST_COMBAT_*, DONE). ⇒ **seule sortie serveur = `ChangeTutorialStep`** (framework), fire-and-forget.
- **`ChangeTutorialStep`** = `{type, step, forceSkip}`. `step` **absolu** (cf. `finishIntroForced` pose
  `step = maxStep`). `ServerUser.applyTutorialStep` met à jour l'acte : `step` ← message, `maxStep` ←
  max(courant, step) (« plus haut pas vu »). Copie défensive dans `bootData()` (le client ne mute pas
  l'état autoritaire).
- **Pilote headless** `DesktopLauncher` : `dh.autotap=N` injecte un tap central toutes les N frames via
  l'infra existante `DhInput.tap`→`drain`. Traverse les gates « tap to continue » sans utilisateur.

### Vérifications
- **Unitaire** (sans libGDX) : nouveau joueur = 122 actes step 0 ; apply 12/25/3 → step=3, maxStep=25 ;
  état autoritaire non muté par la copie client ; survit au round-trip wire.
- **En jeu** (`run-online.sh` + `DH_AUTOTAP=45`) : le serveur applique les `ChangeTutorialStep` **réels**
  (INTRO 1→2→3, FRIEND_MISSION 6, FRIENDSHIP_UNLOCK 17, REAL_GEAR_UNLOCK 11, FRIEND_CAMPAIGN 20,
  HEIST_NARRATION 1…), **tous** trouvés dans les 122 actes (0 « type inconnu »), **0 réponse** requise. Le
  client joue l'intro **de bout en bout jusqu'au 1ᵉʳ combat** : GATE_SIGN_DROP → GATE_RALPH_ENTER →
  GATE_VANELLOPE_ENTER → GATE_DIALOG_2 → TRANSFORM_ANIMATION → COMBAT1_INTRO → …_SHOW_LOGO →
  …_POST_LOGO_DIALOG → …_POST_GLITCH_DIALOG. Session stable (Ping échoé).

### Fichiers touchés
- `server/java/dhserver/ServerUser.java` (NEW) : état joueur autoritaire (BootData + progression tuto).
- `server/java/dhserver/LoginServer.java` : handler `ChangeTutorialStep` ; BootData depuis `ServerUser`.
- `server/java/dhserver/NewUserState.java` : recentré (fabrique tutoriels + `latestVersion`).
- `desktop-port/src/main/java/dhdesktop/DesktopLauncher.java`, `run-desktop.sh` : pilote `dh.autotap`.
- Reste : actions post-intro server-validées (nom, campagne, récompenses) = **handlers du hub (étape 6)** ;
  **persistance SQLite (étape 5)** de `ServerUser`.

---

## 2026-07-11 — Serveur autoritaire étape 3 : BootData nouveau joueur → TUTORIEL (vérifié en jeu)

### Résumé
Le serveur envoie désormais un **BootData de nouveau joueur complet** construit **à partir des classes
du jeu**, et le client d'origine **route vers le tutoriel d'intro** : `IntroTutorialActV2` démarre et rend
la scène d'ouverture (Ralph + Vanellope devant le portail). Zéro donnée écrite à la main.

### Détails (décompilation CFR = source de vérité)
- **`GameMain.handleBootData` lu en entier** (949 lignes bytecode → CFR) : recensé **chaque** champ de
  `BootData` déréférencé (doit être non-null). **`BootData` décompilé** : le **constructeur du jeu**
  initialise TOUS ces champs (`userInfo=new UserInfo()`, `userExtra`, `privateUserInfo`, `guildInfo`,
  `currentServer=new Server()`, `allContests`, `individualUserExtra`, `invasionInfo`, `specialEvents`,
  `statData*/statVersions=new HashMap`, `loginEvent=""`, `mailMessages=new ArrayList`…). ⇒ `new BootData()`
  est **complet par construction** (règle : la complétude vient des initialiseurs du jeu, pas d'une liste
  inventée).
- **Routage tuto (chaîne extraite)** : `handleBootData` → `ClientNetworkStateConverter.getIndividualUser
  (individualUserExtra)` → `IndividualUser.setExtra` itère **`individualUserExtra.tutorialActs`** (List de
  `TutorialAct`) → `getUserTutorialAct` → `addTutorialAct`. `TutorialHelper.completedTutorialAct(type)`
  renvoie **true quand `getTutorialAct(type)==null`** (acte ABSENT ⇒ tuto « fait/sauté ») ; sinon complété
  sur `step >= act.getMaxStep()` (`AbstractTutorialAct.getCompletionState`). ⇒ un nouveau joueur doit porter
  **TOUS** les `TutorialHelper.NEW_USER_ACTS` (**122 types**) à **`step 0`** (IN_PROG), sinon des features
  (UNLOCK_HERO…) seraient considérées « déjà faites » et jamais introduites.
- **Aucune saisie** : `NewUserState.newUserTutorialActs()` lit la liste **dans le registre du jeu** —
  `TutorialHelper.NEW_USER_ACTS` (public) + `ACTS` (réflexion, `type→IntMap(version→act)`), en prenant la
  **dernière version enregistrée** par type. Vérifié : les 122 types résolvent une version (117×v1, 5×v2),
  aucun non enregistré. `TutorialHelper` **se charge côté serveur sans libGDX** (les ctors d'actes ne
  touchent pas libGDX à la construction).
- **Correction de fidélité** : la version INTRO enregistrée dans le 12.1.0 est **`IntroTutorialActV2`**
  (`getType()==INTRO`, `getVersion()==2`) — `IntroTutorialActV1` n'est **pas** enregistré. Les anciennes
  notes « IntroTutorialActV1 » étaient erronées.
- **`SEVERE: Missing row in tutorials.tab`** (EMERALD_RANK, FRANCHISE_TRIALS(+STAGE_SELECT), PATCHED_HEROES,
  TEAM_LEVEL_UP, BATTLE_PASS_V2…) : **PAS causé par le serveur**. Le SEVERE inclut `REMOVED__CRYPT` (jamais
  dans mes actes) ⇒ il vient de **`TutorialStats.onMissingRow`** qui parcourt l'enum `TutorialActType`
  complet au chargement de `tutorials.tab` (l'APK 12.1.0 a du **code** en avance sur sa **donnée** `.tab`).
  Comportement d'origine tolérant (même catégorie que l'étape 2). Aucune rustine.

### Vérifications
- **Round-trip wire** (`MessageFactory`, sans libGDX) : BootData nouveau joueur = 8192 o, 122 actes relus,
  INTRO v2/step0, id/teamLevel OK.
- **En jeu** (`run-online.sh`, client d'origine via unidbg) : `[login] ==> BootData nouveau joueur : 122
  actes de tuto (step 0)` ; `IntroTutorialActV2 onTutorialTransition` INITIAL→SCREEN_WAIT→…→GATE_DIALOG_1_A ;
  8× `ChangeTutorialStep1` reçus ; **capture `desktop-port/build/online-tuto.png`** = scène d'ouverture
  (Ralph + Vanellope + portail Disney Heroes, dialogue « I can't believe you talked me into this. »).

### Fichiers touchés
- **`server/java/dhserver/NewUserState.java`** (NEW) : construit le BootData nouveau joueur (identité +
  `tutorialActs` depuis le registre du jeu).
- `server/java/dhserver/LoginServer.java` : `main` envoie le BootData nouveau joueur (via `NewUserState`).
- `docs/SERVER_PLAN.md`, `docs/PROTOCOL.md` §6, `MEMORY.md` : étape 3 ✅ + correction IntroTutorialActV2.
- Prochain (étape 4) : traiter/persister `ChangeTutorialStep` (aujourd'hui journalisé).

---

## 2026-07-09 — Bootstrap du projet (session initiale)

### Résumé
Mise en place complète des fondations : étude du projet de référence DragonSoul,
récupération et reconnaissance de l'APK Disney Heroes, extraction des données de jeu,
et création du système de mémoire (MEMORY.md + JOURNAL.md) et de la documentation.

### Détails chronologiques

**1. Étude du dépôt de référence DragonSoul.**
- Ajouté `aciderix/dragonsoul-web` à la session et cloné dans `/workspace/dragonsoul-web`.
- La branche par défaut `main` n'a pas `desktop-port/` ; récupéré la branche
  `claude/game-transpile-debug-2p5irx` (`git fetch --depth 1 origin <branch>` → `FETCH_HEAD`).
- Lu les docs clés de `desktop-port/` : `PRINCIPLES.md`, `SERVER_DESIGN.md`, `PROTOCOL.md`,
  `PROGRESS.md`, `STARTING_STATE.md`, `SHIMS.md`.
- **Architecture DragonSoul comprise** : backend desktop LWJGL3 maison (`dsbackend/`)
  remplaçant la couche plateforme Android ; jeu (bytecode) réutilisé tel quel (seul
  traitement autorisé = remap non-sémantique des collisions de noms) ; serveur Java
  (`server/Ds*.java`) réutilisant les classes du jeu (`ServerXORConnectionWrapper`,
  `MessageFactory`, `BootData`) pour une sérialisation identique. Login en 2 étapes
  (POST /login HTTP puis TCP jeu). Codec = Deflate + XOR roulant, clé fixe 8 octets.
  Multi-serveur (passerelle locale, mot de passe HMAC hors protocole, découverte
  Direct/LAN/communautaire, persistance SQLite).

**2. Récupération de l'APK Disney Heroes.**
- Téléchargé depuis Google Drive (`id=1u-3G-aKMfOMuLSEMY7XuvMbk8hWHZmSF`) via le
  contournement de la page « Virus scan warning » (form → `drive.usercontent.google.com`
  avec `confirm=t&uuid=...`). Résultat : `disney-heroes.apk`, **96 Mo**, APK Android valide.
- Contenu : 6 `classes*.dex` (base APK d'un App Bundle → **pas de `.so` natifs** ici,
  ils sont dans les splits par ABI). `assets/{stats,strings,automation,shaders,sound,fonts…}`.
- `assets/info.txt` : `version_name 12.1.0`, `git_commit a53845c9`, build 2023-02-22,
  `env release`. `assets/api_key.txt` (JWT Amazon) → `pkg com.perblue.herocities`.

**3. Reconnaissance du bytecode (strings sur les .dex).**
- **Confirmé : même stack réseau que DragonSoul, mais NON obfusquée.** Classes en clair :
  `com.perblue.heroes.network.messages.MessageFactory`, `...network.messages.*` (ClientInfo1,
  BootData1, ArenaAttack, …), `...network.{XORConnectionWrapper,DHXORConnectionWrapper,
  NetworkProvider,GameServerAddress}`, `com.perblue.common.network.{XORConnectionWrapper,
  XORCipher,XORInputStream,DeflateConnectionWrapper,StackedConnectionWrapper}`,
  `com.perblue.grunt.translate.{GruntMessageFactory,ConnectionWrapper,DummyConnectionWrapper}`.
- **AssetUpdater** identique (gate `MISSING_ADDITIONAL`, `WORLD_ADDITIONAL`, `UI_DYNAMIC`,
  log « Failed to load %s, setting MISSING_ADDITIONAL flag », « loadDynamicUI setting
  MISSING_ADDITIONAL true for »). Contenu LIVE : `http://content.disneyheroesgame.com/live/index.txt`.
- **ServerType** = `com.perblue.heroes.ServerType` (getters `gameHost`, `getGameHost`,
  `contentLocation`, `getContentLocation`). Login : `login.disneyheroesgame.com`
  (+ `login.staging.disneyheroesgame.com`, `dhstaging...:10070/content/beta/index.txt`).
- **PIÈGE identifié** : les classes `gateway/v1/*` (AdRequest, ClientInfoOuterClass,
  InitializationCompletedEventRequest, DiagnosticEvent, DeveloperConsent…) sont le **SDK
  Unity Ads**, PAS le protocole du jeu. Le protocole du jeu reste le binaire
  `MessageFactory` (FULL_NAME façon `BootData1`), comme DragonSoul.

**4. Extraction des données du jeu (source de vérité).**
- Écrit `tools/extract_game_data.sh` : extrait `assets/stats/*` et `assets/strings/*` de
  l'APK vers `game-data/` (principe §4 : aucune donnée recopiée à la main).
- Résultat committé : `game-data/stats/` = **274 `.tab`** (TSV ; ex. `arena_constants.tab`
  = clé + colonnes `FIGHT_PIT_VALUE/COLISEUM_VALUE`), `game-data/strings/` = **325
  `.properties`** (locales incluses). Ces fichiers se chargent **tels quels** côté serveur.

**5. Mémoire projet & docs.**
- Créé `MEMORY.md` (récupération de contexte, tenu à jour) et `JOURNAL.md` (ce fichier).
- Créé `docs/PRINCIPLES.md`, `docs/ARCHITECTURE.md`, `docs/PROTOCOL.md`, `docs/ASSETS.md`,
  `docs/RECON.md`. Créé `.gitignore` (APK/dex/jars/zip non committés — régénérables).
- Conservé en l'état l'infra d'upload archive.org existante (`upload_batch.py`,
  `disney_heroes_live_index.txt`, `index.txt`, workflow) — non déplacée pour ne pas
  casser le workflow.

### Découvertes importantes / risques
- **RISQUE #1 (ouvert)** : incohérence potentielle version APK (12.1.0) vs `index.txt`
  (GameVersion 7.8.1, Revision 325-326). À vérifier : révision de contenu exigée par cet
  APK (extraire de l'AssetUpdater) ↔ assets archivés. Ne pas rustiner le contrôle.
- Avantage majeur vs DragonSoul : **noms non obfusqués** → reverse et réutilisation des
  classes bien plus simples. Et **assets + index.txt disponibles** (archive.org).

### Fichiers ajoutés/modifiés
- `+ MEMORY.md`, `+ JOURNAL.md`, `+ .gitignore`, `+ README.md`
- `+ docs/{PRINCIPLES,ARCHITECTURE,PROTOCOL,ASSETS,RECON}.md`
- `+ tools/extract_game_data.sh`
- `+ game-data/stats/*.tab` (274), `+ game-data/strings/**/*.properties` (325)

**6. Décompilation ciblée (androguard).**
- dex2jar/jadx **indisponibles** : téléchargements GitHub Releases bloqués par le proxy
  (403 « GitHub access to this repository is not enabled »). Contournement : `pip install
  'androguard<4'` (pypi autorisé ; `mutf8` compile via gcc présent).
- Localisé les classes cibles dans `classes4.dex` puis désassemblé (`DalvikVMFormat`) :
  - **`DHXORConnectionWrapper`** : champ statique `KEY` = 8 octets
    `CE 85 D4 F9 29 A8 24 56` ; ctor = `StackedConnectionWrapper(DeflateConnectionWrapper,
    XORConnectionWrapper2(KEY))` ⇒ codec **Deflate + XOR(KEY)** (identique à DragonSoul en
    conception, clé propre à Disney Heroes).
  - **`ServerType`** (ctor `(name, ordinal, protocol, loginHost, port, contentLocation)`) :
    LIVE = `https://` / `login.disneyheroesgame.com` / `443` / contenu
    `http://content.disneyheroesgame.com/live/index.txt`. STAGING, LOCAL (`localhost:8080`),
    NONE/TRUNK/DEV relevés aussi. **APK NON patché** (vrais domaines PerBlue) → redirection
    prévue par réécriture `ServerType` (réflexion) ou passerelle, sans patch bytecode.
- Cherché une **révision de contenu embarquée** (RISK #1) : aucun constant évident ; le gate
  repose vraisemblablement sur des **marqueurs de catégorie** (fichiers repères) comme
  DragonSoul → à confirmer en décompilant `AssetUpdater`. RISK #1 reste ouvert.
- Docs mises à jour : `PROTOCOL.md` (§0, §1.1), `RECON.md`, `MEMORY.md` §3/§7.

**7. Décompilation de l'`AssetUpdater` → RISQUE #1 RÉSOLU.**
Package `com.perblue.heroes.assets_external` : `ExternalAssetManager` (orchestre),
`AssetIndexDownloader` (parse/décide), `ArchiveInfo`, `ContentServerKeys`, `AssetCategory`.
- **`retainRowsForVersion(rows, gameVersion)`** : retire les lignes dont `GameVersion` >
  version du client (`client.compareTo(new VersionNumber(row.GameVersion)) < 0`). ⇒ ne
  bloque que le contenu **futur** ; garde l'égal/plus ancien.
- **`checkArchives`** (par catégorie `shouldDownload()`) : décide **uniquement sur la
  révision** — `getMostRecentCompleteArchive` (Mode==COMPLETE & Category, rev max),
  `getLatestDownloadedRevision` (prefs), `getNeededIncrementalArchives`. Aucun test de
  GameVersion. Logs : « complete download: rev N », « incremental download », « up-to-date! »,
  « no prior complete archive ». Garde-fou `handleBootLoop`.
- **Filtrage device** (`lambda$onComplete$0`) : ne retient que `Environment==LIVE` +
  `Density`/`Compression` du device (SON/TEXT/PNG traités à part). ⇒ servir l'index d'origine
  **tel quel**, le client sélectionne ses lignes.
- **Conclusion RISQUE #1** : APK 12.1.0 > index 7.8.1/7.9 → **toutes les lignes retenues** ;
  install neuve → télécharge `COMPLETE rev 325` puis `INCREMENTAL rev 326`. **L'APK accepte
  les assets archivés.** Le libellé GameVersion de l'index n'est pas un critère de rejet.
  Risque résiduel = complétude **runtime** (à constater en exécutant). Docs : `ASSETS.md`
  (algorithme complet), `MEMORY.md` §7.

**8. Serveur de contenu v0 (`server/content_server.py`).**
- Python stdlib (zéro dépendance → hébergeable partout). Endpoints :
  - `GET /live/index.txt` : sert le manifeste avec les **URLs d'archives réécrites** vers
    ce serveur (`/live/<nom>.zip`) — le jeu filtre lui-même par device/version.
  - `GET|HEAD /live/<nom>.zip` : sert une **copie locale** (`--cache assets-cache/`) si
    présente, sinon **302** vers l'archive publique (archive.org).
  - `GET /health`.
- Config via options/env : `--port/--host/--index/--cache/--archive-base/--rewrite-host`.
- Ajouté `server/run-content-server.sh` + `server/README.md`.
- **Vérifié de bout en bout** (port 8899) : index réécrit (URLs → 127.0.0.1:8899) ; requête
  `.zip` → 302 → archive.org → 200, `Content-Length` 4422179 = colonne `Size` de l'index.
  Confirmé aussi que l'archive.org du projet renvoie bien les fichiers (HEAD 200, tailles OK).

**9. Décompilation en jar régénérable + preuve de réutilisation du codec du jeu.**
- Outillage : dex2jar/jadx GitHub bloqués par le proxy, mais le **fork maintenu
  `de.femtopedia.dex2jar:dex-tools:2.4.28` est sur Maven Central** (accessible). Maven,
  Gradle, javac, jar présents.
- `tools/decompile.sh <apk>` (committé, reproductible) : `mvn dependency:copy-dependencies`
  → lance `com.googlecode.dex2jar.tools.Dex2jarCmd -f -o libs/game.jar <apk>` + copie
  `commons-logging` en `libs/`. Sortie : `libs/game.jar` (~70 Mo, 66 134 classes ; gitignored).
- Vérifs de chargement (JVM desktop) :
  - `javap` OK ; **clé XOR recoupée** (`DHXORConnectionWrapper.KEY` = `{-50,-123,-44,-7,41,
    -88,36,86}` = `CE 85 D4 F9 29 A8 24 56`, identique à la recon androguard).
  - **`VerifyError: Expecting a stackmap frame`** au chargement → résolu par **`-Xverify:none`**
    (bytecode dex2jar sans stackmap frames ; contrôle de *chargement*, pas d'exécution).
  - **`NoClassDefFoundError commons-logging`** → ajouté `commons-logging:1.2` (Maven).
- **Smoke test `server/smoke/CodecRoundTrip.java`** (committé) : instancie deux
  `DHXORConnectionWrapper` du jeu (client/serveur), `server.wrapIn(client.wrapOut(msg)) == msg`
  → **ROUND-TRIP OK**. Wire commence par `78 9C` (en-tête zlib/Deflate). ⇒ **le codec réseau
  du jeu se réutilise tel quel côté serveur** (stratégie validée, comme DragonSoul).
- Docs : `docs/SHIMS.md` créé (contraintes `-Xverify:none` + `commons-logging` + jar
  régénérable). `.gitignore` : ajout `*.class`, `*-error.zip`. `MEMORY.md` §6/§7 à jour.

**10. Sérialisation des messages du jeu prouvée (sans libGDX).**
- Probes JVM desktop (`libs/game.jar`, `-Xverify:none`, `commons-logging`) :
  - `MessageFactory.getInstance()` **OK** et `new BootData()` **OK** → se chargent **sans
    libGDX** (le clinit de MessageFactory enregistre les messages sans dépendance graphique).
  - API sérialisation : `GruntMessage.writeAll(GruntOutputStream)`, `GruntOutputStream.getBytes()`,
    `MessageFactory.readMessage(GruntInputStream)`. `BootData.getFullName()=="BootData1"`.
  - **Round-trip message** (`server/smoke/MessageRoundTrip`) : `BootData`(serverTime=1234567890,
    serverHasArenaSeasons=true, loginEvent="hello") → `writeAll` (4096 o) → `readMessage` →
    champs identiques. **OK**.
- Ajout `server/smoke/MessageRoundTrip.java` ; `run.sh` compile+exécute les 2 smoke tests.
- Docs : `PROTOCOL.md` §2bis (API sérialisation vérifiée) ; `MEMORY.md` §6/§7.
- ⇒ Toute la pile serveur (codec + sérialisation) est **validée avec les vraies classes du
  jeu** : le serveur pourra décoder un `ClientInfo1` et répondre un `BootData1` au format wire
  exact, sans réimplémentation ni libGDX.

**11. Serveur de login v1 (squelette) + handshake TCP prouvé bout-en-bout.**
- Découverte : le jar du jeu contient aussi la **pile SERVEUR** du framework grunt
  (`GruntNIOTCPServer`, `GruntTCPServer`, `GruntUDPServer`, `GruntBuilder`) → on **réutilise
  le serveur du jeu** au lieu de refaire le framing (`packInt`) à la main.
- Obstacles levés (reversés) :
  - `GruntNIOTCPServer` est **package-private** sans fabrique publique → ajout de
    `com.perblue.grunt.translate.GruntServerFactory` (classe dans le même package, pas une
    modif du jeu) pour l'instancier. Ctor : `(port, factory, executor, connectionListener,
    wrapperClass, sendTimeout, keepAlive, noDelay, useProxyProtocol, bufferSize)` (mappé par
    décompilation).
  - Le ctor **crée le thread NIO mais ne l'active pas** : `running=false` (AtomicBoolean) et
    `thread` non démarré. Diagnostic clé : premier essai en TIMEOUT (client TCP-connecté via
    backlog kernel mais aucun accept applicatif). Fix : lever `running` (réflexion) + démarrer
    le thread daemon. → handshake OK.
- `server/java/dhserver/LoginServer.java` : sur `ClientInfo` reçu, répond un `BootData`
  (`setAsReplyTo` + `send`) via `MessageFactory` + codec `DHXORConnectionWrapper` du jeu.
- **Smoke test `server/smoke/HandshakeRoundTrip`** : client `GruntBuilder` envoie
  `ClientInfo1`, `LoginServer` répond `BootData1`, le client décode (serverTime/loginEvent
  corrects) — **sur socket TCP réelle, sans libGDX**. `run.sh` compile `server/java` + lance
  les 3 smoke tests (codec, message, handshake) : **tous OK**.
- Docs : `PROTOCOL.md` §2ter, `SHIMS.md` (GruntServerFactory + 3 smoke tests), `server/README.md`,
  `MEMORY.md` §6/§7. `.gitignore` : `/server/smoke/out/`.

**12. Artefacts committés + démarrage du port desktop.**
- **APK + jar décompilé committés** (demande utilisateur : éviter re-téléchargement/décompilation
  après reset) : `game/disney-heroes-12.1.0.apk` (~92 Mo), `libs/game.jar` (~68 Mo),
  `libs/commons-logging.jar`. `.gitignore` : exceptions. `tools/{extract_game_data,decompile}.sh`
  par défaut sur `game/disney-heroes-12.1.0.apk`. Push OK (warnings GitHub >50 Mo, sous la
  limite dure 100 Mo).
- **Rendu headless FAISABLE et prouvé** (corrige mon caveat précédent) : conteneur = Mesa
  (`libgl1-mesa-dri`, `mesa-libgallium`, llvmpipe) + `Xvfb`. `desktop-port/GLSmokeTest` +
  `run-gl-smoke.sh` : `GL 4.5 llvmpipe` sous Xvfb, frame rendue (`glError=0`), capture PPM.
  ⇒ on peut lancer ET vérifier le jeu en headless + captures (comme l'agent DragonSoul).
- **Scaffold `desktop-port/`** : Gradle (LWJGL 3.3.4 + natifs libGDX **1.9.7** [version du jeu,
  ≠ 1.9.3 DragonSoul] + stubs Android + game.jar). `settings.gradle`, `run-gl-smoke.sh`.
- **Découvertes majeures (recon jar) → stratégie révisée** (`desktop-port/PROGRESS.md`) : le jar
  embarque **`com.badlogic.gdx.backends.lwjgl.LwjglApplication`** (backend desktop LWJGL2 →
  **757 classes `org/lwjgl`**, d'où la collision de compilation du smoke test),
  **`HeadlessApplication`**, le root **`com.perblue.heroes.GameMain extends ApplicationAdapter`**,
  et un **framework d'automatisation** `com.perblue.heroes.automation.*` (`TouchRecorder`,
  `TouchPlayback`) + `automation.crawler.*` (`CrawlerNavigation`, `CrawlerScript`, scripts
  `ChestBuyScript`/`GoldScript`/`MarketBuyScript`…). ⇒ **le pilotage "sait ce qui est cliquable
  et exécute des actions" existe déjà** dans le jeu ; et on peut **réutiliser le backend bundlé**
  (`LwjglApplication`) au lieu d'écrire un backend complet → bien moins de shims que DragonSoul.

**13. Launcher desktop écrit + débogage de boot → VERDICT backend.**
- Écrit `dhdesktop/DesktopLauncher.java` (construit `GameMain(new DhDeviceInfo())` ; redirection
  `ServerType.LIVE` par réflexion via `-Ddh.server`), shim `dhbackend/DhDeviceInfo.java`
  (implémente l'interface `DeviceInfo`, valeurs FACTICE cohérentes, `Platform.ANDROID`),
  `run-desktop.sh` (Xvfb + llvmpipe + extraction assets/ressources APK + classpath). GL smoke
  test déplacé en `diag/`.
- Itérations de boot (chaîne réellement atteinte) :
  1. Backend **LWJGL2 bundlé** : natifs stock incompatibles avec les classes `org/lwjgl`
     **réduites par ProGuard** dans game.jar (`PointerWrapper.getPointer()` absente) → shadow
     par LWJGL 2.9.3 stock ; puis **`LinuxDisplay.getAvailableDisplayModes` AIOOBE sous Xvfb**
     (Display X11 LWJGL2 = hostile headless) + audio absent.
  2. Backend **LWJGL3 Maven** (GLFW, headless-friendly) : GLFW init OK → atteint
     `GameMain.<clinit>` → `NoSuchFieldError Group.DEFAULT_TRANSFORM` (**PerBlue a AJOUTÉ des
     champs au core libGDX**). En gardant le core PerBlue : le backend stock appelle
     `InputEventQueue.setProcessor(...)` **absente** du core PerBlue (RÉDUIT par ProGuard).
- **VERDICT** : core libGDX PerBlue **modifié ET réduit** → aucun backend/core stock ne matche.
  Comme DragonSoul, il faut un **backend maison LWJGL3** implémentant les interfaces du core du
  jeu. **Décision : adapter le `dsbackend/` de DragonSoul** (même core PerBlue) plutôt que
  repartir de zéro. Launcher + `DhDeviceInfo` + extraction assets + redirection `ServerType`
  déjà écrits = réutilisables. Détail complet : `desktop-port/PROGRESS.md`.

**14. Backend LWJGL3 maison écrit → le jeu BOOTE jusqu'à l'écran de chargement.**
- Étude de portabilité de `dsbackend/` d'abord (à la demande) : PAS réutilisable tel quel
  (noms libGDX obfusqués 1.9.3, API RPGMain, `getType():int`) mais MÊME fork libGDX PerBlue →
  jeux d'interfaces coïncident. Méthode : régénérer chaque shim contre l'interface RÉELLE de DH
  (`javap`, noms clairs) + porter le corps depuis dsbackend.
- Backend écrit (`desktop-port/src/main/java/dhbackend/`) : `DhGL20` (75 méth, délègue LWJGL3),
  `DhGraphics` (19, +GLVersion réel), `DhInput` (17) + `GlfwInput`, `DhFiles`/`DhFileHandle`,
  `DhPreferences` (19), `DhApplication` (18, getType=Android), `DhDeviceInfo`, `DhAudio` (STUB
  no-op), `DhNet` (STUB), `DhBridges` (NO-OP proxies), `DhStatFileExt` (ouvre les `.tab`).
  Launcher `dhdesktop/DesktopLauncher` : GLFW+GL, câble `Gdx.*`, `GameMain(DhDeviceInfo)`,
  boucle create()/render() + capture PPM + redirection `ServerType` (`-Ddh.server`).
- Build : LWJGL3 brut + natif libGDX 1.9.7 + stubs Android + `game-logic.jar` (game.jar SANS
  `org/lwjgl` ni backends bundlés — sinon shadowing de nos classes LWJGL3). `tools/fetch_assets.sh`
  télécharge le contenu ETC1 initial depuis archive.org (le boot exige des assets hors APK).
- Débogage de boot itératif (murs franchis) : GLVersion null → réel ; `StatFileHelper.getOpener()`
  null (=champ EXT) → `DhStatFileExt` via `setExt`, lecture des `.tab` depuis `stats/` (classpath).
- **Résultat** : `GameMain.create()` **complète** (compression **ETC1**, RPGAssetManager, shaders,
  viewport, UI stats **XHDPI 1280×720**) puis `render()` → **LoadingScreen** exécute ses tâches
  (`LoadBootAtlasUI`, `LoadPerBlueUI`, `ShowDisneyLogo`, `StartServerLogin`) et **rend des frames**.
  Bloqué sur `DhNet` (login, #NET) + `android.os.SystemClock.elapsedRealtimeNanos()` absent des
  stubs API 16 (#ANDROIDSTUBS). Tous les shims/deferrals tracés dans `desktop-port/BACKEND_STATUS.md`.

### Login → BootData → MainScreen de bout en bout (reframe ASM + Firebase + bridges)
- **Reframe ASM (RÉEL, sans changement de sémantique)** : game.jar (dex2jar) n'a pas de
  `StackMapTable` → sous `-Xverify:none` la JVM plantait (`generateOopMap.cpp`, « Illegal class
  file ... in method loadBinaryData ») en parsant `unit_abilities.tabb` pendant le handshake.
  `tools/reframe/src/ReframeJar.java` (ASM 9.7, COMPUTE_FRAMES) réécrit les **63 249** classes
  avec des frames valides (hiérarchie résolue depuis les octets, sans lier — repli sûr par classe).
  `run-desktop.sh` produit `game-logic-framed.jar` (ombrage classpath) et **retire `-Xverify:none`**.
  ⚠️ ce n'est PAS une rustine : aucune logique modifiée, on ajoute seulement les métadonnées de
  vérif que dex2jar omet (équivalent recompilation) — et on RE-vérifie le bytecode.
- **Shadow `com.google.firebase.perf.network.FirebasePerfUrlConnection` (RÉEL)** : le
  téléchargeur du jeu enveloppe chaque connexion par `instrument()` (télémétrie Firebase) ; l'init
  Firebase touchait `android.os.StrictMode.allowThreadDiskReads()` (« Stub! ») et TUAIT le thread
  de download. `instrument()` renvoie la connexion RÉELLE inchangée → download HTTP réel, analytics
  externe neutralisée (#BRIDGES). Le contenu requis se télécharge (index.txt 62 968 o).
- **DhBridges (PARTIEL, services plateforme absents)** : `INative.createPurchasingInterface()`
  renvoyait `null` → `setNativeAccess` faisait NPE (avalé) puis `handleBootData` NPE sur
  `purchasing`. `defaultReturn` renvoie désormais un **no-op imbriqué** pour tout retour interface
  (et collections vides pour Set/List/Map). ⚠️ à auditer : l'enum `PurchaseErrorState` renvoyée
  par `startPurchase` (1ʳᵉ constante) ne doit pas être un état « succès ».
- **RÉSULTAT — jalon majeur** : le vrai client fait `/login` sur notre serveur → se connecte en
  TCP `:8081` → **notre LoginServer envoie BootData1** → le client appelle **`handleBootData`
  sans crash** (langue serveur, offerwall, rewards initialisés) → atteint le **MainScreen** (hub,
  chargement du monde `mainscreen_winter`). Capture `desktop-port/build/online.png`.
- **Bug en cours (à corriger PROPREMENT, pas contourner)** : les `.skel` du décor échouent avec
  `NoSuchMethodError: com.badlogic.gdx.utils.DataInput.readString()` — incompatibilité entre
  `SkeletonBinary` (spine-libgdx) et le `DataInput` MODIFIÉ de PerBlue (ProGuard). Le jeu tolère
  ces assets manquants (userErrorListener) mais le décor animé ne s'affiche pas → à réparer.
- **⚠️ À valider (règle « pas de faux OK »)** : (1) cparticle reste un STUB (rendu différé,
  `update→complete=true` pourrait avancer une logique gatée sur une particule) ; (2) le BootData
  de notre serveur doit être **complet et correct** (serveur autoritatif), pas « minimal pour
  atteindre le menu ».

### Modules natifs Spine + particules réimplémentés en Java (#SPINE ✅ / #CPARTICLE ⚠️)
- **#SPINE résolu (Option A)** : les natifs Spine de PerBlue (`libspine-native64.so`, absents des
  splits x86_64) sont remplacés par un module Java **`com.perblue.heroes.cspine.*`** (shadow
  classpath) au-dessus de **spine-libgdx 3.6.53.1** — 12 classes : `Native` (coquille, plus aucun
  `.so`), `NativeAtlas`/`NativeAtlasLoader`, `NativeSkeletonData`/`Loader`, `NativeSkeleton`,
  `NativeAnimationState`/`Data`, `NativeSkeletonRenderer` (`Mesh` 2-couleurs `a_position/a_light/
  a_dark/a_texCoord0` dessiné avec le shader de `ShaderChannels`). Détail clé : le jeu suffixe les
  atlas/skel Spine par `@native` ; comme l'original, on retire ce suffixe (`lastIndexOf('@')` +
  `substring` + re-resolve) avant d'ouvrir le vrai fichier. Constantes GL effacées par ProGuard
  → littéraux (`GL_BLEND=0x0BE2`…). `mesh.render(shader, GL_TRIANGLES, 0, n, true)` (signature 5-args
  de PerBlue).
- **#CPARTICLE (partiel)** : découverte d'un **2ᵉ moteur natif** — `com.perblue.heroes.cparticle.*`
  (format `.np` binaire propriétaire, dérivé au build de `ParticleConverter`, pas de runtime Java
  prêt). Les wrappers `NativeParticleEffect`/`Pool`/`Loader`/`Renderer` sont de fins JNI → on shadow
  la SEULE classe **`cparticle.Native`** (pur Java, sans `.so`) : `Effect_create`→handle non nul,
  `getVertices`→0, `update`→complete. Les `.np` se **chargent** (octets réels lus) mais ne sont pas
  encore **simulés** (aucun rendu). Débloque le boot ; simulation réelle = chantier suivant. PAS de
  rustine (aucune donnée de jeu falsifiée, seul un effet cosmétique n'est pas encore affiché).
- **Résultat** : le boot franchit `WaitForDisneyAnimation` + `WaitForPerBlueAnimation` sans crash
  (capture `desktop-port/build/spine-test.png`). Reste bloqué au login en mode OFFLINE (attendu :
  `run-desktop.sh` sans `DH_SERVER` vise `login.disneyheroesgame.com`).

### Login → BootData → MainScreen bout-en-bout + Spine réparé (ABI PerBlue)
- **Reframe ASM de game-logic.jar** (`tools/reframe/ReframeJar`) : recalcule les StackMapTable de
  toutes les classes (dex2jar les omet) → plus de crash JVM `generateOopMap.cpp` (« Illegal class
  file … loadBinaryData ») sur le parse des stats binaires ; on retire `-Xverify:none`. Sémantique
  inchangée (métadonnées de vérif seulement) — c'est la solution durable prévue dans SHIMS.md.
- **Shadow `FirebasePerfUrlConnection.instrument`** → renvoie la connexion réelle (désactive
  l'analytics Firebase qui plantait le thread de téléchargement via le stub `StrictMode`). Le
  téléchargement HTTP du contenu requis reste réel (#BRIDGES).
- **DhBridges** : `INative.createPurchasingInterface()` (et toute méthode renvoyant une interface)
  renvoie un NO-OP imbriqué au lieu de `null` → `setNativeAccess` ne lève plus, `handleBootData`
  n'a plus de NPE `purchasing`. **Résultat : login → BootData → `handleBootData` → MainScreen** de
  bout en bout avec NOTRE serveur (le client se connecte à :8081, reçoit notre BootData1).
- **Spine réparé (ABI)** — les `.skel` échouaient sur `NoSuchMethodError` (tous : logos, décor,
  **héros de combat**). Deux causes, deux vrais correctifs :
  1. `com.badlogic.gdx.utils.DataInput` de PerBlue réduit par ProGuard (plus de `readString()`/
     `readInt(boolean)` var-int, requis par spine) → **shadow** avec l'implémentation EXACTE de
     libGDX 1.9.7 (self-contained).
  2. spine-libgdx 3.6.53.1 (Maven) est compilé contre le gdx STOCK, mais PerBlue a modifié l'ABI
     (ex. `Array implements Collection` → `add(Object)` renvoie `boolean`, pas `void`) → **patcheur
     ASM** `tools/reframe/PatchGdxCalls` réécrit les 106 appels gdx divergents sur les descripteurs
     réels de game.jar (+ `POP` si `void`→valeur) → `spine-libgdx-perblue.jar`.
  ⇒ **0 échec de chargement `.skel`**, squelettes chargés ET animés, jeu au **MainScreen** interactif.
- **#CPARTICLE** : toujours en dette (#NP-V3, format `.np` v3 ≠ writer courant, cf. NP_FORMAT.md).
  Le stub cparticle.Native reste en place (étiqueté) pour que le jeu tourne ; particules non rendues.

### Point de reprise
Modules natifs Spine/particules franchis. **Prochaine étape** : lancer **`run-online.sh`**
(contenu `:8080` + login + serveur de jeu `:8081`, `DH_SERVER`) → franchir le login
(`ClientInfo1`→`BootData1`), atteindre le menu / le tutoriel `IntroTutorialActV1` (nouveau joueur,
BootData neuf — NE PAS seeder) et capturer. Voir `desktop-port/BACKEND_STATUS.md` (#SPINE ✅,
#CPARTICLE ⚠️, #AUDIO, #BRIDGES).

### Règle renforcée + jeu original en natif (2026-07-11)
- **Règle d'or clarifiée** (PRINCIPLES §4/§4bis) : on ne réécrit RIEN du jeu à la main — on **extrait**
  (données ET code) par commande. Seule couche manuelle = plateforme (`dhbackend/`), minimale. Binaires
  natifs = code du jeu → binaire d'origine, sinon rebuild **vérifié fidèle** (désassemblage lib
  d'origine), jamais inventé. **Fidélité vérifiée contre captures du jeu original**.
- **Bascule sur le code d'origine natif** : suppression des shadows Java (cspine, spine-libgdx, DataInput) ;
  `spine-native64.so` (spine-c officiel + interface JNI exacte de PerBlue) branché via le
  `SharedLibraryLoader` du jeu → `cspine.*`/`cparticle.*` d'ORIGINE tournent. MainScreen rendu, 0 crash.
- **Dettes de fidélité identifiées** (à corriger par extraction, pas invention) : `getVertices` (banding =
  drawCalls multi-pages à rendre fidèles) ; `cparticle` (échafaudage neutre → vrai moteur). Source de
  vérité = **désassemblage de la lib `spine-native` ARM d'origine**.

### cspine : banding CORRIGÉ — drawCalls multi-pages fidèles (2026-07-11)
- **Cause du banding** : l'ancien `Skeleton_getVertices` renvoyait 1 seul draw call (`draws[0]=nb
  sommets`, 2e short non initialisé) → le renderer liait UNE seule page de texture pour tout le
  maillage. Toute géométrie d'une autre page d'atlas s'affichait avec la mauvaise texture (bandes).
- **Contrat drawCalls EXTRAIT** (pas deviné) du bytecode EN CLAIR
  `com.perblue.heroes.cspine.NativeSkeletonRenderer.renderPreparedVertices` (javap -c) :
  `drawCount = getVertices(verts, indices, drawCalls)` ; `drawCalls.position(0)` ; boucle `drawCount`
  fois : `indexCount = drawCalls.get()` ; `tex = textures.get(drawCalls.get())` ; `tex.bind()` ;
  `mesh.render(shader, GL_TRIANGLES=4, indexStart, indexCount, false)` ; `indexStart += indexCount`.
  ⇒ `drawCalls` = N paires de shorts `(indexCount, texturePageIndex)`, `getVertices` renvoie N.
  `texturePageIndex` = index **0-based** dans `NativeAtlas.getTextures()` (`Array.get(int)`), dont
  l'ordre = pages via `Atlas_getTexture(handle, 0..n)` dans `NativeAtlas.load` (boucle en clair).
- **Implémentation** (`native/src/cspine_jni.c`) :
  - `Atlas_create` tague chaque page par sa **position 0-based** dans `page->rendererObject`
    (même parcours de liste chaînée que `Atlas_getTexture` → indices alignés avec `getTextures()`).
  - `attachmentPage()` : `spRegionAttachment/spMeshAttachment->rendererObject` (= `spAtlasRegion`)
    `->page->rendererObject` = pageIndex.
  - `buildVertices` émet les tris **dans l'ordre de dessin** (draw order) et ouvre un nouveau draw
    call à chaque changement de page ; les attachments consécutifs sur la même page fusionnent
    (indexCount cumulé). Renvoie le nombre de draw calls.
  - **`bufferSetLimit`** : le natif écrit en mémoire brute (`GetDirectBufferAddress`) sans toucher
    `position/limit` des `java.nio.Buffer` ; or le chemin VertexArray de `Mesh.render` fait
    `indices.getBuffer().position(offset)/limit(offset+count)` → il FAUT que `limit` couvre tout
    l'écrit. On appelle `Buffer.position(0)/limit(n)` (descripteur `(I)Ljava/nio/Buffer;` stable) sur
    verts (=nb floats), indices (=nb indices) et drawCalls (=N*2). Sans ça : `IllegalArgumentException:
    newPosition > limit` dès le 2e draw call (offset>0). C'est le comportement du natif d'origine.
- **Résultat** : `run-online.sh` (Xvfb+llvmpipe, 150 frames) → **splash MainScreen rendu sans banding**,
  tous les héros Spine (multi-pages) corrects, logo/ballons/confettis nets. 0 crash de rendu.
  Capture de référence : `native/reference/shots/mainscreen-nobanding.png`.
- Reste : cparticle (échafaudage neutre → moteur fidèle via oracle ARM) ; extensions cspine
  (`setSlotEyeState`, `setTintBlack`, `nextEvent`) à confirmer contre la lib ARM.

### cparticle : format `.np` EXTRAIT + #NP-V3 confirmé (2026-07-11)
- **Source d'écriture EN CLAIR trouvée** : `ParticleConverter.convertFileNative` →
  `ParticleEffect.saveBinary(ParticleEffectPacker)` → `ParticleEmitter.saveBinary` (+ `*Value.saveBinary`,
  `packer.writeTimeline/writeTimelines`) dans game.jar. Donne l'en-tête (`byte 0, byte 3, int count`),
  les formats de valeurs (Ranged=10o, Scaled=32o, Numeric=5o, Gradient=13o, SpawnShape), et le **pool de
  timelines différé** par emitter (poolSize, tagLen, pool floats, atlasTag).
- **Lecteur natif désassemblé** (`ParticleEmitter::load` @ 0x19755, ARM). Helpers classés :
  `readInt` @0x1a770 = 4 o **BIG-ENDIAN** (`rev`), `readBool` @0x1a0c4 = 1 o, `readRanged` @0x19fd0 = 10 o,
  `readScaled` @0x1a020 = 32 o — **formats IDENTIQUES** au `*Value.saveBinary` clair. Registres : `r4`=
  readScaled, `fp`=read4 (préservé). L'encodage bas niveau est donc certifié.
- **#NP-V3 CONFIRMÉ (2 fois)** : parse des 535 assets `.np` réels avec (a) l'ordre du `saveBinary`
  COURANT → **0/535** EOF-exact ; (b) l'ordre reconstruit statiquement depuis la séquence d'appels du
  lecteur natif → **0/535**. Les FORMATS matchent ; seul l'ORDRE/ensemble des champs diffère (le writer
  courant a évolué). ⇒ Ne PAS implémenter un parseur deviné (PRINCIPLES §2/§4).
- **Voie suivante SANS devinette** : (1) oracle d'exécution (faire tourner le vrai `ParticleEffect::load`
  sous qemu → struct parsée = vérité bit-à-bit) ; OU (2) auto-parse validé par l'invariant des offsets de
  pool (chaque triplet timeline `(N, offA, offB)` doit égaler le curseur de pool courant) → l'ordre correct
  = celui qui donne 535/535. Détail complet : `desktop-port/NP_FORMAT.md`.

### cparticle : oracle d'exécution qemu — avancement (2026-07-11)
- But : exécuter le VRAI `ParticleEffect::load`/`ParticleEmitter::load` (ARM) sous qemu sur de vrais
  `.np` → struct parsée + octets consommés = **vérité bit-à-bit** de l'ordre v3 (pas de devinette).
- `native/oracle/harness_np.c` : dlopen + ctor `ParticleEmitter` + `load(&cursor,&remaining)` + dump.
- **Corrections du shim bionic** (`gen_shim.py`) qui ont supprimé le SIGSEGV initial :
  1. `__aeabi_mem{cpy,move,set,clr}{4,8}` (supposent l'alignement, fautent sous qemu sur la source non
     alignée du pool `.np`) → repointées vers la variante NON alignée de base (même sémantique).
  2. Repli **no-op** pour tout symbole absent de la glibc (ex. `_zf_log_write`, log Android) au lieu
     d'un pointeur NULL (crash). 3. Littéral `.word` EMBARQUÉ dans chaque trampoline naked (le pool
     distant cassait l'assemblage « pool needs to be closer »).
- **État** : plus de crash, mais **hang** dans `operator new` (boucle retry malloc @0x31406 ; helpers
  résolus : `0x155b4`=malloc PLT, `0x15974`=`std::get_new_handler`) → `malloc` échoue sur une taille
  aberrante. Cause probable : membres C++ non initialisés (Effect::load malloc les emitters SANS ctor)
  ou ctor `C2` (base) insuffisant / désalignement sous émulation. ← À FINIR : ctor complet `C1`, ou
  zéro-init + trace du site d'appel `operator new` ; sinon poursuivre l'auto-parse validé par
  l'invariant des offsets de pool (approche #2, pur Python). Détail : `desktop-port/NP_FORMAT.md`.

### PIVOT unidbg : exécuter le binaire d'origine + intégration en jeu (2026-07-11)
- **Décision (user)** : « 100% origine ». On exécute le VRAI `libspine-native.so` (ARM) via unidbg au
  lieu de rebâtir spine / RE les particules.
- **Prototypes `native/unidbg/`** (SpineUnidbg/SpineLoad/SpineBench/SpineVerts2/SpineSkel) :
  - Charge la lib d'origine, `Spine_init` OK.
  - `Atlas_create`+`Effect_create` parsent de vrais assets (err="") → **#NP-V3 résolu par exécution**.
  - `Effect_getVertices`/`Skeleton_getVertices` rendent de vrais sommets 2-couleurs.
  - Perf unicorn : particules ~141 µs/frame/effet (~118/frame) ; spine ~1.5-2.1 ms/squelette (7-11/frame).
  - dynarmic (JIT) : crash NEON (`vldr d16`, registres d16-d31 non activés) → inutilisable.
  - Trou unidbg comblé : `GetDirectBufferAddress`/`Capacity` (JNI 230/231) implémentés via `ArmSvc`
    écrasant les slots de la table JNIEnv (renvoie le pointeur émulé du DvmObject).
- **Intégration en jeu** : `dhbackend/unidbg/UnidbgVM` (VM persistante, dispatch synchronisé mono-thread,
  buffers émulés réutilisés + recopie vers les FloatBuffer/ShortBuffer du jeu) ; shadows
  `com.perblue.heroes.cspine.Native`/`cparticle.Native` (mêmes signatures, dispatch → UnidbgVM ; câblage,
  pas récréation) ; `build.gradle` += `unidbg-android:0.9.8` (hôte unicorn embarqué → autonome) ;
  `run-desktop.sh` : `-Ddh.spinelib=native/reference/libspine-native.so`, retrait du build spine-native64.so.
- **État** : compile ; le jeu boote et tourne à travers unidbg **sans crash** jusqu'au splash (capture
  `desktop-port/build/unidbg.png`). **0 appel natif spine/particule** durant le run → écran encore au
  pré-download (art statique), pas de squelette vivant. ⚠️ Rendu spine in-game **à valider** en atteignant
  un écran héros/combat (dépend du pipeline d'assets WORLD_ADDITIONAL). Puis comparer aux captures
  d'origine (§4bis) + mesurer le fps réel.

### JEU DANS LE HUB PRINCIPAL — spine + particules d'origine via unidbg (2026-07-11)
- **Serveur de contenu = RELAIS** : le 302 vers archive.org échouait (le java.net du jeu n'a pas de
  proxy). Le serveur relaie désormais (fetch via proxy côté serveur, stream au client). Le jeu
  télécharge WORLD_ADDITIONAL (394 Mo) etc. et **franchit la barrière de download**.
- **Rendu particules corrigé** : `NativeParticleEffect.getVertices` lit `drawCalls.get(n*3)` = nombre
  total de sommets (écrit par le natif APRÈS les n*3 shorts de draw calls) pour poser verts.limit/
  indices.limit. On copie donc **n*3+1** shorts (≠ spine : 2/pair). Buffers émulés dimensionnés pour le
  plus grand mesh (particules 4000 v). Plus de crash `newPosition > limit`.
- **✅ RÉSULTAT** : le jeu atteint le **HUB PRINCIPAL** (ville Zootopia enneigée, menu HEROES/ITEMS/…,
  CAMPAIGN/CRATES, nouveau joueur CHOOSE NAME) — capture `native/reference/shots/mainscreen-hub-unidbg.png`.
  Confirmé par logs `[UnidbgVM]` : `Skeleton_getVertices -> drawCount=39` (spine d'origine rendu) +
  `Effect_getVertices` (particules d'origine) — **100% code d'origine via unidbg, 0 crash de rendu**.
- ⚠️ RESTE (serveur) : la connexion TCP tombe après BootData → overlay **« Reconnecting… »**. Le
  serveur de jeu doit MAINTENIR la session (keepalive + messages post-BootData). Et le désaccord de
  stats (`GuildStats` NumberFormatException) → le serveur doit **synchroniser les stats** (SyncStatData,
  extraites du jeu). Prochaines étapes serveur.

### Serveur : session STABLE — écho Ping (2026-07-11)
- `LoginServer` instrumenté : journalise chaque message reçu (client = source de vérité). Flux nouveau
  joueur relevé (cf. PROTOCOL.md §6) : ClientInfo→BootData, puis télémétrie + **Ping1**.
- **Cause du « Reconnecting… » = Ping non répondu** : le keepalive/latence `Ping` sans écho déclenche le
  chien de garde du client → fermeture. ⇒ serveur échoue Ping (serverReceive/serverTime=now). Résultat :
  **0 reconnexion, session STABLE dans le hub** (capture `native/reference/shots/mainscreen-hub-stable.png`),
  spine+particules d'origine rendus (unidbg). Prochain : BootData complet nouveau joueur → tuto.

### Coffre GRATUIT du tuto débloqué — cause racine du blocage à l'étape GOLD (2026-07-13, nuit)
**Symptôme** : après avoir « ouvert » le coffre GOLD, le tuto (`IntroFeaturesActV2`) ne repartait pas.
Capture (`build/goldstuck.png`) : écran de détail du coffre GOLD (« DIAMOND CRATE »), mention
**« Free in : 1j 23h 46m 28s »**, et un pop-up « You can't do that just yet. Follow the tutorial arrow! »
— le pilote DEV, sans cible, tapait les boutons d'achat payants et se faisait bloquer.

**Ce que le tuto attend** : il exige que le joueur **POSSÈDE Frozone** après le coffre GOLD
(`checkForMissingFrozone`→`finishTutorial` si absent ; `HERO_LIST_TAP_FROZONE` requiert
`getHero(FROZONE)!=null`). Il était bloqué à l'étape 9 (serveur : `INTRO_FEATURES -> step 9`).

**Mécanisme réel** (décompilation, source de vérité) :
- `ChestHelper.openChest(...)` construit **toujours** un `BuyChests` (avec `ServerRollRequest` CHEST) et
  appelle `ServerRollHelper.sendRollRequest` → `NetworkProvider.sendMessage`. Le serveur répond
  `LootResults{heroesUnlocked=[Frozone]}` ; le client ajoute Frozone → le tuto avance.
- MAIS l'ouverture n'a lieu que si le coffre est **gratuit et disponible** :
  `ChestHelper.getTimeUntilNextFreeChest = UserHelper.getResourceGenerationRemaining(freeChestResource)`,
  et `getFreeChestResource(GOLD) = ResourceType.GOLD_CHEST`. `hasFreeChest(GOLD)` ⇔ `getResource(GOLD_CHEST) >= 1`.

**Cause racine** : le coffre gratuit est une **ressource régénérée** (comme la stamina). Notre amorce
compte-neuf (`ServerUser.initNewPlayerResources`) ancrait le gen-time de toutes les ressources régénérées à
la création MAIS ne mettait au cap **que STAMINA**. Donc `GOLD_CHEST=0`, gen-time = maintenant → « Free in
~48 h » → `hasFreeChest=false` → le bouton FREE n'ouvre rien → **aucun `BuyChests` envoyé** (confirmé : le
`LoginServer` journalise chaque message reçu ; **0 `BuyChests`, 0 `ServerRollRequest`** dans le run — que
`ChangeTutorialStep`×69 + télémétrie + 1 `Action` VIEWED_CHESTS) → Frozone jamais accordé → tuto bouclé.

**Correctif** (fidèle, PRINCIPLES §3 « lire & exécuter ») : à t=création aucun temps ne s'est écoulé → un
compte neuf démarre **chaque ressource régénérée à SON cap** (généralisation exacte du fix stamina).
Boucle unique : `setLastResourceGenerationTime(rt, creation)` + `setResource(rt, getResourceCap(rt))` pour
chaque `resourceGenerates(rt)`. Caps du jeu au niv.1 (probe) : STAMINA=120, GOLD_CHEST=1, SILVER_CHEST=1,
SOCIAL_CHEST=1, SKILL_POINTS=50, SOUL_CHEST=0, FRIEND_STAMINA=175, INVASION_STAMINA=80… **Aucune valeur
inventée** (caps issus de `UserHelper.getResourceCap`).

**Vérifications** :
- `hasFreeChest(GOLD)=true`, `GOLD_CHEST=1`, `SILVER_CHEST=1`, `STAMINA=120`, roster=2 (probe).
- `ServerUser.openChest(BuyChests{GOLD})` d'un compte neuf → **Frozone 8/8** (la table de drop GOLD du jeu
  est déterministe pour le nouveau joueur — le serveur exécute `DropTable.rollNode("ROOT")`, rien de truqué).
- `server/smoke/ResourceTest` étendu (assertions `GOLD_CHEST=1`, `SILVER_CHEST=1`, `hasFreeChest(GOLD)`),
  `RosterTest`/`ViewedChestsTest` toujours OK.

**Chaîne complète attendue en jeu** : coffre GOLD dispo → clic FREE → `BuyChests` → serveur
`LootResults{Frozone}` → `getHero(FROZONE)!=null` → tuto passe à `HERO_LIST_TAP_FROZONE`. Fichiers :
`server/java/dhserver/ServerUser.java` (initNewPlayerResources), `server/smoke/ResourceTest.java`.

### Pipeline de combat de CAMPAGNE côté serveur — recordOutcome autoritatif (2026-07-14)
**Objectif** (demande utilisateur) : valider le pipeline de combat de campagne — entrée, choix héros,
skills, vagues, loot (gold/items), récompenses, XP, conso énergie.

**Protocole (décompilation, client = source de vérité)** :
- Le combat tourne CÔTÉ CLIENT (unidbg spine, déjà fonctionnel depuis l'intro).
- `CampaignAttackScreen.doCombatDone` → `ClientNetworkStateConverter.getCampaignAttack(user, type, ch, lvl,
  outcome, stars, attackers, stagesCleared, screen, snapshot)` qui **roule `CampaignHelper.recordOutcome`
  côté client** (optimiste) puis `NetworkProvider.sendMessage(campaignAttack)`.
- `CampaignAttack{base:AttackBase(attackers,defenders,outcome,stars,…), campaignType, chapter, level,
  lootEarned, memoryChanges, stagesCleared}`. `attackers/defenders` = Collection de **`AttackLineupSummary`**
  (`{List units:AttackUnitSummary}`), une par vague. **Aucun champ roll/seed** (≠ BuyChests) ; **aucun
  listener client** pour la réponse → **fire-and-forget**.

**Logique autoritative = `CampaignHelper.recordOutcome`** (à exécuter, PRINCIPLES §3) :
consomme la stamina (`getStaminaCost`+`UserHelper.chargeUser`), donne loot/gold/XP
(`giveLoot`/`giveGold`/`giveTeamXP`), met à jour `ICampaignLevelStatus`. `IUser extends IGuildPerkProvider`
→ `recordOutcome(user, user, …)`. Param 11 = `SpecialEventSnapshot` : **`.NONE`** (déréférencé sinon NPE).

**Implémenté** : `ServerUser.recordCampaignAttack(CampaignAttack)` + branche `LoginServer` (fire-and-forget,
persiste). Reconstruit le User, résout `CampaignLevel.of(GameMode.CAMPAIGN/ELITE_CAMPAIGN, ch, lvl)`,
appelle `recordOutcome`, resync + save.

**Vérifié** `server/smoke/CampaignAttackTest` (NORMAL 1-1, WIN, équipe Ralph+Elastigirl+Frozone) :
- **énergie -6** (120→114 ; `staminaCost=6`)  — conso énergie ✓
- **or +340** (0→340 ; `giveGold`→`giveUser`, appliqué direct)  — récompense ✓
- **niveau 1-1 → 3★** (`ICampaignLevelStatus`)  — progression ✓
- team XP donné (pas assez pour monter de niveau) ; `lootEarned`=0 objet (normal en 1-1, items seulement).
ResourceTest/RosterTest toujours OK.

**PARTIEL (SHIMS)** : `SpecialEventSnapshot.NONE` (pas de bonus évènement) ; outcome/stars = ceux du client
(combat client-autoritatif, comme le jeu d'origine) ; contrat fire-and-forget à reconfirmer en jeu.

**Frontière pilote DEV (non bloquant pour le serveur)** : l'auto-pilote ne sait pas ENTRER dans un chapitre.
Observé (recorder) sur `CampaignScreen` : animation « SCANNING CITY MAP », `getPointers()` **vide** headless,
nœud `CAMPAIGN_CHAPTER_ONE_NAME` = `Stack childrenOnly` + label `disabled` sans `[CLICK]` (input carte
custom). Le pilote idle → RETOUR → boucle. À résoudre séparément pour la démo visuelle in-game.
Fichiers : `server/java/dhserver/{ServerUser,LoginServer}.java`, `server/smoke/CampaignAttackTest.java`.

### Combat de campagne JOUÉ & GAGNÉ en jeu + progression persistée (2026-07-14, soir)
Suite du pipeline campagne. **Pilote DEV** (entrée niveau) : la carte de campagne est une scène **g2d**
(`CityMapDisplay`), pas du scene2d → aucun acteur cliquable, `getPointers()` vide headless. Découvert via
une **sonde `dh.mapprobe`** (« cliquer + monitorer quel élément s'active » — outil de diag, cf. MEMORY §6ter
B-bis) : le tap est géré par `CityMapScreen` (caméra `MapCamera2D` → `CityMapDisplay.getHitCampaignLevel` →
`onCampaignLevelTapped` → `normalOrEliteNodeSelected(CampaignLevelID)` → `CampaignPreviewScreen`). Le pilote
appelle donc `normalOrEliteNodeSelected(1-1)` (API du jeu). Ajouts pilote : tap flèche « TAP TO CONTINUE »
(fin de vague), tap boutons FIGHT par nom sans pointeur (replay après défaite), garde anti-RETOUR sur
`*AttackScreen`.

**Bug AUTO** (cause de la défaite au 1-1) : `Boolean.getBoolean("dh.autofight")` n'accepte que `"true"` →
`dh.autofight=1` restait false → `setAutoAttack` jamais appelé → héros passifs, skills seulement sur tap
manuel → défaite. Corrigé (accepte non-"0"/"false"). Résultat : **VICTOIRE 1-1 en jeu**, `CampaignAttack :
NORMAL 1-1 outcome=WIN → recordOutcome appliqué [persisté]`, énergie **114/120** visible (débitée de 6).
Skills confirmés déclenchés (AUTO + 21 taps `ATTACK_SCREEN_HERO_BUTTON` guidés par le tuto SKILL_USE).

**Bug PERSISTANCE progression** (révélé par une sonde post-run : `wire_levelStatuses=0`, `1-1 stars=0`,
`1-2 unlocked=false` alors que gold=340 persistait) : les statuts de niveau vivent HORS `this.extra`
(`ClientCampaignLevelStatus` en mémoire, lus depuis `individualUserExtra.levelStatuses` au chargement) ;
`recordOutcome` les mute EN MÉMOIRE mais n'écrit pas la liste wire → **étoiles/complétion perdues au
round-trip, 1-2 jamais débloqué**. Correctif `resyncCampaign` (dans `recordCampaignAttack`) : reconstruit
`individualUserExtra.levelStatuses` depuis `iu.getCampaignLevels()` (champs mappés 1:1 ; `lastWinTime` sans
getter → 0, non requis), comme `resyncHeroes`. Vérifié `server/smoke/CampaignPersistTest` : après save+reload
SQLite → **1-1 à 3★, 1-2 DÉBLOQUÉ, or=340, stamina=114**. `CampaignAttackTest` toujours OK.

**Réponse à « ça passe au niveau suivant ? »** : le jeu débloque 1-2 après une victoire au 1-1 (désormais
persisté). Le pilote, lui, entre toujours au 1-1 (`dh.playlevel`) → à généraliser au prochain niveau
débloqué (`getLatestCompletedLevel`) pour enchaîner la campagne.

⚠️ Suivi séparé : dans un run COMPLET (tuto→campagne), la stamina persistée réapparaît à « 39,96 M »
(fuite de gen-time dans la phase TUTO — `recordCampaignAttack` la gère bien, 114 en test isolé).
Fichiers : `server/java/dhserver/ServerUser.java` (resyncCampaign), `server/smoke/CampaignPersistTest.java`,
`desktop-port/src/main/java/dhdesktop/{TutorialDriver,DesktopLauncher}.java`.
