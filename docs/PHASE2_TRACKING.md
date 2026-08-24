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
| **A2** | Câblage : messages envoyés par écran non routés par `LoginServer` | `audit.sh a2` (`ScreenContract` §C) | 📄 `docs/AUDIT_WIRING.md` | 🚧 14 manques → triés (voir notes) |
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

**A2 (câblage) — triage des 14 manques** (`AUDIT_WIRING.md`) :
| Message | Verdict | Raison |
|---|---|---|
| `GetHeist`, `StartHeist`, `KickHeistParticipant` (heist) | **[OK-connu]** | HEIST **retiré du jeu** (HUB_NAV §7.2 : désactivé 9999) — écran inatteignable, rien à câbler |
| `GetServers` (windows) | **[Phase 2 C]** | liste de serveurs = front-end launcher multi-serveur (chantier C), pas un manque de mode |
| `RequestResync` (powerpromote/pvp/windows) | **[à vérifier — faible]** | requête générique de resync, non routée ; probablement fire-and-forget (le client ne bloque pas). À confirmer en jeu qu'aucun écran ne hang. |
| `GetGMemInvasionRankInfo` (invasion) | **[GAP réel]** | classement invasion par membre de guilde — onglet rankings potentiellement vide |
| `GetPrizeWallData` (prizewall, screens) | **[GAP réel]** | Prize Wall (mur de lots d'event) — écran vide si atteint |
| `GetChestConsumableHistory` (screens) | **[GAP réel — mineur]** | historique d'ouverture de coffres (écran journal) |
| `GetCodebaseAttackLogs` (screens) | **[GAP réel — mineur]** | journaux d'attaque « codebase » |
| `GetBlockedList` (windows) | **[GAP réel — mineur]** | liste des joueurs bloqués (social/chat) |
| `GetUserSaveData` (windows) | **[GAP réel]** | données de sauvegarde/transfert de compte |

⇒ **Bilan A2** : sur 14, **3 non-gaps** (HEIST retiré), **1 = Phase 2 C** (GetServers), **3 faibles** (RequestResync,
fire-and-forget à confirmer), **7 GAPS réels** à trancher (handler vide/réponse minimale, comme les hall-of-fame du contest).
La plupart = écrans secondaires (rankings/journaux/social) → correctif type « réponse vide » pour débloquer l'écran, à
vérifier EN JEU un par un. Prochaine sous-étape : implémenter les réponses vides + vérif en jeu, en commençant par les plus
visibles (invasion rank, prizewall).

**A1 (inventaire)** : 179 écrans listés dans `AUDIT_SCREENS.md`. La majorité des MODES est déjà ✅ en jeu (cf.
`EXPLORATION.md`) ; le croisement fin « chaque écran individuel rejoué » se fait via le balayage en jeu (méthode §7.4 HUB_NAV).
Aucun MODE oublié détecté ; le reste = sous-écrans/onglets d'un mode déjà couvert (à cocher au balayage).

**A5 (couverture des `.tab`)** — `AUDIT_TABS.md` : **272 `.tab` sur disque, 265 référencées, 67 classes `Stats`**.
- **Q : le code du jeu associe-t-il chaque `.tab` à une partie du jeu ? → OUI.** Chaque `.tab` est déclarée par une classe
  `Stats` (le PACKAGE = le mode/feature). Carte complète auto-générée dans `AUDIT_TABS.md` (ex. `arena_*.tab`→`ArenaStats`,
  `battle_pass_v2_*`→`BattlePassV2Stats`, `invasion_*`→`InvasionStats`…).
- **Orphelines sur disque** : `content.{1,13,14,21,23,25,99}.tab` (⚠️ **PAS orphelines** — chargées par nom CONSTRUIT
  `content.<shard>.tab` via `ContentStats`) + `invasion_boss_rewards.tab` (loot tiré CÔTÉ CLIENT, §SHIMS #25 — normal).
- **Référencée par le code mais ABSENTE du disque** : **`unit_abilities.tab`** → notre `extract_game_data.sh` ne l'extrait
  pas. **[À VÉRIFIER]** : le serveur en a-t-il besoin ? (le combat est client-autoritatif via unidbg → probablement non côté
  serveur, mais à confirmer ; sinon = simple lacune d'extraction sans impact serveur).
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
