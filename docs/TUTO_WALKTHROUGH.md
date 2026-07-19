# TUTO WALKTHROUGH — relevé manuel (clic à clic) + analyse auto-pilote (2026-07-19)

Parcours **manuel** du tuto post-équip (client 100 % d'origine + notre serveur), **sans auto-pilote**, via
l'outil de **clic manuel** `dh.clickfile` enrichi d'un **dump du clic** (`TutorialDriver.dumpClickTarget` :
hit-test `Stage.hit` au point cliqué → chaîne cible→ancêtres avec **classe / tag tutoriel / name / listeners /
texte** + écran + fenêtres). Chaque ligne du clic-fichier `x,y` tape (et dumpe) ; `dump x,y` = dump SANS taper.

But (demande user) : **enregistrer chaque élément d'UI activé** pour savoir (a) **quoi faire détecter/câbler à
l'auto-pilote**, et (b) **ce qui ne marche pas client↔serveur**. Verdict : **client↔serveur = tout marche**
(aucune action non gérée) ; le blocage de l'auto-pilote est **côté pilote** (pointeurs de tuto non rendus/vides
en headless → rien à suivre).

## 1. Séquence relevée (écran → cible → tag → réaction serveur)

| # | Écran | Cible cliquée (tag tutoriel / name) | Classe | Réaction serveur |
|---|---|---|---|---|
| 1 | MainScreen | **`MAIN_SCREEN_CHESTS`** (name=CHESTS) | MainScreenHitArea | → ChestsScreen |
| 2 | ChestsScreen | **`SILVER_CHEST_CARD`** / `SILVER_ACTION_BUTTON` | DHTextButton | → SilverChestDetailScreen |
| 3 | SilverChestDetailScreen | **`SILVER_CHEST_FREE_BUTTON`** / `CHEST_BUY_ONE_BUTTON` / name=`FREE_NOW` | DFTextButton | **`BuyChests1` → `LootResults` (coffre SILVER, persisté)** + `RECORD_SERVER_ROLL_FINISHED` |
| 4 | …/ `ChestResultsWindow` | **`BACK_BUTTON`** (name=base/buttons/button_back) | Table | ferme la fenêtre de récompense |
| 5 | SilverChestDetailScreen | **`BASE_MENU_BUTTON`** (name=MENU_HAMBURGER) | Table | ouvre le SideMenu |
| 6 | SideMenu | **`BASE_MENU_HERO_BUTTON`** (name=icon_heroes) | SideMenuIcon | → HeroListScreen |
| 7 | HeroListScreen | **`HERO_LISTFROZONE`** (name=FROZONE) | HeroListCard | → HeroDetailScreen |
| 8 | HeroDetailScreen | **`HERO_GEAR_SLOT_ SIX`** [ESPACE] / `HERO_SUMMARY_ITEM_SLOT5` | PressableStack | ouvre la CraftingWindow (équip) |
| 9 | CraftingWindow (modale) | bouton **EQUIP** (DFTextButton, arbre CraftingWindow) | DFTextButton | **`Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP, SLOT=SIX}` → « appliquée [persisté] »** (Power 62→77) |
| 10 | HeroDetailScreen | **`BACK_BUTTON`** | Table | → HeroListScreen |
| 11 | HeroListScreen | **`FILTER_BUTTON`** (name=FILTER) | DHTextButton | ouvre la **HeroFilterWindow** (Sources/Effects/Stats…) |
| 12 | …/ `HeroFilterWindow` | **`BACK_BUTTON`** | Table | ferme le filtre |

**Navigation** : `BACK_BUTTON` (name=`base/buttons/button_back`, tag `BACK_BUTTON`/`BACK_BUTTON_WRAP`) **dépile
un écran à la fois** (HeroDetail→HeroList→SilverChestDetail→Chests→MainScreen). Burger = `BASE_MENU_BUTTON`.

## 2. Client ↔ serveur — RIEN de cassé

À chaque étape « à effet serveur », le protocole a fonctionné et **persisté** :
- **Coffre** : `BuyChests1` → le serveur roule la table (`SILVER`) → `LootResults` (badge) → persisté ; puis
  `RECORD_SERVER_ROLL_FINISHED` (NO-OP fidèle) et `VIEWED_CHESTS` (horodatage).
