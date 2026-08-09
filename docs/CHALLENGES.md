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
2. 🟢 **Boucle de défi** — **LIVRÉE HEADLESS (g74), vérif EN JEU restante** :
   - **Fixture** (`ServerContext.init`) : `DH.app.historicWeeklyChallenges = new HistoricWeeklyChallenges()` — la
     valeur EXACTE du ctor du jeu (notre shim l'alloue sans ctor → null → l'extension `StickerHelper$1
     .getHistoricChallenges()` NPE). Couche plateforme (§4), zéro invention ; posée GLOBALEMENT (contrairement à
     `userChallengeData` réservé à l'oracle, g59 : ce champ n'est que LU, aucune cascade `notifyChallenges`).
   - **Sérialiseur FERMÉ** `ServerChallenges.toMessage(ClientUserChallengeData) → UserChallengeDataExtra` : le jeu
     n'a PAS de sérialiseur inverse (le client ne renvoie jamais tout l'état). Miroir du sync héros de §3 ; réflexion
     lecture seule sur `nextChallengeID`/`attemptID`/`userID` (aucun getter). Validé par round-trip (`ChallengeLoopTest`).
   - **SETUP** `ServerChallenges.ensureSetup(su)` (appelé au boot par `LoginServer`, gaté `Unlockable.CHALLENGES`
     TL20) : exécute `StickerHelper.setupStarterChallenges` (auto-population — le jeu choisit le 1er défi STARTER non
     complété par `starterChallenge` croissant : `TO_CATCH_A_STAR`→`THE_NAMES_NICK`→…) + `setupWeeklyChallenges`.
   - **START/CLAIM/CANCEL** (handlers `LoginServer`) — **protocole client PROUVÉ au bytecode** (`ClientActionHelper`) :
     `START Action{TYPE, TIME}` (sans SLOT → serveur choisit via `canStart`), `CLAIM`/`CANCEL Action{TYPE, SLOT, TIME}`
     (extras en `.name()` String). Serveur ré-exécute `createHandleExtra`/`claimSticker`/`cancelChallenge` (autoritatif)
     + persiste (fire-and-forget, le client a appliqué localement — patron loot/raid).
   - **CLAIM autoritatif** (`claimSticker`) : crédite le sticker cosmétique + `CHALLENGE_TOKENS` (`getTokenReward`,
     ex. 500) + bonus de livre, pose `completionTime`, marque `claimed`, RETIRE le handle du slot puis (STARTER)
     ré-avance au défi suivant. **Anti-double** : re-claim → 0 token (handle retiré / `claimed`). Prouvé `ChallengeLoopTest`.
   - **Persistance** : `challengeData BLOB` (`UserStore` migration + `ServerUser.challengeData`), livré au boot par
     `bootData()`. Round-trip DB prouvé (`ChallengeLoopTest`).
   - **PROGRESSION** (transversale, hooks `ChallengeImpl`) et **autorité client vs serveur de la progression** :
     **RESTENT à observer EN JEU** (comme loot/raid SURGE). Le test force `currentProgress = maxProgress` pour
     exercer la réclamation. **⚠️ Vérif EN JEU obligatoire** (rendu du défi + claim + auto-avance).
2bis. 🧭 (archive recon g73b) **ARCHITECTURE RÉSOLUE** :
   - **Conversion (§3)** : `ClientNetworkStateConverter.getUserChallengeData(UserChallengeDataExtra)` →
     `ClientUserChallengeData` (impl `IUserChallengeData`) ; `setUserChallengeData(client, msg)` → re-sérialise pour
     persistance/BootData. ⇒ le serveur charge notre message persisté en objet du jeu, mute via les helpers, resérialise.
   - **START** `Action{START_STICKER_CHALLENGE, extra={TYPE=StickerType, TIME=<t>}}` (pas de SLOT → **le serveur choisit
     le slot** via `canStart(data, type, slot)` sur les slots libres). Handle créé par `StickerHelper.createHandleExtra
     (long, StickerType, int)` : `endTime = serverTime() + ChallengeSticker.getDuration()`, `maxProgress =
     ChallengeSticker.getMaxProgress()` (données `challenge_stickers.tab`, **zéro invention**), puis `data.setHandle(slot,…)`.
   - **PROGRESSION** : un défi avance via les **hooks `ChallengeImpl`** (`onCampaignAttack`, `onChestOpen`,
     `onBreakerAttack`, `onArenaPromotion`, `checkAttackBase`, `onChallengeComplete`…) — **transversal** : à brancher
     dans les handlers d'événements existants (campagne/chest/arène/breaker). Défis à `maxProgress>0` = gameplay ;
     défis PUREMENT temporels = complétés à `endTime`. (Autorité de progression client vs serveur : **à confirmer EN
     JEU**, comme le loot/raid — observer `UpdateChallengeProgress`.)
   - **CLAIM** `Action{CLAIM_STICKER_CHALLENGE, extra={TYPE, SLOT, …}}` → `StickerHelper.claimSticker(user, data, long,
     StickerType, ChallengeSlots)` (autoritatif : sticker + récompenses) ; **CANCEL** → `StickerHelper.cancelChallenge(…)`.
   - **Persistance** : `UserChallengeDataExtra` n'est PAS dans `UserExtra` → **nouveau champ persisté dans le blob
     `ServerUser`** (bootData() n'a pas accès au store → doit vivre dans l'état du joueur), livré à l'incr. 1.
   - **⚠️ Recommandé avant câblage** : une observation EN JEU du START/CLAIM (extras exacts, sélection de slot,
     `UpdateChallengeProgress`) — comme pour le raid SURGE — pour ne rien inventer (§4).
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
