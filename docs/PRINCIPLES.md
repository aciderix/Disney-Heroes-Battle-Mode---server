# Principes de développement (règles non négociables)

Adapté des principes du portage DragonSoul (dépôt de référence) et des consignes du
projet. **Toute décision technique doit s'y conformer.** Voir aussi
[`ARCHITECTURE.md`](ARCHITECTURE.md), [`PROTOCOL.md`](PROTOCOL.md).

## 1. Modifications minimales du jeu
Le jeu (`com.perblue.heroes.*` + libGDX) reste **aussi proche que possible de l'original**.
On ne remplace que la **couche plateforme** (Application/Graphics/Input/Files/Audio/GL,
réseau) par un backend desktop maison, et on fournit un **serveur**. Le seul traitement du
bytecode autorisé est une **normalisation non-sémantique** pour le charger hors Android
(remap de collisions de noms, correction d'attributs incohérents laissés par dex2jar).
**Jamais** de patch de logique de jeu. On privilégie toujours la **compréhension du
fonctionnement réel** plutôt que des modifications destinées à contourner un problème.

## 2. Aucune rustine — un shim doit être FONCTIONNEL
Il ne faut **jamais** « faire croire » au jeu que tout va bien pour passer une étape :
pas de faux « OK » à une vérification, pas de bypass de sécurité, pas d'état forcé. Un
faux acquiescement produit un jeu **cassé plus tard**, impossible à déboguer car la cause
est perdue. Chaque substitution est soit **RÉELLE** (équivalente à l'originale), soit
explicitement notée PARTIEL/NO-OP/FACTICE **avec son risque** (registre des shims à tenir,
cf. `SHIMS.md` à créer). **Résoudre les causes, jamais masquer les symptômes.**

## 3. Le serveur est la source de vérité (autoritatif)
Le serveur doit être **entièrement autoritatif** pour limiter la triche. Il **ne contient
aucune copie écrite à la main** des données du jeu ; il utilise **directement les données
officielles** comme référence. On le construit **en miroir du code du jeu** (mêmes calculs
de coûts/loot/combat via les classes/données du jeu), pas en réinventant les règles.

## 4. Aucune réécriture manuelle — on EXTRAIT le code/les données du jeu (par commande)
Règle **non négociable** : on ne **réécrit rien** du jeu à la main (ni données ni **code**).
Tout ce qui vient du jeu est **extrait automatiquement** par un outil/commande, dans des fichiers
régénérables — jamais retranscrit/deviné/inventé à la main.
- **Données** (stats, équilibrage, objets, compétences, textes) : extraites via
  `tools/extract_game_data.sh` → `game-data/`. Jamais recopiées.
- **Code Java du jeu** (`com.perblue.heroes.*` + core libGDX) : **utilisé tel quel** depuis
  `libs/game.jar` (décompilé par `tools/decompile.sh`). On ne réimplémente **jamais** une classe
  de logique de jeu — on fait tourner l'originale.
- **La SEULE couche écrite à la main = la plateforme** (backend desktop `dhbackend/` :
  Application/Graphics/GL/Input/Files/Audio/Net/Preferences + bridges plateforme), gardée
  **minimale** (miroir de `dsbackend/` DragonSoul). Elle ne contient **aucune** logique de jeu.
- **Binaires natifs** (`gdx`, `gdx-freetype`, `spine-native`) : ce sont du **code du jeu** aussi.
  On fournit le **binaire d'origine** quand c'est possible. À défaut (absent pour x86_64), un
  rebuild n'est acceptable QUE s'il est **vérifié fidèle bit-à-bit au comportement de la lib
  d'origine** (désassemblage de la lib ARM = source de vérité), **jamais** un comportement
  **inventé/deviné**. Toute glue JNI écrite à la main doit reproduire EXACTEMENT l'original
  (format de sommets/drawCalls, conventions d'ID, parsing) — un rendu qui diverge d'une capture
  du jeu original est un **bug**, pas une approximation acceptable.

## 4bis. Fidélité vérifiée contre le jeu original
Le rendu et le comportement doivent être **identiques au jeu original**. On compare régulièrement
à des **captures d'écran du jeu d'origine** ; tout écart visuel/fonctionnel (élément d'UI buggé,
décor mal rendu, effet manquant) est un **défaut à corriger en revenant au comportement d'origine**,
jamais à ignorer ni à masquer. La cause est toujours un écart entre notre couche et l'original →
on la résout à la source (extraction/fidélité), pas par un contournement cosmétique.

## 5. Multi-serveur dès le départ
L'architecture prévoit dès maintenant : héberger son propre serveur, **lister / rejoindre**,
**mot de passe optionnel**, **mode sécurisé**. Les choix locaux ne doivent pas fermer cette
porte. (Détails : `ARCHITECTURE.md`.)

## 6. Persistance complète et fidèle
Sauvegarder un joueur = **tout** son état (or, énergie, diamants, héros, équipement,
progression campagne, tuto, lineups, flags…), pas un sous-ensemble. Une persistance
partielle est **pire qu'inutile** (incohérences). Sérialisation via les **classes du jeu**
(octets identiques au wire).

## 7. Reproductibilité & reprise
Le conteneur peut se réinitialiser : **commit/push réguliers** sur la branche de travail ;
les artefacts lourds (APK, jars décompilés, assets) sont **régénérables par script** et
**non committés** (voir `.gitignore` + `docs/ASSETS.md`) ; la doc (`MEMORY.md`/`JOURNAL.md`)
porte le point de reprise. **L'identifiant de modèle n'apparaît jamais** dans un
commit/artefact.

## Philosophie
Le but n'est **pas de recréer le jeu**, mais de le faire fonctionner dans un environnement
moderne avec le **moins de modifications possible**. À chaque fois que c'est possible :
réutiliser le code existant, réutiliser les formats de données existants, automatiser les
extractions, **comprendre** le fonctionnement réel plutôt que le contourner. Résultat visé :
un projet **propre, maintenable, reproductible et fidèle** au comportement d'origine.
