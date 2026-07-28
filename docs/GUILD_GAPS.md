# GUILD — Comblement des trous multi-serveur (suivi)

> Document de SUIVI vivant (demande user 2026-07-27 : « fais tout, travail propre et rigoureux,
> commits réguliers, ne t'arrête pas »). Chaque trou identifié lors de l'audit (g37) est traité
> feature par feature : implémentation via la **logique + les données du jeu** (jamais de valeur
> inventée, cf. `PRINCIPLES.md` §4), test de régression, commit dédié, doc.
>
> **Contexte compte de test** : userID=1 shard=1 TL100, guilde « Baroness Legion » guildID=1.
> Régression : `bash server/smoke/regression.sh` (le seul échec toléré = flake connu `ChestWireTest`,
> `NumberFormatException` intermittent au parse paresseux de `guild_perk_levels.tab`, vert en isolation).

## Tableau de bord

| Gap | Sujet | Statut | Commit |
|-----|-------|--------|--------|
| A | Avatars calculés depuis le niveau de guilde | ✅ FAIT (test + push) | `5be06ee` |
| C | Mercenaire — Social Bucks + **coût GOLD** (formule du jeu) | ✅ FAIT (2ᵉ passe) | `cb58d5e`+ |
| B | Dons SKILL_LEVEL + **HERO_XP** (dérivé du jeu) | ✅ LES DEUX FAITS (2ᵉ passe) | `daeb260`+ |
| E | Cadeaux de crate + **déclencheur admin** (`AdminGuild`) | ✅ FAIT (2ᵉ passe) | `4632348`+ |
| D | MULTI-USER + registre connexions + broadcast | ✅ FAIT (socle, compile-clean) | `0536a52` |
| F | Contests — classements + **ventilation par membre** (v6) + admin | ✅ FAIT (2ᵉ passe) | `f6e831d`+ |

---

## A — Avatars calculés depuis le niveau de guilde ✅

**Problème** : `GetUnlockedGuildAvatars` renvoyait hard-empty → BUG multi-serveur (faux dès que la guilde
a un niveau ≥ 1). **Fait** : `ServerUser.unlockedGuildAvatars(g)` = `avatars[GuildHelper.getGuildLevel(perks)]
.getList()`. Niveau = plus haut perk `GLn`. Table `GuildStats.GUILD_AVATAR_STATS.avatars[]` (privée statique,
lue par réflexion ; indexée 0..99 par `REQUIRED_GUILD_LEVEL` ; **CUMULATIVE** : `avatars[L]` ⊇ niveaux ≤ L,
`avatars[0]=avatars[1]`). `getList()` = méthode publique sur classe imbriquée non publique → `setAccessible(true)`.
`GuildAvatarTest` : niveau 0 → 20 avatars, niveau 2 → 40 (cumulatif), round-trip DB.

## C — Mercenaire : créditer le posteur en Social Bucks ✅

**Problème** : le posteur d'un mercenaire ne gagnait rien. **Fait** : `HIRE_HERO` →
`ServerUser.creditMercenaryHireReward()` = `MercenaryHelper.getHiredMercenaryReward` (SocialBuckStats × bonus
VIP, =100 à VIP 0) → `SOCIAL_BUCKS` + incrément `UserFlag.MERCENARY_SOCIAL_BUCKS` (compteur hebdo « earned this
week », reset via `getAndUpdateSocialBucks`), persisté (sauf auto-location). `GuildMercRewardTest` : +100/location,
cumul, round-trip DB. **Reste** : coût GOLD à l'emprunt (`MercenaryHeroData.cost` via `chargeForMercenary`) = fixé
à la construction du pool. **2ᵉ passe (vérification demandée) : CORRIGÉ — la formule EXISTE bien dans les données
du jeu !** `user_values.tab` → `MERCENARY_COST = min(2500+(0.5*P), 2000000000)` (P = puissance du héros). Scan du
pool de constantes de TOUT le jar : **aucune classe cliente** ne référence `MERCENARY_COST` → l'expression est
faite pour être évaluée par le SERVEUR, qui remplit `MercenaryHeroData.cost`. Implémenté :
`ServerUser.mercenaryCost(power)` évalue l'expression avec l'évaluateur DU JEU (`SimpleExpressionContext`,
variable `P`) ; `postedMercenaries()` renseigne `md.cost` avec la puissance réelle du héros (`IHero.getPower(0)`).
Vérifié : P=0→2500, P=1000→3000, P=100k→52500, P=5M→2502500.

## B — Dons SKILL_LEVEL ✅ + HERO_XP (opérateur §4)

Faits (bytecode `GuildDonationHelper` + `guild_constants.tab`) :
- `requestHelp(user, type, unit, skill)` valide+charge `GUILD_DONATION_REQUEST_{STAMINA|SKILL|HERO_XP}`.
- `doDonation(user, request, offered)` gère les 3 types : `isDonationEscrowed(SKILL_LEVEL)=true` → l'item du
  donneur (`removeItem`) est séquestré ; sinon `useItem`/`chargeUser`.
- Constantes : `DONATIONS_PER_HELP_REQUEST=5` (total dons SKILL/XP), `MAX_DONATIONS_PER_USER_PER_HELP_REQUEST=1`,
  `HERO_XP_DONATION_MAX_QTY=4`. Pas de getter pour `DONATIONS_PER_HELP_REQUEST` → lu par réflexion
  (`GuildStats.CONSTANT_STATS.getStats().DONATIONS_PER_HELP_REQUEST`).
- Item séquestré SKILL = `ItemType.SKILL_POINT_CONSUMABLE`. Action porte `heroType`=unit + `extra[SKILL]`=slot.

**SKILL_LEVEL** ✅ **FAIT** : `ServerUser.postGuildSkillRequest(g, unit, skill)` (requestHelp valide via
`canRequestSkillLevelHelp` + charge `GUILD_DONATION_REQUEST_SKILL` ; don = `{SKILL_POINT_CONSUMABLE, 1}` escrow ;
total = `DONATIONS_PER_HELP_REQUEST` lu par réflexion). Handler `REQUEST_GUILD_DONATION` route STAMINA/SKILL_LEVEL
(slot depuis `extra[SKILL]` via `parseSkillSlot`). `deliverDonationResult` : branche SKILL → demandeur reçoit
nbDons × `SKILL_POINT_CONSUMABLE` par courrier `GUILD_DONATION_SUCCESS`. `GuildSkillDonationTest` : validation du
jeu câblée (refus héros sans skill), escrow débite le donneur, cap 1, livraison + round-trip DB.

**HERO_XP** ✅ **FAIT** (2ᵉ passe — vérification demandée : « les valeurs existent-elles vraiment ? »).
Balayage EXHAUSTIF (274 `.tab` + `user_values.tab` + UI `ui/donations/*` + tuto `DonationActV1`) : aucune table ne
donne l'item/quantité du don. **MAIS** la dérivation, elle, est 100 % dans le jeu :
- `canRequestHeroXPHelp` REFUSE la demande si `hero.getEXP() == UnitStats.getEXPToNextLevel(level)` → **preuve que
  la demande porte sur l'XP MANQUANT** du héros pour son prochain niveau ;
- `ItemHelper.convertHeroXPToItems(xp, mode)` convertit un montant d'XP en items RÉELS via les données
  `ItemStats.EXP_ITEMS_LARGE_TO_SMALL` + stat `EXP_GIVEN` ;
- `DONATIONS_PER_HELP_REQUEST`=5 dons remplissent la demande ; `HERO_XP_DONATION_MAX_QTY`=4 plafonne la quantité.

→ `postGuildHeroXPRequest(g, unit)` : don = `convertHeroXPToItems(XPmanquant / 5)`, plus grosse dénomination,
quantité plafonnée à 4. **Seule lecture structurelle** (documentée, non issue d'une table) : « part = XP manquant ÷
nombre de dons ». Livraison : nbDons × le drop, par courrier. `GuildHeroXPDonationTest` : don dérivé = item d'XP
réel du jeu (≤ plafond), donneurs débités, demande remplie, livraison, round-trip DB.

**🐛 Bug corrigé au passage** : `guildConstantInt` utilisait `getField` alors que les champs de
`GuildStats$Constants` sont **package-private** → il retombait TOUJOURS sur les défauts (les constantes n'étaient
jamais lues, y compris pour SKILL). Corrigé en `getDeclaredField` + `setAccessible`.

## E — Génération serveur des cadeaux de crate ✅

**FAIT.** `GuildGiftRewards{eventID, gifters: List<BasicUserInfo>, lastGiftTime, rewards: List<RewardDrop>}`.
- **Stockage** `ServerGuild` **v5** : 3 listes parallèles (giftGifterWire=BasicUserInfo, giftTimes, giftRewardsBlob
  =`[n][len+RewardDrop]×n`) + `giftClaimTimes` (userID→dernier réclamé) + `giftEventID`. Persisté (toBytes/fromBytes v5).
- **Génération** (`ServerUser.grantGuildGift(g, rewards, time)`) = capacité OPÉRATEUR/admin (aucun `GuildGiftHelper`
  client ; dans le vrai jeu = déclenché par un ACHAT membre). Offreur = ce joueur, récompenses = pour tous.
- **Lecture** `GetGuildGiftRewards` → `buildGuildGiftRewards` (offreurs + récompenses agrégées + dernier temps).
- **Réclamation** `CLAIM_GUILD_GIFT_REWARDS` → `claimGuildGifts` : crédite les cadeaux plus récents que la marque
  du joueur (`RewardHelper.giveRewards`, source `PURCHASE`), avance la marque (anti-double-claim), persiste,
  répond `GuildGiftRewardsUpdate{newRewards}`.
- `GuildGiftTest` : génère (500 GOLD + 3 STAMINA), build (1 offreur/2 récompenses), membre 2 réclame (+crédité),
  2ᵉ claim = rien, round-trip DB (cadeaux + marques persistent, membre 3 réclame, membre 2 non).
- **DÉCLENCHEUR ADMIN ✅ (2ᵉ passe)** : `AdminGuild --gift --from <userID> --reward TYPE:qté ...` génère le cadeau
  (accepte ResourceType ET ItemType), persisté en base → réclamable en jeu. Vérifié de bout en bout : cadeau
  (GOLD:50000 + STAMINA_CONSUMABLE:3) → un membre réclame → **GOLD 0→50000, STAMINA 0→3**.
- **Reste** : le brancher aussi sur un ACHAT réel (coffre payant #15) si tu veux le déclenchement automatique.

## D — MULTI-USER + registre de connexions + broadcast ✅ (socle)

**FAIT (socle multi-serveur)**. `ClientInfo` porte `public long userID` → chaque connexion s'identifie.
- **Résolution PAR CONNEXION** : au début de `onReceive`, un local `ServerUser user` **SHADOW** le champ ;
  à `ClientInfo`, `user = store.loadOrCreate(ClientInfo.userID, shard)` + enregistrement. Tous les handlers (~140
  références à `user`) utilisent ce local **sans modification** (refactor sûr, compilateur garant). Repli = compte
  par défaut (pilote DEV / 1ᵉ message).
- **Registres** : `connUsers` (socket→compte) et `online` (userID→socket), remplis à `ClientInfo`, vidés à
  `onClose`.
- **Broadcast** : `pushToGuild(g, exceptUserID, msg)` pousse à tous les membres EN LIGNE. Câblé sur `SendChat`
  guilde → le `Chat` autoritatif est diffusé en temps réel aux autres membres connectés (en plus de l'écho
  émetteur + persistance). Base réutilisable pour toute livraison live (dons, joins…).

**Restes** : le shard est fixe (celui du compte par défaut) ; l'authentification est l'`userID` du `ClientInfo`
(le vrai serveur ajouterait un jeton). Vérif 2 sessions simultanées = à faire en jeu quand utile.

## F — Contests ✅ (classements réels + scoring guilde)

**FAIT (classements + scoring guilde).**
- **`GET_GUILD_CONTEST_RANKINGS`** → `GuildContestRankings{topGuilds: List<GuildContestRankingRow>}` **RÉEL** :
  `buildGuildContestRankings` trie les guildes du shard par `GuildInfo.contestPoints` (row = BasicGuildInfo +
  points + rank). Comme le leaderboard de guilde.
- **`GET_CONTEST_RANKINGS`** → `ContestRankings{guildMembers, topPlayers, yourInfo}` : `buildContestRankings`
  classe les membres de la guilde par leurs points de contest.
- **Scoring GUILDE** : `ServerUser.awardGuildContestPoints(g, points)` → `GuildInfo.contestPoints` (persisté).
  Capacité opérateur (le contest = planifié par l'admin, comme #37).
- **« Les points de contest sont-ils bien gérés par le serveur ? » — OUI, et le bytecode le prouve** :
  `User.getGuildContestPoints() { return DH.app.getYourGuildInfo().contestPoints; }` et
  `setGuildContestPoints(n) { getYourGuildInfo().contestPoints = n; }`. **La source de vérité est donc
  `GuildInfo.contestPoints`** — un champ que le SERVEUR (nous) remplit, persiste et envoie dans `GuildInfo`.
  La ressource `ResourceType.GUILD_CONTEST_POINTS` n'est **pas un stock** mais un canal d'ÉVÉNEMENT UI
  (`UserProperty.get(...)`) : d'où le no-op de `setResource`. Rien n'est donc « perdu » côté serveur.
- **🐛 Défaut corrigé** : `buildContestRankings` lisait cette ressource → il aurait TOUJOURS renvoyé 0. La
  ventilation par membre n'existe nulle part côté client → **état serveur (ServerGuild v6)** :
  `contestPointsByUser` (userID → points), alimenté par `awardGuildContestPoints` (qui incrémente AUSSI le total
  guilde), lu par `ContestRankings`, persisté.
- `GuildContestTest` : 3 guildes (100/200/300) → tri 300>200>100 ; ventilation 120+80 cohérente avec le total ;
  round-trip DB v6 ; confirme que la ressource joueur n'est pas un stock.
- **PLANIFICATION = admin** (confirmé) : `AdminGuild --contest-points <n> [--member <id>]` attribue les points
  (guilde + membre), persistés et immédiatement visibles dans les deux classements. La fenêtre temporelle /
  récompenses de fin d'un contest restent du live-ops à programmer si tu veux des saisons.

---

## Conclusion — périmètre RÉELLEMENT couvert (audit 2026-07-28)

Les 6 trous A–F portaient sur les fonctionnalités de guilde **déjà branchées**. Un audit exhaustif
(tous les messages `*Guild*` + toutes les `CommandType` `*GUILD*` du jar, confrontés à `LoginServer`)
donne l'image honnête suivante.

### ✅ Couvert (le « social » de guilde, de bout en bout)
Créer / chercher / rejoindre / quitter / dissoudre · candidatures · roster étendu · gestion des membres
(kick, promote, demote) · réglages + renommage · perks (permanents et temporisés) · check-in · dons GUILD AID
(STAMINA, SKILL_LEVEL, HERO_XP) · mercenaires (poster, louer, Social Bucks, coût GOLD) · cadeaux / GUILD CRATE ·
chat + wall (avec broadcast temps réel) · avatars · classements de guilde · contests (classements + ventilation
par membre). **Tout est autoritatif, persisté et testé.**

### ❌ NON couvert — 2 modes de jeu entiers + 2 petits trous
| Trou | Ampleur | Note |
|------|---------|------|
| **GUILD WAR** | **0 / 68 messages** | Mode complet : matchmaking, lineups de défense, attaques, voitures + bonus, sabotage, logs, saisons/ligues, récompenses. Le plus gros reste du jeu. |
| **INVASION** | **0 / 50 messages** | Mode complet : boss, breakers, ligues, rangs de membres, récompenses. |
| `ClaimInactiveGuild` | 1 message | Reprendre une guilde dont le chef est inactif. Isolé, petit. |
| `EditGuildWarSettings` | 1 message | Réglages de guerre — dépend de WAR. |

> `CommandType.CREATE_GUILD` n'est pas câblé, mais la création de guilde **fonctionne et est vérifiée en jeu**
> via le message `CreateGuild` (#49) : ce chemin de commande semble hérité/annexe (à re-vérifier si un écran
> de création se bloque un jour).

### Ce qui reste « opérateur » sur le couvert
Ce ne sont PAS des trous techniques mais des **décisions éditoriales** (le mécanisme est codé et testé) :
- **quelle règle** déclenche un cadeau de guilde sur un achat réel (le jar ne la contient pas — c'était une règle
  de monétisation serveur) ; la génération manuelle/admin fonctionne déjà ;
- **le calendrier** d'un contest (fenêtre, thème, récompenses de fin de saison) ; l'attribution et les classements
  fonctionnent déjà.

Suivi : tâches **#68 (WAR)**, **#69 (INVASION)**, **#70 (ClaimInactiveGuild)**.