- **Équip** : `Action{EQUIP_ITEM, FROZONE, BADGE_OF_FRIENDSHIP, SLOT=SIX}` → « appliquée [persisté] » ; **en jeu
  Power 62→77**, badge visible en slot SIX. (Le fix « honorer le SLOT client » est donc bien exercé.)
- **Tuto** : les `ChangeTutorialStep` (INTRO_FEATURES) sont appliqués/persistés ; **0 action non gérée** sur tout
  le parcours.
⇒ **Le serveur n'est PAS le blocage** de la progression du tuto.

## 3. Pourquoi l'AUTO-PILOTE cale (analyse) + quoi câbler

- **Cause** : le pilote (`TutorialDriver`) suit `TutorialHelper.getPointers` (la flèche du tuto). Sur ces
  **sous-écrans du hub** (ChestsScreen, HeroListScreen, HeroDetailScreen…), la **flèche de tuto ne se rend pas /
  `getPointers` renvoie vide en headless** (déjà constaté sur la carte de campagne g2d, cf. MEMORY §6ter). Sans
  pointeur, le pilote n'a **aucune cible désignée** → il idle puis fait RETOUR → il ne franchit pas l'étape
  (symptôme « bloqué à HERO_FILTERS »). En **manuel**, la séquence marche parfaitement (§1).
- **Correctif proposé (pilote, à câbler)** : **piloter par l'ÉTAPE de tuto connue, pas seulement par la flèche.**
  Le serveur SAIT à quelle étape on est (`ChangeTutorialStep`), et chaque acte de tuto expose sa **cible
  `UIComponentName`** (ex. `HeroFiltersActV1.Step.getPointer()` → `FILTER_BUTTON`). ⇒ Quand `getPointers` est
  vide, résoudre la cible via l'acte de tuto courant → **taper l'acteur par son tag connu** (tags relevés en §1),
  avec la table de correspondance écran→tag ci-dessus. Ne relève d'AUCUNE modif jeu/serveur (pilote DEV).
- **Garde-fous à conserver/étendre** : ne PAS fermer les **fenêtres de FLUX** (CraftingWindow d'équip,
  HeroFilterWindow) via le drainage de popups — y **taper** l'action (EQUIP / toggle) puis le tuto avance.
- **Limite de l'outil de dump** : `dumpClickTarget` hit-teste le stage de l'écran ; certaines **modales**
  (CraftingWindow) sont sur une couche que ce hit-test manque (il a montré `UnitDisplay` au lieu du bouton EQUIP,
  alors que le **vrai tap** a bien touché EQUIP → serveur OK). La HeroFilterWindow, elle, est bien capturée.
  À améliorer : itérer toutes les couches/`Stage` (ou la fenêtre du dessus) pour le hit-test de dump.

## 4. Méthode (réutilisable) — clic manuel + enregistrement

1. Lancer **sans** auto-pilote : `DH_CLICKFILE=/tmp/dh_clicks.txt ./run-online.sh` (capture continue
   `build/manual.ppm` toutes 10 frames). Reprise rapide via snapshot post-équip.
2. Convertir `manual.ppm`→png, **regarder** l'écran, décider où cliquer.
3. Écrire `x,y` (ou `dump x,y` pour observer sans taper) dans le clic-fichier → le jeu **dumpe** l'acteur
   touché (classe/tag/listeners) **puis tape** ; lire `[clicdump]` (client) + `/tmp/dh_game.log` (serveur).
4. Répéter, enregistrer la chaîne (table §1). Câbler l'auto-pilote sur les tags relevés (§3).

## 5. Reste à observer (prochaines étapes du tuto)

- Après équip+filtre, INTRO_FEATURES enchaîne vers **CAMPAGNE** (Ralph « Ready when you are! », flèche vers le
  centre du hub = carte de campagne g2d) — à relever de même.
- **SIGN IN claim** : le bâtiment SIGN IN (hub) ouvre `SignInScreen` (UIScreen), le serveur répond 31 jours
  (confirmé en jeu, cf. SIGNIN_EVENTS §6). Le bouton CLAIM est un **DFTextButton** (le tag `claim_button` est
  un **nom de son**, pas un tag d'acteur) → à taper par collecte de bouton-texte, pas par tag.
