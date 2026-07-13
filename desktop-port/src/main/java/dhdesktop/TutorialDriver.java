package dhdesktop;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.perblue.heroes.GameMain;
import com.perblue.heroes.game.objects.User;
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
    private static String lastTargets = "";
    private static String lastTrace = "";

    /** Renvoie true si un tap/fermeture a été injecté sur une cible désignée par le tutoriel. */
    public static boolean driveOnce(GameMain game, DhInput input, int w, int h) {
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

            // 2) Pas de popup : il faut une cible de tutoriel pour agir.
            if (targets.isEmpty()) return false;
            Group root = (Group) screen.getClass().getMethod("getRootStack").invoke(screen);
            if (root == null) return false;
            // Chercher dans TOUTE la scène (pas seulement getRootStack) : certains éléments (menu latéral
            // HEROES/ITEMS…, overlays) sont hors du rootStack → sinon l'acteur désigné (ex.
            // BASE_MENU_HERO_BUTTON) n'est pas trouvé alors que le jeu, lui, y pointe (flèche jaune).
            Group searchRoot = root;
            Stage stg = root.getStage();
            if (stg != null && stg.getRoot() != null) searchRoot = stg.getRoot();
            List<Actor> found = new ArrayList<>();
            collect(searchRoot, targets, found);
            if (DEBUG && !targets.toString().equals(lastTargets)) {
                lastTargets = targets.toString();
                System.out.println("[tutodrive] " + screen.getClass().getSimpleName()
                    + " cibles=" + targets + " trouvés=" + found.size());
            }
            return tapAll(found, input, w, h);
        } catch (Throwable t) {
            return false;   // écran/étape sans pointeur exploitable → no-op
        }
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

    /** Tape le centre de chaque acteur trouvé (conversion stage → écran par le viewport du jeu). */
    private static boolean tapAll(List<Actor> found, DhInput input, int w, int h) {
        boolean tapped = false;
        for (Actor a : found) {
            Stage st = a.getStage();
            if (st == null || a.getWidth() <= 0 || a.getHeight() <= 0) continue;
            Vector2 v = a.localToStageCoordinates(new Vector2(a.getWidth() / 2f, a.getHeight() / 2f));
            float sw = st.getWidth(), sh = st.getHeight();
            if (sw <= 0 || sh <= 0) continue;
            input.tap(Math.round(v.x / sw * w), Math.round(h - v.y / sh * h));
            tapped = true;
        }
        return tapped;
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
