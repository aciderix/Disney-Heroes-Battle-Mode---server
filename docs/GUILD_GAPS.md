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
| B | Dons SKILL_LEVEL (✅) + HERO_XP (opérateur §4) | ✅ SKILL FAIT ; HERO_XP = donnée opérateur | `<en cours>` |
| E | Génération serveur des cadeaux de crate | ⏳ à faire | — |
| D | Registre de connexions + broadcast + **REFACTOR MULTI-USER** | ⏳ à faire (in scope, confirmé user) | — |
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

## B — Dons HERO_XP + SKILL_LEVEL 🔨

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

## E — Génération serveur des cadeaux de crate ⏳

Faits : aucun `GuildGiftHelper` client (génération 100 % opérateur) ; messages `GetGuildGiftRewards` /
`GuildGiftRewards` / `GuildGiftRewardsUpdate`. Déclencheur = achat d'un membre (les coffres payants #15 existent).
**Design** : quand un membre fait un achat qualifiant, générer un cadeau de guilde (persisté dans l'état de
guilde), réclamable par tous via `CLAIM_GUILD_GIFT_REWARDS`. À concevoir : structure `GuildGiftRewards`
(champs), condition d'achat qualifiant, anti-double-claim par joueur.

## D — Registre de connexions + broadcast (chat live) ⏳

**BLOQUÉ par un socle** : `LoginServer` est actuellement **mono-utilisateur** (`main` charge id=1 ;
`private final ServerUser user` ; tous les handlers référencent ce champ). Le broadcast présuppose des
**connexions multi-utilisateurs**. **Design** : (1) résoudre l'utilisateur PAR CONNEXION depuis `ClientInfo`
(au lieu du champ unique) ; (2) registre `Map<Long,GruntConnection>` (bind onOpen après auth, retrait onClose) ;
(3) sur `SendChat` guilde, pousser le `Chat` à tous les membres connectés de la guilde. Gros jalon (touche tous
les handlers). Tant qu'il n'est pas fait, le chat marche pour la connexion pilote + persistance (les autres voient
au rechargement).

## F — Contests programmables (admin) ⏳

Faits : page perk `GUILD_CONTESTS`, ressource `GUILD_CONTEST_POINTS`. Contests = événements datés (live-ops).
**Design** : planification via le panneau admin (#37), scoring (contribution des membres → `contestPoints`),
classements `GET_(GUILD_)CONTEST_RANKINGS` réels. Le plus lourd.
