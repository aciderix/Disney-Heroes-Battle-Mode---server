# Pipeline d'implémentation d'un écran/mode — INDUSTRIALISÉ (#73)

But : implémenter chaque nouvel écran/mode **sans hallucination, sans oubli, sans incompatibilité**, en
s'appuyant sur les FAITS du jar (bytecode) et sur nos LEÇONS (défauts récurrents documentés). On ne devine plus
« ce dont l'écran a besoin » : **on l'extrait**.

## Outils

| Outil | Rôle | Ancrage |
|---|---|---|
| `tools/screentool/contract.sh <prefixe-classe-écran>` | Extrait le **CONTRAT** d'un écran : messages lus (→ champs que le serveur doit peupler), messages envoyés (→ handlers requis) + **couverture** vs `LoginServer`, gate `Unlockable`, + la **checklist des défauts récurrents**. | **Bytecode** de l'écran (ASM) + `instanceof` de `LoginServer*` (faits, zéro devinette). |
| `server/smoke/WireCheck.java` (`WireCheck.assertRoundTrips(resp)`) | Garde-fou **réutilisable** : écrit la réponse serveur→client sur le fil (`writeAll`) et la relit. Attrape le **typage wire faux** qui explose à l'écriture (invisible headless). | Codec + `MessageFactory` du jeu. |

## Procédure par écran

1. **Trouver les classes de l'écran** :
   `unzip -l libs/game.jar | grep -oE 'com/perblue/heroes/ui/<mode>/[A-Za-z]+Screen'`
   (ajouter le display/window qui lit les données, ex. `…/InvasionBreakerQuestDisplay`).

2. **Extraire le contrat** (+ option `--scaffold <Mode> <outdir>` en fin d'argument pour GÉNÉRER les squelettes) :
   `tools/screentool/contract.sh com/perblue/heroes/ui/<mode>/<Ecran>[,<…Display>]`
   Scaffold : `java … ScreenContract <game> <srv> <Ecran> --scaffold <Mode> scratchpad/scaffold/<Mode>` →
   `Server<Mode>Scaffold.java` (builder posant CHAQUE champ du contrat, TODO), `<Mode>ScaffoldTest.java`
   (WireCheck sur chaque réponse), `LoginServer-<Mode>.snippet.txt` (handlers à insérer). **STRUCTURE seulement**
   — la LOGIQUE (règles) reste à brancher via le code du jeu, jamais inventée (§3/§4).
   Lire les sections :
   - **A/B — champs à peupler** : chaque champ listé DOIT être posé par le serveur dans la réponse (sinon
     écran vide / bouton inerte = défaut nº1). C'est la spec de la réponse.
   - **C — couverture handlers** : chaque `[MANQUE]` = message envoyé par l'écran sans route `LoginServer` → à
     brancher. Chaque `[OK]` = déjà routé.
   - **D — gate** : verrou `Unlockable` (souvent un Team Level) à respecter, jamais à désactiver.

3. **Recon logique (là où le contrat ne suffit pas)** : lire au bytecode/`javap` la classe `…Helper`/`…Stats`
   du mode pour la RÈGLE (coûts, barème, RNG). On EXÉCUTE la logique du jeu, on ne la réécrit pas (PRINCIPLES §3/§4).
   Preuve d'appartenance serveur : une constante `*_constants.tab` qui n'apparaît QUE dans sa déclaration parmi
   les classes `com/perblue/**` = calcul serveur (technique de g41).

4. **Implémenter le handler** (`LoginServer` route + `ServerUser`/`Server<Mode>` logique) en peuplant EXACTEMENT
   les champs du contrat, en exécutant la logique du jeu, avec anti-triche (recalcul serveur).

5. **Test headless OBLIGATOIRE** avec, sur la réponse construite : `WireCheck.assertRoundTrips(resp)` (défaut nº3)
   + assertions d'état + **round-trip DB** (persistance). Ajouter le test à `server/smoke/regression.sh`.

6. **Cocher la checklist E** (imprimée par l'outil) — les défauts que le bytecode de l'écran ne montre pas :
   resync-hors-`this.extra`, poussée au boot/ordre, gate réel, piège `PatchStats.<clinit>` au boot, etc.

7. **VÉRIF EN JEU** (client réel → serveur → persistance → affichage). Headless = 🟢 ; **✅ seulement en jeu**.

## Les 9 défauts récurrents (checklist E, distillée de MEMORY/SHIMS/JOURNAL)

1. **Champ jamais renseigné** — l'écran lit un champ que le serveur ne pose jamais (g46 `activeBreakerFight`,
   g47 `InvasionBossInfo.actionState`, g50 `bossClaimStatus`, g41 `GuildInfo.warEndTime`). → **§A/B de l'outil**.
2. **Handler manquant** — message envoyé sans route (g45 `GetBreakerQuest`). → **§C de l'outil**.
3. **Typage wire faux** (explose à l'écriture, invisible headless) — g44 `WarDefense.defenders` = `WarHeroData`
   (≠ `WarHeroSummary`) ; g45 `activeCars` = `WarAttackCarBonus` (≠ `WarCarType`). → **`WireCheck`**.
4. **Resync après mutation** — champ muté hors `this.extra` perdu au round-trip (teamLevel, nom, diamants,
   statuts campagne, héros, compteurs `UserFlag`).
5. **Poussée au boot / ordre** — info d'entrée à pousser au login (g45 `InvasionInfo` → sinon nav refusée) ;
   `SocialHistory` tamponnée jusqu'au `BootData`.
6. **Anti-triche** — coûts/points/paliers RECALCULÉS serveur (index/coût client ignoré) ; valider AVANT d'accorder.
7. **Gate réel du jeu** — respecter le verrou (`Unlockable`/TL), jamais le désactiver ; l'atteindre par l'état légitime.
8. **`PatchStats.<clinit>`** — ne pas déclencher `getBossHP` au push du boot (stat-sync incomplète → poison classe).
9. **Vérif en jeu obligatoire** — headless prouvé = 🟢, pas ✅.

## Limites connues de l'outil (validé contre le travail fait, g57)

Comparé aux écrans DÉJÀ implémentés (arène, breaker), pour distinguer « vrai bug passé » de « outil à améliorer » :
- **§C ne liste QUE les vraies requêtes client→serveur** : un message serveur→client CONSTRUIT localement par
  l'écran (ArenaInfo, ArenaRow…) n'est PAS un envoi → exclu (heuristique : lu par l'écran = inbound ; sinon nom
  de requête Get*/Set*/…/*Attack). *(C'était un faux positif de la 1ʳᵉ version, corrigé — pas un bug de l'arène.)*
- **§A/B peut sous-lister** si l'écran lit ses données via un HOLDER/manager plutôt que par `GETFIELD` direct sur
  le message (ex. arène) → compléter la recon à la main pour ces écrans. Fournir aussi le display/holder en argument aide.
- **Vérifier « champ peuplé »** : un champ peut être rempli via `.add()`/`.put()` sur la liste/map pré-initialisée
  du message (ctor par défaut), PAS forcément via `champ =` (ex. breaker `wardLineups.add(...)`). Ne pas conclure
  « non peuplé » sur la seule absence de `champ =`.

## Sonde `sideOf`/`putSide` (état wire d'instantané)

Pour les états stockés en octets wire (guerre…) : `sideOf` DÉCODE (objet neuf), muter ne change RIEN tant qu'on
n'a pas rappelé `putSide` qui RE-ENCODE. Oublier le `putSide` final perd la mutation (g52).
