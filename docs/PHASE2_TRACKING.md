# PHASE 2 — SUIVI (tracker vivant)

> **Document de SUIVI** (demandé par l'utilisateur, 2026-08-24). Le **plan détaillé** reste dans
> [`PHASE2_PLAN.md`](PHASE2_PLAN.md) (chantiers A→G) ; CE fichier est le **tableau de bord** qu'on met à jour au fil de l'eau :
> statut de chaque étape, liens vers les rapports auto-générés, décisions. **À maintenir en permanence** (comme MEMORY/JOURNAL).
>
> Règles inchangées (`PRINCIPLES.md` §1-§8) : serveur exécute le code du jeu · rien d'inventé (tab/bytecode) · vérif EN JEU ·
> perfs non destructrices de la fidélité · identifiant de modèle JAMAIS dans un artefact. **Rien n'est « fait » sans preuve.**

## Légende de statut
⬜ non engagé · 🚧 en cours · 🟢 prouvé headless (pas encore en jeu) · ✅ prouvé EN JEU / validé · 📄 rapport auto-généré · ⏸️ en attente décision utilisateur

---

## Vue d'ensemble des chantiers (détail : `PHASE2_PLAN.md`)

| # | Chantier | Statut | Note |
|---|---|---|---|
| **A** | **Vérification globale & chasse aux oublis** | 🚧 **EN COURS** | audit auto 4 axes (ci-dessous) |
| B | Performance du portage (non destructive) | ⬜ | combat unidbg = point chaud ; Opt.3 spine Java certifié oracle |
| C | Front-end joueur (launcher : liste/join serveurs, login) | ⬜ | redirection `LIVE` aujourd'hui en dur 127.0.0.1 |
| D | Backend/front d'hébergement (panneau opérateur, multi-shard) | ⬜ | unifier AdminClock/War/Invasion/Mail/Events |
| E | Tests & intégration APK mobile | ⬜ | même wire 12.1.0 ; vrai client Android → serveur |
| F | Tests inter-machines réels (Internet, NAT/TLS, charge) | ⬜ | soak, multi-région, sécurité réseau |
| G | Qualité/outillage transverse (CI, repro, doc self-hoster, légal) | ⬜ | CI = régression + WireCheck/ClientOracle |

---

## CHANTIER A — VÉRIFICATION GLOBALE (audit automatisé, 4 axes)

**Objectif utilisateur** : auditer TOUT le jeu d'un coup, via les outils d'industrialisation, pour vérifier —
1. qu'aucun **écran** n'a été oublié ;
2. que tout est bien **câblé** (chaque message envoyé par un écran a un handler serveur) ;
3. qu'aucune **valeur en dur** ne traîne dans le serveur alors qu'elle est dans le code du jeu / `.tab` / admin ;
4. qu'il n'y a pas d'**erreurs client** (scan des logs en jeu).
→ **les manques sont auto-loggés** dans des rapports `docs/AUDIT_*.md` pour suivi.

**Outil** : `tools/audit/audit.sh` (réutilise `tools/screentool` : `ModeGraph` + `ScreenContract`). Régénère les rapports.
Périmètre mesuré au départ : **179 écrans** client (`*Screen`), ~37 packages UI.

| Axe | Description | Outil | Rapport | Statut |
|---|---|---|---|---|
| **A1** | Inventaire exhaustif des écrans + couverture (aucun oublié) | `audit.sh a1` (unzip + `EXPLORATION.md`) | 📄 `docs/AUDIT_SCREENS.md` | 🚧 179 écrans listés, triage en cours |
| **A2** | Câblage : messages envoyés par écran non routés par `LoginServer` | `audit.sh a2` (`ScreenContract` §C) | 📄 `docs/AUDIT_WIRING.md` | ✅ 14 triés — 3 implémentés (invasion rank ✅ en jeu, blocked, save) ; reste = retiré/debug/étape4/différé/Phase2C/faible |
| **A3** | Valeurs en dur serveur qui devraient venir de `.tab`/jeu/admin | `audit.sh a3` (heuristique) | 📄 `docs/AUDIT_HARDCODED.md` | ✅ **0 candidat** (§4 respecté) |
| **A4** | Erreurs client : scan des logs en jeu (exceptions/NPE/GL) | `audit.sh a4` (parseur logs) | 📄 `docs/AUDIT_CLIENT_ERRORS.md` | ✅ **0 erreur non-bénigne** |
| **A5** | Couverture des `.tab` : data d'un mode/feature non câblé + orphelines + carte `.tab`→classe | `audit.sh a5` (carte bytecode) | 📄 `docs/AUDIT_TABS.md` | 🚧 272 `.tab` mappées → triage (voir notes) |

**Méthode par manque trouvé** : chaque entrée de rapport = `[GAP]` avec (écran/mode, message/valeur, gravité proposée,
piste de correction). On tranche ensuite un par un (§8 : corriger + vérifier EN JEU, ou documenter PARTIEL/NO-OP avec risque
dans `SHIMS.md`, ou décision utilisateur). Les faux positifs sont marqués `[OK-connu]` avec justification (ne pas re-signaler).

### Notes / décisions A

**A3 (valeurs en dur)** : ✅ 0 candidat détecté — le serveur ne code pas les valeurs de RÈGLE en dur (elles viennent de
`.tab`/bytecode) ; les défauts de config restent dans les outils `Admin*` (légitime). Discipline §4 tenue.

**A4 (erreurs client)** : ✅ 0 erreur non-bénigne sur 8 logs scannés. Bénins CONNUS confirmés (documentés) : stat-parse
`NumberFormatException ""`, `PatchTalent.PREDICTIVE_FORTIFICATION`, `black_market_merchant_drops.tab row 18 "Can't assign
auto weight → defaulting to 1"` (absorbé, BLACK_MARKET/MEGA_MART fonctionnels), trader INVASION, + env/layout/audio headless
(`XDG_RUNTIME_DIR`, `auto weight`, `sound not loaded`).

**A2 (câblage) — triage + RÉSOLUTION des 14 manques** (`AUDIT_WIRING.md`) — ÉTAPE 2 (2026-08-24, g173) :
| Message (émetteur) | Verdict | Détail |
|---|---|---|
| `GetGMemInvasionRankInfo` (InvasionRankingsScreen) | **✅ IMPLÉMENTÉ + EN JEU** | rang invasion par MEMBRE de guilde (données réelles `user_invasion`) ; `ServerInvasion.guildMemberRanking` + handler. Vérifié en jeu : onglet GUILD MEMBERS rend 2 membres (avant : LOADING). Les 2 autres onglets (User/Guild league) étaient déjà servis. |
| `GetBlockedList` (BlockedPlayersWindow) | **✅ IMPLÉMENTÉ** (headless+wire) | `BlockedList` VIDE — le blocage n'est pas implémenté (communautaire) → 0 bloqué = réponse FIDÈLE (pas un faux endpoint). `WiringGapsTest` + round-trip wire. In-game : fenêtre sociale (sous-menu), non pilotée — bas risque. |
| `GetUserSaveData` (SaveRestoreUserWindow) | **✅ IMPLÉMENTÉ** (headless+wire) | `UserSaveData{info,extra,individualUserExtra}` = sauvegarde du compte DU DEMANDEUR (= ce qu'on persiste, via `bootData()`), avec GARDE anti-fuite (userID doit être le sien). Données RÉELLES. `WiringGapsTest`. In-game : fenêtre settings, non pilotée — bas risque. |
| `GetHeist`, `StartHeist`, `KickHeistParticipant` (heist) | **[OK-connu]** | HEIST **retiré du jeu** (💤) — écran inatteignable, rien à câbler. |
| `GetChestConsumableHistory` (**Debug**ChestConsumablesScreen) | **[OUT OF SCOPE]** | écran **DEBUG dev-only**, non atteignable par le joueur. |
| `GetCodebaseAttackLogs` (CodebaseAttackLogScreen) | **[→ étape 4]** | fait partie du mode **codebase** (GAP feature A5) — à trancher avec la feature codebase. |
| `GetPrizeWallData` (PrizeWallScreen) | **[DIFFÉRÉ — feature event]** | Prize Wall = feature d'EVENT ; `PrizeWallState` n'a AUCUN état « inactif » (IN_PROGRESS/REWARD_PREVIEW/STAGE_COMPLETE) → **pas de réponse vide fidèle** ; renvoyer un faux mur violerait « pas de faux endpoint ». Nécessite un vrai builder d'event prize-wall (comme Contest/ExtraChest) → chantier live-ops ultérieur. `nav PRIZE_WALL` : à vérifier s'il hang ou est gaté. |
| `GetServers` (windows) | **[Phase 2 C]** | liste de serveurs = launcher multi-serveur (chantier C). |
| `RequestResync` (powerpromote/pvp/windows) | **[OK-faible]** | requête générique fire-and-forget (le serveur resync déjà après chaque mutation) ; pas de hang. Documenté, non implémenté. |

⇒ **Bilan A2 (étape 2)** : sur 14 — **3 IMPLÉMENTÉS** (invasion member rank ✅ en jeu ; blocked + save : réel/fidèle, headless+wire,
`WiringGapsTest`, régression **156/156**) ; **3 non-gaps** (HEIST retiré) ; **1 debug-only** (ChestConsumableHistory) ; **1 →
étape 4** (CodebaseAttackLogs) ; **1 différé** (PrizeWall = feature event, pas de faux endpoint) ; **1 Phase 2 C** (GetServers) ;
**3 faibles** (RequestResync, fire-and-forget). **Aucun faux endpoint créé** — seules des réponses RÉELLES ou FIDÈLES (vide =
état correct du serveur) ont été ajoutées. Re-run A2 : les 3 messages implémentés ont disparu des manques.

**A1 (inventaire)** : 179 écrans listés dans `AUDIT_SCREENS.md`. La majorité des MODES est déjà ✅ en jeu (cf.
`EXPLORATION.md`) ; le croisement fin « chaque écran individuel rejoué » se fait via le balayage en jeu (méthode §7.4 HUB_NAV).
Aucun MODE oublié détecté ; le reste = sous-écrans/onglets d'un mode déjà couvert (à cocher au balayage).

**A5 (couverture des `.tab`)** — `AUDIT_TABS.md` : **272 `.tab` sur disque, 265 référencées, 67 classes `Stats`**.
- **Q : le code du jeu associe-t-il chaque `.tab` à une partie du jeu ? → OUI.** Chaque `.tab` est déclarée par une classe
  `Stats` (le PACKAGE = le mode/feature). Carte complète auto-générée dans `AUDIT_TABS.md` (ex. `arena_*.tab`→`ArenaStats`,
  `battle_pass_v2_*`→`BattlePassV2Stats`, `invasion_*`→`InvasionStats`…).
- **Orphelines sur disque** : `content.{1,13,14,21,23,25,99}.tab` (⚠️ **PAS orphelines** — chargées par nom CONSTRUIT
  `content.<shard>.tab` via `ContentStats`) + `invasion_boss_rewards.tab` (loot tiré CÔTÉ CLIENT, §SHIMS #25 — normal).
- **~~Référencée mais absente : `unit_abilities.tab`~~ → RÉSOLU (2026-08-24, étape 1) : FAUX POSITIF de l'outil, PAS un gap.**
  L'APK ne livre `unit_abilities` et `friendship_campaign` qu'en variante **BINAIRE `.tabb`** (double « b » ; les 2 seuls
  binaires) — bien extraites sur disque. Le jeu les charge **binaire-d'abord** (`ServerStats.forceText()=false` → « essaie
  `.tabb` puis `.tab` » ; `StatFileHelper`). **Preuve** : `friendship_campaign.tabb` alimente la campagne d'amitié ✅ vérifiée
  en jeu → la résolution `.tabb` marche, donc `unit_abilities.tabb` l'est aussi. L'outil A5 flagguait à tort car (a) le regex
  `\.tab` tronquait `.tabb`, (b) le glob `*.tab` ratait `.tabb` → **corrigé** (regex `\.tabb?` + normalisation `.tabb`→`.tab`).
  **Besoin serveur** : le serveur ne nomme JAMAIS `AbilityStats`/`unit_abilities` (grep server/java = 0) → **client-only**
  (données d'aptitudes des héros, lues par la SIMULATION de combat côté CLIENT, qui est client-autoritative via unidbg ; le
  serveur ne rejoue que la progression `recordOutcome`, pas la simulation). Aucun ajout artificiel — le fichier est présent et
  chargeable si un chemin serveur venait à en avoir besoin. ⇒ **[OK-connu]**, non-gap.
- **⚠️ « Nommée serveur » est APPROXIMATIF** : beaucoup de features à `—` sont en réalité UTILISÉES via la logique du jeu que
  le serveur exécute (sans que notre glue nomme la classe) — **CONFIRMÉ implémentées** : `CampaignStats`, `FriendshipStats`/
  `FriendshipCampaignStats`, `CollectionStats` (CollectionClaim/Mastery/Avatar ✅), `GuildCheckInStats` (GuildCheckInTest ✅),
  `SpotlightTrialStats`/`TeamTrialsStats` (✅ en jeu), `EnchantingStats` (✅), `PrizeWallStats` (MEDALS ✅ via COMPLETE_QUEST),
  `PortStats`, `RealGearStats`, `ModStats`, `CosmeticCollectionStats`… → **faux négatifs**, à NE PAS re-signaler.
- **VRAIS candidats « data présente, mode/feature NON câblé »** (à trancher un par un — implémenter, ou documenter « hors
  scope/retiré » avec justification) :
  | Feature | Classe `Stats` (.tab) | Verdict / note |
  |---|---|---|
  | `heist` | `HeistStats` (4) | **[OK-connu]** mode RETIRÉ du jeu (EXPLORATION 💤) — data résiduelle, rien à câbler |
  | `codebase` | `CodebaseStats` (4) + `codebase_use` | **[GAP mode]** « The Codebase » non implémenté (cohérent A2 `GetCodebaseAttackLogs`) |
  | `campaign.reinfection` | `CampaignReinfectionStats` (4) | **[GAP feature]** variante campagne « reinfection » non câblée |
  | `chest.upgrade` | `ChestUpgradeStats` (3) | **[GAP feature]** pistes d'amélioration de coffre (chest upgrade tracks) |
  | `emerald` | `EmeraldStats` (4) | **[à vérifier]** gear/monnaie émeraude — les slots emerald sont déjà gérés en lineup ; la FEATURE dédiée ? |
  | `airdrop` | `AirDropStats` (2) | **[GAP feature]** air drop (récompenses larguées) |
  | `herospotlight` | `HeroSpotlightStats` (1) | **[à vérifier]** hero spotlight (distinct du spotlight trial ✅) |
  | `marketing`/`misc.Offerwall`/`video`/`content.StarterDeal`/`DeepLink`/`misc.DisneyEmoji`/`misc.SupportLinks` | (1 ch.) | **[hors scope]** marketing/IAP/périphérique (store FERMÉ, cf. audit store §7.3) |

  ⇒ **Vrais GAPS de mode/feature à décision** : `codebase`, `campaign reinfection`, `chest upgrade`, `airdrop`
  (+ `emerald`/`herospotlight` à vérifier). Les autres = retirés (heist) ou hors scope (marketing/IAP).

### ÉTAPE 3 — analyse `content.N.tab` : détermination de l'ère + faisabilité « choix d'ère » (2026-08-24, g174) — ANALYSE, PAS d'implémentation

**Comment l'ère est déterminée (faits, bytecode)** :
- `ContentStats extends TimeTable<String, ContentColumn>` : `getColumn(date)` = la colonne (release Rn) dont la date ≤ `date`
  (table = `content.<shard>.tab`, chargée par `ContentHelper.setShardID(shard, {})`).
- `getServerColumn()` = `getColumn(serverTimeNow())` ; `getServerColumn(IUser)` = `getColumn(serverTimeNow() + getUserOffset(userID))`.
  ⇒ **l'ère = résolution DATE→colonne** ; un offset de contenu PAR-USER existe nativement (`setUserOffset`/`getUserOffset`).
- Le serveur envoie `BootData.serverTime = serverTimeNow()` (ServerUser ~l.212).

**AdminClock vs AdminSeason (déjà en place)** :
- `AdminClock` (`CLOCK_OFFSET`→`serverTimeNow`) : bouge ÈRE (getServerColumn) + SAISON + **TOUS les timers joueur** + `BootData.serverTime`
  de façon COHÉRENTE (« le monde entier à la date X ») → client consistant. ✅ vérifié §8. *Effet* : impossible d'isoler l'ère des
  timers (viser la date de R95 recule aussi resets/cooldowns).
- `AdminSeason` (`SEASON_ANCHOR_OFFSET`→`seasonTimeNow`) : bouge UNIQUEMENT la sélection de saison des trials (interne serveur) ;
  **ne touche PAS l'ère de contenu**.

**Faisabilité + DÉPENDANCE CACHÉE** :
- **Levier natif** : mapper une release → sa date (ligne « dates » de `content.N.tab`) et régler l'horloge dessus = **wrapper mince
  au-dessus d'AdminClock** (release Rn → date → `CLOCK_OFFSET`). Aucun mécanisme nouveau requis.
- **⚠️ DÉPENDANCE CACHÉE** : `BootData.serverTime` pilote À LA FOIS (a) la résolution du CONTENU DATÉ côté CLIENT et (b) l'AFFICHAGE
  des timers (resets/cooldowns) côté client. Pas de champ « date de contenu » distinct de l'horloge dans `BootData`. ⇒ On ne peut PAS
  décaler le CONTENU sans décaler l'HORLOGE PERÇUE par le client (donc l'affichage des timers). Le serveur, lui, ENFORCE les timers
  sur son horloge réelle (g151) → gameplay non cassé, mais AFFICHAGE client des resets suivrait l'ère choisie.
- `ContentStats.setUserOffset` NE règle PAS le problème : il décale le contenu CÔTÉ SERVEUR seulement ; le client résout par
  `BootData.serverTime` (non décalé) → désynchro affichage (client R102 / serveur R95), sauf à décaler aussi `BootData.serverTime`
  (ré-introduit le couplage timers).

**VERDICT étape 3 (documenté, NON implémenté — consigne « documenter les dépendances d'abord »)** :
- Release-picker admin = **faisable et sûr** UNIQUEMENT comme wrapper d'AdminClock (release→date→horloge), en ACCEPTANT que
  l'affichage client des timers suive l'ère (même compromis qu'AdminClock, déjà vérifié). Le serveur garde l'enforcement réel.
- « Choix d'ère SANS toucher les timers » = **PAS proprement réalisable** : le jeu couple contenu-daté ↔ horloge via l'unique
  `BootData.serverTime` ; le forcer exigerait des modifs CLIENT (hors §1) ou désynchroniserait l'affichage de contenu.
- ⇒ **DÉCISION UTILISATEUR (chantier D)** : (1) release-picker = wrapper AdminClock (accepte décalage affichage timers) ; ou
  (2) rester sur AdminClock brut ; ou (3) vrai découplage (modifs client, hors §1). **Pas d'implémentation avant ce choix.**

---

**`content.N.tab` — richesse pour l'admin (ta 2ᵉ question)** : c'est une **TimeTable d'ère** (colonnes = DATES de release
R102→R1 ; lignes = clés de contenu). Contient par release : **Max Chapter, Max TL, Max GL, Max Rarity, Max Trials/Port
Difficulty**, nœuds de chapitre (`CH_100_NODE_*`), exclusivités battle-pass, sorties de héros… Déjà UTILISÉE pour résoudre
l'ère (`ContentStats.getServerColumn(date)` ; `AdminClock`/`AdminSeason` décalent la date → change l'ère servie). **Piste
admin (chantier D)** : surfacer ces données pour **choisir l'ère/release** à servir (et donc le plafond de contenu — chapitres,
TL, raretés, difficultés) via le panneau opérateur, plutôt que par décalage de date brut. À planifier en D (panneau opérateur).

