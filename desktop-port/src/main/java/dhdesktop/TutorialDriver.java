package dhdesktop;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.perblue.heroes.GameMain;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.IHero;
import com.perblue.heroes.game.logic.HeroHelper;
import com.perblue.heroes.network.messages.HeroEquipSlot;
import com.perblue.heroes.game.tutorial.TutorialHelper;
import com.perblue.heroes.game.tutorial.TutorialPointerInfo;
import dhbackend.DhInput;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pilote de DEV headless (OFF par défaut, drapeau lanceur) — ne s'exécute JAMAIS en prod et ne
 * modifie NI le jeu NI le serveur. Il ne fait qu'INTERROGER le jeu pour savoir où le tutoriel
 * demande de taper, puis délègue le tap au système d'entrée du jeu (comme un doigt).
 *
 * <p>Il réutilise les API du jeu : {@link TutorialHelper#getPointers(com.perblue.heroes.game.objects.IUser)}
 * donne les cibles (par nom de composant UI) ; on retrouve l'acteur correspondant dans l'arbre de
 * l'écran (tag {@code Actor.getTutorialName()}, comme le fait {@code Group.findTutorialActor}), et on
 * tape son centre (converti stage → écran par le jeu). Sans pointeur actif (dialogue « tap to
 * continue »), on tape au centre. Aucune coordonnée devinée : tout vient de l'acteur désigné par le jeu.
 *
 * <p><b>Popups modaux (y compris EMPILÉES)</b> (récompenses de coffre « CRATE REWARDS », « CRATE READY »,
 * info héros…) : seule la fenêtre du <b>dessus</b> reçoit l'entrée ; taper la cible du tuto quand elle est
 * <b>derrière</b> une modale est absorbé par celle-ci (faux « tapé » → blocage). On interroge
 * {@code BaseScreen.getScreenWindows()} et on raisonne sur la fenêtre du dessus : (a) le tuto pointe DEDANS
 * → on tape le bouton désigné ; (b) le tuto pointe AILLEURS (la modale est un <b>résidu</b> couvrant la
 * cible, ex. « CRATE READY » restée par-dessus l'onglet GEAR) → on la <b>ferme</b> ({@code BaseModalWindow
 * .hide()}, = bouton X / retour), ce qui <b>draine la pile</b> une fenêtre par frame jusqu'à révéler la
 * cible ; (c) aucune cible active → on ATTEND sur la popup (récompense → fermer ; interactive → bouton
 * VIEW/OK). Aucune coordonnée devinée, aucune modif du jeu — c'est le tuto qui dicte l'action.
 */
public final class TutorialDriver {

    private TutorialDriver() {}

