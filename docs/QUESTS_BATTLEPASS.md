# QUÊTES & BATTLE PASS — couverture serveur (analyse par les faits, 2026-07-19)

Analyse demandée (user) : les quêtes sont-elles **toutes** prises en charge par le serveur ? Le battle pass
(hors niveau) est-il payant, à considérer « acheté pour tous », et bien pris en charge partout ? Établi **par
les faits** (`.tab` extraits + bytecode `game.jar`), conformément à PRINCIPLES §8.

## 1. QUÊTES — types (`game-data/stats/quests.tab`, colonne `QUEST_TYPE`)

| Type | Nombre | Mécanisme de **claim** | Statut serveur |
|---|---|---|---|
| `ACHIEVEMENT` | 1469 | `COMPLETE_QUEST` → `QuestHelper.completeQuest` | ✅ **géré & vérifié EN JEU** (MEDALS → fragments Vanellope ×10/Yax ×2 crédités+persistés) |
| `DAILY_QUEST` | 34 | `COMPLETE_QUEST` (claim) + `VIEW_DAILY_QUESTS` (vue) | ✅ **géré** (claim uniforme + marquage vu) |
| `FREE_STAMINA` | 6 | `COMPLETE_QUEST` | ✅ **géré** (mais **time-gated** « 21h-23h » → réclamable seulement en fenêtre) |
| `MONTHLY_CARD` | 3 | `COMPLETE_QUEST` | ✅ code géré, MAIS exige `UserFlag.MONTHLY_DIAMOND_DAYS>0` = **abonnement PAYANT acheté** → sinon `completeQuest` **refuse** (correct : pas d'achat = pas de claim) |
| `MEGA_MONTHLY_CARD` | 3 | `COMPLETE_QUEST` | idem (payant) |
| `PRIZE_WALL_MEDAL` | 56 | feature **Prize Wall** (séparée) | ⬜ **non testé** (feature à part, gated par progression) |
| `PRIZE_WALL_TASK` | 10 | Prize Wall | ⬜ non testé |

**Note** : `BUTTON_ACTION` de `quests.tab` = **cible de NAVIGATION** (où aller pour progresser : `skills`,
`arena`, `campaign/NORMAL?chapter=2&level=7`, `chestDetails/GOLD`…), **pas** une commande serveur. Le **claim**
de TOUTE quête passe par la même commande **`COMPLETE_QUEST`** (qui branche par type en interne : monthly card,
weekly counter, etc.). Le serveur l'exécute via `QuestHelper.completeQuest` (logique du jeu, anti-triche réel).

### Actions quêtes NON encore gérées (gaps identifiés)
- **`REDEEM_DAILY_QUESTS`** = claim des **récompenses WEEKLY** (barre « Rewards 0/5 » de l'écran QUESTS →
  `QuestHelper.claimWeeklyReward`). **Non géré**, MAIS **non atteignable actuellement** (fait : `canRedeemWeeklyRewards`
  exige **lundi** (`getUserDailyActivityDayOfWeek==2`) + `LAST_REDEEMED_WEEKLY_REWARDS + 2j < now` + progression >0 ;
  compte neuf = 0/105). → **à implémenter quand atteignable** (lundi + quêtes hebdo faites).
- **`START_QUEST`** = démarrer certaines quêtes. **Non géré** (à vérifier quelles quêtes l'exigent).
- **Progression weekly** (« Quests 0/105 ») : **suivie** via `UserFlag.WEEKLY_DAILY_QUESTS_COMPLETE`, incrémentée
  par `completeQuest` sur un `DAILY_QUEST` → dans `this.extra` (**auto-persisté**) ✅. Seul le **claim** hebdo
  (REDEEM) manque.

## 2. BATTLE PASS V2 — payant ? bien pris en charge ? (`battle_pass_v2_*.tab` + bytecode)

**Deux verrous, prouvés :**
1. **Niveau d'équipe 11** (`unlockables.tab` `BATTLE_PASS=11` ; on est TL2 → onglet désactivé, `listeners=[]`).
2. **Saisonnier** (`battle_pass_v2_constants.tab`) : `SEASON_START_TIME = 2026-04-07`, `HIDE_BATTLE_PASS_AFTER =
   2026-04-30`. Notre serveur envoie **juillet 2026** (`System.currentTimeMillis`) → la saison est **TERMINÉE**
   → `battlePassHidden()` vrai. **Même décalage d'ère que la stamina** (client figé fév. 2023, contenu daté 2026,
   et même dans 2026 la fenêtre de saison est passée).

**Payant ?**
- **Track GRATUIT** : accessible à **tous** (une fois TL11, pendant la saison) — `getUnclaimedFreeRewards`.
- **Track PREMIUM** : **PAYANT** (`getPremiumUnlocked()` / champ `boughtBattlePass`, achat réel) — `getUnclaimedPremiumRewards`.
- **Buyout** : **PAYANT en DIAMANTS** (`BUYOUT_COST` par palier dans `battle_pass_v2_tiers.tab`, ex. 625/650…).

**Pris en charge partout ?** — Actions battle pass : **`UPDATE_BATTLE_PASS`, `VIEW_BATTLE_PASS_SCORE`,
`BATTLE_PASS_V2_*` (claim/buyout) = AUCUNE gérée** côté serveur. La progression BP vient des quêtes quotidiennes
(`MAX_DAILY_POINTS=15`, `QUEST_POINT_COOLDOWN=24h`). **Non atteignable actuellement** (TL11 + saison passée) →
handlers à écrire quand la feature est active. **✅ Fait (g7)** : on envoie `BootData.battlePassV2Data{type=QUEST}`
pour ne plus **crasher** l'écran QUESTS (`computeRewards(DEFAULT)`).

**« Considérer acheté pour tous » (idée user, serveurs d'achats fermés)** : **faisable** — poser
`battlePassV2Data.boughtBattlePass=1` (et l'état premium) dans `bootData()` → `getPremiumUnlocked()` vrai → track
premium débloqué pour tous, **sans achat réel**. C'est un **choix d'OPÉRATEUR légitime** (le serveur ré-hébergé
décide de son économie ; §3). ⚠️ **MAIS** ça n'a d'effet que si la saison BP est **ACTIVE** — or à juillet 2026
elle est passée. ⇒ dépend de la **décision d'ère de contenu** (cf. ci-dessous).

> **⚠️ MISE À JOUR (2026-07-21) — cette section 2/3 est DÉPASSÉE (historique).** Le battle pass est désormais
> **entièrement fonctionnel EN JEU** : handlers serveur complets (claim/collect/buyout/rollover/premium-pour-tous),
> et l'**ère de contenu est résolue POUR LE BATTLE PASS** via **stat-sync** (`BootData.statDataTxt` pousse un
> `battle_pass_v2_constants.tab` à saison courante → le client voit la saison active). Vérifié EN JEU à TL65 :
> onglet actif, `BattlePassV2GetData`↔`BattlePassV2Data(premium)`, **collecte** de palier créditée+persistée,
> compteur « ① 0/9 » = palier courant + `QUEST_POINTS`/seuil serveur-autoritatif. Cf. `MEMORY.md` g18/g19,
> `docs/EXPLORATION.md`. **NB** : la stamina géante (39,96 M) et le reste du contenu daté restent sur l'ère R102
> (le stat-sync n'a ciblé QUE `battle_pass_v2_constants`) — un alignement global d'ère reste un chantier séparé.

## 3. Décision de fond commune : l'ÈRE DE CONTENU (ouverte)

Battle pass (saison 04/2026), stamina (regen R102 = 39,96 M), et tout le contenu daté butent sur le **même
choix** : notre serveur envoie **juillet 2026** à un client **figé fév. 2023**. Options (cf. `SHIMS`/MEMORY,
décision user attendue) : (a) garder l'ère fin-de-vie 2026 (authentique mais BP/events passés, stamina géante) ;
(b) **ancrer la date serveur dans une fenêtre de contenu valide** (ex. pendant une saison BP) → BP actif, events
actifs, stamina saine, **et** premium-for-all prend son sens ; (c) surcharger des `.tab` ciblés. **Le battle
pass premium-for-all n'est pleinement testable qu'avec une saison ACTIVE** (option b).

## 4. Verdict (par les faits)

- **Quêtes** : le **cœur est géré** (claim = `COMPLETE_QUEST` ✅ pour ACHIEVEMENT/DAILY/FREE_STAMINA/monthly-si-acheté ;
  `VIEW_DAILY_QUESTS` ✅ ; progression weekly suivie & persistée ✅). **Gaps** : `REDEEM_DAILY_QUESTS` (claim weekly,
  lundi-gated → non atteignable maintenant), `START_QUEST`, Prize Wall — **à traiter quand atteignables**.
- **Battle pass** : hors niveau, il est **partiellement payant** (premium + buyout ; track gratuit pour tous).
  **Non pris en charge côté serveur** (aucun handler BP) et **non atteignable** (TL11 + saison passée). Le crash
  d'écran est corrigé. « Acheté pour tous » = faisable et légitime, **mais** subordonné à la décision d'ère de
  contenu (une saison BP active).
