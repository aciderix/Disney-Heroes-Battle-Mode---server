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
 * <p><b>Popups modaux</b> (récompenses de coffre « CRATE REWARDS », info héros…) : ils captent l'entrée
 * et masquent l'écran de base. La cible désignée par le tuto (ex. {@code MAIN_SCREEN_AVATAR}) est alors
 * « not visible » car <b>derrière</b> la popup, et un tap sur ses coordonnées est absorbé par la fenêtre.
 * On interroge {@code BaseScreen.getScreenWindows()} : si le tuto pointe DANS la popup, on tape dedans ;
 * sinon on <b>ferme</b> la popup via l'API du jeu ({@code BaseModalWindow.hide()}, exactement ce que fait
 * le bouton X / le retour) pour révéler la cible. Aucune coordonnée devinée, aucune modif du jeu.
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
                // Le tuto pointe DANS une popup ouverte → taper dedans (bouton désigné).
                List<Actor> inWindow = new ArrayList<>();
                for (Object win : windows) if (win instanceof Actor) collect((Actor) win, targets, inWindow);
                if (!inWindow.isEmpty()) return tapAll(inWindow, input, w, h);

                Object top = windows.get(windows.size() - 1);
                String cls = top.getClass().getSimpleName();
                // On ne FERME (hide) que les popups d'AFFICHAGE de récompense (« CRATE REWARDS » =
                // ChestResultsWindow, écrans de butin) : le joueur les rejette, elles n'ont pas d'action.
                // Les popups INTERACTIVES (ChestReadyWindow « ouvrir le coffre », confirmations…) ne
                // doivent PAS être fermées → on laisse le tap central du lanceur en frapper le bouton.
                if (isRewardDisplay(cls)) {
                    top.getClass().getMethod("hide").invoke(top);
                    if (DEBUG) System.out.println("[tutodrive] popup " + cls + " fermée (récompense)");
                    return true;
                }
                // Popup interactive (ex. « CRATE READY » avec bouton VIEW) : le tap central du lanceur
                // frappe le fond, pas le bouton (qui n'est PAS au centre). On frappe le bouton d'action
                // PRINCIPAL = le bouton-texte du jeu (DFTextButton « VIEW/OPEN/OK »).
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