    // NB: Boolean.getBoolean n'accepte que "true" → on accepte aussi "1".
    private static final boolean DEBUG = System.getProperty("dh.tutodrive.debug") != null
            && !"0".equals(System.getProperty("dh.tutodrive.debug"))
            && !"false".equalsIgnoreCase(System.getProperty("dh.tutodrive.debug"));
    // Mode ENREGISTREUR (dh.tutorec) : à CHAQUE tick, dump EXHAUSTIF (sans dédup) de l'état + de TOUS les
    // pointeurs du tuto (pointAt / tutorialName) + des acteurs actionnables → pour reconstituer pas à pas
    // ce que le tuto désigne (couplé aux captures numérotées du lanceur). Sert au diagnostic « manuel ».
    private static final boolean REC = System.getProperty("dh.tutorec") != null
            && !"0".equals(System.getProperty("dh.tutorec"))
            && !"false".equalsIgnoreCase(System.getProperty("dh.tutorec"));
    private static int recStep = 0;
    // Tap RÉEL (press-relâche sur N frames, comme un doigt) au lieu du tap 1-frame : certains boutons
    // (ex. DHResourceButton du coffre) ne déclenchent pas sur un down+up instantané. 0 = tap 1-frame.
    private static final int TAP_HOLD = Integer.getInteger("dh.taphold", 0);
    private static int tapCooldown = 0;   // évite d'empiler les press-relâches (attendre la fin du précédent)
    // SONDE DEV carte de campagne (dh.mapprobe) : sur CampaignScreen, NE PAS faire RETOUR ; hit-tester une
    // grille autour du nœud de chapitre (acteur + listeners), le TAPER et journaliser la transition d'écran.
    // But : découvrir empiriquement quel élément s'active pour entrer dans un chapitre (auto-pilote #17).
    private static final boolean MAP_PROBE = System.getProperty("dh.mapprobe") != null
            && !"0".equals(System.getProperty("dh.mapprobe"));
    private static final String PROBE_ACTOR = System.getProperty("dh.probeactor", "CAMPAIGN_CHAPTER_ONE_NAME");
    private static int probeTick = 0;
    private static String probeLastScreen = "";
    // Entrée de niveau de campagne : la carte est une scène g2d (CityMapDisplay) sans acteur cliquable, et
    // getPointers() est vide headless. On entre le niveau JOUABLE via l'API du jeu — la méthode EXACTE que
    // le vrai tap déclenche : CampaignScreen.onCampaignLevelTapped(CampaignLevelID). Niveau configurable
    // (dh.playlevel="chapitre,niveau", défaut 1,1 = tuto). Cf. MEMORY §6ter B-bis (sonde dh.mapprobe).
    private static final int[] PLAY_LEVEL = parseLevel(System.getProperty("dh.playlevel", "1,1"));
    private static int enterCooldown = 0;
    private static int combatCooldown = 0;   // cadence des taps sur la flèche « TAP TO CONTINUE » (combat)
    private static boolean justFoughtCampaign = false;   // vrai après un combat → revenir à la carte (enchaîner)
    // Boutons d'action du flux de combat à taper SANS pointeur (replay après défaite : le tuto n'émet plus
    // de pointeur mais il faut relancer le combat). Acteurs du jeu, tapés par tutorialName.
    private static final Set<String> ADVANCE_BUTTONS = new HashSet<>(java.util.Arrays.asList(
        "CAMPAIGN_PREVIEW_FIGHT_BUTTON", "HERO_CHOOSER_FIGHT_BUTTON"));
    private static int[] parseLevel(String s) {
        try { String[] p = s.split(","); return new int[]{Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim())}; }
        catch (Throwable t) { return new int[]{1, 1}; }
    }
    // AUTO-ÉQUIPEMENT (DEV, dh.autoequip) : à l'étape équip (OBLIGATOIRE dans le tuto — on ne peut pas
    // avancer sans), le pilote doit ALLER ÉQUIPER au lieu de foncer en campagne. Avant ce correctif, le
    // pilote entrait en campagne via l'API du jeu (normalOrEliteNodeSelected), COURT-CIRCUITANT le verrou
    // d'équip du tuto (infidèle). Détection 100% LOGIQUE du jeu (HeroHelper.hasItemsToEquip = le « +equip
    // vert »), action via l'UI du jeu (menu HÉROS → HeroDetail → slot GEAR → CraftingWindow → EQUIP).
    private static final boolean AUTO_EQUIP = System.getProperty("dh.autoequip") != null
            && !"0".equals(System.getProperty("dh.autoequip"))
            && !"false".equalsIgnoreCase(System.getProperty("dh.autoequip"));
    private static String equipDumpedScreen = "";

    /** Au moins un héros possédé a-t-il un objet équipable (le « +equip vert ») ? Logique du jeu. */
    private static boolean anyHeroNeedsEquip(User user) {
        return firstHeroNeedingEquip(user) != null;
    }
    /** 1er héros possédé avec un objet équipable, sinon null. */
    private static IHero firstHeroNeedingEquip(User user) {
        try {
            for (Object o : user.getHeroes()) {
                IHero hh = (IHero) o;
                if (HeroHelper.hasItemsToEquip(user, hh)) return hh;
            }
        } catch (Throwable t) {}
        return null;
    }
    // Le recorder DÉCIME : on pilote à chaque frame (fiable — certains boutons pulsés exigent des taps
    // rapprochés) mais on ne DUMP + capture que toutes les RECEVERY frames (étapes nettes, peu de fichiers).
    private static final int RECEVERY = Math.max(1, Integer.getInteger("dh.recevery", 20));
    private static int recCall = 0;
    private static boolean recCaptureRequested = false;
    /** Vrai quand le dernier {@link #driveOnce} était un « pas » d'enregistreur (le lanceur capture alors). */
    public static boolean recCaptureRequested() { return recCaptureRequested; }
    private static String lastTargets = "";
    private static String lastTrace = "";
    private static boolean hadTarget = false;
    // Back-out « libre » : nombre de ticks consécutifs SANS pointeur sur le même écran non-hub, au-delà
    // duquel on tape le bouton RETOUR (le tuto attend qu'on sorte du sous-écran, ex. post-équip).
    private static int idleTicks = 0;
    private static String idleScreen = "";
    // Seuil exprimé en ~frames d'inactivité (≈120 frames ≈ plusieurs sec), robuste à l'intervalle d'autotap
    // (idleTicks compte les APPELS = 1 par intervalle) : un vrai blocage post-équip accumulait ~49 ticks à
    // autotap=30 ; un dialogue « tap to continue » avance en 1-2 taps → pas de retour prématuré.
    private static final int IDLE_BACK_THRESHOLD =
        Math.max(3, 120 / Math.max(1, Integer.getInteger("dh.autotap", 1)));

    /**
     * Vrai si, au dernier {@link #driveOnce}, le tutoriel avait un <b>pointeur ACTIF</b> (cible désignée).
     * Le lanceur ne doit PAS taper au centre dans ce cas : le tuto veut un élément <b>précis</b> (pas le
     * centre) et un tap central part hors-script (ex. coffre Diamant → « Follow the tutorial arrow! »).
     * Le tap central n'est légitime que pour les dialogues « tap to continue » (aucun pointeur).
     */
    public static boolean hadActiveTarget() { return hadTarget; }

    /** Renvoie true si un tap/fermeture a été injecté sur une cible désignée par le tutoriel. */
    public static boolean driveOnce(GameMain game, DhInput input, int w, int h) {
        hadTarget = false;
        try {
            User user = game.getYourUser();
            if (user == null) return false;

            Object screen = game.getScreenManager().getScreen();
            if (screen == null) return false;

            // Cibles désignées par le tutoriel (peut être vide : ex. le tuto attend qu'on ferme une popup).
            List<?> pointers = TutorialHelper.getPointers(user);
            Set<String> targets = new HashSet<>();
            if (pointers != null) for (Object p : pointers) {
                String name = ((TutorialPointerInfo) p).getActorTutorialName();
                if (name != null && !name.isEmpty()) targets.add(name);
            }
            hadTarget = !targets.isEmpty();

            // 1) Popups modaux ouverts (coffre « CRATE REWARDS », récompense, info) — traités AVANT tout,
            //    même sans pointeur actif : le tuto met souvent en pause ses pointeurs tant que la popup
            //    n'est pas fermée. Si le tuto pointe DANS la popup → taper dedans ; sinon la popup bloque
            //    → la FERMER via l'API du jeu (BaseModalWindow.hide(), = bouton X / retour).
            List<?> windows = screenWindows(screen);
            if (DEBUG) {
                StringBuilder wl = new StringBuilder();
                if (windows != null) for (Object win : windows) wl.append(win.getClass().getSimpleName()).append(',');
                String trace = screen.getClass().getSimpleName() + " win=[" + wl + "] cibles=" + targets;
                if (!trace.equals(lastTrace)) { lastTrace = trace; System.out.println("[tutodrive] " + trace); }
            }

            // ENREGISTREUR (décimé) : dump exhaustif toutes les RECEVERY frames (pilotage à chaque frame).
            recCaptureRequested = false;
            if (REC && (++recCall % RECEVERY == 0)) {
                recCaptureRequested = true;
                StringBuilder wl = new StringBuilder();
                if (windows != null) for (Object win : windows) wl.append(win.getClass().getSimpleName()).append(',');
                System.out.println("[tutorec] === step " + recStep + " === écran=" + screen.getClass().getSimpleName()
                    + " fenêtres=[" + wl + "]");
                if (pointers != null) for (Object p : pointers) {
                    TutorialPointerInfo pi = (TutorialPointerInfo) p;
                    System.out.println("[tutorec]   POINTEUR pointAt=" + pi.getPointAt()
                        + " tutorialName=" + pi.getActorTutorialName());
                }
                if (pointers == null || pointers.isEmpty())
                    System.out.println("[tutorec]   (aucun pointeur actif)");
                // acteurs actionnables : de la fenêtre du dessus si popup, sinon de l'écran de base.
                Actor dumpRoot = null;
                if (windows != null && !windows.isEmpty() && windows.get(windows.size() - 1) instanceof Actor)
                    dumpRoot = (Actor) windows.get(windows.size() - 1);
                else {
                    try {
                        Group r = (Group) screen.getClass().getMethod("getRootStack").invoke(screen);
                        if (r != null && r.getStage() != null) dumpRoot = r.getStage().getRoot(); else dumpRoot = r;
                    } catch (Throwable ignore) {}
                }
                if (dumpRoot != null) dumpRec(dumpRoot);
                recStep++;
            }
            if (windows != null && !windows.isEmpty()) {
                // C'est le TUTO qui désigne où agir. Une seule fenêtre modale (celle du DESSUS) reçoit
                // l'entrée ; taper les coordonnées d'un acteur situé DERRIÈRE elle est absorbé par la modale
                // (→ faux « tapé », blocage). On raisonne donc sur la fenêtre du dessus uniquement.
                Object top = windows.get(windows.size() - 1);
                String cls = top.getClass().getSimpleName();

                // (a) Le tuto pointe DANS la fenêtre du dessus → taper le bouton désigné.
                List<Actor> inTop = new ArrayList<>();
                if (top instanceof Actor) collect((Actor) top, targets, inTop);
                if (!inTop.isEmpty()) return tapAll(inTop, input, w, h);

                // (a-bis) Fenêtre de FLUX (CraftingWindow = UI d'équipement/craft) : ce n'est PAS un résidu à
                //     fermer — c'est l'écran où l'action se fait. Le tuto (étape équipement) pointe le SLOT
                //     derrière (HERO_GEAR_SLOT_SIX), pas encore le bouton de la fenêtre → sans ce cas, (b) la
                //     fermerait, le slot se re-taperait, elle se rouvrirait… boucle infinie (observé). On tape
                //     donc son bouton EQUIP (l'action attendue, API du jeu par nom de tuto) au lieu de fermer.
                if (top instanceof Actor && cls.contains("Crafting")) {
                    List<Actor> eq = findByName((Actor) top, "CRAFTING_WINDOW_EQUIP_BUTTON");
                    if (eq.isEmpty()) collectTextButtons((Actor) top, eq);
                    if (!eq.isEmpty()) {
                        if (DEBUG) System.out.println("[tutodrive] " + cls + " → tap EQUIP (fenêtre de flux, pas un résidu)");
                        return tapAll(eq, input, w, h);
                    }
                }

                // (b) Le tuto pointe AILLEURS (écran de base ou fenêtre inférieure) : la modale du dessus
                //     n'est PAS la cible courante → c'est un RÉSIDU qui COUVRE la cible (ex. « CRATE READY »
                //     empilée par-dessus l'onglet GEAR). On la FERME via l'API du jeu (BaseModalWindow.hide()
                //     = bouton X / retour). Draine la pile une fenêtre par frame jusqu'à révéler la cible.
                //     Distinction clé (pas de rustine) : on ne ferme que si le tuto veut manifestement autre
                //     chose ; sans cible active (c), on ATTEND sur la popup au lieu de la fermer.
                if (!targets.isEmpty() && top instanceof Actor) {
                    try {
                        top.getClass().getMethod("hide").invoke(top);
                        if (DEBUG) System.out.println("[tutodrive] popup " + cls
                            + " fermée (résidu bloquant ; cible du tuto ailleurs=" + targets + ")");
                        return true;
                    } catch (Throwable t) { /* pas de hide() → traiter comme (c) */ }
                }

                // (c) Aucune cible de tuto active → on est EN ATTENTE sur cette popup (le tuto met souvent
                //     ses pointeurs en pause tant qu'elle n'est pas traitée).
                //   - popup d'AFFICHAGE de récompense (« CRATE REWARDS » = ChestResultsWindow, butin) : la
                //     rejeter (hide()) — pas d'action, le joueur la ferme.
                if (isRewardDisplay(cls)) {
                    top.getClass().getMethod("hide").invoke(top);
                    if (DEBUG) System.out.println("[tutodrive] popup " + cls + " fermée (récompense)");
                    return true;
                }
                //   - popup INTERACTIVE (« CRATE READY » avec bouton VIEW) : frapper le bouton d'action
                //     PRINCIPAL = le bouton-texte du jeu (DFTextButton « VIEW/OPEN/OK »), pas le centre.
                List<Actor> primary = new ArrayList<>();
                collectTextButtons((Actor) top, primary);
                if (!primary.isEmpty()) {
                    if (DEBUG && !cls.equals(lastTrace)) {
                        lastTrace = cls; dumpActionable((Actor) top, cls);
                    }
                    return tapAll(primary, input, w, h);
                }
                if (DEBUG && !cls.equals(lastTrace)) { lastTrace = cls; dumpActionable((Actor) top, cls); }
                return false;   // pas de bouton-texte : laisse le lanceur taper au centre
            }

            // 2) Pas de popup. Racine de recherche = TOUTE la scène (menu latéral HEROES/ITEMS…, overlays
            //    hors rootStack) → sinon l'acteur désigné (ex. BASE_MENU_HERO_BUTTON) n'est pas trouvé.
            Group root = (Group) screen.getClass().getMethod("getRootStack").invoke(screen);
            if (root == null) return false;
            Group searchRoot = root;
            Stage stg = root.getStage();
            if (stg != null && stg.getRoot() != null) searchRoot = stg.getRoot();
            String screenName = screen.getClass().getSimpleName();

            // SONDE DEV : sur l'écran carte, prend la main (pas de RETOUR) pour cliquer le chapitre et observer.
            if (MAP_PROBE && screenName.contains("Campaign") && stg != null) {
                mapProbe(screenName, searchRoot, stg, input, w, h);
                return true;   // handled : empêche le tap central du lanceur
            }
            // ÉTAPE ÉQUIPEMENT (OBLIGATOIRE) : si un héros a un objet équipable, ALLER ÉQUIPER en PRIORITÉ
            // (avant toute entrée en campagne). Le tuto verrouille la progression tant qu'on n'a pas équipé ;
            // sans ça le pilote forçait la campagne via l'API et sautait cette étape (infidèle).
            boolean needEquip = AUTO_EQUIP && anyHeroNeedsEquip(user);
            if (needEquip && equipDrive(user, screenName, searchRoot, input, w, h)) return true;

            // ENTRÉE DE NIVEAU : sur la carte de campagne (scène g2d, aucun acteur cliquable, getPointers vide),
            // on déclenche la MÊME méthode du jeu que le vrai tap d'un nœud de niveau (onCampaignLevelTapped)
            // pour le niveau jouable → ouvre le choix des héros (le pilote gère ensuite le bouton FIGHT).
            // Suspendu tant qu'un équipement est en attente (ne pas court-circuiter le verrou d'équip du tuto).
            if (!MAP_PROBE && !needEquip && screenName.equals("CampaignScreen") && enterCampaignLevel(screen, user)) return true;

            // Aucun pointeur de tuto : soit un dialogue « tap to continue » (le lanceur tape au centre), soit
            // le tuto attend qu'on SORTE d'un sous-écran de nous-mêmes (ex. post-équip sur HeroDetailScreen :
            // AUCUN pointeur n'est émis — vérifié : getPointers rafraîchit et renvoie vide — le jeu attend un
            // retour « libre », le bouton retour est mis en avant). Heuristique : après IDLE_BACK_THRESHOLD
            // ticks INACTIFS sur un même écran NON-hub, on tape le bouton RETOUR pour revenir vers le hub (où
            // le tuto reprendra ses pointeurs). Un dialogue, lui, avance au tap central → l'écran/état change
            // → le compteur se réinitialise (pas de retour prématuré).
            if (targets.isEmpty()) {
                if (screenName.equals(idleScreen)) idleTicks++; else { idleScreen = screenName; idleTicks = 0; }
                // ÉCRAN DE COMBAT (*AttackScreen) : ne JAMAIS faire RETOUR. Le « TAP TO CONTINUE » de fin de
                // vague se ferme en tapant la FLÈCHE (droite-centre) → vague suivante. On tape donc cette zone
                // périodiquement (inoffensif en combat actif = ciel vide) ; l'auto-combat joue les vagues.
                if (screenName.contains("AttackScreen")) {
                    justFoughtCampaign = true;   // on est EN combat → au retour, revenir à la carte (enchaîner)
                    if (combatCooldown <= 0) {
                        int ax = Math.round(w * 0.93f), ay = Math.round(h * 0.5f);
                        if (TAP_HOLD > 0) input.tapHold(ax, ay, TAP_HOLD); else input.tap(ax, ay);
                        combatCooldown = 8;
                    } else combatCooldown--;
                    return true;   // géré ici (flèche de continuation) ; pas de tap central du lanceur
                }
                // POST-VICTOIRE — ENCHAÎNER : après un combat, le client revient sur l'aperçu/choix du MÊME
                // niveau ; sans intervention le pilote re-taperait FIGHT (rejoue le même niveau). On revient
                // plutôt à la CARTE (BACK) une fois : sur CampaignScreen, enterCampaignLevel prendra
                // nextPlayableLevel = niveau débloqué SUIVANT (1-1→1-2→…). Le déblocage est autoritatif serveur.
                if (justFoughtCampaign
                        && (screenName.contains("CampaignPreview") || screenName.contains("HeroChooser"))) {
                    List<Actor> back = findByName(searchRoot, "BACK_BUTTON");
                    if (!back.isEmpty()) {
                        justFoughtCampaign = false;
                        if (DEBUG) System.out.println("[tutodrive] " + screenName
                            + " post-victoire → RETOUR carte pour enchaîner le niveau suivant");
                        return tapAll(back, input, w, h);
                    }
                }
                // CHOIX DES HÉROS : sur un écran de choix (CampaignHeroChooserScreen…), si l'équipe est vide
                // et qu'aucun pointeur tuto ne guide la sélection, on SÉLECTIONNE les héros dispo via l'API du
                // jeu (unitSelected) AVANT de taper FIGHT — sinon TEAM POWER=0 et « select at least one hero ».
                if (screenName.contains("HeroChooser") && selectHeroesIfNeeded(screen)) return true;
                // BOUTON D'ACTION connu SANS pointeur : après une défaite/replay, le tuto n'émet plus de
                // pointeur sur l'aperçu du niveau ni le choix des héros, mais il faut quand même taper le
                // bouton FIGHT pour (re)lancer le combat. On tape donc l'acteur du jeu par son tutorialName
                // au lieu de faire RETOUR (qui bouclait aperçu↔carte). L'équipe est mémorisée entre essais.
                List<Actor> adv = new ArrayList<>();
                collect(searchRoot, ADVANCE_BUTTONS, adv);
                if (!adv.isEmpty()) {
                    if (DEBUG) System.out.println("[tutodrive] " + screenName + " sans pointeur → tap bouton d'action "
                        + adv.get(0).getTutorialName());
                    return tapAll(adv, input, w, h);
                }
                if (!screenName.contains("MainScreen") && idleTicks >= IDLE_BACK_THRESHOLD) {
                    List<Actor> back = findByName(searchRoot, "BACK_BUTTON");
                    if (!back.isEmpty()) {
                        if (DEBUG) System.out.println("[tutodrive] " + screenName + " sans pointeur depuis "
                            + idleTicks + " ticks → RETOUR (BACK_BUTTON) vers le hub");
                        idleTicks = 0;
                        return tapAll(back, input, w, h);
                    }
                }
                return false;   // dialogue → le lanceur tape au centre (aucun pointeur actif)
            }
            idleTicks = 0; idleScreen = screenName;   // un pointeur est actif → pas d'inactivité

            List<Actor> found = new ArrayList<>();
            collect(searchRoot, targets, found);
            if (DEBUG && !targets.toString().equals(lastTargets)) {
                lastTargets = targets.toString();
                System.out.println("[tutodrive] " + screenName + " cibles=" + targets + " trouvés=" + found.size());
            }
            if (!found.isEmpty()) return tapAll(found, input, w, h);

            // Cible désignée INTROUVABLE sur l'écran courant : typiquement un élément du HUB (ex.
            // BASE_MENU_HERO_BUTTON) alors qu'on est resté sur un écran de détail. Le tap central est
            // désactivé (hadActiveTarget=true) → on REVIENT vers le hub via BACK_BUTTON. Sinon on attend.
            List<Actor> back = findByName(searchRoot, "BACK_BUTTON");
            if (!back.isEmpty()) {
                if (DEBUG && !("BACK:" + targets).equals(lastTargets)) {
                    lastTargets = "BACK:" + targets;
                    System.out.println("[tutodrive] cible " + targets + " absente de " + screenName
                        + " → RETOUR (BACK_BUTTON) vers le hub");
                }
                return tapAll(back, input, w, h);
            }
            return false;   // cible absente et pas de RETOUR → attendre (le lanceur ne tape PAS au centre)
        } catch (Throwable t) {
            return false;   // écran/étape sans pointeur exploitable → no-op
        }
    }

    /** Routine d'ÉQUIPEMENT autonome (DEV). Détection logique (HeroHelper), action via l'UI du jeu.
     *  Étapes : (ailleurs) ouvrir le menu HÉROS → (HeroList) taper la carte du héros → (HeroDetail) taper
     *  le slot {@code getSlotThatCanEquip} → CraftingWindow → EQUIP (géré par le bloc popup existant).
     *  Ne touche JAMAIS un écran de combat (*AttackScreen). Renvoie true si une action a été injectée. */
    private static boolean equipDrive(User user, String screenName, Group searchRoot,
                                      DhInput input, int w, int h) {
        if (screenName.contains("AttackScreen")) return false;   // ne pas interrompre un combat
        IHero hero = firstHeroNeedingEquip(user);
        if (hero == null) return false;

        // DUMP DEV (une fois par écran, TOUS écrans pendant l'équip) : révèle les VRAIS tags/structure
        // (recon B-bis : menu latéral, cartes HeroList, onglets/slots HeroDetail).
        if (DEBUG && !screenName.equals(equipDumpedScreen)) {
            equipDumpedScreen = screenName;
            HeroEquipSlot sl = HeroHelper.getSlotThatCanEquip(user, hero);
            System.out.println("[autoequip] écran=" + screenName + " héros=" + hero.getType() + " slot=" + sl);
            dumpActionable(searchRoot, screenName);
        }

        // HeroDetail : taper le SLOT équipable → ouvre CraftingWindow (le bloc popup tape ensuite EQUIP).
        if (screenName.contains("HeroDetail")) {
            HeroEquipSlot slot = HeroHelper.getSlotThatCanEquip(user, hero);
            if (slot != null) {
                List<Actor> s = findByName(searchRoot, "HERO_GEAR_SLOT_" + slot);
                if (!s.isEmpty()) {
                    if (DEBUG) System.out.println("[autoequip] tap slot HERO_GEAR_SLOT_" + slot);
                    return tapAll(s, input, w, h);
                }
            }
            return false;   // slot introuvable (onglet GEAR pas actif ?) → le dump ci-dessus aide à câbler
        }
        // HeroList : taper la carte du héros à équiper (tag si présent, sinon 1re carte HeroListCard).
        if (screenName.contains("HeroList")) {
            List<Actor> card = findByName(searchRoot, "HERO_LIST_CARD_" + hero.getType());
            if (card.isEmpty()) card = findByClass(searchRoot, "HeroListCard");
            if (card.isEmpty()) card = findByClass(searchRoot, "HeroListDetailedCard");
            if (!card.isEmpty()) {
                if (DEBUG) System.out.println("[autoequip] tap carte héros " + hero.getType());
                return tapAll(card.subList(0, 1), input, w, h);
            }
            return false;
        }
        // HUB (MainScreen) : ouvrir le menu HÉROS. BASE_MENU_HERO_BUTTON est dans le menu latéral (burger) →
        // si absent, ouvrir d'abord le burger (BASE_MENU_BUTTON / SIDE_MENU). Le tap sur BASE_MENU_HERO_BUTTON
        // hors du hub (ex. CampaignPreviewScreen) ne navigue PAS → on ne le tente QUE sur MainScreen.
        if (screenName.contains("MainScreen")) {
            List<Actor> heroBtn = findByName(searchRoot, "BASE_MENU_HERO_BUTTON");
            if (!heroBtn.isEmpty()) {
                if (DEBUG) System.out.println("[autoequip] MainScreen → menu HÉROS (BASE_MENU_HERO_BUTTON)");
                return tapAll(heroBtn.subList(0, 1), input, w, h);
            }
            List<Actor> burger = findByName(searchRoot, "BASE_MENU_BUTTON");
            if (burger.isEmpty()) burger = findByName(searchRoot, "SIDE_MENU");
            if (!burger.isEmpty()) {
                if (DEBUG) System.out.println("[autoequip] MainScreen → ouvrir le menu latéral (burger)");
                return tapAll(burger.subList(0, 1), input, w, h);
            }
            return false;
        }
        // Ailleurs (aperçu/chooser/carte) : revenir vers le hub (BACK) — le menu HÉROS n'y fonctionne pas.
        List<Actor> back = findByName(searchRoot, "BACK_BUTTON");
        if (back.isEmpty()) back = findByName(searchRoot, "BACK_BUTTON_WRAP");
        if (!back.isEmpty()) {
            if (DEBUG) System.out.println("[autoequip] " + screenName + " → BACK (vers le hub pour équiper)");
            return tapAll(back.subList(0, 1), input, w, h);
        }
        return false;
    }

    /** Acteurs dont la classe simple contient {@code s} (et de taille non nulle). */
    private static List<Actor> findByClass(Actor root, String s) {
        List<Actor> out = new ArrayList<>();
        findByClassRec(root, s, out);
        return out;
    }
    private static void findByClassRec(Actor a, String s, List<Actor> out) {
        if (a.getClass().getSimpleName().contains(s) && a.getWidth() > 0 && a.getHeight() > 0) out.add(a);
        if (a instanceof Group) for (Actor c : ((Group) a).getChildren()) findByClassRec(c, s, out);
    }

    /** Collecte les boutons-texte cliquables (action principale : « VIEW/OPEN/OK/CONTINUE »…). */
    private static void collectTextButtons(Actor a, List<Actor> out) {
        if (a.getClass().getSimpleName().contains("TextButton") && a.getWidth() > 0 && a.getHeight() > 0) {
            boolean clickable = false;
            for (com.badlogic.gdx.scenes.scene2d.EventListener l : a.getListeners())
                if (l instanceof com.badlogic.gdx.scenes.scene2d.utils.ClickListener) { clickable = true; break; }
            if (clickable) out.add(a);
        }
        if (a instanceof Group) for (Actor c : ((Group) a).getChildren()) collectTextButtons(c, out);
    }

    /** DEV : liste les acteurs actionnables d'une fenêtre (bouton/label/tag tuto + position stage). */
    private static void dumpActionable(Actor window, String cls) {
        System.out.println("[tutodrive] --- acteurs actionnables de " + cls + " ---");
        dumpRec(window);
        System.out.println("[tutodrive] --- fin " + cls + " ---");
    }

    private static void dumpRec(Actor a) {
        boolean clickable = false;
        for (com.badlogic.gdx.scenes.scene2d.EventListener l : a.getListeners()) {
            if (l instanceof com.badlogic.gdx.scenes.scene2d.utils.ClickListener) { clickable = true; break; }
        }
        String text = null;
        if (a instanceof com.badlogic.gdx.scenes.scene2d.ui.Label) {
            CharSequence t = ((com.badlogic.gdx.scenes.scene2d.ui.Label) a).getText();
            if (t != null && t.length() > 0) text = t.toString();
        }
        if (clickable || text != null || a.getTutorialName() != null) {
            String pos = "";
            Stage st = a.getStage();
            if (st != null && a.getWidth() > 0) {
                Vector2 v = a.localToStageCoordinates(new Vector2(a.getWidth() / 2f, a.getHeight() / 2f));
                pos = " @stage(" + (int) v.x + "," + (int) v.y + ") size(" + (int) a.getWidth() + "x" + (int) a.getHeight() + ")";
            }
            System.out.println("[tutodrive]   " + a.getClass().getSimpleName()
                + (clickable ? " [CLICK]" : "")
                + (a.getTutorialName() != null ? " tut=" + a.getTutorialName() : "")
                + (text != null ? " text=\"" + text + "\"" : "")
                + pos + " touch=" + a.getTouchable());
        }
        if (a instanceof Group) for (Actor c : ((Group) a).getChildren()) dumpRec(c);
    }

    /** Popup d'AFFICHAGE de récompense (à rejeter), vs popup interactive (à actionner). */
    private static boolean isRewardDisplay(String simpleClassName) {
        String n = simpleClassName.toLowerCase();
        return n.contains("result") || n.contains("reward") || n.contains("loot");
    }

    /** Liste des popups modaux ouverts sur l'écran ({@code BaseScreen.getScreenWindows()}). */
    private static List<?> screenWindows(Object screen) {
        try { return (List<?>) screen.getClass().getMethod("getScreenWindows").invoke(screen); }
        catch (Throwable t) { return null; }
    }

    /** Tape chaque acteur trouvé sur un point dont le HIT-TEST retombe sur la cible (conversion stage →
     *  écran par le viewport du jeu). Le jeu hit-teste en coords stage ({@code Stage.hit}) ; pour une Table
     *  {@code touch=childrenOnly} le centre peut ne PAS toucher l'enfant qui reçoit l'évènement (qui bulle
     *  ensuite vers le ClickListener de la Table). On choisit donc un point qui touche vraiment un descendant
     *  de la cible → le clic se déclenche de façon fiable (ex. GOLD_CHEST_FREE_BUTTON). */
    private static boolean tapAll(List<Actor> found, DhInput input, int w, int h) {
        boolean tapped = false;
        for (Actor a : found) {
            Stage st = a.getStage();
            if (st == null || a.getWidth() <= 0 || a.getHeight() <= 0) continue;
            float sw = st.getWidth(), sh = st.getHeight();
            if (sw <= 0 || sh <= 0) continue;
            Vector2 v = reliableTapPoint(st, a);   // point dont Stage.hit retombe sur la cible
            if (REC || DEBUG) {
                Actor hit = st.hit(v.x, v.y, true);
                boolean onTarget = hit != null && isDescendant(a, hit);
                System.err.println("[tuthit] cible=" + a.getTutorialName() + " @stage(" + (int) v.x + ","
                    + (int) v.y + ") → touché=" + describe(hit) + (onTarget ? "  [OK]" : "  [!! HORS-CIBLE]"));
            }
            int sx = Math.round(v.x / sw * w), sy = Math.round(h - v.y / sh * h);
            if (TAP_HOLD > 0) {
                // Press-relâche RÉEL, un seul en vol à la fois (cooldown = maintien + petit intervalle) :
                // évite les down/up superposés qui empêchent les boutons de déclencher.
                if (tapCooldown <= 0) { input.tapHold(sx, sy, TAP_HOLD); tapCooldown = TAP_HOLD + 3; tapped = true; }
            } else {
                input.tap(sx, sy);
                tapped = true;
            }
        }
        if (tapCooldown > 0) tapCooldown--;
        return tapped;
    }

    /** Point (coords stage) à taper pour déclencher {@code a} : son centre s'il touche déjà un descendant de
     *  {@code a} (cas normal), sinon le centre d'un descendant intérieur dont le hit-test retombe sur {@code a}
     *  (gère les Table {@code childrenOnly} dont le centre ne touche pas l'enfant cliquable). */
    private static Vector2 reliableTapPoint(Stage st, Actor a) {
        Vector2 c = a.localToStageCoordinates(new Vector2(a.getWidth() / 2f, a.getHeight() / 2f));
        Actor hit = st.hit(c.x, c.y, true);
        if (hit != null && isDescendant(a, hit)) return c;      // le centre atteint bien la cible
        Vector2 alt = pointHittingDescendant(st, a, a);
        return alt != null ? alt : c;                           // fallback : centre
    }

    /** Renvoie le centre (stage) du 1er descendant de {@code a} dont le hit-test retombe DANS {@code a}. */
    private static Vector2 pointHittingDescendant(Stage st, Actor a, Actor node) {
        if (!(node instanceof Group)) return null;
        for (Actor c : ((Group) node).getChildren()) {
            if (c.isVisible() && c.getWidth() > 0 && c.getHeight() > 0
                && c.getTouchable() != com.badlogic.gdx.scenes.scene2d.Touchable.disabled) {
                Vector2 cc = c.localToStageCoordinates(new Vector2(c.getWidth() / 2f, c.getHeight() / 2f));
                Actor hit = st.hit(cc.x, cc.y, true);
                if (hit != null && isDescendant(a, hit)) return cc;
            }
            Vector2 deep = pointHittingDescendant(st, a, c);
            if (deep != null) return deep;
        }
        return null;
    }

    /**
     * SONDE DEV (dh.mapprobe) : sur l'écran carte, journalise l'état et hit-teste une grille autour du
     * nœud de chapitre pour révéler l'acteur réellement interactif (+ ses listeners) et le déclencheur
     * d'entrée dans un chapitre. Tape ensuite le centre du nœud et observe la transition d'écran. Aucune
     * modif du jeu : lecture de la scène (Stage.hit / getListeners) + tap, comme un joueur.
     */
    private static void mapProbe(String screenName, Group searchRoot, Stage st, DhInput input, int w, int h) {
        if (!screenName.equals(probeLastScreen)) {
            System.out.println("[mapprobe] ===> ÉCRAN = " + screenName);
            probeLastScreen = screenName;
        }
        if (++probeTick % 25 != 0) return;   // toutes les ~25 frames (laisse l'anim « SCANNING » avancer)

        boolean scanning = labelContains(searchRoot, "SCANNING");
        List<Actor> nodes = findByName(searchRoot, PROBE_ACTOR);
        if (nodes.isEmpty()) {
            System.out.println("[mapprobe] " + PROBE_ACTOR + " ABSENT (scanning=" + scanning + ")");
            return;
        }
        Actor node = nodes.get(0);
        Vector2 c = node.localToStageCoordinates(new Vector2(node.getWidth() / 2f, node.getHeight() / 2f));
        System.out.println("[mapprobe] " + PROBE_ACTOR + " @stage(" + (int) c.x + "," + (int) c.y
            + ") scanning=" + scanning + " — hit-test grille (acteur ← ancêtre-avec-listener) :");
        Set<String> seen = new HashSet<>();
        for (int dy = -60; dy <= 60; dy += 30) {
            for (int dx = -100; dx <= 100; dx += 50) {
                Actor hit = st.hit(c.x + dx, c.y + dy, true);
                Actor clickable = nearestWithListener(hit);
                String d = describe(hit) + "  |  clickable=" + (clickable == null ? "(aucun)"
                    : clickable.getClass().getSimpleName()
                      + (clickable.getTutorialName() != null ? "[tut=" + clickable.getTutorialName() + "]" : "")
                      + " listeners=" + listenerTypes(clickable));
                if (seen.add(d)) System.out.println("[mapprobe]    (" + dx + "," + dy + ") → " + d);
            }
        }
        int sx = Math.round(c.x / st.getWidth() * w), sy = Math.round(h - c.y / st.getHeight() * h);
        System.out.println("[mapprobe] TAP centre chapitre @screen(" + sx + "," + sy + ") — observe l'écran suivant");
        if (TAP_HOLD > 0) { if (tapCooldown <= 0) { input.tapHold(sx, sy, TAP_HOLD); tapCooldown = TAP_HOLD + 3; } }
        else input.tap(sx, sy);
        if (tapCooldown > 0) tapCooldown--;
    }

    /**
     * Entre le niveau de campagne JOUABLE en appelant la méthode du jeu qu'un vrai tap de nœud déclenche :
     * {@code CampaignScreen.onCampaignLevelTapped(new CampaignLevelID(chapitre, niveau))}. La carte étant
     * une scène g2d ({@code CityMapDisplay}) sans acteur scene2d cliquable et {@code getPointers()} vide
     * headless, c'est le point d'entrée FIDÈLE (l'API du jeu, pas une coordonnée devinée). Cooldown pour
     * laisser la transition (→ choix des héros) se faire. Renvoie true si l'appel a été émis.
     */
    private static boolean enterCampaignLevel(Object screen, User user) {
        if (enterCooldown > 0) { enterCooldown--; return false; }
        try {
            int[] lvl = nextPlayableLevel(user);   // enchaîne 1-1 → 1-2 → … (prochain niveau débloqué)
            Class<?> idCls = Class.forName("com.perblue.heroes.ui.campaign.CampaignLevelID");
            Object id = idCls.getConstructor(int.class, int.class).newInstance(lvl[0], lvl[1]);
            // normalOrEliteNodeSelected = méthode du jeu que le tap d'un nœud atteint (via onCampaignLevelTapped) :
            // vérifie le statut de déverrouillage puis pousse CampaignPreviewScreen(type, ch, lvl). On la cible
            // DIRECTEMENT car onCampaignLevelTapped no-ope tant que la carte est dézoomée (garde mapZoomedOut).
            java.lang.reflect.Method m = null;
            for (Class<?> c = screen.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try { m = c.getDeclaredMethod("normalOrEliteNodeSelected", idCls); } catch (NoSuchMethodException ignore) {}
            }
            if (m == null) { if (DEBUG) System.out.println("[tutodrive] normalOrEliteNodeSelected introuvable"); return false; }
            m.setAccessible(true);
            m.invoke(screen, id);
            justFoughtCampaign = false;   // entrée FRAÎCHE d'un niveau → on va combattre (pas un retour post-victoire)
            System.out.println("[tutodrive] CampaignScreen → normalOrEliteNodeSelected(" + lvl[0] + "-"
                + lvl[1] + ") [API du jeu → CampaignPreviewScreen]");
            enterCooldown = 90;   // ~90 frames avant un éventuel nouvel essai (laisse ouvrir l'aperçu du niveau)
            return true;
        } catch (Throwable t) {
            if (DEBUG) System.out.println("[tutodrive] enterCampaignLevel échec: " + t);
            return false;
        }
    }

    /**
     * Sur un écran de CHOIX DES HÉROS (CampaignHeroChooserScreen…), sélectionne les héros disponibles via
     * l'API du jeu qu'un tap de portrait déclenche : {@code HeroChooserScreen.unitSelected(UnitData, provider,
     * x, y)} (cœur = {@code HeroChooserHelper.selectUnitPressed} ; pour la campagne le {@code provider}/coords
     * ne sont PAS utilisés — vérifié au bytecode : seul SURGE les lit → null/0 sûrs). Sans ça, TEAM POWER=0
     * et FIGHT affiche « select at least one hero » → aucun {@code CampaignAttack} envoyé. On n'ajoute QUE les
     * héros pas déjà dans l'équipe ({@code unitSelected} TOGGLE) et sélectionnables ({@code canSelectUnit} =
     * false quand l'équipe est pleine), donc l'appel se stabilise. Appelé seulement quand AUCUN pointeur tuto
     * n'est actif (sinon on suit le pointeur — fidélité au guidage du tuto). Renvoie true si au moins un héros
     * a été ajouté ce frame (on cède la main ; FIGHT est tapé aux frames suivants, équipe non vide).
     */
    private static boolean selectHeroesIfNeeded(Object screen) {
        try {
            Class<?> unitC = Class.forName("com.perblue.heroes.game.objects.UnitData");
            Class<?> dataC = Class.forName("com.perblue.heroes.ui.herochooser.HeroChooserData");
            Class<?> provC = Class.forName("com.perblue.heroes.game.logic.CollectionHelper$CollectionLevelProvider");
            Class<?> helperC = Class.forName("com.perblue.heroes.ui.herochooser.HeroChooserHelper");
            java.util.List<?> avail = (java.util.List<?>) screen.getClass().getMethod("getAvailableHeroes").invoke(screen);
            if (avail == null || avail.isEmpty()) return false;
            // champ protégé 'data' de HeroChooserScreen — parcourt les superclasses.
            Object data = null;
            for (Class<?> c = screen.getClass(); c != null && data == null; c = c.getSuperclass()) {
                try { java.lang.reflect.Field f = c.getDeclaredField("data"); f.setAccessible(true); data = f.get(screen); }
                catch (NoSuchFieldException ignore) {}
            }
            if (data == null) return false;
            java.lang.reflect.Method isIn = helperC.getMethod("isUnitInSelectedLineup", dataC, unitC);
            java.lang.reflect.Method canSelect = screen.getClass().getMethod("canSelectUnit", unitC);
            java.lang.reflect.Method unitSelected = screen.getClass().getMethod("unitSelected", unitC, provC, float.class, float.class);
            boolean selectedAny = false;
            for (Object unit : avail) {
                if ((Boolean) isIn.invoke(null, data, unit)) continue;      // déjà dans l'équipe (ne pas re-toggle)
                if (!(Boolean) canSelect.invoke(screen, unit)) continue;    // équipe pleine / non sélectionnable
                unitSelected.invoke(screen, unit, null, 0f, 0f);            // = tap du portrait (API du jeu)
                selectedAny = true;
            }
            if (selectedAny) System.out.println("[tutodrive] " + screen.getClass().getSimpleName()
                + " → héros sélectionnés via unitSelected (API du jeu) → équipe prête pour FIGHT");
            return selectedAny;
        } catch (Throwable t) {
            if (DEBUG) System.out.println("[tutodrive] selectHeroesIfNeeded échec: " + t);
            return false;
        }
    }

    /**
     * Prochain niveau JOUABLE de la campagne NORMAL, via l'API du jeu : {@code getLatestCompletedLevel}
     * (dernier niveau complété) puis le suivant s'il est débloqué ({@code isLevelUnlocked}), sinon le 1ᵉʳ
     * du chapitre suivant, sinon on rejoue le dernier. Permet d'enchaîner 1-1 → 1-2 → … Override explicite
     * possible via {@code dh.playlevel="ch,lvl"}. Le combat gagné débloque le suivant (statuts re-syncés serveur).
     */
    private static int[] nextPlayableLevel(User user) {
        if (System.getProperty("dh.playlevel") != null) return PLAY_LEVEL;   // niveau forcé (debug)
        try {
            com.perblue.heroes.network.messages.CampaignType N = com.perblue.heroes.network.messages.CampaignType.NORMAL;
            com.perblue.heroes.game.data.campaign.CampaignLevel latest =
                com.perblue.heroes.game.logic.CampaignHelper.getLatestCompletedLevel(user, N);
            if (latest == null) return new int[]{1, 1};
            int ch = latest.getChapter(), lv = latest.getLevel();
            if (com.perblue.heroes.game.logic.CampaignHelper.isLevelUnlocked(user, N, ch, lv + 1)) return new int[]{ch, lv + 1};
            if (com.perblue.heroes.game.logic.CampaignHelper.isLevelUnlocked(user, N, ch + 1, 1)) return new int[]{ch + 1, 1};
            return new int[]{ch, lv};   // rien de plus débloqué → rejouer le dernier
        } catch (Throwable t) { return new int[]{1, 1}; }
    }

    /** Vrai si un DFLabel sous {@code root} contient {@code sub} dans son texte (réflexion getText). */
    private static boolean labelContains(Actor root, String sub) {
        try {
            java.lang.reflect.Method m = root.getClass().getMethod("getText");
            Object t = m.invoke(root);
            if (t != null && t.toString().toUpperCase().contains(sub)) return true;
        } catch (Throwable ignore) {}
        if (root instanceof Group)
            for (Actor c : ((Group) root).getChildren()) if (labelContains(c, sub)) return true;
        return false;
    }

    /** Remonte de {@code a} vers ses ancêtres et renvoie le 1er acteur portant ≥1 listener (= interactif). */
    private static Actor nearestWithListener(Actor a) {
        for (Actor p = a; p != null; p = p.getParent())
            if (p.getListeners() != null && p.getListeners().size > 0) return p;
        return null;
    }

    /** Noms des classes de listeners d'un acteur (pour identifier ClickListener / gesture / input). */
    private static String listenerTypes(Actor a) {
        if (a == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (com.badlogic.gdx.scenes.scene2d.EventListener l : a.getListeners())
            sb.append(l.getClass().getSimpleName()).append(',');
        return sb.append(']').toString();
    }

    /** Décrit un acteur touché : classe + tutorialName + chaîne d'ancêtres (tutorialName / classe). */
    private static String describe(Actor a) {
        if (a == null) return "(rien)";
        StringBuilder sb = new StringBuilder(a.getClass().getSimpleName());
        if (a.getTutorialName() != null) sb.append("[tut=").append(a.getTutorialName()).append(']');
        Group p = a.getParent();
        int depth = 0;
        while (p != null && depth++ < 8) {
            sb.append(" ← ").append(p.getClass().getSimpleName());
            if (p.getTutorialName() != null) sb.append("[tut=").append(p.getTutorialName()).append(']');
            p = p.getParent();
        }
        return sb.toString();
    }

    /** Vrai si {@code maybe} est {@code ancestor} ou un descendant de {@code ancestor}. */
    private static boolean isDescendant(Actor ancestor, Actor maybe) {
        for (Actor p = maybe; p != null; p = p.getParent()) if (p == ancestor) return true;
        return false;
    }

    /** Retrouve les acteurs portant un {@code getTutorialName()} donné (helper pour BACK_BUTTON…). */
    private static List<Actor> findByName(Actor root, String name) {
        Set<String> s = new HashSet<>(); s.add(name);
        List<Actor> out = new ArrayList<>();
        collect(root, s, out);
        return out;
    }

    /** Collecte récursivement les acteurs dont {@code getTutorialName()} ∈ targets. */
    private static void collect(Actor actor, Set<String> targets, List<Actor> out) {
        String name = actor.getTutorialName();
        if (name != null && targets.contains(name)) out.add(actor);
        if (actor instanceof Group) {
            for (Actor child : ((Group) actor).getChildren()) collect(child, targets, out);
        }
    }
}
