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
 */
public final class TutorialDriver {

    private TutorialDriver() {}

    private static final boolean DEBUG = Boolean.getBoolean("dh.tutodrive.debug");
    private static String lastTargets = "";

    /** Renvoie true si un tap a été injecté sur une cible désignée par le tutoriel. */
    public static boolean driveOnce(GameMain game, DhInput input, int w, int h) {
        try {
            User user = game.getYourUser();
            if (user == null) return false;
            List<?> pointers = TutorialHelper.getPointers(user);
            if (pointers == null || pointers.isEmpty()) return false;

            // Noms des composants visés par le tutoriel à cet instant (nom + index héros).
            Set<String> targets = new HashSet<>();
            for (Object p : pointers) {
                String name = ((TutorialPointerInfo) p).getActorTutorialName();
                if (name != null && !name.isEmpty()) targets.add(name);
            }
            if (targets.isEmpty()) return false;

            // Racine de l'écran courant.
            Object screen = game.getScreenManager().getScreen();
            if (screen == null) return false;
            Group root = (Group) screen.getClass().getMethod("getRootStack").invoke(screen);
            if (root == null) return false;

            // Tape TOUS les acteurs portant un des noms visés (gère l'index héros 0/1/2 :
            // seul l'acteur réellement actionnable par le tutoriel réagira).
            List<Actor> found = new ArrayList<>();
            collect(root, targets, found);
            if (DEBUG && !targets.toString().equals(lastTargets)) {
                lastTargets = targets.toString();
                System.out.println("[tutodrive] " + screen.getClass().getSimpleName()
                    + " cibles=" + targets + " trouvés=" + found.size());
            }
            boolean tapped = false;
            for (Actor a : found) {
                Stage st = a.getStage();
                if (st == null || a.getWidth() <= 0 || a.getHeight() <= 0) continue;
                Vector2 v = a.localToStageCoordinates(new Vector2(a.getWidth() / 2f, a.getHeight() / 2f));
                // stage (unités virtuelles, y montant) → écran (pixels, y descendant). Le viewport UI
                // remplit la fenêtre → mise à l'échelle par la taille virtuelle du stage.
                float sw = st.getWidth(), sh = st.getHeight();
                if (sw <= 0 || sh <= 0) continue;
                input.tap(Math.round(v.x / sw * w), Math.round(h - v.y / sh * h));
                tapped = true;
            }
            return tapped;
        } catch (Throwable t) {
            return false;   // écran/étape sans pointeur exploitable → no-op
        }
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
