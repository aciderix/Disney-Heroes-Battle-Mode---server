# CHALLENGES (#72) — mode « Sticker Challenges » — suivi d'implémentation

> Attaqué avec le pipeline industrialisé #73/#74 (comme SURGE). **Lire avant de continuer.**
> Chaque incrément : contrat (ScreenContract) → logique du jeu (§3) → test headless (WireCheck + ClientOracle) →
> **vérif EN JEU obligatoire** (🟢 headless, ✅ en jeu).

## Ce que CHALLENGES est (recon `contract.sh --mode` + bytecode)

Mode **Sticker Challenges** — gaté **`Unlockable.CHALLENGES` = TL 20** (`unlockables.tab` : `CHALLENGES TRUE 20`).
Écrans `ui/challenges/*` (15 classes) : **Livres → Chapitres → Défis** (`BooksViewScreen`/`ChallengesChaptersViewScreen`/
`ChallengesViewScreen`/`ChallengesMainScreen`) + **Stickers** (collection : `StickerOverviewWindow`/`StickerPickerWindow`/
`StickerClaimWindow`/`StickerStamp`).

**Système IDLE / à progression** (pas du combat temps réel) : on lance un défi dans un **slot**, il se complète par le
**temps** (`endTime`) et/ou la **progression** (`currentProgress`/`maxProgress` alimentée par le jeu), puis on
**réclame** → **sticker** (+ récompenses). Contenu data-driven CLIENT (`challenge_books.tab` 8 lignes,
`challenge_constants.tab`, `challenge_stickers.tab`, `challenge_merchant_drops.tab`) ; le serveur livre la
**PROGRESSION** du joueur.

### Données (contrat)
- **`BootData.userChallengeDataExtra`** (`UserChallengeDataExtra`) — **LIVRÉ AU BOOT** (pas de requête pour le rendu
  principal). Champs : `userID`, `slots` (Map `ChallengeSlots → ChallengeHandleExtra`), `nextChallengeID`,
  `completedChapters` (List), `completionTime`/`purchaseTime` (Maps). `new BootData()` l'initialise déjà **non-null
  vide** — mais avec `userID=0` (à corriger) et sans persistance.
- **`BootData.historicWeeklyChallenges`** (`HistoricWeeklyChallengeRaw`) — défis hebdo historiques (lu par
  `StickerHelper.getHistoricChallenges` — celui qui NPE-ait quand `userChallengeData` était posé globalement, g59).
  Défaut non-null vide OK.
- **`ChallengeSlots`** : `STARTER, NORMAL_1, NORMAL_2, WEEKLY_1, WEEKLY_2, DEFAULT`.
- **`ChallengeHandleExtra`** (état d'un slot) : `attemptID, claimed, currentProgress, maxProgress, endTime,
  type (StickerType), userID, data (Map), lastViewedProgress`.
- **`UserExtra`** porte déjà `completedChallenges` (int) + `favoriteSticker` (StickerType) [persistés].

### Actions client→serveur (CommandType, via `Action`)
`START_STICKER_CHALLENGE`, `CLAIM_STICKER_CHALLENGE`, `CANCEL_STICKER_CHALLENGE`, `BUY_STICKER`, `BUY_STICKER_BOOK`,
`BUY_STICKER_CHALLENGE_SLOT`, `SET_FAVORITE_STICKER`. + message `UpdateChallengeProgress` (progression) +
`GetUserChallengeDataExtra{targetUserID} → UserChallengeDataExtra` (envoyé par `StickerOverviewWindow` — vue d'un
joueur ; **seul handler « MANQUE » au niveau du mode**).

## Plan d'incréments
1. ⬜ **Livraison BootData** : `ServerChallenges.freshData/load` → `bd.userChallengeDataExtra` avec le **bon userID**
   + état persisté (slots/progression), wire-sûr ; `historicWeeklyChallenges` non-null. Écran CHALLENGES rend.
   Test headless (WireCheck + userID). **Vérif EN JEU** (écran s'ouvre, TL100).
2. ⬜ **Boucle de défi** : `START_STICKER_CHALLENGE` (assigne un défi à un slot : type/maxProgress/endTime) →
   progression (temps/`UpdateChallengeProgress`) → `CLAIM_STICKER_CHALLENGE` (sticker + récompenses, slot vidé,
   `completedChallenges++`/`nextChallengeID`). Persisté. `CANCEL_STICKER_CHALLENGE`.
3. ⬜ **Stickers** : `BUY_STICKER`/`BUY_STICKER_BOOK`/`BUY_STICKER_CHALLENGE_SLOT`/`SET_FAVORITE_STICKER`
   (`favoriteSticker` déjà dans UserExtra) + livre de stickers.
4. ⬜ **`GetUserChallengeDataExtra` handler** (vue StickerOverviewWindow).
5. ⬜ **Vérif EN JEU complète** (rendu + lancer/réclamer un défi + sticker).

## Notes §3/§4
- Logique : `ModeGraph --logic` n'a trouvé AUCUNE méthode statique `IUser` dans les helpers du mode → la logique est
  dans `StickerHelper`/`ChallengeHelper` (instance) et surtout **appliquée par les rappels d'Action côté client** ;
  le serveur MIROITE les transitions d'état (comme le loot/campagne : client-autoritatif re-exécuté), sans inventer
  les valeurs (durées/rewards/stickers = `challenge_*.tab`).
- Persistance : `UserChallengeDataExtra` n'est PAS dans `UserExtra`/`IndividualUserExtra` → **blob per-user dédié** à
  ajouter (comme `user_invasion`), ou octets wire dans un slot de store.
