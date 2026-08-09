# FRIENDSHIPS / MISSIONS (#72) — mode « Amitiés » — suivi d'implémentation

> Attaqué au pipeline industrialisé #73/#74 (`contract.sh --mode Friendship` + `ModeGraph --logic`), comme SURGE/CHALLENGES.
> Chaque incrément : contrat (ScreenContract) → logique du jeu (§3) → test headless (WireCheck + ClientOracle) →
> **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que le mode EST (recon pipeline + bytecode)

Deux systèmes liés, gatés **`Unlockable.FRIENDSHIPS` / `MISSIONS` = TL 24** (`unlockables.tab`) :
- **Amitiés (disks)** : chaque **paire de héros** (`FriendPairID{primary, secondary}`, `friendship_pairs.tab`) a une
  amitié qui **monte en niveau** (`empowerment`) → débloque un **disk** (capacité). XP gagnée via le combat/campagne d'amitié.
- **MISSIONS = campagne d'amitié** : une mini-campagne PAR paire (chapitres/nœuds/étages, `friendship_campaign*.tab`),
  combat via `recordOutcome`, récompenses de chapitre.

Écran principal **`MissionsMainScreen`** (Destination **`MISSIONS`**). Fenêtres : `FriendshipCampaignWindow`,
`FriendshipDiskUnlockWindow`, `FriendshipWallWindow` (mur/historique), `FriendFinderWindow`,
`FriendshipCampaignCompleteWindow`, `FriendshipUnlockedAnimationWindow`, `FriendshipLockedWindow`.
Hero chooser : `FriendCampaignHeroChooserScreen`.

### Données / persistance — **DÉJÀ dans `IndividualUserExtra` (persisté par write-through)**
- `friendships` (Map `FriendPairID→FriendPairData{empowerment, campaignBitsEarned, history, lastBattle,
  lastHistoryViewTime, viewedUnlockAnimation}`)
- `friendshipCampaignProgress` (Map), `friendshipMissionData` (Map), `inProgressFriendshipMissions` (List),
  `favoriteFriendships` (List), `lastFriendRequestTimes` (Map).
- `IndividualUser.getFriendship(pair)` / `getFriendships()` / `getFriendshipCampaignProgress(pair)` construisent
  depuis l'extra ; `ClientFriendship` = impl `IFriendship`. **⇒ mutations via IndividualUser = auto-persistées**
  (gros avantage vs CHALLENGES qui avait un blob dédié).
- **`BootData.friendshipOffsetData`** (`FriendshipOffsetData{shardID, contentTime, friendships[], levelOffsets[],
  rarityOffsets[]}`) = **config d'échelle CONTENU** (dérive de version), lue au boot par `FriendshipOffsets.setOffsets`.
  Défaut `new BootData()` = **non-null vide** (offsets vides ⇒ `getLevelOffset/getRarityOffset = 0` = pas de dérive,
  baseline fidèle pour une version de contenu figée). Le catalogue des paires est de la donnée CLIENTE
  (`friendship_pairs.tab`) → le client connaît déjà les paires ; le serveur livre l'ÉTAT du joueur.

### Logique du jeu (§3 — points d'entrée à EXÉCUTER)
- `FriendshipHelper` : `empowerFriendship(user, pair, int)` (montée de niveau), `buyFriendStamina(user)`,
  `setFavoritedFriendship(user, pair, bool)`, `viewedWall`/`viewedUnlockAnimation`, `getUnlockStatus`,
  `getVisibleFriends`.
- `FriendshipCampaignHelper` : `recordOutcome(user, pair, chapter, outcome, …)` (combat de campagne d'amitié =
  autoritatif, patron `CampaignAttack`/`SurgeCombat`), `giveChapterRewards`, `getRewardsForChapter`, `doNodeUpdate`,
  `getChapterStatus`, `getDifficulty`, `calculateGoldEarned`, `getFriendCampaignPower`.

### Actions client→serveur (`ClientActionHelper`)
- `empowerFriendship(pair, int)` · `buyFriendStamina()` · `setFavoriteFriendship(pair, bool)`.
- Combat de campagne d'amitié : `FriendshipCampaignAttack` (issue, patron des attaques de campagne).
- (À confirmer au bytecode/en jeu : messages de mur/historique, requêtes d'amis.)

## Plan d'incréments
1. ✅ **Livraison / rendu — LIVRÉ (g79) + VÉRIFIÉ EN JEU** : `BootData.friendshipOffsetData` + conteneurs
   `IndividualUserExtra` non-null (**déjà OK par les défauts `new BootData()`/`new IndividualUserExtra()`** — aucun
   changement serveur requis). `FriendshipBootTest` (non-null + listes d'offsets de même longueur + `setOffsets`
   rejoué headless sans NPE + round-trip wire). **✅ EN JEU (compte TL100)** : `nav MISSIONS` → écran **MISSIONS**
   rend « **0/1 missions** », **ADD MISSION**, « No rewards to claim yet! » / CLAIM ALL — état frais correct, aucun
   NPE `FriendshipOffsets.setOffsets`. Le catalogue de paires est de la donnée CLIENTE (`friendship_pairs.tab`).
2. ⬜ **Empower + favori** : Actions `EMPOWER_FRIENDSHIP`/`SET_FAVORITE_FRIENDSHIP` → `FriendshipHelper` (autoritatif),
   persistance (individualUserExtra). Mur/historique (`viewedWall`).
3. ⬜ **Campagne d'amitié (combat)** : `FriendshipCampaignAttack` → `FriendshipCampaignHelper.recordOutcome`
   (autoritatif) + `giveChapterRewards` ; stamina d'amitié (`buyFriendStamina`).
4. ⬜ **Vérif EN JEU complète** (empower → disk débloqué, campagne jouée → récompenses de chapitre).

## Notes §3/§4
- Persistance quasi-gratuite (état dans `individualUserExtra` write-through). Zéro invention : niveaux/récompenses/
  difficulté/puissance viennent des `friendship_*.tab` via les Helpers.
- `friendshipOffsetData` vide = baseline fidèle (aucune dérive de contenu) ; à enrichir depuis le contenu SI une
  vérif en jeu montre un écart (§8).
