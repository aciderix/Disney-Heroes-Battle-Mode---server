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
| C | Mercenaire — créditer Social Bucks au posteur | ✅ FAIT (test + push) | `cb58d5e` |
| B | Dons SKILL_LEVEL (✅) + HERO_XP (opérateur §4) | ✅ SKILL FAIT + push | `daeb260` |
| E | Génération serveur des cadeaux de crate | ✅ FAIT (test) | `<en cours>` |
| D | MULTI-USER + registre connexions + broadcast | ✅ FAIT (socle, compile-clean) | `0536a52` |
| F | Contests programmables (admin) | ⏳ à faire (live-ops) | — |

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
à la construction du pool (opérateur), aucune formule dans le jar client → non simulé.

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

**HERO_XP** = le `RewardDrop` de don (quel `ItemType` d'XP parmi EXP_VIAL/FLASK/… et quelle quantité) est fixé
à la construction de la demande côté OPÉRATEUR — **absent du jar client** (seul le plafond de quantité=4 est connu).
Le choisir = inventer une valeur → **INTERDIT (§4)**. Handler : journalise « non géré (donnée opérateur, §4) ».
→ HERO_XP documenté comme reste opérateur (comme le coût merc à l'emprunt).

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
- **Reste** : brancher `grantGuildGift` sur un ACHAT réel (coffre payant #15) et/ou le panneau admin (#37).

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

## F — Contests programmables (admin) ⏳

Faits : page perk `GUILD_CONTESTS`, ressource `GUILD_CONTEST_POINTS`. Contests = événements datés (live-ops).
**Design** : planification via le panneau admin (#37), scoring (contribution des membres → `contestPoints`),
classements `GET_(GUILD_)CONTEST_RANKINGS` réels. Le plus lourd.