---

## Autres chantiers — sous-étapes (à engager après A)

### B. Performance (non destructive)
- [ ] Profilage combat (unidbg/frame) — mesures avant/après, A/B bit-fidèle.
- [ ] Opt.3 : backend spine **Java** certifié contre l'oracle unidbg (matrice héros/graines).
- [ ] Profilage serveur (sérialisation wire, verrous store↔user), warm-ups.
- [ ] Temps de boot client (assets/spine) — cache/lazy-load, mesuré.

### C. Front-end joueur (launcher)
- [ ] Liste de serveurs (favoris, ping, nb joueurs, version) + création/login multi-serveur.
- [ ] Redirection `ServerType.LIVE` → serveur choisi (aujourd'hui 127.0.0.1 en dur via `content_server.py`).
- [ ] UX self-hosting (« héberger » = lance serveur local + launcher).

### D. Backend/hébergement
- [ ] Panneau opérateur unifié (englobe Admin*).
- [ ] Multi-shard (création/isolation, bus inter-shard), découverte, observabilité, sauvegarde/restore, rechargement à chaud.

### E. Intégration APK
- [ ] Vrai client APK Android → serveur (redirection `/login`, boot, jeu).
- [ ] Parité desktop↔APK ; versionnage multi-APK.

### F. Tests inter-machines réels
- [ ] Réseau réel (latence/perte/MTU), NAT/pare-feu/UPnP, TLS, DNS/annuaire.
- [ ] Charge/stress (N joueurs × N shards), soak 24 h+, reconnexions.
- [ ] Multi-région (FR↔autre continent), sécurité réseau (rejeu/forge, rate-limit, bans).

### G. Qualité/outillage
- [ ] CI (régression + WireCheck/ClientOracle), reproductibilité build pinnée.
- [ ] Doc self-hoster (install/dépannage), cadre légal usage privé.
- [ ] Migration de sauvegardes inter-schémas (généraliser + tester).

---

## Journal des mises à jour de ce suivi
- **2026-08-24 (g170)** — création du doc de suivi + cadrage de l'audit global (chantier A, 4 axes) et de l'outil `tools/audit`.
