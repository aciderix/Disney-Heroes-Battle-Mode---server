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
import com.perblue.heroes.network.messages.UnitType;
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
    // NAVIGATION SIGN-IN (DEV, dh.gosignin) : une fois au hub, ouvrir le bâtiment SIGN IN via l'API du jeu
    // (UINavHelper.navigateTo(Destination.SIGN_IN)) pour DÉCLENCHER le flux REFRESH_SPECIAL_EVENTS → le
    // serveur répond SpecialEventsRaw{signinRewards} (récompenses de connexion, cf. docs/SIGNIN_EVENTS.md).
    // Le client n'envoie ce refresh QUE si SpecialEventsHelper.userNeedsNewData=true OU si le SignInScreen
    // s'ouvre (refreshSpecialEvents direct) — donc on force l'ouverture. One-shot (signinNavDone).
    private static final boolean GO_SIGNIN = System.getProperty("dh.gosignin") != null
            && !"0".equals(System.getProperty("dh.gosignin"))
            && !"false".equalsIgnoreCase(System.getProperty("dh.gosignin"));
    private static boolean signinNavDone = false;
    private static int signinWaitTicks = 0;   // frames d'attente du bouton CLAIM sur SignInScreen (borné)
    // Slots dont l'équip a ÉCHOUÉ en jeu (ErrorWindow) → à SAUTER pour éviter la boucle re-EQUIP→erreur.
    // Clé "HERO:SLOT". lastEquip* = dernier slot tenté (pour l'attribuer à l'échec quand l'erreur surgit).
    private static final Set<String> failedEquipSlots = new HashSet<>();
    private static UnitType lastEquipHero = null;
    private static HeroEquipSlot lastEquipSlot = null;

    /** Au moins un héros possédé a-t-il un objet équipable (le « +equip vert ») ET non-bloqué ? */
    private static boolean anyHeroNeedsEquip(User user) {
        return firstHeroNeedingEquip(user) != null;
    }
    /** 1er héros possédé avec un objet équipable dont le slot n'est PAS déjà en échec, sinon null. */
    private static IHero firstHeroNeedingEquip(User user) {
        try {
            for (Object o : user.getHeroes()) {
                IHero hh = (IHero) o;
                if (!HeroHelper.hasItemsToEquip(user, hh)) continue;
                HeroEquipSlot s = HeroHelper.getSlotThatCanEquip(user, hh);
                // slot suivant déjà connu comme échouant (item manquant/non équipable) → héros bloqué, on saute.
                if (s != null && failedEquipSlots.contains(hh.getType().name() + ":" + s.name())) continue;
                return hh;
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

                // (a0) Fenêtre d'ERREUR (équip refusé : « can't equip / don't have ») dans la pile → la FERMER
                //     et ABANDONNER l'équip courant (fermer la CraftingWindow) + mémoriser le (héros,slot)
                //     échoué, sinon boucle re-EQUIP→erreur→re-EQUIP (observé). equipDrive sautera ce slot.
                for (Object wnd : windows) {
                    if (wnd.getClass().getSimpleName().toLowerCase().contains("error")) {
                        if (lastEquipHero != null && lastEquipSlot != null)
                            failedEquipSlots.add(lastEquipHero.name() + ":" + lastEquipSlot.name());
                        try { wnd.getClass().getMethod("hide").invoke(wnd); } catch (Throwable ignore) {}
                        for (Object w2 : windows) if (w2.getClass().getSimpleName().contains("Crafting"))
                            try { w2.getClass().getMethod("hide").invoke(w2); } catch (Throwable ignore) {}
                        if (DEBUG) System.out.println("[autoequip] équip REFUSÉ (ErrorWindow) slot=" + lastEquipSlot
                            + " → fermé + slot marqué échoué");
                        return true;
                    }
                }

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

            // SIGN-IN (DEV, dh.gosignin). SignInScreen est un ÉCRAN (UIScreen, pushScreen) → screenName=
            // "SignInScreen". (a) SUR cet écran : taper le bouton CLAIM (tag « claim_button ») →
            // Action{CLAIM_SIGNIN_REWARD} ; on TIENT l'écran (pas de RETOUR) le temps que les données sign-in
            // arrivent et peuplent le bouton (borné, sinon on laisse repartir). (b) Au hub : navigateTo(SIGN_IN).
            if (GO_SIGNIN && screenName.contains("SignIn")) {
                // Le bouton CLAIM est un DFTextButton (pas de tag tutoriel — « claim_button » est un nom de SON).
                // On collecte les boutons-texte de l'écran et on tape (le CLAIM = bouton d'action principal).
                List<Actor> btns = new ArrayList<>();
                collectTextButtons(searchRoot, btns);
                if (!btns.isEmpty() && tapAll(btns, input, w, h)) {
                    System.out.println("[gosignin] SignInScreen → tap bouton d'action (CLAIM), " + btns.size() + " bouton(s)");
                    hadTarget = true; signinWaitTicks = 0;
                    return true;
                }
                // bouton pas encore présent (données sign-in en réception) → tenir l'écran quelques frames.
                if (signinWaitTicks++ < 40) { hadTarget = true; return true; }
                // au-delà : rien à réclamer (déjà pris aujourd'hui) → laisser le flux normal (RETOUR).
            }
            // NAVIGATION : au hub sans popup, OUVRIR le bâtiment SIGN IN (déclenche REFRESH_SPECIAL_EVENTS → le
            // serveur répond). On RESPECTE le verrou de nav du tuto (canNavigateTo=false → on attend ; §2).
            if (GO_SIGNIN && !signinNavDone && screenName.contains("MainScreen")
                    && (windows == null || windows.isEmpty())) {
                try {
                    com.perblue.heroes.ui.UINavHelper.Destination dest =
                        com.perblue.heroes.ui.UINavHelper.Destination.SIGN_IN;
                    if (!com.perblue.heroes.ui.UINavHelper.canNavigateTo(dest, false)) {
                        if (!"signin-blocked".equals(idleScreen)) {
                            System.out.println("[gosignin] SIGN_IN bloqué par le tuto (canNavigateTo=false) → j'attends");
                        }
                    } else {
                        System.out.println("[gosignin] hub libre → navigateTo(SIGN_IN)");
                        com.perblue.heroes.ui.UINavHelper.navigateTo(dest, "dev", new String[0]);
                        signinNavDone = true;
                        return true;
                    }
                } catch (Throwable t) {
                    System.out.println("[gosignin] navigateTo(SIGN_IN) échec: " + t);
                    signinNavDone = true;
                }
            }

            // SONDE DEV : sur l'écran carte, prend la main (pas de RETOUR) pour cliquer le chapitre et observer.
            if (MAP_PROBE && screenName.contains("Campaign") && stg != null) {
                mapProbe(screenName, searchRoot, stg, input, w, h);
                return true;   // handled : empêche le tap central du lanceur
            }
            // ÉTAPE ÉQUIPEMENT (OBLIGATOIRE) : si un héros a un objet équipable, on NE FONCE PAS en campagne
            // (le tuto verrouille la progression tant qu'on n'a pas équipé ; sans ça le pilote forçait la
            // campagne via l'API et sautait cette étape). La navigation d'équip elle-même est laissée AU TUTO
            // (ses flèches guident bâtiment→coffre→burger→HEROES→héros→+EQUIP→EQUIP, suivies plus bas par le
            // bloc « cible désignée ») ; equipDrive n'intervient qu'en REPLI si aucune flèche n'est active.
            boolean needEquip = AUTO_EQUIP && anyHeroNeedsEquip(user);

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
                // NB : la NAVIGATION d'équip est laissée au TUTO (ses flèches guident coffre→burger→HEROES→
                // héros→+EQUIP→EQUIP, suivies par le bloc « cible désignée »). On ne FORCE PAS equipDrive ici :
                // il entrait en conflit avec le pas courant du tuto (ex. sur ChestsScreen le tuto veut d'abord
                // OUVRIR un coffre, mais equipDrive faisait BACK). Le rôle du pilote à l'équip = juste NE PAS
                // foncer en campagne (gates needEquip ci-dessus/dessous) et suivre les pointeurs. equipDrive
                // reste dispo comme repli de DERNIER recours (idle prolongé) — branché plus bas.
                // POST-VICTOIRE — ENCHAÎNER : après un combat, le client revient sur l'aperçu/choix du MÊME
                // niveau ; sans intervention le pilote re-taperait FIGHT (rejoue le même niveau). On revient
                // plutôt à la CARTE (BACK) une fois : sur CampaignScreen, enterCampaignLevel prendra
                // nextPlayableLevel = niveau débloqué SUIVANT (1-1→1-2→…). Le déblocage est autoritatif serveur.
                // Suspendu si un équipement est en attente (ne pas enchaîner la campagne avant d'équiper).
                if (!needEquip && justFoughtCampaign
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
                // (suspendu si équipement en attente : ne pas relancer un combat avant d'équiper)
                List<Actor> adv = new ArrayList<>();
                if (!needEquip) collect(searchRoot, ADVANCE_BUTTONS, adv);
                if (!adv.isEmpty()) {
                    if (DEBUG) System.out.println("[tutodrive] " + screenName + " sans pointeur → tap bouton d'action "
                        + adv.get(0).getTutorialName());
                    return tapAll(adv, input, w, h);
                }
                // REPLI ÉQUIP (dernier recours) : équipement en attente + idle prolongé SANS pointeur de tuto
                // → naviguer vers l'équip nous-mêmes (menu HÉROS → héros → +EQUIP). Ne fait rien tant que le
                // tuto guide (pointeur actif → on ne passe pas dans ce bloc idle).
                if (needEquip && idleTicks >= IDLE_BACK_THRESHOLD
                        && equipDrive(user, screenName, searchRoot, input, w, h)) { idleTicks = 0; return true; }
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
        // Tag réel relevé au dump : "HERO_GEAR_SLOT_ ONE" (avec une ESPACE avant le nom du slot).
        if (screenName.contains("HeroDetail")) {
            HeroEquipSlot slot = HeroHelper.getSlotThatCanEquip(user, hero);
            if (slot != null) {
                List<Actor> s = findByName(searchRoot, "HERO_GEAR_SLOT_ " + slot.name());
                if (!s.isEmpty()) {
                    lastEquipHero = hero.getType(); lastEquipSlot = slot;   // pour attribuer un éventuel échec
                    if (DEBUG) System.out.println("[autoequip] tap slot HERO_GEAR_SLOT_ " + slot.name()
                        + " (" + hero.getType() + ")");
                    return tapAll(s, input, w, h);
                }
            }
            // slot introuvable → l'onglet GEAR/ITEMS n'est peut-être pas actif : l'activer d'abord.
            List<Actor> gearTab = findByName(searchRoot, "HERO_SUMMARY_GEAR_TAB");
            if (!gearTab.isEmpty()) {
                if (DEBUG) System.out.println("[autoequip] HeroDetail → activer l'onglet GEAR (HERO_SUMMARY_GEAR_TAB)");
                return tapAll(gearTab.subList(0, 1), input, w, h);
            }
            return false;
        }
        // HeroList : taper la carte du héros à équiper. Tag réel = "HERO_LIST"+heroType (ex.
        // HERO_LISTELASTIGIRL), relevé au dump — PAS de findByClass (la 1re carte = un héros VERROUILLÉ
        // « new hero to unlock », ex. HERO_LISTBENJAMIN_FRANKLIN_GATES → mauvais écran).
        if (screenName.contains("HeroList")) {
            List<Actor> card = findByName(searchRoot, "HERO_LIST" + hero.getType().name());
            if (!card.isEmpty()) {
                if (DEBUG) System.out.println("[autoequip] tap carte héros HERO_LIST" + hero.getType().name());
                return tapAll(card.subList(0, 1), input, w, h);
            }
            return false;   // carte du héros pas (encore) visible → attendre/scroller (le tuto pointe déjà dessus)
        }
        // HUB (MainScreen) : ouvrir le menu HÉROS. BASE_MENU_HERO_BUTTON est dans le menu latéral (burger) →
        // si absent, ouvrir d'abord le burger (BASE_MENU_BUTTON / SIDE_MENU). Le tap sur BASE_MENU_HERO_BUTTON
        // hors du hub (ex. CampaignPreviewScreen) ne navigue PAS → on ne le tente QUE sur MainScreen.
        if (screenName.contains("MainScreen")) {
            // Le menu latéral est COLLAPSÉ par défaut : les icônes (BASE_MENU_HERO_BUTTON) sont HORS écran
            // à droite (x≈1341 > 1280). On ne peut les taper que si le menu est OUVERT → si l'icône HÉROS est
            // hors écran, taper d'abord le burger (BASE_MENU_BUTTON, à x≈1226, ON écran) pour dérouler.
            List<Actor> heroBtn = findByName(searchRoot, "BASE_MENU_HERO_BUTTON");
            if (!heroBtn.isEmpty() && onStage(heroBtn.get(0))) {
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

    /** Vrai si le centre de l'acteur tombe DANS les bornes du stage (donc réellement tapable à l'écran). */
    private static boolean onStage(Actor a) {
        Stage st = a.getStage();
        if (st == null || a.getWidth() <= 0 || !a.isVisible()) return false;
        Vector2 v = a.localToStageCoordinates(new Vector2(a.getWidth() / 2f, a.getHeight() / 2f));
        return v.x >= 0 && v.x <= st.getWidth() && v.y >= 0 && v.y <= st.getHeight();
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

    /**
     * DEV (clic manuel, méthode B-bis) : hit-teste l'acteur sous le point écran (cx,cy) et ENREGISTRE
     * « ce que le clic active » — la CHAÎNE cible→ancêtres (classe / tag tutoriel / name / listeners / texte),
     * l'écran courant et les fenêtres ouvertes. Aucune modif du jeu : pure lecture de la scène (Stage.hit /
     * getListeners), comme un joueur. Appelé par {@code DesktopLauncher.injectManualClicks} avant le tap ;
     * la TRANSITION (nouvel écran/fenêtre) apparaît dans la capture + le dump du clic SUIVANT.
     */
    public static void dumpClickTarget(GameMain game, int cx, int cy) {
        try {
            Object screen = game.getScreenManager().getScreen();
            String screenName = screen == null ? "null" : screen.getClass().getSimpleName();
            Stage st = null;
            try {
                Group root = (Group) screen.getClass().getMethod("getRootStack").invoke(screen);
                if (root != null) st = root.getStage();
            } catch (Throwable ignore) { /* pas de getRootStack → on tente via une fenêtre */ }
            if (st == null) {
                List<?> ws = screenWindows(screen);
                if (ws != null) for (Object w : ws) if (w instanceof Actor && ((Actor) w).getStage() != null) { st = ((Actor) w).getStage(); break; }
            }
            StringBuilder wl = new StringBuilder();
            List<?> ws = screenWindows(screen);
            if (ws != null) for (Object w : ws) wl.append(w.getClass().getSimpleName()).append(',');
            if (st == null) { System.out.println("[clicdump] écran=" + screenName + " fenêtres=[" + wl + "] (pas de stage)"); return; }
            Vector2 sc = st.screenToStageCoordinates(new Vector2(cx, cy));
            Actor hit = st.hit(sc.x, sc.y, true);
            System.out.println("[clicdump] écran=" + screenName + " fenêtres=[" + wl + "] clic écran(" + cx + "," + cy
                + ") → stage(" + (int) sc.x + "," + (int) sc.y + ")");
            if (hit == null) { System.out.println("[clicdump]   (aucun acteur touché à ce point)"); return; }
            int depth = 0;
            for (Actor a = hit; a != null && depth < 8; a = a.getParent(), depth++) {
                StringBuilder sb = new StringBuilder("[clicdump]   ")
                    .append(depth == 0 ? "CIBLE   " : "ancetre" + depth + " ")
                    .append(a.getClass().getSimpleName());
                if (a.getTutorialName() != null) sb.append(" tut=").append(a.getTutorialName());
                if (a.getName() != null) sb.append(" name=").append(a.getName());
                java.util.List<String> ls = new java.util.ArrayList<>();
                for (com.badlogic.gdx.scenes.scene2d.EventListener l : a.getListeners()) ls.add(l.getClass().getSimpleName());
                if (!ls.isEmpty()) sb.append(" listeners=").append(ls);
                if (a instanceof com.badlogic.gdx.scenes.scene2d.ui.Label) {
                    CharSequence t = ((com.badlogic.gdx.scenes.scene2d.ui.Label) a).getText();
                    if (t != null && t.length() > 0) sb.append(" text=\"").append(t).append('"');
                }
                boolean visible = a.isVisible() && a.getWidth() > 0;
                if (!visible) sb.append(" [invisible/0]");
                System.out.println(sb);
            }
        } catch (Throwable t) { System.out.println("[clicdump] err " + t); }
    }

    /** DEV : CLIC ROBUSTE — trouve l'acteur scene2d au point (comme dumpClickTarget) et lui envoie une séquence
     *  d'{@code InputEvent} touchDown+touchUp CORRECTE (stage/coords/pointer/bouton) via {@code actor.fire()}, ce qui
     *  déclenche à coup sûr les {@code ClickListener}/{@code ChangeListener} — indépendamment de quel {@code Stage}
     *  est enregistré comme {@code InputProcessor} courant (certains sous-écrans ont un stage propre non branché sur
     *  {@code Gdx.input}, d'où des taps « perdus » via le chemin processor). Log l'acteur touché + ses listeners.
     *  Renvoie true si un acteur a été touché. Invoqué via dh.clickfile "fire x,y". */
    /** Vrai si le sous-arbre contient une fenêtre MODALE / un prompt VISIBLE (classe ou super-classe évoquant
     *  {@code *ModalWindow}/{@code *Prompt}/{@code *Confirm*}). Sert à savoir si {@code aboveBlurStage} tient une
     *  modale (⇒ le pilote doit y taper) plutôt que le seul HUD. Pure lecture de la scène. */
    private static boolean hasModal(Actor a) {
        if (a == null || !a.isVisible()) return false;
        for (Class<?> c = a.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getSimpleName();
            if (n.contains("ModalWindow") || n.contains("Prompt") || n.contains("ConfirmationWindow")
                || n.contains("ConfirmWindow")) return true;
        }
        if (a instanceof Group)
            for (Actor child : ((Group) a).getChildren()) if (hasModal(child)) return true;
        return false;
    }

    public static boolean fireClick(GameMain game, int cx, int cy) {
        try {
            Object screen = game.getScreenManager().getScreen();
            // Stages candidats, DU PLUS HAUT AU PLUS BAS : (1) aboveBlurStage du ScreenManager — les fenêtres
            // MODALES (confirmations BUY/RESTART/« ARE YOU SURE ») y vivent AU-DESSUS du blur ; le pilote les
            // ratait car il ne visait que le stage de l'ÉCRAN (belowBlur) → il touchait le bouton DERRIÈRE la
            // modale. (2) le root stack de l'écran. (3) une fenêtre ouverte. On hit-teste dans l'ordre et on
            // retient le 1er stage où un VRAI acteur (≠ racine) est touchable au point → la modale gagne.
            java.util.List<Stage> stages = new java.util.ArrayList<>();
            // aboveBlurStage porte le HUD ET les fenêtres MODALES. On ne l'utilise QUE si une modale est
            // réellement ouverte (sinon on taperait le HUD au lieu du bouton d'écran derrière). Détection : un
            // acteur visible dont la classe (ou une super-classe) évoque une fenêtre modale / un prompt.
            Stage above = null;
            try {
                Object sm = game.getScreenManager();
                java.lang.reflect.Field f = sm.getClass().getDeclaredField("aboveBlurStage"); f.setAccessible(true);
                above = (Stage) f.get(sm);
            } catch (Throwable ignore) {}
            if (above != null && hasModal(above.getRoot())) stages.add(above);
            try { Group root = (Group) screen.getClass().getMethod("getRootStack").invoke(screen);
                  if (root != null && root.getStage() != null && !stages.contains(root.getStage())) stages.add(root.getStage()); } catch (Throwable ignore) {}
            List<?> ws0 = screenWindows(screen);
            if (ws0 != null) for (Object w : ws0) if (w instanceof Actor && ((Actor) w).getStage() != null && !stages.contains(((Actor) w).getStage())) { stages.add(((Actor) w).getStage()); break; }
            if (stages.isEmpty()) { System.out.println("[fire] pas de stage"); return false; }
            // On retient le 1er stage (du plus haut au plus bas) où le point touche un acteur CLIQUABLE (lui-même
            // ou un ancêtre proche a des listeners) — sinon on saute (ex. aboveBlurStage porte un Group INERTE de
            // fond même SANS modale ; sans ce filtre, il masquerait le vrai bouton du belowBlurStage derrière).
            Stage st = null; Actor hit = null; Vector2 sc = null;
            for (Stage s : stages) {
                Vector2 p = s.screenToStageCoordinates(new Vector2(cx, cy));
                Actor h = s.hit(p.x, p.y, true);
                if (h == null || h == s.getRoot()) continue;
                boolean clickable = false;
                int depth = 0;
                for (Actor a = h; a != null && depth < 6; a = a.getParent(), depth++)
                    if (!a.getListeners().isEmpty()) { clickable = true; break; }
                if (clickable) { st = s; hit = h; sc = p; break; }
            }
            if (hit == null) { System.out.println("[fire] aucun acteur cliquable en (" + cx + "," + cy + ")"); return false; }
            java.util.List<String> ls = new java.util.ArrayList<>();
            for (com.badlogic.gdx.scenes.scene2d.EventListener l : hit.getListeners()) ls.add(l.getClass().getSimpleName());
            System.out.println("[fire] cible=" + hit.getClass().getSimpleName() + " stage(" + (int) sc.x + "," + (int) sc.y
                + ") listeners=" + ls);
            com.badlogic.gdx.scenes.scene2d.InputEvent down = new com.badlogic.gdx.scenes.scene2d.InputEvent();
            down.setType(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchDown);
            down.setStage(st); down.setStageX(sc.x); down.setStageY(sc.y); down.setPointer(0); down.setButton(0);
            hit.fire(down);
            com.badlogic.gdx.scenes.scene2d.InputEvent up = new com.badlogic.gdx.scenes.scene2d.InputEvent();
            up.setType(com.badlogic.gdx.scenes.scene2d.InputEvent.Type.touchUp);
            up.setStage(st); up.setStageX(sc.x); up.setStageY(sc.y); up.setPointer(0); up.setButton(0);
            hit.fire(up);
            System.out.println("[fire] touchDown+touchUp envoyés sur " + hit.getClass().getSimpleName());
            return true;
        } catch (Throwable t) { System.out.println("[fire] échec: " + t); return false; }
    }

    /** DEV DIAGNOSTIC : imprime l'état CLIENT du check-in (les FAITS qui décident si le bouton s'envoie) —
     *  guildID, dernier check-in, borne de reset, canCheckIn, horloge serveur. Invoqué via "checkinstate". */
    public static void checkInState(GameMain game) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            long gid = u.getGuildID();
            long last = u.getTime(com.perblue.heroes.network.messages.TimeType.LAST_GUILD_CHECK_IN);
            long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
            // ⚠️ getLastCheckinResetTime/canCheckIn prennent le TIMESTAMP « maintenant » en 2e arg, PAS le guildID.
            long reset = com.perblue.heroes.game.logic.GuildCheckInHelper.getLastCheckinResetTime(now);
            boolean can = com.perblue.heroes.game.logic.GuildCheckInHelper.canCheckIn(u, now);
            com.perblue.heroes.network.messages.GuildInfo gi = game.getYourGuildInfo();
            System.out.println("[checkinstate] guildID=" + gid + " lastCheckIn=" + last + " resetTime=" + reset
                + " canCheckIn=" + can + " serverNow=" + now + " influence=" + (gi == null ? "?" : gi.influence)
                + " guildTZ=" + (gi == null ? "?" : gi.timeZone));
        } catch (Throwable t) { System.out.println("[checkinstate] échec: " + t); }
    }

    /** DEV : déclenche le CHECK-IN de guilde via le CHEMIN D'ENVOI RÉEL du jeu ({@code GuildCheckInScreen.doCheckIn}
     *  → {@code ClientActionHelper.checkInToGuild}), en contournant la GARDE CLIENTE du bouton ({@code canCheckIn}
     *  s'appuie sur {@code getLastCheckinResetTime} qui dépend de l'infra de fuseau de guilde et peut renvoyer une
     *  valeur aberrante → bouton no-op) — le SERVEUR reste autoritatif (sa propre garde quotidienne décide). Invoqué
     *  via dh.clickfile "docheckin". */
    public static void doGuildCheckIn(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("GuildCheckIn")) {
                System.out.println("[docheckin] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas GuildCheckInScreen)");
                return;
            }
            java.lang.reflect.Method mth = null;
            for (Class<?> c = screen.getClass(); c != null && mth == null; c = c.getSuperclass()) {
                try { mth = c.getDeclaredMethod("doCheckIn"); } catch (NoSuchMethodException ignore) {}
            }
            if (mth == null) { System.out.println("[docheckin] doCheckIn introuvable"); return; }
            mth.setAccessible(true);
            mth.invoke(screen);
            System.out.println("[docheckin] doCheckIn() invoqué [chemin d'envoi réel du jeu]");
        } catch (Throwable t) { System.out.println("[docheckin] échec: " + t); }
    }

    /** DEV : navigue vers une destination du hub via l'API DU JEU (UINavHelper.navigateTo), en RESPECTANT le
     *  verrou de nav (canNavigateTo=false = tuto/unlockable non levé → on n'ouvre pas ; fidèle, §2). */
    public static void navTo(GameMain game, com.perblue.heroes.ui.UINavHelper.Destination dest) {
        try {
            if (!com.perblue.heroes.ui.UINavHelper.canNavigateTo(dest, false)) {
                System.out.println("[nav] " + dest + " BLOQUÉ (canNavigateTo=false — verrou tuto/unlockable)");
                return;
            }
            System.out.println("[nav] navigateTo(" + dest + ")");
            com.perblue.heroes.ui.UINavHelper.navigateTo(dest, "dev", new String[0]);
        } catch (Throwable t) { System.out.println("[nav] " + dest + " échec: " + t); }
    }

    /** DEV : ouvre l'APERÇU d'un niveau de campagne (CampaignPreviewScreen) via l'API DU JEU
     *  {@code CampaignScreen.normalOrEliteNodeSelected(CampaignLevelID)} — la MÊME méthode que le tap d'un nœud
     *  (la carte est g2d, pas d'acteur scene2d cliquable → on cible l'API, pas une coordonnée devinée, cf. B-bis).
     *  NORMAL ou ELITE selon l'onglet actif de la carte (état de CampaignScreen). Invoqué via dh.clickfile
     *  "enterlevel ch,lvl". De là on peut taper PLAY ou RAID (boutons scene2d de l'aperçu) au clic. */
    public static void enterLevel(GameMain game, int ch, int lvl) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("Campaign")) {
                System.out.println("[enterlevel] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas une carte de campagne)");
                return;
            }
            Class<?> idCls = Class.forName("com.perblue.heroes.ui.campaign.CampaignLevelID");
            Object id = idCls.getConstructor(int.class, int.class).newInstance(ch, lvl);
            java.lang.reflect.Method m = null;
            for (Class<?> c = screen.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try { m = c.getDeclaredMethod("normalOrEliteNodeSelected", idCls); } catch (NoSuchMethodException ignore) {}
            }
            if (m == null) { System.out.println("[enterlevel] normalOrEliteNodeSelected introuvable"); return; }
            m.setAccessible(true);
            m.invoke(screen, id);
            System.out.println("[enterlevel] normalOrEliteNodeSelected(" + ch + "-" + lvl + ") [API du jeu → aperçu du niveau]");
        } catch (Throwable t) { System.out.println("[enterlevel] échec: " + t); }
    }

    /** DEV : crée une guilde depuis l'écran CREATE GUILD courant, en fournissant le NOM (ce que ferait le clavier
     *  natif — non pilotable en headless) puis en déclenchant le CHEMIN D'ENVOI RÉEL du jeu ({@code tryCreateGuild})
     *  qui valide côté client (NameChangeHelper.isNameLegal) et envoie {@code CreateGuild} au serveur via la pile
     *  réseau du client. Le reste (motto, politique, min level…) garde les valeurs par défaut de l'écran. Le tour
     *  client→serveur→UserGuildUpdate→transition « en guilde » est donc exercé EN JEU. Invoqué via
     *  dh.clickfile "createguild &lt;nom&gt;". */
    public static void createGuild(GameMain game, String name) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("CreateGuild")) {
                System.out.println("[createguild] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas CreateGuildScreen)");
                return;
            }
            // settingsTable.setGuildName(name) — champ nom réel de l'écran (public).
            java.lang.reflect.Field stf = null;
            for (Class<?> c = screen.getClass(); c != null && stf == null; c = c.getSuperclass()) {
                try { stf = c.getDeclaredField("settingsTable"); } catch (NoSuchFieldException ignore) {}
            }
            if (stf == null) { System.out.println("[createguild] champ settingsTable introuvable"); return; }
            stf.setAccessible(true);
            Object settingsTable = stf.get(screen);
            settingsTable.getClass().getMethod("setGuildName", String.class).invoke(settingsTable, name);
            // tryCreateGuild() — chemin d'envoi réel (validation client + envoi CreateGuild au serveur).
            java.lang.reflect.Method m = null;
            for (Class<?> c = screen.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try { m = c.getDeclaredMethod("tryCreateGuild"); } catch (NoSuchMethodException ignore) {}
            }
            if (m == null) { System.out.println("[createguild] tryCreateGuild introuvable"); return; }
            m.setAccessible(true);
            m.invoke(screen);
            System.out.println("[createguild] nom='" + name + "' → tryCreateGuild() [chemin d'envoi réel du jeu]");
        } catch (Throwable t) { System.out.println("[createguild] échec: " + t); }
    }

    // ===================== SURGE (#72) — pilotage en jeu =====================
    // La carte des districts est un widget dessiné sur mesure (taps pixel peu fiables). On emprunte donc les
    // MÊMES chemins d'envoi que l'UI, par réflexion : SurgeScreen.fightPressed (→ SurgeHeroChooserScreen qui
    // envoie StartSurgeAttack) puis SurgeHeroChooserScreen.quickFightPressed (résout le combat côté client et
    // envoie SurgeAttack), et doRaidSurge/onRaidButtonClick pour un RAID. Aucune logique réinventée : on
    // déclenche les rappels réels du jeu.

    private static java.lang.reflect.Method findMethod(Object o, String name, Class<?>... params) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { java.lang.reflect.Method m = c.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignore) {}
        }
        return null;
    }

    /** DEV : diagnostique pourquoi SURGE est (in)navigable — chaque prédicat du jeu, côté CLIENT. "surgenav". */
    public static void surgeNav(GameMain game) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            System.out.println("[surgenav] teamLevel=" + u.getTeamLevel() + " guildID(client)=" + u.getGuildID());
            System.out.println("[surgenav] isUnlocked(SURGE_OBJECTIVES)=" + com.perblue.heroes.game.data.misc.Unlockables.isUnlocked(
                com.perblue.heroes.game.data.misc.Unlockable.SURGE_OBJECTIVES, u));
            try {
                java.lang.reflect.Method msb = null;
                for (java.lang.reflect.Method mm : com.perblue.heroes.ui.UINavHelper.class.getDeclaredMethods())
                    if (mm.getName().equals("mainScreenTutorialBlocked")) { msb = mm; break; }
                if (msb != null) { msb.setAccessible(true);
                    System.out.println("[surgenav] mainScreenTutorialBlocked(SURGE)=" + msb.invoke(null,
                        com.perblue.heroes.ui.UINavHelper.Destination.SURGE)); }
            } catch (Throwable t) { System.out.println("[surgenav] mainScreenTutorialBlocked introuvable: " + t); }
            System.out.println("[surgenav] canNavigateTo(SURGE,true)=" + com.perblue.heroes.ui.UINavHelper.canNavigateTo(
                com.perblue.heroes.ui.UINavHelper.Destination.SURGE, true, new String[0]));
            System.out.println("[surgenav] getSurgeData()=" + (game.getSurgeData() == null ? "null" : "present"));
        } catch (Throwable t) { System.out.println("[surgenav] échec: " + t); }
    }

    /** DEV : RÉCLAME les récompenses du surge précédent — envoie le VRAI {@code SurgeClaimRewards{surgeID}} (même
     *  message que le bouton CLAIM de SurgeResultsWindow) via le network provider du jeu, pour exercer le handler
     *  serveur. surgeID = clé de {@code unclaimedRewards} (= previousResults.surgeID). Invoqué via "surgeclaim". */
    public static void surgeClaim(GameMain game) {
        try {
            com.perblue.heroes.network.messages.SurgeData d = game.getSurgeData();
            if (d == null) { System.out.println("[surgeclaim] getSurgeData()=null"); return; }
            long sid = 0L;
            if (d.unclaimedRewards != null && !d.unclaimedRewards.isEmpty())
                sid = ((Number) d.unclaimedRewards.keySet().iterator().next()).longValue();
            else if (d.previousResults != null) sid = d.previousResults.surgeID;
            if (sid == 0L) { System.out.println("[surgeclaim] aucune récompense non réclamée (unclaimedRewards vide)"); return; }
            com.perblue.heroes.network.messages.SurgeClaimRewards scr =
                new com.perblue.heroes.network.messages.SurgeClaimRewards();
            scr.surgeID = sid;
            com.perblue.heroes.DH.app.getNetworkProvider().sendMessage(scr);
            System.out.println("[surgeclaim] SurgeClaimRewards{surgeID=" + sid + "} envoyé [chemin réel du jeu]");
        } catch (Throwable t) { System.out.println("[surgeclaim] échec: " + t); }
    }

    /** DEV : imprime l'état SURGE côté client (districts jouables, verrous, raid). Invoqué via "surgestate". */
    public static void surgeState(GameMain game) {
        try {
            com.perblue.heroes.network.messages.SurgeData d = game.getSurgeData();
            if (d == null) { System.out.println("[surgestate] getSurgeData()=null"); return; }
            System.out.println("[surgestate] surgeID=" + d.surgeID + " youAreInRaid=" + d.youAreInRaid
                + " yourRaidsUsed=" + d.yourRaidsUsed + " wavesCompleted=" + d.wavesCompleted
                + " raidEnd=" + new java.util.Date(d.raidEndTime));
            try {
                com.perblue.heroes.game.objects.User u = game.getYourUser();
                int hc = 0; for (Object h : u.getHeroes()) hc++;
                System.out.println("[surgestate] clientHeroes=" + hc + " areHeroesAvailable(SURGE)="
                    + com.perblue.heroes.game.logic.SurgeHelper.areHeroesAvailable(u));
                com.perblue.heroes.game.objects.surge.SurgeClientMember me =
                    com.perblue.heroes.ui.surge.SurgeClientHelper.getYourMember(d);
                System.out.println("[surgestate] yourMember=" + (me == null ? "null"
                    : ("raidsUsed=" + me.getRaidsUsed() + " storedGold=" + me.getStoredGold())));
            } catch (Throwable t) { System.out.println("[surgestate] avail check: " + t); }
            int i = 0;
            if (d.opponents != null) for (Object o : d.opponents) {
                com.perblue.heroes.network.messages.SurgeOpponentSummary op =
                    (com.perblue.heroes.network.messages.SurgeOpponentSummary) o;
                if (i++ < 6) System.out.println("[surgestate]   district=" + op.district + " cleared="
                    + op.clearedThisWave + " lockExp=" + op.lockExpiration + " power="
                    + (op.lineup != null ? op.lineup.power : -1));
            }
        } catch (Throwable t) { System.out.println("[surgestate] échec: " + t); }
    }

    /** DEV : ouvre le combat du 1er district JOUABLE (non vaincu) — appelle SurgeScreen.fightPressed, qui pousse
     *  SurgeHeroChooserScreen (lequel envoie StartSurgeAttack au serveur). Invoqué via "surgefight". */
    public static void surgeFight(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("SurgeScreen")) {
                System.out.println("[surgefight] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas SurgeScreen)"); return;
            }
            com.perblue.heroes.network.messages.SurgeData d = game.getSurgeData();
            if (d == null || d.opponents == null) { System.out.println("[surgefight] pas de SurgeData/opponents"); return; }
            com.perblue.heroes.network.messages.SurgeOpponentSummary target = null;
            for (Object o : d.opponents) {
                com.perblue.heroes.network.messages.SurgeOpponentSummary op =
                    (com.perblue.heroes.network.messages.SurgeOpponentSummary) o;
                if (!op.clearedThisWave) { target = op; break; }
            }
            if (target == null) { System.out.println("[surgefight] aucun district jouable (tous vaincus)"); return; }
            java.lang.reflect.Method fp = findMethod(screen, "fightPressed",
                com.perblue.heroes.network.messages.SurgeOpponentSummary.class,
                com.perblue.heroes.network.messages.SurgeData.class, boolean.class);
            if (fp == null) { System.out.println("[surgefight] fightPressed introuvable"); return; }
            fp.invoke(screen, target, d, false);
            System.out.println("[surgefight] fightPressed(district=" + target.district
                + ") → SurgeHeroChooserScreen (envoi StartSurgeAttack) [chemin réel]");
        } catch (Throwable t) { System.out.println("[surgefight] échec: " + t); }
    }

    /** DEV : sur SurgeHeroChooserScreen, AUTO-SÉLECTIONNE l'équipe (lambda du bouton AUTO du jeu →
     *  SurgeHelper.autoSelectHeroes → setSelectedUnits) puis lance le QUICK FIGHT → StartSurgeAttack + SurgeAttack.
     *  Invoqué via "surgeteamfight". */
    public static void surgeTeamFight(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("SurgeHeroChooser")) {
                System.out.println("[surgeteamfight] pas sur SurgeHeroChooserScreen ("
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + ")"); return;
            }
            long id = 0L;
            com.perblue.heroes.network.messages.SurgeData d = game.getSurgeData();
            if (d != null) id = d.surgeID;
            // AUTO : lambda$createRightSideExtraUI$2(long) — bouton AUTO réel (autoSelectHeroes + setSelectedUnits).
            java.lang.reflect.Method auto = findMethod(screen, "lambda$createRightSideExtraUI$2", long.class);
            if (auto != null) { auto.invoke(screen, id); System.out.println("[surgeteamfight] auto-sélection (id=" + id + ")"); }
            else System.out.println("[surgeteamfight] lambda auto-sélection introuvable");
            java.lang.reflect.Method can = findMethod(screen, "canStartQuickFight");
            boolean ready = can != null && Boolean.TRUE.equals(can.invoke(screen));
            System.out.println("[surgeteamfight] canStartQuickFight=" + ready);
            java.lang.reflect.Method qf = findMethod(screen, "quickFightPressed");
            if (qf != null && ready) { qf.invoke(screen); System.out.println("[surgeteamfight] quickFightPressed() → StartSurgeAttack + combat + SurgeAttack [chemin réel]"); }
            else System.out.println("[surgeteamfight] quick fight non lancé (ready=" + ready + ")");
        } catch (Throwable t) { System.out.println("[surgeteamfight] échec: " + t); }
    }

    /** DEV : sur SurgeHeroChooserScreen, résout le combat en QUICK FIGHT (combat côté client) — envoie SurgeAttack
     *  au serveur (issue autoritative recordOutcome). Invoqué via "surgequick". */
    public static void surgeQuick(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("SurgeHeroChooser")) {
                System.out.println("[surgequick] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas SurgeHeroChooserScreen)"); return;
            }
            java.lang.reflect.Method can = findMethod(screen, "canStartQuickFight");
            if (can != null) {
                Object ok = can.invoke(screen);
                if (Boolean.FALSE.equals(ok)) { System.out.println("[surgequick] canStartQuickFight=false (StartSurgeAttackResponse pas encore là) — je réessaierai"); return; }
            }
            java.lang.reflect.Method qf = findMethod(screen, "quickFightPressed");
            if (qf == null) { System.out.println("[surgequick] quickFightPressed introuvable"); return; }
            qf.invoke(screen);
            System.out.println("[surgequick] quickFightPressed() → combat résolu + SurgeAttack [chemin réel]");
        } catch (Throwable t) { System.out.println("[surgequick] échec: " + t); }
    }

    /** DEV : sur SurgeHeroChooserScreen, déclenche un RAID (onRaidButtonClick / doRaidSurge) pour OBSERVER le
     *  protocole de raid côté fil (incrément 5, inconnu). Invoqué via "surgeraid". */
    public static void surgeRaid(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("SurgeHeroChooser")) {
                System.out.println("[surgeraid] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas SurgeHeroChooserScreen)"); return;
            }
            // Le raid exige une équipe sélectionnée (doRaidSurge → getSelectedLineup). On auto-sélectionne d'abord
            // (bouton AUTO du jeu), comme surgeteamfight, puis on lance doRaidSurge → HeroLineupUpdate + Action RAID_SURGE.
            long id = 0L;
            com.perblue.heroes.network.messages.SurgeData d = game.getSurgeData();
            if (d != null) id = d.surgeID;
            java.lang.reflect.Method auto = findMethod(screen, "lambda$createRightSideExtraUI$2", long.class);
            if (auto != null) { auto.invoke(screen, id); System.out.println("[surgeraid] auto-sélection (id=" + id + ")"); }
            java.lang.reflect.Method dr = findMethod(screen, "doRaidSurge", com.perblue.heroes.game.ActionListener.class);
            if (dr != null) { dr.invoke(screen, (Object) null); System.out.println("[surgeraid] doRaidSurge(null) → HeroLineupUpdate + Action RAID_SURGE [chemin réel]"); return; }
            java.lang.reflect.Method rb = findMethod(screen, "onRaidButtonClick");
            if (rb != null) { rb.invoke(screen); System.out.println("[surgeraid] onRaidButtonClick() [chemin réel]"); return; }
            System.out.println("[surgeraid] ni doRaidSurge ni onRaidButtonClick trouvés");
        } catch (Throwable t) { System.out.println("[surgeraid] échec: " + t); }
    }

    /** DEV : ouvre le HERO CHOOSER d'un nœud d'expédition (comme taper le nœud actif sur la carte CITY WATCH) —
     *  pousse {@code ExpeditionHeroChooserScreen(nodeIndex, NONE)} (le VRAI écran du jeu). Le nœud = nombre de nœuds
     *  déjà vaincus (nœud courant). Invoqué via "expfight". */
    public static void expFight(GameMain game) {
        try {
            com.perblue.heroes.game.objects.ExpeditionClientData exp = game.getExpeditionData();
            if (exp == null || exp.getData() == null) { System.out.println("[expfight] pas de getExpeditionData() (fais 'nav EXPEDITION' d'abord)"); return; }
            int node = exp.getNodesDefeated();
            java.util.List defs = exp.getData().defenders;
            java.util.List nrs = exp.getData().nodeRewards;
            System.out.println("[expfight] nœud courant=" + node + " defenders=" + (defs == null ? "null" : defs.size())
                + " nodeRewards=" + (nrs == null ? "null" : nrs.size()));
            com.perblue.heroes.ui.herochooser.ExpeditionHeroChooserScreen s =
                new com.perblue.heroes.ui.herochooser.ExpeditionHeroChooserScreen(
                    node, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
            game.getScreenManager().pushScreen(s);
            System.out.println("[expfight] ExpeditionHeroChooserScreen(node=" + node + ") poussé [chemin réel]");
        } catch (Throwable t) { System.out.println("[expfight] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : sur ExpeditionHeroChooserScreen, lance le QUICK FIGHT → {@code quickFightPressed()} construit
     *  {@code ExpeditionAttackScreen} (→ createStageDefenders, l'ancien point de crash) puis résout le combat côté
     *  client et envoie {@code ExpeditionAttack} au serveur (issue autoritative). Invoqué via "expquick". */
    public static void expQuick(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("ExpeditionHeroChooser")) {
                System.out.println("[expquick] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas ExpeditionHeroChooserScreen)"); return;
            }
            java.lang.reflect.Method gs = findMethod(screen, "getSelectedHeroes");
            int sel = -1;
            if (gs != null) { Object arr = gs.invoke(screen); try { sel = (Integer) arr.getClass().getField("size").get(arr); } catch (Throwable ig) {} }
            java.lang.reflect.Method can = findMethod(screen, "canStartQuickFight");
            boolean ready = can != null && Boolean.TRUE.equals(can.invoke(screen));
            System.out.println("[expquick] heroesSélectionnés=" + sel + " canStartQuickFight=" + ready);
            if (!ready) { System.out.println("[expquick] pas prêt (sélection incomplète / réponse pas encore là) — je réessaierai"); return; }
            java.lang.reflect.Method qf = findMethod(screen, "quickFightPressed");
            if (qf == null) { System.out.println("[expquick] quickFightPressed introuvable"); return; }
            qf.invoke(screen);
            System.out.println("[expquick] quickFightPressed() → ExpeditionAttackScreen + combat + ExpeditionAttack [chemin réel]");
        } catch (Throwable t) { System.out.println("[expquick] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : ENCHANTE l'équipement d'un héros — chemin client réel
     *  {@code ClientActionHelper.enchantItem(hero, slot, {mat:count}, false, NONE, null)} (exécute enchantItem
     *  localement + envoie {@code EnchantItem} ; le serveur ré-exécute l'autorité). Invoqué via
     *  "enchant &lt;HERO&gt; &lt;SLOT&gt; &lt;MATERIAL&gt; &lt;count&gt;". */
    public static void enchant(GameMain game, String heroS, String slotS, String matS, int count, boolean useDiamonds) {
        try {
            com.perblue.heroes.network.messages.UnitType hero = com.perblue.heroes.network.messages.UnitType.valueOf(heroS);
            com.perblue.heroes.network.messages.HeroEquipSlot slot = com.perblue.heroes.network.messages.HeroEquipSlot.valueOf(slotS);
            com.perblue.heroes.network.messages.ItemType mat = com.perblue.heroes.network.messages.ItemType.valueOf(matS);
            java.util.Map<com.perblue.heroes.network.messages.ItemType, Integer> used = new java.util.HashMap<>();
            if (count > 0) used.put(mat, count);
            var it = game.getYourUser().getHero(hero).getItem(slot);
            System.out.println("[enchant] " + hero + "/" + slot + " avant : item=" + (it == null ? "null" : it.getType())
                + " étoiles=" + (it == null ? "?" : it.getStars()) + " useDiamonds=" + useDiamonds);
            com.perblue.heroes.game.ActionListener noop = new com.perblue.heroes.game.ActionListener() {
                public void onResult(boolean ok, Object o) { System.out.println("[enchant] onResult=" + ok); }
            };
            com.perblue.heroes.game.ClientActionHelper.enchantItem(
                hero, slot, used, useDiamonds, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE, noop);
            System.out.println("[enchant] enchantItem(" + hero + "/" + slot + ", " + count + " " + mat
                + ", diamants=" + useDiamonds + ") → EnchantItem envoyé [chemin réel]");
        } catch (Throwable t) { System.out.println("[enchant] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : MAX-UPGRADE PRIME BADGES d'un héros (bouton « MAX » de l'écran d'enchant) — chemin client réel
     *  {@code ClientActionHelper.maxUpgradePrimeBadges(plan, NONE, listener)} : construit le plan localement
     *  ({@code EnchantingHelper.buildMaxUpgradePlanForHero}, comme l'écran), applique + envoie
     *  {@code EnhanceMaxPrimeBadge} ; le serveur ré-dérive le plan et ré-exécute l'autorité. Invoqué via
     *  "maxupgrade &lt;HERO&gt;". */
    public static void maxUpgrade(GameMain game, String heroS) {
        try {
            com.perblue.heroes.network.messages.UnitType hero = com.perblue.heroes.network.messages.UnitType.valueOf(heroS);
            var snap = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
            var plan = com.perblue.heroes.game.logic.EnchantingHelper.buildMaxUpgradePlanForHero(
                game.getYourUser(), hero, snap);
            System.out.println("[maxupgrade] " + hero + " plan : slots=" + plan.executionOrder.size()
                + " or=" + plan.totalGold + " items=" + plan.totalItems + " (vide=" + plan.isEmpty() + ")");
            var h = game.getYourUser().getHero(hero);
            for (com.perblue.heroes.network.messages.HeroEquipSlot s : com.perblue.heroes.network.messages.HeroEquipSlot.values()) {
                var it = h.getItem(s);
                if (it != null) System.out.println("[maxupgrade]   avant " + s + "=" + it.getType() + " étoiles=" + it.getStars());
            }
            com.perblue.heroes.game.ActionListener noop = new com.perblue.heroes.game.ActionListener() {
                public void onResult(boolean ok, Object o) { System.out.println("[maxupgrade] onResult=" + ok); }
            };
            com.perblue.heroes.game.ClientActionHelper.maxUpgradePrimeBadges(plan, snap, noop);
            System.out.println("[maxupgrade] maxUpgradePrimeBadges(" + hero + ") → EnhanceMaxPrimeBadge envoyé [chemin réel]");
        } catch (Throwable t) { System.out.println("[maxupgrade] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : SAUVEGARDE un lineup enregistré nommé — chemin client réel
     *  {@code ClientActionHelper.saveHeroLineup(type, id, lineup, customName, NONE)} (applique localement + envoie
     *  {@code HeroLineupUpdate} ; le serveur ré-applique setHeroLineup + persiste). Invoqué via
     *  "savelineup &lt;SAVED_N&gt; &lt;name&gt; &lt;HERO1,HERO2,...&gt;". Les héros doivent être POSSÉDÉS (saveHeroLineup
     *  lit leurs slots émeraude). */
    public static void saveLineup(GameMain game, String typeS, String name, String heroesCsv) {
        try {
            com.perblue.heroes.network.messages.HeroLineupType type =
                com.perblue.heroes.network.messages.HeroLineupType.valueOf(typeS);
            com.perblue.heroes.network.messages.HeroLineup l = new com.perblue.heroes.network.messages.HeroLineup();
            l.heroes = new java.util.ArrayList<>();
            for (String hs : heroesCsv.split("\\+")) {
                if (hs.trim().isEmpty()) continue;
                l.heroes.add(com.perblue.heroes.network.messages.UnitType.valueOf(hs.trim().toUpperCase()));
            }
            l.mercenaryType = com.perblue.heroes.network.messages.UnitType.DEFAULT;
            System.out.println("[savelineup] " + type + " « " + name + " » héros=" + l.heroes + " [chemin réel]");
            com.perblue.heroes.game.ClientActionHelper.saveHeroLineup(
                type, 0L, l, name, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
            var back = game.getYourUser().getHeroLineup(type, 0L);
            System.out.println("[savelineup] " + type + " envoyé → local getHeroLineup="
                + (back == null ? "null" : back.heroes) + " nom=" + game.getYourUser().getHeroLineupName(type));
        } catch (Throwable t) { System.out.println("[savelineup] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : RÉCLAME les récompenses d'un palier de collection — chemin client réel
     *  {@code ClientActionHelper.claimCollectionRewards(type, tier, level)} (envoie {@code Action
     *  CLAIM_COLLECTION_REWARDS} ; le serveur ré-exécute l'autorité). Invoqué via
     *  "claimcollection &lt;TYPE&gt; &lt;TIER&gt; &lt;LEVEL&gt;". */
    public static void claimCollection(GameMain game, String typeS, String tierS, int level) {
        try {
            com.perblue.heroes.network.messages.CollectionType type =
                com.perblue.heroes.network.messages.CollectionType.valueOf(typeS);
            com.perblue.heroes.network.messages.CollectionTier tier =
                com.perblue.heroes.network.messages.CollectionTier.valueOf(tierS);
            var st = com.perblue.heroes.game.logic.CollectionHelper.getCollectionState(game.getYourUser(), type, tier, level);
            int highest = game.getYourUser().getIndividual().getHighestClaimedCollectionLevel(type, tier);
            System.out.println("[claimcollection] " + type + "/" + tier + " niv." + level + " state=" + st
                + " highestClaimed=" + highest + " [chemin réel]");
            com.perblue.heroes.game.ClientActionHelper.claimCollectionRewards(type, tier, level);
            System.out.println("[claimcollection] CLAIM_COLLECTION_REWARDS envoyé");
        } catch (Throwable t) { System.out.println("[claimcollection] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : joue un COMBAT DE CAMPAGNE en QUICK FIGHT — pousse le VRAI écran
     *  {@code CampaignHeroChooserScreen(NORMAL, chapter, level, …)} puis {@code quickFightPressed()} (chemin réel :
     *  le client joue le combat headless + envoie {@code CampaignAttack}). Sert à vérifier l'accumulation de maîtrise
     *  de collection en jeu. Invoqué via "campfight &lt;chapter&gt; &lt;level&gt;". */
    public static void campFight(GameMain game, int chapter, int level) {
        try {
            com.perblue.heroes.ui.herochooser.CampaignHeroChooserScreen s =
                new com.perblue.heroes.ui.herochooser.CampaignHeroChooserScreen(
                    com.perblue.heroes.network.messages.CampaignType.NORMAL, chapter, level, null,
                    com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
            game.getScreenManager().pushScreen(s);
            System.out.println("[campfight] CampaignHeroChooserScreen(NORMAL," + chapter + "," + level
                + ") poussé (laisser rendre puis 'campquick') ; canQuickFight=" + s.canStartQuickFight());
        } catch (Throwable t) { System.out.println("[campfight] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : sur un CampaignHeroChooserScreen déjà ouvert (rendu → lineup auto-sélectionné), lance le QUICK FIGHT
     *  → {@code quickFightPressed()} joue le combat + envoie {@code CampaignAttack}. Invoqué via "campquick". */
    public static void campQuick(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null || !screen.getClass().getSimpleName().contains("CampaignHeroChooser")) {
                System.out.println("[campquick] écran courant = "
                    + (screen == null ? "null" : screen.getClass().getSimpleName()) + " (pas CampaignHeroChooserScreen)"); return;
            }
            com.perblue.heroes.ui.herochooser.CampaignHeroChooserScreen s =
                (com.perblue.heroes.ui.herochooser.CampaignHeroChooserScreen) screen;
            // Sélectionne des héros POSSÉDÉS via le VRAI chemin unitSelected (comme un tap). On tente d'abord le lineup
            // NORMAL_CAMPAIGN, sinon N'IMPORTE QUELS héros possédés sélectionnables (jusqu'à 5). On logge canSelectUnit
            // pour diagnostiquer.
            var provider = com.perblue.heroes.game.logic.CollectionHelper.fromUser(game.getYourUser());
            java.util.List<com.perblue.heroes.network.messages.UnitType> want = new java.util.ArrayList<>();
            var lineup = game.getYourUser().getHeroLineup(
                com.perblue.heroes.network.messages.HeroLineupType.NORMAL_CAMPAIGN, 0L);
            if (lineup != null && lineup.heroes != null)
                for (Object ht : lineup.heroes) want.add((com.perblue.heroes.network.messages.UnitType) ht);
            // fallback : tous les héros possédés
            for (Object ho : game.getYourUser().getHeroes()) {
                com.perblue.heroes.game.objects.IHero h = (com.perblue.heroes.game.objects.IHero) ho;
                if (!want.contains(h.getType())) want.add(h.getType());
            }
            System.out.println("[campquick] lineup NORMAL_CAMPAIGN=" + (lineup == null ? "null" : lineup.heroes)
                + " ; héros possédés candidats=" + want.size());
            for (com.perblue.heroes.network.messages.UnitType t : want) {
                if (s.getSelectedHeroes().size >= 5) break;
                var hero = game.getYourUser().getHero(t);
                if (!(hero instanceof com.perblue.heroes.game.objects.UnitData)) continue;
                com.perblue.heroes.game.objects.UnitData ud = (com.perblue.heroes.game.objects.UnitData) hero;
                boolean can = s.canSelectUnit(ud);
                if (can) s.unitSelected(ud, provider, 0f, 0f);
                System.out.println("[campquick]   " + t + " canSelect=" + can + " → sel=" + s.getSelectedHeroes().size);
            }
            System.out.println("[campquick] héros sélectionnés=" + s.getSelectedHeroes().size
                + " canQuickFight=" + s.canStartQuickFight()
                + " (1-1 stars vu client=" + game.getYourUser().getCampaignLevel(
                    com.perblue.heroes.network.messages.CampaignType.NORMAL, 1, 1).getStars() + ")");
            // doQuickCombat() = l'exécuteur RÉEL (charge + roule loot + combat headless + envoie CampaignAttack) ;
            // il NE gate PAS sur le bouton canStartQuickFight (contrairement à quickFightPressed) → fonctionne même
            // si le client ne voit pas encore le 3★.
            s.doQuickCombat();
            System.out.println("[campquick] quickFightPressed() → combat + CampaignAttack [chemin réel]");
        } catch (Throwable t) { System.out.println("[campquick] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : ACHÈTE un avatar de collection (mastery shop) — chemin client réel
     *  {@code ClientActionHelper.buyCollectionAvatar(itemType)} (Action BUY_COLLECTION_AVATAR ; le serveur ré-exécute
     *  buyCollectionAvatar : gate + débit MASTERY_TOKENS + don). Invoqué via "buyavatar &lt;ITEM&gt;". */
    public static void buyAvatar(GameMain game, String itemS) {
        try {
            com.perblue.heroes.network.messages.ItemType it = com.perblue.heroes.network.messages.ItemType.valueOf(itemS);
            long tok = game.getYourUser().getResource(com.perblue.heroes.network.messages.ResourceType.MASTERY_TOKENS);
            System.out.println("[buyavatar] " + it + " avant : MASTERY_TOKENS=" + tok
                + " possédé=" + game.getYourUser().getItemAmount(it) + " [chemin réel]");
            com.perblue.heroes.game.ClientActionHelper.buyCollectionAvatar(it);
            System.out.println("[buyavatar] BUY_COLLECTION_AVATAR(" + it + ") envoyé");
        } catch (Throwable t) { System.out.println("[buyavatar] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : fixe le HÉROS CIBLE du puits aux souhaits — chemin client réel
     *  {@code ClientActionHelper.setWishingWellTargetHero(UnitType, listener)} (Action SET_WISHING_WELL_TARGET_HERO ;
     *  le serveur ré-exécute WishingWellHelper.setTargetHero : gate éligibilité + pose cible + poids). Invoqué via
     *  "wishtarget &lt;HERO&gt;". */
    public static void wishTarget(GameMain game, String heroS) {
        try {
            com.perblue.heroes.network.messages.UnitType h = com.perblue.heroes.network.messages.UnitType.valueOf(heroS);
            com.perblue.heroes.network.messages.UnitType before = game.getYourUser().getIndividual().getWishingWellHero();
            System.out.println("[wishtarget] " + h + " avant : cible=" + before + " [chemin réel]");
            com.perblue.heroes.game.ClientActionHelper.setWishingWellTargetHero(h, null);
            System.out.println("[wishtarget] SET_WISHING_WELL_TARGET_HERO(" + h + ") envoyé");
        } catch (Throwable t) { System.out.println("[wishtarget] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : FAIT UN SOUHAIT — ouvre un coffre {@code ChestType.WISH} par le chemin client réel
     *  {@code ChestHelper.openChestInner} (construit le BuyChests + envoie la requête de roll au serveur, sans la
     *  boîte de confirmation). Le serveur roule la table du puits biaisée par la cible + crédite les shards.
     *  Invoqué via "wish [count]". */
    public static void wishOpen(GameMain game, int count) {
        try {
            com.perblue.heroes.network.messages.ChestType t = com.perblue.heroes.network.messages.ChestType.WISH;
            com.perblue.heroes.game.specialevent.SpecialEventSnapshot NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
            int cost = com.perblue.heroes.game.logic.ChestHelper.getPurchaseCost(game.getYourUser(), t, count, NONE);
            com.perblue.heroes.network.messages.UnitType target = game.getYourUser().getIndividual().getWishingWellHero();
            System.out.println("[wish] souhait x" + count + " cost=" + cost + " cible=" + target + " [chemin réel]");
            com.perblue.heroes.game.logic.ChestHelper.openChestInner(
                t, count, cost, com.perblue.heroes.network.messages.ItemType.DEFAULT, false, NONE);
            System.out.println("[wish] BuyChests(WISH x" + count + ") envoyé");
        } catch (Throwable e) { System.out.println("[wish] échec: " + e); e.printStackTrace(); }
    }

    /** DEV : OUVRE le VRAI écran du puits aux souhaits {@code WishingWellChestScreen} (chemin réel, pushScreen) —
     *  pour vérification VISUELLE (cible + coffre WISH). Invoqué via "wishscreen". */
    public static void wishScreen(GameMain game) {
        try {
            game.getScreenManager().pushScreen(new com.perblue.heroes.ui.wishingwell.WishingWellChestScreen());
            System.out.println("[wishscreen] WishingWellChestScreen poussé [chemin réel]");
        } catch (Throwable t) { System.out.println("[wishscreen] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : OUVRE le VRAI écran d'un MARCHAND {@code MerchantScreen(type)} (chemin réel, pushScreen) — pour
     *  vérification VISUELLE du stock généré serveur (objets + prix). Invoqué via "merchantscreen &lt;TYPE&gt;". */
    public static void merchantScreen(GameMain game, String typeS) {
        try {
            com.perblue.heroes.network.messages.MerchantType t = com.perblue.heroes.network.messages.MerchantType.valueOf(typeS);
            int n = 0; for (Object o : game.getYourUser().getMerchantItems(t)) n++;
            System.out.println("[merchantscreen] " + t + " : " + n + " objets côté client [chemin réel]");
            game.getScreenManager().pushScreen(new com.perblue.heroes.ui.screens.MerchantScreen(t));
            System.out.println("[merchantscreen] MerchantScreen(" + t + ") poussé");
        } catch (Throwable e) { System.out.println("[merchantscreen] échec: " + e); e.printStackTrace(); }
    }

    /** DEV : ACHÈTE l'objet le moins cher ABORDABLE d'un marchand — chemin client réel
     *  {@code ClientActionHelper.purchaseMerchantItem} (message PurchaseMerchantItem ; le serveur ré-exécute
     *  purchaseItem : anti-triche + débit + don + purchased). Invoqué via "merchantbuy &lt;TYPE&gt;". */
    public static void merchantBuy(GameMain game, String typeS) {
        try {
            com.perblue.heroes.network.messages.MerchantType t = com.perblue.heroes.network.messages.MerchantType.valueOf(typeS);
            com.perblue.heroes.game.specialevent.SpecialEventSnapshot NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;
            com.perblue.heroes.game.objects.IMerchantItem best = null; int bestIdx = 0;
            long bestCost = Long.MAX_VALUE;
            int seen = 0;
            java.util.List<com.perblue.heroes.game.objects.IMerchantItem> items = new java.util.ArrayList<>();
            for (Object o : game.getYourUser().getMerchantItems(t)) items.add((com.perblue.heroes.game.objects.IMerchantItem) o);
            for (int i = 0; i < items.size(); i++) {
                com.perblue.heroes.game.objects.IMerchantItem mi = items.get(i);
                if (mi.isPurchased()) continue;
                long cost = com.perblue.heroes.game.logic.MerchantHelper.getItemCost(game.getYourUser(), t, mi, NONE);
                if (cost <= 0) continue;
                long have = game.getYourUser().getResource(mi.getCurrency());
                if (cost > have) continue;                 // abordable
                if (cost >= bestCost) continue;
                // typeIndex = rang parmi identiques non achetés AVANT i
                int rank = 0;
                for (int j = 0; j < i; j++) {
                    com.perblue.heroes.game.objects.IMerchantItem p = items.get(j);
                    if (!p.isPurchased() && com.perblue.heroes.game.logic.RewardHelper.compareDrops(p.getItem(), mi.getItem(), false)) rank++;
                }
                best = mi; bestIdx = rank; bestCost = cost;
            }
            if (best == null) { System.out.println("[merchantbuy] aucun objet abordable dans " + t); return; }
            System.out.println("[merchantbuy] " + t + " achat " + best.getItem().itemType + " coût " + bestCost + " "
                + best.getCurrency() + " (idx " + bestIdx + ") [chemin réel]");
            com.perblue.heroes.game.ClientActionHelper.purchaseMerchantItem(t, best.getItem(), bestIdx, bestCost,
                (int) best.getItem().quantity, NONE);
            System.out.println("[merchantbuy] PurchaseMerchantItem envoyé");
        } catch (Throwable e) { System.out.println("[merchantbuy] échec: " + e); e.printStackTrace(); }
    }

    /** DEV : ouvre l'écran du mastery shop {@code CollectionsShopScreen} (chemin réel, pushScreen). Invoqué via "shopscreen". */
    public static void shopScreen(GameMain game) {
        try {
            game.getScreenManager().pushScreen(new com.perblue.heroes.ui.collections.CollectionsShopScreen());
            System.out.println("[shopscreen] CollectionsShopScreen poussé [chemin réel]");
        } catch (Throwable t) { System.out.println("[shopscreen] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : OUVRE le VRAI écran de détail de collection {@code CollectionsDetailScreen(type)} (chemin réel,
     *  pushScreen) — pour vérification VISUELLE (paliers + boutons de claim). Invoqué via "collectionscreen &lt;TYPE&gt;". */
    public static void collectionScreen(GameMain game, String typeS) {
        try {
            com.perblue.heroes.network.messages.CollectionType type =
                com.perblue.heroes.network.messages.CollectionType.valueOf(typeS);
            game.getScreenManager().pushScreen(new com.perblue.heroes.ui.collections.CollectionsDetailScreen(type));
            System.out.println("[collectionscreen] CollectionsDetailScreen(" + type + ") poussé [chemin réel]");
        } catch (Throwable t) { System.out.println("[collectionscreen] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : OUVRE le VRAI écran de lineup enregistré {@code SavedLineupHeroChooserScreen(type)} (chemin réel,
     *  pushScreen) — pour vérification VISUELLE (le lineup sauvé du serveur est chargé/affiché). Invoqué via
     *  "lineupscreen &lt;SAVED_N&gt;". */
    public static void lineupScreen(GameMain game, String typeS) {
        try {
            com.perblue.heroes.network.messages.HeroLineupType type =
                com.perblue.heroes.network.messages.HeroLineupType.valueOf(typeS);
            com.perblue.heroes.ui.herochooser.SavedLineupHeroChooserScreen s =
                new com.perblue.heroes.ui.herochooser.SavedLineupHeroChooserScreen(type);
            game.getScreenManager().pushScreen(s);
            System.out.println("[lineupscreen] SavedLineupHeroChooserScreen(" + type + ") poussé [chemin réel]");
        } catch (Throwable t) { System.out.println("[lineupscreen] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : demande la VALIDATION d'un nom de lineup — envoie {@code CheckLineupName{name}} (comme la fenêtre de
     *  nommage) ; le serveur répond {@code CheckLineupNameResult{isValid}}. Invoqué via "checkname &lt;name&gt;". */
    public static void checkName(GameMain game, String name) {
        try {
            com.perblue.heroes.network.messages.CheckLineupName m =
                new com.perblue.heroes.network.messages.CheckLineupName();
            m.name = name;
            game.getNetworkProvider().sendMessage(m);
            System.out.println("[checkname] CheckLineupName(\"" + name + "\") envoyé [chemin réel]");
        } catch (Throwable t) { System.out.println("[checkname] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : OUVRE le coffre d'expédition disponible — chemin client réel
     *  {@code ClientActionHelper.openExpeditionChest(NONE, null)} (exécute openChest localement + envoie
     *  {@code OpenExpeditionChest} ; le serveur ré-exécute l'autorité). Nécessite un coffre disponible (tous les 3
     *  nœuds). Invoqué via "expchest". */
    public static void expChest(GameMain game) {
        try {
            com.perblue.heroes.game.objects.ExpeditionClientData exp = game.getExpeditionData();
            if (exp != null && exp.getData() != null)
                System.out.println("[expchest] nodesDefeated=" + exp.getData().nodesDefeated
                    + " chestsOpened=" + exp.getData().chestsOpened);
            com.perblue.heroes.game.ClientActionHelper.openExpeditionChest(
                com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE, null);
            System.out.println("[expchest] openExpeditionChest → OpenExpeditionChest envoyé [chemin réel]");
        } catch (Throwable t) { System.out.println("[expchest] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : RELANCE l'expédition (reset) à la difficulté donnée — chemin client réel
     *  {@code ClientExpeditionHelper.resetExpedition(difficulty, NONE)} (construit + envoie {@code ResetExpedition} ;
     *  le serveur consomme un reset gratuit via chargeForReset et régénère le run). Invoqué via "expreset [diff]". */
    public static void expReset(GameMain game, int difficulty) {
        try {
            System.out.println("[expreset] resetExpedition(diff=" + difficulty + ") …");
            com.perblue.heroes.ui.expedition.ClientExpeditionHelper.resetExpedition(
                difficulty, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
            System.out.println("[expreset] ResetExpedition envoyé [chemin réel]");
        } catch (Throwable t) { System.out.println("[expreset] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : déclenche un RAID d'expédition à la difficulté courante — chemin client réel
     *  {@code ExpeditionHelper.doRaidFromClient(user, difficulty, NONE)} (exécute doRaid localement + envoie
     *  {@code ExpeditionRaid} au serveur qui ré-exécute l'autorité). Nécessite une difficulté RAIDABLE + tickets.
     *  Invoqué via "expraid". */
    public static void expRaid(GameMain game) {
        try {
            com.perblue.heroes.game.objects.ExpeditionClientData exp = game.getExpeditionData();
            if (exp == null || exp.getData() == null) { System.out.println("[expraid] pas de getExpeditionData() (fais 'nav EXPEDITION' d'abord)"); return; }
            int diff = exp.getDifficulty();
            boolean raidable = com.perblue.heroes.game.logic.ExpeditionHelper.isDifficultyRaidable(game.getYourUser(), diff);
            System.out.println("[expraid] difficulté=" + diff + " raidable=" + raidable);
            if (!raidable) { System.out.println("[expraid] difficulté non raidable (clear complet requis) — abandon"); return; }
            com.perblue.heroes.game.logic.ExpeditionHelper.doRaidFromClient(
                game.getYourUser(), diff, com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE);
            System.out.println("[expraid] doRaidFromClient(diff=" + diff + ") → ExpeditionRaid envoyé [chemin réel]");
        } catch (Throwable t) { System.out.println("[expraid] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : envoie un message dans le CHAT de guilde (salon GUILD) — même chemin d'envoi que
     *  {@code ChatWindow.sendChatMessage} (construit un SendChat et l'envoie au serveur), en contournant le
     *  clavier virtuel. Le serveur renvoie le Chat autoritatif que la ChatWindow affiche. Invoqué via
     *  clickfile "guildchat &lt;message&gt;". */
    public static void sendGuildChat(GameMain game, String message) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null || !com.perblue.heroes.game.logic.GuildHelper.isInGuild(u)) {
                System.out.println("[guildchat] joueur sans guilde — ignoré"); return;
            }
            com.perblue.heroes.network.messages.SendChat sc =
                new com.perblue.heroes.network.messages.SendChat();
            sc.message = message;
            sc.room = com.perblue.heroes.network.messages.ChatRoomType.GUILD;
            sc.time = new java.util.Date(com.perblue.heroes.util.TimeUtil.serverTimeNow());
            sc.toUserID = 0L;
            game.getNetworkProvider().sendMessage(sc);
            System.out.println("[guildchat] envoyé « " + message + " » (salon GUILD) [chemin SendChat réel]");
        } catch (Throwable t) { System.out.println("[guildchat] échec: " + t); }
    }

    /** DEV : poste une demande d'aide STAMINA (GUILD AID) via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.requestStamina()} → Action REQUEST_GUILD_DONATION), sans navigation gear.
     *  Invoqué via clickfile "requeststamina". */
    public static void requestStaminaAid(GameMain game) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null || !com.perblue.heroes.game.logic.GuildHelper.isInGuild(u)) {
                System.out.println("[requeststamina] joueur sans guilde — ignoré"); return;
            }
            com.perblue.heroes.game.ClientActionHelper.requestStamina();
            System.out.println("[requeststamina] Action REQUEST_GUILD_DONATION(STAMINA) envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[requeststamina] échec: " + t); }
    }

    /** DEV (#72 incr. 3c) : DÉMARRE une MISSION IDLE d'amitié via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.addMission(type, pair)} → Action ADD_MISSION), exactement ce que le bouton START
     *  de {@code MissionsChooseWindow} appelle. Contourne le hit-test capricieux des fenêtres de sélection
     *  (MissionsSelectFriendsWindow/ChooseWindow, g83). Invoqué via clickfile
     *  "missionadd &lt;POWER_UP_MISSION|MEMORY_MISSION|DISK_POWER_MISSION&gt; &lt;PRIMARY&gt; &lt;SECONDARY&gt;". */
    public static void addMission(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            if (p.length < 3) { System.out.println("[missionadd] usage: missionadd <TYPE> <PRIMARY> <SECONDARY>"); return; }
            com.perblue.heroes.network.messages.MissionType type =
                com.perblue.heroes.network.messages.MissionType.valueOf(p[0].trim().toUpperCase());
            com.perblue.heroes.network.messages.UnitType a =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[1].trim().toUpperCase());
            com.perblue.heroes.network.messages.UnitType b =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[2].trim().toUpperCase());
            com.perblue.heroes.game.objects.FriendPairID pair =
                com.perblue.heroes.game.objects.FriendPairID.of(a, b);
            com.perblue.heroes.game.ClientActionHelper.addMission(type, pair);
            System.out.println("[missionadd] Action ADD_MISSION(" + type + ", " + pair + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[missionadd] échec: " + t); }
    }

    /** DEV (#72 incr. 3c) : RÉCLAME les récompenses de missions terminées via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.claimMissionRewards} → Action CLAIM_MISSION_REWARDS), comme le bouton
     *  « CLAIM ALL ». Invoqué via clickfile "missionclaim". */
    public static void claimMissions(GameMain game) {
        try {
            com.perblue.heroes.game.ClientActionHelper.claimMissionRewards(null);
            System.out.println("[missionclaim] Action CLAIM_MISSION_REWARDS envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[missionclaim] échec: " + t); }
    }

    /** DEV (#72 incr. 3c) : ANNULE la 1ʳᵉ mission en cours (ou celle portant le héros donné) via le CHEMIN RÉEL
     *  ({@code ClientActionHelper.cancelMission(mission)} → Action CANCEL_MISSION). Invoqué via clickfile
     *  "missioncancel [PRIMARY]". */
    public static void cancelMission(GameMain game, String arg) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null) { System.out.println("[missioncancel] pas d'utilisateur"); return; }
            com.perblue.heroes.network.messages.UnitType want = null;
            if (arg != null && !arg.trim().isEmpty())
                want = com.perblue.heroes.network.messages.UnitType.valueOf(arg.trim().toUpperCase());
            com.perblue.heroes.game.missions.IMission target = null;
            for (Object o : u.getIndividual().getMissions()) {
                com.perblue.heroes.game.missions.IMission mm = (com.perblue.heroes.game.missions.IMission) o;
                if (want == null || mm.getFriendship().getPrimary() == want || mm.getFriendship().getSecondary() == want) {
                    target = mm; break;
                }
            }
            if (target == null) { System.out.println("[missioncancel] aucune mission à annuler"); return; }
            com.perblue.heroes.game.ClientActionHelper.cancelMission(target);
            System.out.println("[missioncancel] Action CANCEL_MISSION(" + target.getFriendship() + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[missioncancel] échec: " + t); }
    }

    /** DEV (#72 incr. 3c) : ACCÉLÈRE la mission d'une paire via le CHEMIN RÉEL du jeu — calcule le
     *  {@code MissionSpeedupData} (via {@code MissionHelper.getSpeedupData}) puis
     *  {@code ClientActionHelper.speedupMission(mission, data, null)} (→ Action SPEEDUP_MISSION), comme le bouton
     *  {@code >>} de la carte. Invoqué via clickfile "speedup &lt;PRIMARY&gt; &lt;SECONDARY&gt; &lt;count&gt;". */
    public static void speedupMission(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            if (p.length < 3) { System.out.println("[speedup] usage: speedup <PRIMARY> <SECONDARY> <count>"); return; }
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            com.perblue.heroes.network.messages.UnitType a =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[0].trim().toUpperCase());
            com.perblue.heroes.network.messages.UnitType b =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[1].trim().toUpperCase());
            int count = Integer.parseInt(p[2].trim());
            com.perblue.heroes.game.objects.FriendPairID pair =
                com.perblue.heroes.game.objects.FriendPairID.of(a, b);
            com.perblue.heroes.game.missions.IMission mission = null;
            for (Object o : u.getIndividual().getMissions()) {
                com.perblue.heroes.game.missions.IMission mm = (com.perblue.heroes.game.missions.IMission) o;
                if (mm.getFriendship().equals(pair)) { mission = mm; break; }
            }
            if (mission == null) { System.out.println("[speedup] aucune mission pour " + pair); return; }
            long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
            com.perblue.heroes.game.missions.MissionHelper.MissionSpeedupData data =
                com.perblue.heroes.game.missions.MissionHelper.getSpeedupData(
                    u, mission, com.perblue.heroes.network.messages.ItemType.MISSION_SPEEDUP, count, now);
            com.perblue.heroes.game.ClientActionHelper.speedupMission(mission, data, null);
            System.out.println("[speedup] Action SPEEDUP_MISSION(" + pair + " x" + count + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[speedup] échec: " + t); }
    }

    /** DEV (#72 incr. 3c) : plafond de dépense d'un objet en missions via le CHEMIN RÉEL
     *  ({@code ClientActionHelper.setMissionItemCostLimit(item, N)} → Action SET_MISSION_ITEM_COST_LIMIT). Invoqué
     *  via clickfile "costlimit &lt;ITEMTYPE&gt; &lt;N&gt;". */
    public static void setMissionCostLimit(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            if (p.length < 2) { System.out.println("[costlimit] usage: costlimit <ITEMTYPE> <N>"); return; }
            com.perblue.heroes.network.messages.ItemType item =
                com.perblue.heroes.network.messages.ItemType.valueOf(p[0].trim().toUpperCase());
            int n = Integer.parseInt(p[1].trim());
            com.perblue.heroes.game.ClientActionHelper.setMissionItemCostLimit(item, n);
            System.out.println("[costlimit] Action SET_MISSION_ITEM_COST_LIMIT(" + item + "=" + n + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[costlimit] échec: " + t); }
    }

    /** DEV (#72 incr. 3c) : DUMP l'état des missions côté CLIENT (ce que l'écran MISSIONS voit) — missions en cours
     *  + MissionClaimData en attente. Invoqué via clickfile "missiondump". */
    public static void missionDump(GameMain game) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null) { System.out.println("[missiondump] pas d'utilisateur"); return; }
            int n = 0;
            for (Object o : u.getIndividual().getMissions()) {
                com.perblue.heroes.game.missions.IMission mm = (com.perblue.heroes.game.missions.IMission) o;
                System.out.println("[missiondump]   mission #" + (++n) + " type=" + mm.getType()
                    + " pair=" + mm.getFriendship() + " baseTimeRemaining=" + mm.getBaseTimeRemaining()
                    + " speed=" + mm.getSpeed() + " startTime=" + mm.getStartTime());
            }
            int c = 0;
            for (Object o : u.getIndividual().getMissionClaimData()) c++;
            System.out.println("[missiondump] missions=" + n + " claimEnAttente=" + c);
        } catch (Throwable t) { System.out.println("[missiondump] échec: " + t); }
    }

    /** DEV (#72 FRIENDSHIPS) : OUVRE la vue de détail d'une AMITIÉ (disk/campagne/mur) via le CHEMIN RÉEL du jeu
     *  ({@code HeroDetailFriendsContent.navigateToFriendUI(pair, mode)}) — le point d'entrée que le hub emprunte
     *  (HÉROS → hero detail → onglet Friends → une amitié). Rend {@code FriendshipCampaignWindow}/disk. Invoqué via
     *  clickfile "friendui &lt;PRIMARY&gt; &lt;SECONDARY&gt; [CAMPAIGN|GEAR|MISSIONS|WALL]". */
    public static void friendUI(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            if (p.length < 2) { System.out.println("[friendui] usage: friendui <PRIMARY> <SECONDARY> [MODE]"); return; }
            com.perblue.heroes.network.messages.UnitType a =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[0].trim().toUpperCase());
            com.perblue.heroes.network.messages.UnitType b =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[1].trim().toUpperCase());
            com.perblue.heroes.ui.herodetails.FriendModeType mode = p.length >= 3
                ? com.perblue.heroes.ui.herodetails.FriendModeType.valueOf(p[2].trim().toUpperCase())
                : com.perblue.heroes.ui.herodetails.FriendModeType.CAMPAIGN;
            com.perblue.heroes.game.objects.FriendPairID pair =
                com.perblue.heroes.game.objects.FriendPairID.of(a, b);
            com.perblue.heroes.ui.herodetails.HeroDetailFriendsContent.navigateToFriendUI(pair, mode);
            System.out.println("[friendui] navigateToFriendUI(" + pair + ", " + mode + ") [chemin réel]");
        } catch (Throwable t) { System.out.println("[friendui] échec: " + t); }
    }

    /** DEV (#72 incr. 3a) : EMPOWER une amitié (consomme des FRIENDSHIP_EMPOWER_STONE) via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.empowerFriendship(pair, count)} → Action EMPOWER_FRIENDSHIP), comme le bouton de la
     *  vue disk. Invoqué via clickfile "empower &lt;PRIMARY&gt; &lt;SECONDARY&gt; &lt;count&gt;". */
    public static void empowerFriendship(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            if (p.length < 3) { System.out.println("[empower] usage: empower <PRIMARY> <SECONDARY> <count>"); return; }
            com.perblue.heroes.network.messages.UnitType a =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[0].trim().toUpperCase());
            com.perblue.heroes.network.messages.UnitType b =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[1].trim().toUpperCase());
            int count = Integer.parseInt(p[2].trim());
            com.perblue.heroes.game.objects.FriendPairID pair =
                com.perblue.heroes.game.objects.FriendPairID.of(a, b);
            com.perblue.heroes.game.ClientActionHelper.empowerFriendship(pair, count);
            System.out.println("[empower] Action EMPOWER_FRIENDSHIP(" + pair + " x" + count + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[empower] échec: " + t); }
    }

    /** DEV (#72 incr. 2) : (dé)FAVORISE une amitié via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.setFavoriteFriendship(pair, bool)} → Action SET_FAVORITE_FRIENDSHIP). Invoqué via
     *  clickfile "setfavorite &lt;PRIMARY&gt; &lt;SECONDARY&gt; &lt;0|1&gt;". */
    public static void setFavoriteFriendship(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            if (p.length < 3) { System.out.println("[setfavorite] usage: setfavorite <PRIMARY> <SECONDARY> <0|1>"); return; }
            com.perblue.heroes.network.messages.UnitType a =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[0].trim().toUpperCase());
            com.perblue.heroes.network.messages.UnitType b =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[1].trim().toUpperCase());
            boolean fav = !p[2].trim().equals("0");
            com.perblue.heroes.game.objects.FriendPairID pair =
                com.perblue.heroes.game.objects.FriendPairID.of(a, b);
            com.perblue.heroes.game.ClientActionHelper.setFavoriteFriendship(pair, fav);
            System.out.println("[setfavorite] Action SET_FAVORITE_FRIENDSHIP(" + pair + "=" + fav + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[setfavorite] échec: " + t); }
    }

    /** DEV (#72 incr. 2) : ACHÈTE de l'énergie d'amitié via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.buyFriendStamina} → Action BUY_FRIEND_STAMINA : débite DIAMONDS, crédite
     *  FRIEND_STAMINA). Invoqué via clickfile "buystamina". */
    public static void buyFriendStamina(GameMain game) {
        try {
            com.perblue.heroes.game.ClientActionHelper.buyFriendStamina(null);
            System.out.println("[buystamina] Action BUY_FRIEND_STAMINA envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[buystamina] échec: " + t); }
    }

    /** DEV (#72) : DUMP l'état d'une amitié côté CLIENT (empowerment + statut de déblocage). Invoqué via clickfile
     *  "frienddump &lt;PRIMARY&gt; &lt;SECONDARY&gt;". */
    public static void friendDump(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            if (p.length < 2) { System.out.println("[frienddump] usage: frienddump <PRIMARY> <SECONDARY>"); return; }
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            com.perblue.heroes.network.messages.UnitType a =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[0].trim().toUpperCase());
            com.perblue.heroes.network.messages.UnitType b =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[1].trim().toUpperCase());
            com.perblue.heroes.game.objects.FriendPairID pair =
                com.perblue.heroes.game.objects.FriendPairID.of(a, b);
            int emp = u.getIndividual().getFriendship(pair).getEmpowerment();
            int stones = u.getIndividual().getItemAmount(com.perblue.heroes.network.messages.ItemType.FRIENDSHIP_EMPOWER_STONE);
            System.out.println("[frienddump] " + pair + " empowerment=" + emp
                + " unlock=" + com.perblue.heroes.game.logic.FriendshipHelper.getUnlockStatus(u, pair)
                + " stones=" + stones
                + " favorite=" + u.getIndividual().isFavoriteFriendship(pair)
                + " diamonds=" + u.getResource(com.perblue.heroes.network.messages.ResourceType.DIAMONDS)
                + " friendStamina=" + u.getResource(com.perblue.heroes.network.messages.ResourceType.FRIEND_STAMINA));
        } catch (Throwable t) { System.out.println("[frienddump] échec: " + t); }
    }

    /** DEV : INSCRIT (ou retire) la guilde de la file de GUERRE via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.changeGuildWarQueueState} → Action CHANGE_WAR_QUEUE), sans avoir à trouver
     *  le bouton d'un écran de guerre encore vide. Invoqué via clickfile
     *  "warqueue &lt;QUEUED_SINGLE|QUEUED_PERSISTENT|NOT_QUEUED&gt;". */
    public static void changeWarQueue(GameMain game, String state) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null || !com.perblue.heroes.game.logic.GuildHelper.isInGuild(u)) {
                System.out.println("[warqueue] joueur sans guilde — ignoré"); return;
            }
            com.perblue.heroes.network.messages.WarQueueState st =
                com.perblue.heroes.network.messages.WarQueueState.valueOf(state.trim().toUpperCase());
            // Le message ne porte QUE l'état (relevé au bytecode) ; le WarInfo ne sert qu'au rappel local.
            com.perblue.heroes.network.messages.WarInfo wi =
                new com.perblue.heroes.network.messages.WarInfo();
            com.perblue.heroes.game.ClientActionHelper.changeGuildWarQueueState(wi, st);
            System.out.println("[warqueue] Action CHANGE_WAR_QUEUE(" + st + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[warqueue] échec: " + t); }
    }

    /** DEV : POSE une équipe de DÉFENSE DE GUERRE via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.saveHeroLineup} → message {@code HeroLineupUpdate}), avec les 5 héros
     *  suivants du roster. Le garage étant entièrement CLOS pendant la phase de sabotage (jour 1), l'écran
     *  n'offre pas de point d'entrée — on emprunte donc l'API que la fenêtre de lineups appelle elle-même.
     *  Invoqué via clickfile "wardefense &lt;1|2|3&gt;". */
    public static void setWarDefense(GameMain game, String which) {
        try {
            int n = Integer.parseInt(which.trim());
            com.perblue.heroes.network.messages.HeroLineupType type =
                com.perblue.heroes.network.messages.HeroLineupType.valueOf("WAR_DEFENSE_" + n);
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null) { System.out.println("[wardefense] pas de joueur"); return; }
            java.util.List<com.perblue.heroes.network.messages.UnitType> all = new java.util.ArrayList<>();
            for (Object o : u.getHeroes()) {
                all.add(((com.perblue.heroes.game.objects.UnitData) o).getType());
            }
            com.perblue.heroes.network.messages.HeroLineup hl =
                new com.perblue.heroes.network.messages.HeroLineup();
            @SuppressWarnings("unchecked") java.util.List<Object> heroes = (java.util.List<Object>) hl.heroes;
            int start = (n - 1) * 5;
            for (int i = start; i < start + 5 && i < all.size(); i++) heroes.add(all.get(i));
            com.perblue.heroes.game.ClientActionHelper.saveHeroLineup(type, 0L, hl, null);
            System.out.println("[wardefense] " + type + " ← " + heroes.size() + " héros " + heroes
                + " [chemin saveHeroLineup réel]");
        } catch (Throwable t) { System.out.println("[wardefense] échec: " + t); }
    }

    /** DEV : AFFECTE le joueur à une SALLE du garage via le CHEMIN RÉEL du jeu
     *  ({@code ClientActionHelper.assignWarCar} → Action {@code ASSIGN_WAR_CAR}).
     *  Invoqué via clickfile "warassign &lt;WarCarType&gt;". */
    public static void assignWarCar(GameMain game, String car) {
        try {
            com.perblue.heroes.network.messages.WarCarType ct =
                com.perblue.heroes.network.messages.WarCarType.valueOf(car.trim().toUpperCase());
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null) { System.out.println("[warassign] pas de joueur"); return; }
            com.perblue.heroes.game.ClientActionHelper.assignWarCar(u.getID(), ct);
            System.out.println("[warassign] ASSIGN_WAR_CAR(" + ct + ") envoyée [chemin réel]");
        } catch (Throwable t) { System.out.println("[warassign] échec: " + t); }
    }

    /** Le {@code WarInfo} que l'écran de guerre courant détient (champ {@code warInfo} de
     *  {@code WarBattlefieldScreen}) — c'est l'objet que les actions du jeu prennent en paramètre. */
    private static com.perblue.heroes.network.messages.WarInfo currentWarInfo(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == com.perblue.heroes.network.messages.WarInfo.class) {
                        f.setAccessible(true);
                        return (com.perblue.heroes.network.messages.WarInfo) f.get(screen);
                    }
                }
            }
            System.out.println("[war] écran courant sans WarInfo : " + screen.getClass().getSimpleName());
        } catch (Throwable t) { System.out.println("[war] WarInfo introuvable : " + t); }
        return null;
    }

    /** L'identifiant du premier membre ADVERSE (pour cibler sabotage/attaque/spar). */
    private static long firstEnemyMember(com.perblue.heroes.network.messages.WarInfo wi) {
        try {
            for (Object o : wi.enemyGuild.members.values()) {
                com.perblue.heroes.network.messages.WarMemberInfo m =
                    (com.perblue.heroes.network.messages.WarMemberInfo) o;
                if (m.userInfo != null) return m.userInfo.iD;
            }
        } catch (Throwable ignore) {}
        return 0L;
    }

    /** DEV : SABOTE un héros défenseur adverse via le chemin réel
     *  ({@code ClientActionHelper.sabotageWarDefender} → Action {@code WAR_SABOTAGE_DEFENDER}).
     *  Invoqué via clickfile "warsabotage &lt;UnitType&gt; &lt;WarSabotageType&gt;". */
    public static void sabotageWarDefender(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            com.perblue.heroes.network.messages.WarInfo wi = currentWarInfo(game);
            if (wi == null) return;
            long target = firstEnemyMember(wi);
            com.perblue.heroes.network.messages.UnitType hero =
                com.perblue.heroes.network.messages.UnitType.valueOf(p[0].toUpperCase());
            com.perblue.heroes.network.messages.WarSabotageType sab =
                com.perblue.heroes.network.messages.WarSabotageType.valueOf(p[1].toUpperCase());
            com.perblue.heroes.game.ClientActionHelper.sabotageWarDefender(wi, target, hero, sab,
                com.perblue.heroes.network.messages.ResourceType.WAR_TOKENS, 0, null);
            System.out.println("[warsabotage] " + sab + " sur " + hero + " de " + target + " [chemin réel]");
        } catch (Throwable t) { System.out.println("[warsabotage] échec: " + t); }
    }

    /** DEV : pose un BAN (ou une PROTECTION) via {@code ClientActionHelper.doWarEditBanProtect} →
     *  Action {@code WAR_EDIT_BAN_PROTECT}. Invoqué via "warban &lt;UnitType&gt; [protect]". */
    public static void warBanProtect(GameMain game, String args) {
        try {
            String[] p = args.trim().split("[,;\\s]+");
            com.perblue.heroes.network.messages.WarInfo wi = currentWarInfo(game);
            if (wi == null) return;
            boolean protect = p.length > 1 && p[1].equalsIgnoreCase("protect");
            java.util.List<com.perblue.heroes.network.messages.UnitType> units = new java.util.ArrayList<>();
            units.add(com.perblue.heroes.network.messages.UnitType.valueOf(p[0].toUpperCase()));
            // On demande D'ABORD au client SON verdict (c'est lui qui décide ce qui est légal) : s'il refuse,
            // rien n'est envoyé et on saura POURQUOI au lieu de constater un silence.
            Object code = com.perblue.heroes.ui.war.WarClientHelper.tryEditWarBanProtect(
                game.getYourUser(), wi.yourGuild, units, protect, 0L);
            System.out.println("[warban] verdict client = " + code);
            if (code != null) return;
            com.perblue.heroes.game.ClientActionHelper.doWarEditBanProtect(wi, units, protect, 0L, null);
            System.out.println("[warban] " + (protect ? "PROTECT" : "BAN") + " " + units + " [chemin réel]");
        } catch (Throwable t) { System.out.println("[warban] échec: " + t); }
    }

    /** DEV : SPAR contre un membre adverse ({@code ClientActionHelper.doSpar} → {@code WAR_SPAR_TARGET}).
     *  Invoqué via "warspar". */
    public static void warSpar(GameMain game) {
        try {
            com.perblue.heroes.network.messages.WarInfo wi = currentWarInfo(game);
            if (wi == null) return;
            long target = firstEnemyMember(wi);
            Object code = com.perblue.heroes.ui.war.WarClientHelper.trySpar(game.getYourUser(), wi, target, 0);
            System.out.println("[warspar] verdict client = " + code);
            if (code != null) return;
            com.perblue.heroes.game.ClientActionHelper.doSpar(wi, target, 0, null);
            System.out.println("[warspar] spar contre " + target + " [chemin réel]");
        } catch (Throwable t) { System.out.println("[warspar] échec: " + t); }
    }

    /** DEV : DÉMARRE une attaque de guerre ({@code ClientActionHelper.startWarAttack} →
     *  {@code START_WAR_ATTACK}). Invoqué via "warattack". */
    public static void warAttack(GameMain game) {
        try {
            com.perblue.heroes.network.messages.WarInfo wi = currentWarInfo(game);
            if (wi == null) return;
            long target = firstEnemyMember(wi);
            // ⚠️ NE PAS pré-appeler `WarClientHelper.doStartWarAttack` pour « connaître le verdict » : ce
            // n'est pas un prédicat, c'est LE RAPPEL de l'action — il CONSOMME l'attaque localement. Le
            // pré-appeler faisait échouer le vrai passage juste après (`WAR_EXTRA_ATTACKS_DEPLETED`), et rien
            // n'était émis puisque le message ne part qu'à `completeAction`, après le rappel.
            com.perblue.heroes.game.ClientActionHelper.startWarAttack(wi, target, 0);
            System.out.println("[warattack] START_WAR_ATTACK sur " + target + " [chemin réel]");
        } catch (Throwable t) { System.out.println("[warattack] échec: " + t); }
    }

    /** DEV : change la SALLE CIBLÉE par la guilde ({@code changeWarTarget} → {@code CHANGE_WAR_TARGET}).
     *  Invoqué via "wartarget &lt;WarCarType&gt;". */
    public static void warTarget(GameMain game, String car) {
        try {
            com.perblue.heroes.network.messages.WarInfo wi = currentWarInfo(game);
            if (wi == null) return;
            com.perblue.heroes.game.ClientActionHelper.changeWarTarget(wi,
                com.perblue.heroes.network.messages.WarCarType.valueOf(car.trim().toUpperCase()), true);
            System.out.println("[wartarget] CHANGE_WAR_TARGET(" + car + ") [chemin réel]");
        } catch (Throwable t) { System.out.println("[wartarget] échec: " + t); }
    }

    /** DEV : pourquoi l'INVASION est-elle (in)accessible ? On interroge les prédicats DU JEU côté CLIENT.
     *  Invoqué via clickfile "invstate". */
    public static void invasionState(GameMain game) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            System.out.println("[invstate] heure client = "
                + new java.util.Date(com.perblue.heroes.util.TimeUtil.serverTimeNow()));
            System.out.println("[invstate] teamLevel = " + u.getTeamLevel());
            System.out.println("[invstate] Unlockables.isUnlocked(INVASION) = "
                + com.perblue.heroes.game.data.misc.Unlockables.isUnlocked(
                    com.perblue.heroes.game.data.misc.Unlockable.INVASION, u));
            System.out.println("[invstate] InvasionHelper.getActiveInvasion = "
                + com.perblue.heroes.game.logic.InvasionHelper.getActiveInvasion());
            System.out.println("[invstate] canNavigateTo(INVASION) = "
                + com.perblue.heroes.ui.UINavHelper.canNavigateTo(
                    com.perblue.heroes.ui.UINavHelper.Destination.INVASION, false, new String[0]));
        } catch (Throwable t) { System.out.println("[invstate] échec: " + t); }
    }

    /** DEV : que contient, CÔTÉ CLIENT, la BreakerQuest reçue ? (écran vide → savoir si la donnée manque
     *  ou si c'est la mise en page). Invoqué via clickfile "breakerdump". */
    public static void breakerDump(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            System.out.println("[breakerdump] écran = " + screen.getClass().getSimpleName());
            for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() == com.perblue.heroes.network.messages.BreakerQuest.class) {
                        f.setAccessible(true);
                        com.perblue.heroes.network.messages.BreakerQuest bq =
                            (com.perblue.heroes.network.messages.BreakerQuest) f.get(screen);
                        System.out.println("[breakerdump] (champ écran) BreakerQuest = "
                            + (bq == null ? "null" : ("combats=" + bq.basicBreakerFights.size())));
                    }
                }
            }
            System.out.println("[breakerdump] aucun champ BreakerQuest sur cet écran");
        } catch (Throwable t) { System.out.println("[breakerdump] échec: " + t); }
        // Source de vérité : le HOLDER (GameMain.currentInvasion) et ce qu'il détient.
        try {
            java.lang.reflect.Field ci = GameMain.class.getDeclaredField("currentInvasion");
            ci.setAccessible(true);
            Object holder = ci.get(game);
            System.out.println("[breakerdump] GameMain.currentInvasion = " + holder);
            if (holder != null) {
                com.perblue.heroes.network.messages.BreakerQuest bq =
                    (com.perblue.heroes.network.messages.BreakerQuest) holder.getClass().getMethod("getBreakerQuest").invoke(holder);
                System.out.println("[breakerdump] holder.getBreakerQuest() = "
                    + (bq == null ? "null" : ("combats=" + bq.basicBreakerFights.size())));
                // Le champ CRUCIAL pour pouvoir DÉMARRER un combat : sans lui l'aperçu ne s'ouvre pas.
                if (bq != null) {
                    com.perblue.heroes.network.messages.BreakerUserFightInfo af = bq.activeBreakerFight;
                    System.out.println("[breakerdump] activeBreakerFight = " + (af == null ? "null"
                        : ("index=" + af.index + " breakerLineup=" + af.breakerLineup.size()
                           + " wardLineups=" + af.wardLineups.size())));
                }
            }
        } catch (Throwable t) { System.out.println("[breakerdump] holder : " + t); }
    }

    /** DEV : OUVRE l'aperçu du combat de breaker ACTIF (InvasionBreakerPreviewWindow), sans avoir à viser
     *  la vedette au pixel près — reproduit exactement le onClicked de la vedette. Invoqué via "breakerfight". */
    public static void breakerFight(GameMain game) {
        try {
            java.lang.reflect.Field ci = GameMain.class.getDeclaredField("currentInvasion");
            ci.setAccessible(true);
            Object holder = ci.get(game);
            if (holder == null) { System.out.println("[breakerfight] pas d'invasion courante"); return; }
            com.perblue.heroes.network.messages.BreakerQuest bq =
                (com.perblue.heroes.network.messages.BreakerQuest) holder.getClass().getMethod("getBreakerQuest").invoke(holder);
            if (bq == null || bq.activeBreakerFight == null) {
                System.out.println("[breakerfight] activeBreakerFight ABSENT — le serveur ne l'a pas fourni"); return;
            }
            com.perblue.heroes.network.messages.BreakerUserFightInfo af = bq.activeBreakerFight;
            com.perblue.heroes.ui.invasion.InvasionBreakerPreviewWindow w =
                new com.perblue.heroes.ui.invasion.InvasionBreakerPreviewWindow(
                    (com.perblue.heroes.ui.invasion.InvasionHolder) holder, af, af.index);
            w.show();
            System.out.println("[breakerfight] aperçu ouvert (index=" + af.index + ")");
        } catch (Throwable t) { System.out.println("[breakerfight] échec: " + t); t.printStackTrace(); }
    }

    /** DEV : POSTE un héros comme mercenaire (Action POST_HERO), sans navigation MERCENARIES.
     *  Invoqué via clickfile "postmerc &lt;UnitType&gt;". */
    public static void postMerc(GameMain game, String heroName) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null || !com.perblue.heroes.game.logic.GuildHelper.isInGuild(u)) {
                System.out.println("[postmerc] joueur sans guilde — ignoré"); return;
            }
            com.perblue.heroes.network.messages.UnitType t =
                com.perblue.heroes.network.messages.UnitType.valueOf(heroName.trim().toUpperCase());
            com.perblue.heroes.game.ActionHelper.doAction(
                com.perblue.heroes.network.messages.CommandType.POST_HERO, t, null, u, null, null);
            System.out.println("[postmerc] Action POST_HERO " + t + " envoyée [chemin réel]");
        } catch (Throwable tt) { System.out.println("[postmerc] échec: " + tt); }
    }

    /** DEV : AIDE une demande d'aide (GUILD AID) en envoyant un {@code GuildDonation} réel (comme le bouton AID
     *  via {@code GuildDonationHelper.tryDonation}), sans passer par le prompt UI. Args : requestID, memberID
     *  (le demandeur). Don = 1 STAMINA_CONSUMABLE. Invoqué via clickfile "guilddonate &lt;reqID&gt; &lt;memberID&gt;". */
    public static void guildDonate(GameMain game, long requestID, long memberID) {
        try {
            com.perblue.heroes.game.objects.User u = game.getYourUser();
            if (u == null || !com.perblue.heroes.game.logic.GuildHelper.isInGuild(u)) {
                System.out.println("[guilddonate] joueur sans guilde — ignoré"); return;
            }
            com.perblue.heroes.network.messages.GuildDonation gd = new com.perblue.heroes.network.messages.GuildDonation();
            gd.requestID = requestID;
            gd.memberID = memberID;
            gd.donation = com.perblue.heroes.game.logic.RewardHelper.createDrop(
                com.perblue.heroes.network.messages.ItemType.STAMINA_CONSUMABLE, 1L);
            game.getNetworkProvider().sendMessage(gd);
            System.out.println("[guilddonate] GuildDonation envoyé (demande #" + requestID + ", demandeur "
                + memberID + ", 1 STAMINA_CONSUMABLE) [chemin réel]");
        } catch (Throwable t) { System.out.println("[guilddonate] échec: " + t); }
    }

    /** DEV : dumpe le contenu du salon de chat GUILD tel que le CLIENT le connaît
     *  ({@code SocialDataManager.getChatForRoom(GUILD)}) — prouve que le client a bien reçu/stocké les Chat
     *  (echo serveur + resync de boot). Invoqué via clickfile "chatdump". */
    public static void chatDump(GameMain game) {
        try {
            java.util.List<?> chats = game.getSocialDataManager()
                .getChatForRoom(com.perblue.heroes.network.messages.ChatRoomType.GUILD);
            System.out.println("[chatdump] salon GUILD : " + (chats == null ? 0 : chats.size()) + " message(s)");
            if (chats != null) for (Object cc : chats) {
                java.lang.reflect.Field cf = cc.getClass().getField("chat");
                com.perblue.heroes.network.messages.Chat c =
                    (com.perblue.heroes.network.messages.Chat) cf.get(cc);
                System.out.println("[chatdump]   #" + c.chatID + " ["
                    + (c.sender == null ? "?" : c.sender.name) + "] " + c.message);
            }
        } catch (Throwable t) { System.out.println("[chatdump] échec: " + t); }
    }

    /** DEV : ouvre la fenêtre de chat (drawer) sur le salon par défaut, en invoquant le CHEMIN RÉEL du jeu
     *  ({@code UIScreen.chatStack.showChatWindow()} — exactement ce que fait le clic sur la bulle « … »).
     *  Contourne l'incertitude du clic synthétique. Invoqué via clickfile "openchat". */
    public static void openChat(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            if (screen == null) { System.out.println("[openchat] pas d'écran"); return; }
            java.lang.reflect.Field csf = null;
            for (Class<?> c = screen.getClass(); c != null && csf == null; c = c.getSuperclass()) {
                try { csf = c.getDeclaredField("chatStack"); } catch (NoSuchFieldException ignore) {}
            }
            if (csf == null) { System.out.println("[openchat] chatStack introuvable sur " + screen.getClass().getSimpleName()); return; }
            csf.setAccessible(true);
            Object stack = csf.get(screen);
            if (stack == null) { System.out.println("[openchat] chatStack null"); return; }
            java.lang.reflect.Method m = stack.getClass().getDeclaredMethod("showChatWindow");
            m.setAccessible(true);
            m.invoke(stack);
            System.out.println("[openchat] showChatWindow() invoqué [chemin réel du jeu]");
        } catch (Throwable t) { System.out.println("[openchat] échec: " + t); }
    }

    /** DEV : dumpe les acteurs actionnables de l'ÉCRAN COURANT (bouton/label/tag tuto + position stage) —
     *  pour savoir quoi taper (méthode B-bis). Invoqué via dh.clickfile "dumpscreen". */
    public static void dumpScreen(GameMain game) {
        try {
            Object screen = game.getScreenManager().getScreen();
            String cls = screen == null ? "null" : screen.getClass().getSimpleName();
            Group root = null;
            try { root = (Group) screen.getClass().getMethod("getRootStack").invoke(screen); } catch (Throwable ignore) {}
            Actor searchRoot = root;
            if (root != null && root.getStage() != null && root.getStage().getRoot() != null) searchRoot = root.getStage().getRoot();
            if (searchRoot == null) { System.out.println("[dumpscreen] écran=" + cls + " (pas de racine actionnable)"); return; }
            dumpActionable(searchRoot, cls);
        } catch (Throwable t) { System.out.println("[dumpscreen] err " + t); }
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
