package dhdesktop;

import com.badlogic.gdx.Gdx;
import com.perblue.heroes.GameMain;
import dhbackend.*;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Launcher desktop de Disney Heroes avec **backend LWJGL3 maison** (dhbackend/*), miroir de
 * `dsbackend/` de DragonSoul mais contre le core libGDX (clair) du jeu. On crée une fenêtre
 * GLFW + contexte GL, on câble les shims dans le singleton {@code com.badlogic.gdx.Gdx}, on
 * instancie {@link GameMain} et on pilote son cycle create()/render() nous-mêmes.
 *
 * Props : dh.server=host:port (redirige ServerType.LIVE) ; dh.frames=N (rendre N frames puis
 * quitter, pour capture headless) ; dh.shot=fichier.ppm ; dh.w/dh.h ; dh.gdxnative=libgdx64.so ;
 * dh.rundir=dossier inscriptible (prefs/external/local).
 */
public final class DesktopLauncher {

    public static void main(String[] args) throws Exception {
        int W = Integer.getInteger("dh.w", 1280);
        int H = Integer.getInteger("dh.h", 720);
        int maxFrames = Integer.getInteger("dh.frames", 0);
        File runDir = new File(System.getProperty("dh.rundir", "build/run"));
        runDir.mkdirs();

        // Natif libGDX (Matrix4/BufferUtils JNI) — extrait par run-desktop.sh.
        String gdxNative = System.getProperty("dh.gdxnative");
        if (gdxNative != null && new File(gdxNative).exists()) {
            System.load(new File(gdxNative).getAbsolutePath());
            System.out.println("[launcher] natif libGDX chargé: " + gdxNative);
        }

        maybeRedirectServer(System.getProperty("dh.server"));

        // --- fenêtre GLFW + contexte GL (Xvfb + Mesa llvmpipe en conteneur) ---
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, "1".equals(System.getProperty("dh.visible")) ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 2);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
        long win = glfwCreateWindow(W, H, "Disney Heroes (desktop)", NULL, NULL);
        if (win == NULL) throw new IllegalStateException("glfwCreateWindow failed");
        glfwMakeContextCurrent(win);
        glfwSwapInterval(0);
        GL.createCapabilities();
        System.out.println("[launcher] GL " + glGetString(GL_VERSION) + " / " + glGetString(GL_RENDERER));

        // --- backend maison ---
        DhGL20 gl = new DhGL20();
        DhGraphics graphics = new DhGraphics(gl, W, H);
        DhInput input = new DhInput();
        new GlfwInput(win, input); // callbacks GLFW réels -> DhInput
        DhAudio audio = new DhAudio();
        DhFiles files = new DhFiles(new File(runDir, "external").getPath(), new File(runDir, "local").getPath());
        DhNet net = new DhNet();
        DhDeviceInfo device = new DhDeviceInfo();

        // --- câblage du singleton Gdx (noms clairs) ---
        GameMain game = new GameMain(device);
        DhApplication app = new DhApplication(game, graphics, input, audio, new File(runDir, "prefs"));
        Gdx.app = app;
        Gdx.graphics = graphics;
        Gdx.audio = audio;
        Gdx.input = input;
        Gdx.files = files;
        Gdx.net = net;
        Gdx.gl = gl;
        Gdx.gl20 = gl;
        System.out.println("[launcher] singleton Gdx câblé");

        // --- services plateforme (NO-OP, cf. DhBridges / BACKEND_STATUS.md #BRIDGES) ---
        wireBridges(game);

        // --- ouvreur des fichiers de stats .tab (normalement posé par l'AndroidLauncher) ---
        try { DhStatFileExt.install(); System.out.println("[launcher] StatFileHelper.ext installé"); }
        catch (Throwable t) { System.out.println("[launcher] StatFileExt échec: " + t); }

        // --- consentement enregistré (évite le dialogue d'accord bloquant en headless) ---
        // Écriture des prefs d'accord = ce que le handler « J'accepte » du jeu écrit (consentement
        // réel, pas une fausse réponse). ⚠️ DEFERRED (BACKEND_STATUS.md #CONSENT) : clés/valeurs
        // exactes à confirmer par décompilation de GameMain (noms empruntés à DragonSoul).
        preseedConsent(new File(runDir, "prefs"));

        System.out.println("[launcher] game.create() ...");
        game.create();
        System.out.println("[launcher] game.create() OK");
        game.resize(W, H);

        // --- boucle de rendu ---
        long frames = 0;
        double last = glfwGetTime();
        String shot = System.getProperty("dh.shot");
        // DEV : capture PÉRIODIQUE (dh.shotevery=N frames, 0=off) → on écrase `shot` toutes les N frames.
        // Utile quand le superviseur tue la run avant la fin (exit 144/SIGSTKFLT) : la dernière frame
        // atteinte reste sur disque, même sans arrêt gracieux de la boucle. Aucun effet en prod.
        int shotEvery = Integer.getInteger("dh.shotevery", 0);
        // DEV : ENREGISTREUR pas-à-pas (dh.tutorec) — une capture NUMÉROTÉE par tick d'autotap dans
        // build/rec/step_NNN.ppm, synchronisée avec les dumps [tutorec] du pilote → reconstitution exacte
        // de ce que le tuto déclenche étape par étape. Off par défaut, aucun effet en prod.
        boolean tutoRec = System.getProperty("dh.tutorec") != null
                && !"0".equals(System.getProperty("dh.tutorec"));
        int recCount = 0;
        boolean recTickPending = false;
        if (tutoRec) new java.io.File("build/rec").mkdirs();
        // DEV : CLIC MANUEL (dh.clickfile=chemin) — méthode B-bis « je fais le clic moi-même ». Chaque frame,
        // on lit le fichier ; chaque ligne "x,y" (pixels ÉCRAN, origine haut-gauche = comme la capture) injecte
        // un tap via l'INPUT RÉEL du jeu (donc hit-test correct, contrairement à un clic X synthétique), puis on
        // vide le fichier. Une capture build/manual.ppm est écrite en continu → on VOIT le résultat de chaque
        // clic + on monitore le serveur. Off par défaut, aucun effet en prod.
        String clickFile = System.getProperty("dh.clickfile");
        // Pilotage headless : dh.autotap=N injecte un tap au centre toutes les N frames (0 = off).
        // Sert à FAIRE AVANCER le tutoriel (dialogues « tap to continue ») sans utilisateur, pour
        // vérifier « tuto jouable de bout en bout » et observer ce que le client envoie ensuite.
        int autotap = Integer.getInteger("dh.autotap", 0);
        // Pilotage headless de DEV uniquement (off par défaut) : dh.autofight=1 active l'AUTO-COMBAT
        // D'ORIGINE du jeu (appel de son API publique setAutoAttack) quand on est dans un écran de
        // combat → les héros combattent seuls (l'IA lance les compétences). AUCUNE modif du jeu, AUCUN
        // effet en prod (le joueur lance sans ce drapeau) ni côté serveur. Sert à tester le jeu headless.
        // NB : Boolean.getBoolean n'accepte QUE "true" → "dh.autofight=1" le laissait à false (AUTO jamais
        // activé → héros passifs, skills seulement sur tap manuel → défaite). On accepte toute valeur non
        // "0"/"false" (cohérent avec dh.autotap/dh.tutodrive.debug).
        String autofightProp = System.getProperty("dh.autofight");
        boolean autofight = autofightProp != null && !"0".equals(autofightProp)
            && !"false".equalsIgnoreCase(autofightProp);
        // Mesure FPS : moyenne glissante toutes les N frames (dh.fps=N, 0=off), avec l'écran courant
        // → permet de relever les FPS EN COMBAT (screen=CoreAttackScreen/…).
        int fpsWindow = Integer.getInteger("dh.fps", 0);
        double fpsWindowStart = glfwGetTime();
        // DEV (off par défaut) : spike Opt.2 (#27) — exécuter le vrai HeadlessCombat du jeu dans ce client
        // headless (unidbg+assets) pour mesurer sa lourdeur + servir d'ORACLE. dh.combatspike.exit=1 => quitte
        // après la mesure. Aucun effet en prod ni côté serveur.
        String combatSpikeProp = System.getProperty("dh.combatspike");
        boolean combatSpike = combatSpikeProp != null && !"0".equals(combatSpikeProp)
            && !"false".equalsIgnoreCase(combatSpikeProp);
        boolean combatSpikeExit = "1".equals(System.getProperty("dh.combatspike.exit"));
        while (!glfwWindowShouldClose(win) && (maxFrames == 0 || frames < maxFrames)) {
            double now = glfwGetTime();
            graphics.deltaTime = (float) (now - last);
            graphics.frameId = frames;
            last = now;

            if (autotap > 0 && frames > 90 && frames % autotap == 0) {
                // DEV : d'abord taper la cible désignée par le tutoriel (bouton héros, etc.) via les API du
                // jeu. Le tap central de secours n'est légitime QUE pour un dialogue « tap to continue »
                // (aucun pointeur actif) : si le tuto a une cible non résolue sur l'écran courant
                // (hadActiveTarget), NE PAS taper au centre — ça partirait hors-script (coffre Diamant →
                // « Follow the tutorial arrow! »). Le pilote gère alors le retour vers le hub lui-même.
                if (!TutorialDriver.driveOnce(game, input, W, H) && !TutorialDriver.hadActiveTarget())
                    input.tap(W / 2, H / 2);
                // Le recorder DÉCIME lui-même (toutes RECEVERY frames) : on capture quand le pilote le
                // signale → on peut piloter à chaque frame (autotap=1, fiable) sans des milliers de captures.
                recTickPending = tutoRec && TutorialDriver.recCaptureRequested();
            }
            if (autofight && frames % 20 == 0) enableAutoCombat(game);  // DEV : bouton AUTO d'origine
            // DEV : clic manuel injecté depuis dh.clickfile (voir déclaration).
            if (clickFile != null && frames > 90) injectManualClicks(clickFile, input, game, W, H);
            input.drain();          // input synthétique (pilotage) sur le thread render
            app.drainRunnables();   // Gdx.app.postRunnable
            game.render();
            // DEV : spike Opt.2 — dès que le user a des héros (post-login), exécute UNE fois le vrai
            // HeadlessCombat et mesure (bloque le thread render le temps de la sim = attendu pour la mesure).
            if (combatSpike && frames > 200 && CombatSpikeDriver.tryRunOnce(game) && combatSpikeExit) {
                System.out.println("[combatspike] terminé → fermeture (dh.combatspike.exit=1)");
                break;
            }
            glfwSwapBuffers(win);
            glfwPollEvents();
            frames++;

            if (shot != null && shotEvery > 0 && frames % shotEvery == 0) capture(W, H, shot);
            if (clickFile != null && frames % 10 == 0) capture(W, H, "build/manual.ppm");   // vue continue
            if (recTickPending) { capture(W, H, String.format("build/rec/step_%03d.ppm", recCount++)); recTickPending = false; }

            if (fpsWindow > 0 && frames % fpsWindow == 0) {
                double t = glfwGetTime();
                double fps = fpsWindow / (t - fpsWindowStart);
                fpsWindowStart = t;
                // Part du temps passée DANS l'émulation unidbg (spine+particules) vs le reste
                // (rasterisation logicielle llvmpipe, logique de jeu) → attribution du coût.
                double emuMs = dhbackend.unidbg.UnidbgVM.emuNanos() / 1e6 / fpsWindow;
                long emuCalls = dhbackend.unidbg.UnidbgVM.emuCalls() / fpsWindow;
                dhbackend.unidbg.UnidbgVM.emuReset();
                System.out.printf("[fps] frame %d: %.1f fps (%.1f ms/frame)  unidbg=%.1f ms/frame (%d appels)  reste=%.1f ms  screen=%s%n",
                    frames, fps, 1000.0 / fps, emuMs, emuCalls, Math.max(0, 1000.0 / fps - emuMs), currentScreen(game));
            }
        }

        if (shot != null) capture(W, H, shot);
        System.out.println("[launcher] arrêt après " + frames + " frames");
        glfwDestroyWindow(win);
        glfwTerminate();
    }

    /**
     * DEV : active l'AUTO-COMBAT d'origine du jeu si l'écran courant l'expose (API publique
     * {@code setAutoAttack} de {@code CoreAttackScreen}). Réflexion → aucune dépendance de compilation,
     * aucune modif du jeu. No-op hors combat. Idempotent (n'appelle que si pas déjà en auto).
     */
    /** DEV : lit dh.clickfile et exécute chaque ligne, puis VIDE le fichier. Off en prod.
     *  <p>Formats de ligne (méthode B-bis « je pilote moi-même », passe par l'input RÉEL du jeu) :
     *  <ul>
     *    <li><b>{@code x,y}</b> — tap aux pixels écran (origine haut-gauche = comme la capture) ; dump de la
     *        cible touchée AVANT le tap (acteur/tag/listeners/écran) pour savoir quoi câbler.</li>
     *    <li><b>{@code dump x,y}</b> — dump SANS taper (observer un point).</li>
     *    <li><b>{@code drive}</b> — <b>SEMI-AUTO</b> : appelle {@link TutorialDriver#driveOnce} UNE fois → tape
     *        la cible désignée par le tuto (bouton héros, flèche, « tap to continue » scene2d) via les API du
     *        jeu. Sert quand mon clic manuel ne « prend » pas (cible non-devinable).</li>
     *    <li><b>{@code auto}</b> — <b>SEMI-AUTO</b> : active l'AUTO-COMBAT d'origine ({@code setAutoAttack}) sur
     *        l'écran de combat courant → les héros combattent seuls. À déclencher PENDANT un combat.</li>
     *    <li><b>{@code center}</b> — tap central (W/2,H/2) via l'input réel → avance un « TAP TO CONTINUE » de
     *        la scène de combat (input scène, non scene2d → un tap x,y ciblé ne suffit pas toujours).</li>
     *  </ul>
     *  Tout est de l'outillage DEV côté lanceur : aucune modif du jeu ni du serveur, rien en prod. */
    private static void injectManualClicks(String path, DhInput input, GameMain game, int W, int H) {
        try {
            java.io.File cf = new java.io.File(path);
            if (!cf.isFile() || cf.length() == 0) return;
            java.util.List<String> lines = java.nio.file.Files.readAllLines(cf.toPath());
            try (java.io.PrintWriter pw = new java.io.PrintWriter(cf)) { /* truncate */ }
            for (String ln : lines) {
                ln = ln.trim();
                if (ln.isEmpty() || ln.startsWith("#") || ln.equalsIgnoreCase("shot")) continue;
                String low = ln.toLowerCase();
                // --- Commandes SEMI-AUTO (invoquer une fonction du pilote à la demande) ---
                if (low.equals("drive")) {                       // avancer via la cible désignée du tuto
                    boolean acted = TutorialDriver.driveOnce(game, input, W, H);
                    System.out.println("[semiauto] drive → " + (acted ? "a tapé la cible du tuto"
                        : "aucune cible (hadActiveTarget=" + TutorialDriver.hadActiveTarget() + ")"));
                    continue;
                }
                if (low.equals("auto")) {                        // activer l'auto-combat d'origine
                    System.out.println("[semiauto] auto → activation AUTO-COMBAT (setAutoAttack)");
                    enableAutoCombat(game);
                    continue;
                }
                if (low.equals("center") || low.equals("tapc")) {  // tap central (« TAP TO CONTINUE »)
                    System.out.println("[semiauto] center → tap (" + (W / 2) + "," + (H / 2) + ")");
                    input.tap(W / 2, H / 2);
                    continue;
                }
                if (low.equals("goquests") || low.equals("goquest")) {  // ouvrir l'écran QUESTS (API du jeu)
                    TutorialDriver.navTo(game, com.perblue.heroes.ui.UINavHelper.Destination.QUESTS);
                    continue;
                }
                if (low.startsWith("drag ")) {                   // drag x1,y1,x2,y2[,frames] — GLISSER (scroll de liste)
                    String[] d = ln.substring(5).trim().split("[,;\\s]+");
                    if (d.length >= 4) {
                        int x1 = Integer.parseInt(d[0].trim()), y1 = Integer.parseInt(d[1].trim());
                        int x2 = Integer.parseInt(d[2].trim()), y2 = Integer.parseInt(d[3].trim());
                        int frames = d.length >= 5 ? Integer.parseInt(d[4].trim()) : 15;
                        System.out.println("[manualclick] drag (" + x1 + "," + y1 + ")→(" + x2 + "," + y2 + ") " + frames + "f");
                        input.drag(x1, y1, x2, y2, frames);
                    }
                    continue;
                }
                if (low.startsWith("nav ")) {                    // nav <DESTINATION> — ouvrir un écran du hub (API du jeu)
                    String dest = ln.substring(4).trim().toUpperCase();
                    try {
                        TutorialDriver.navTo(game,
                            com.perblue.heroes.ui.UINavHelper.Destination.valueOf(dest));
                    } catch (Throwable t) { System.out.println("[nav] destination inconnue: " + dest + " (" + t + ")"); }
                    continue;
                }
                if (low.equals("dumpscreen") || low.equals("dumpq")) {  // dumper les acteurs actionnables de l'écran
                    TutorialDriver.dumpScreen(game);
                    continue;
                }
                if (low.startsWith("enterlevel ")) {             // enterlevel ch,lvl — ouvrir l'aperçu d'un niveau (API du jeu)
                    String[] e = ln.substring(11).trim().split("[,;\\s]+");
                    if (e.length >= 2) TutorialDriver.enterLevel(game,
                        Integer.parseInt(e[0].trim()), Integer.parseInt(e[1].trim()));
                    continue;
                }
                if (low.startsWith("createguild ")) {            // createguild <nom> — créer une guilde (chemin d'envoi réel)
                    TutorialDriver.createGuild(game, ln.substring(12).trim());
                    continue;
                }
                if (low.startsWith("guildchat ")) {              // guildchat <message> — envoyer un chat de guilde (SendChat réel)
                    TutorialDriver.sendGuildChat(game, ln.substring(10).trim());
                    continue;
                }
                if (low.equals("chatdump")) {                    // chatdump — dumper le salon GUILD côté client
                    TutorialDriver.chatDump(game);
                    continue;
                }
                if (low.equals("openchat")) {                    // openchat — ouvrir la fenêtre de chat (chemin réel)
                    TutorialDriver.openChat(game);
                    continue;
                }
                if (low.equals("requeststamina")) {              // requeststamina — poster une demande d'aide STAMINA (GUILD AID)
                    TutorialDriver.requestStaminaAid(game);
                    continue;
                }
                if (low.startsWith("missionadd ")) {             // missionadd <TYPE> <PRIMARY> <SECONDARY> — démarrer une mission idle (ADD_MISSION réel)
                    TutorialDriver.addMission(game, ln.substring(11).trim());
                    continue;
                }
                if (low.equals("missionclaim")) {                // missionclaim — réclamer les missions terminées (CLAIM_MISSION_REWARDS réel)
                    TutorialDriver.claimMissions(game);
                    continue;
                }
                if (low.startsWith("missioncancel")) {           // missioncancel [PRIMARY] — annuler une mission (CANCEL_MISSION réel)
                    TutorialDriver.cancelMission(game, ln.length() > 13 ? ln.substring(13).trim() : "");
                    continue;
                }
                if (low.startsWith("speedup ")) {                // speedup <PRIMARY> <SECONDARY> <count> — accélérer une mission (SPEEDUP_MISSION réel)
                    TutorialDriver.speedupMission(game, ln.substring(8).trim());
                    continue;
                }
                if (low.startsWith("costlimit ")) {              // costlimit <ITEMTYPE> <N> — plafond dépense objet en missions (SET_MISSION_ITEM_COST_LIMIT réel)
                    TutorialDriver.setMissionCostLimit(game, ln.substring(10).trim());
                    continue;
                }
                if (low.equals("missiondump")) {                 // missiondump — dump de l'état missions côté client
                    TutorialDriver.missionDump(game);
                    continue;
                }
                if (low.startsWith("friendui ")) {               // friendui <PRIMARY> <SECONDARY> [MODE] — ouvrir la vue détail d'amitié (disk/campagne)
                    TutorialDriver.friendUI(game, ln.substring(9).trim());
                    continue;
                }
                if (low.startsWith("empower ")) {                // empower <PRIMARY> <SECONDARY> <count> — empower amitié (EMPOWER_FRIENDSHIP réel)
                    TutorialDriver.empowerFriendship(game, ln.substring(8).trim());
                    continue;
                }
                if (low.startsWith("frienddump ")) {             // frienddump <PRIMARY> <SECONDARY> — dump état amitié côté client
                    TutorialDriver.friendDump(game, ln.substring(11).trim());
                    continue;
                }
                if (low.startsWith("setfavorite ")) {            // setfavorite <PRIMARY> <SECONDARY> <0|1> — (dé)favorise (SET_FAVORITE_FRIENDSHIP réel)
                    TutorialDriver.setFavoriteFriendship(game, ln.substring(12).trim());
                    continue;
                }
                if (low.equals("buystamina")) {                  // buystamina — achète de l'énergie d'amitié (BUY_FRIEND_STAMINA réel)
                    TutorialDriver.buyFriendStamina(game);
                    continue;
                }
                if (low.startsWith("warqueue ")) {               // warqueue <STATE> — inscrire la guilde en file de GUERRE (CHANGE_WAR_QUEUE réel)
                    TutorialDriver.changeWarQueue(game, ln.substring(9).trim());
                    continue;
                }
                if (low.startsWith("wardefense ")) {             // wardefense <1|2|3> — poser une défense de guerre (saveHeroLineup réel)
                    TutorialDriver.setWarDefense(game, ln.substring(11).trim());
                    continue;
                }
                if (low.startsWith("warassign ")) {              // warassign <WarCarType> — s'affecter à une salle (ASSIGN_WAR_CAR réel)
                    TutorialDriver.assignWarCar(game, ln.substring(10).trim());
                    continue;
                }
                if (low.startsWith("warsabotage ")) {            // warsabotage <hero> <type> — saboter un défenseur adverse
                    TutorialDriver.sabotageWarDefender(game, ln.substring(12).trim());
                    continue;
                }
                if (low.startsWith("warban ")) {                 // warban <hero> [protect] — bannir/protéger un héros
                    TutorialDriver.warBanProtect(game, ln.substring(7).trim());
                    continue;
                }
                if (low.equals("warspar")) {                     // warspar — s'entraîner contre un adversaire
                    TutorialDriver.warSpar(game);
                    continue;
                }
                if (low.equals("warattack")) {                   // warattack — démarrer une attaque de guerre
                    TutorialDriver.warAttack(game);
                    continue;
                }
                if (low.startsWith("wartarget ")) {              // wartarget <WarCarType> — changer la salle ciblée
                    TutorialDriver.warTarget(game, ln.substring(10).trim());
                    continue;
                }
                if (low.equals("invstate")) {                    // invstate — diagnostic d'accès à l'INVASION
                    TutorialDriver.invasionState(game);
                    continue;
                }
                if (low.equals("surgenav")) {                    // surgenav — diagnostic de navigabilité SURGE
                    TutorialDriver.surgeNav(game);
                    continue;
                }
                if (low.equals("surgestate")) {                  // surgestate — dumper l'état SURGE côté client
                    TutorialDriver.surgeState(game);
                    continue;
                }
                if (low.equals("surgeclaim")) {                  // surgeclaim — réclamer les récompenses (SurgeClaimRewards)
                    TutorialDriver.surgeClaim(game);
                    continue;
                }
                if (low.equals("surgefight")) {                  // surgefight — ouvrir le combat du 1er district jouable
                    TutorialDriver.surgeFight(game);
                    continue;
                }
                if (low.equals("surgeteamfight")) {              // surgeteamfight — auto-sélection + quick fight
                    TutorialDriver.surgeTeamFight(game);
                    continue;
                }
                if (low.equals("surgequick")) {                  // surgequick — résoudre le combat SURGE en quick-fight
                    TutorialDriver.surgeQuick(game);
                    continue;
                }
                if (low.equals("surgeraid")) {                   // surgeraid — déclencher un RAID (observer le protocole)
                    TutorialDriver.surgeRaid(game);
                    continue;
                }
                if (low.equals("expfight")) {                    // expfight — ouvrir le hero chooser du nœud courant (EXPEDITION)
                    TutorialDriver.expFight(game);
                    continue;
                }
                if (low.equals("expquick")) {                    // expquick — quick fight du nœud → ExpeditionAttackScreen + ExpeditionAttack
                    TutorialDriver.expQuick(game);
                    continue;
                }
                if (low.equals("expraid")) {                     // expraid — RAID de l'expédition → ExpeditionRaid
                    TutorialDriver.expRaid(game);
                    continue;
                }
                if (low.equals("expchest")) {                    // expchest — ouvre le coffre d'expédition → OpenExpeditionChest
                    TutorialDriver.expChest(game);
                    continue;
                }
                if (low.startsWith("enchant ")) {               // enchant <HERO> <SLOT> <MATERIAL> <count> [diamonds] → EnchantItem
                    String[] en = ln.trim().split("[,;\\s]+");
                    if (en.length >= 5) TutorialDriver.enchant(game, en[1].toUpperCase(), en[2].toUpperCase(),
                        en[3].toUpperCase(), Integer.parseInt(en[4].trim()),
                        en.length >= 6 && (en[5].equalsIgnoreCase("diamonds") || en[5].equalsIgnoreCase("true")));
                    else System.out.println("[enchant] usage: enchant <HERO> <SLOT> <MATERIAL> <count> [diamonds]");
                    continue;
                }
                if (low.startsWith("maxupgrade ")) {            // maxupgrade <HERO> → EnhanceMaxPrimeBadge (bouton « MAX »)
                    String[] mu = ln.trim().split("[,;\\s]+");
                    if (mu.length >= 2) TutorialDriver.maxUpgrade(game, mu[1].toUpperCase());
                    else System.out.println("[maxupgrade] usage: maxupgrade <HERO>");
                    continue;
                }
                if (low.startsWith("savelineup ")) {            // savelineup <SAVED_N> <name> <HERO1+HERO2+...> → HeroLineupUpdate
                    String[] sl = ln.trim().split("\\s+", 4);
                    if (sl.length >= 4) TutorialDriver.saveLineup(game, sl[1].toUpperCase(), sl[2], sl[3]);
                    else System.out.println("[savelineup] usage: savelineup <SAVED_N> <name> <HERO1+HERO2+...>");
                    continue;
                }
                if (low.startsWith("checkname ")) {             // checkname <name> → CheckLineupName → CheckLineupNameResult
                    String[] cn = ln.trim().split("\\s+", 2);
                    if (cn.length >= 2) TutorialDriver.checkName(game, cn[1]);
                    else System.out.println("[checkname] usage: checkname <name>");
                    continue;
                }
                if (low.startsWith("lineupscreen")) {           // lineupscreen <SAVED_N> → ouvre le vrai écran de lineup
                    String[] ls = ln.trim().split("[,;\\s]+");
                    TutorialDriver.lineupScreen(game, ls.length >= 2 ? ls[1].toUpperCase() : "SAVED_1");
                    continue;
                }
                if (low.startsWith("claimcollection ")) {       // claimcollection <TYPE> <TIER> <LEVEL> → CLAIM_COLLECTION_REWARDS
                    String[] cc = ln.trim().split("[,;\\s]+");
                    if (cc.length >= 4) TutorialDriver.claimCollection(game, cc[1].toUpperCase(), cc[2].toUpperCase(), Integer.parseInt(cc[3].trim()));
                    else System.out.println("[claimcollection] usage: claimcollection <TYPE> <TIER> <LEVEL>");
                    continue;
                }
                if (low.startsWith("collectionscreen")) {       // collectionscreen <TYPE> → ouvre le vrai écran de détail
                    String[] cs = ln.trim().split("[,;\\s]+");
                    TutorialDriver.collectionScreen(game, cs.length >= 2 ? cs[1].toUpperCase() : "DAMAGE");
                    continue;
                }
                if (low.startsWith("campfight")) {               // campfight <chapter> <level> → pousse le chooser de campagne
                    String[] cfp = ln.trim().split("[,;\\s]+");
                    int ch = cfp.length >= 2 ? Integer.parseInt(cfp[1].trim()) : 1;
                    int lv = cfp.length >= 3 ? Integer.parseInt(cfp[2].trim()) : 1;
                    TutorialDriver.campFight(game, ch, lv);
                    continue;
                }
                if (low.startsWith("campquick")) {               // campquick → quick fight sur le chooser ouvert → CampaignAttack
                    TutorialDriver.campQuick(game);
                    continue;
                }
                if (low.startsWith("buyavatar ")) {              // buyavatar <ITEM> → BUY_COLLECTION_AVATAR
                    String[] ba = ln.trim().split("[,;\\s]+");
                    if (ba.length >= 2) TutorialDriver.buyAvatar(game, ba[1].toUpperCase());
                    else System.out.println("[buyavatar] usage: buyavatar <ITEM>");
                    continue;
                }
                if (low.startsWith("shopscreen")) {              // shopscreen → ouvre le mastery shop
                    TutorialDriver.shopScreen(game);
                    continue;
                }
                if (low.startsWith("merchantscreen ")) {         // merchantscreen <TYPE> → ouvre un marchand
                    String[] ms = ln.trim().split("[,;\\s]+");
                    if (ms.length >= 2) TutorialDriver.merchantScreen(game, ms[1].toUpperCase());
                    else System.out.println("[merchantscreen] usage: merchantscreen <TYPE>");
                    continue;
                }
                if (low.startsWith("merchantbuy ")) {            // merchantbuy <TYPE> → achète le moins cher abordable
                    String[] mb = ln.trim().split("[,;\\s]+");
                    if (mb.length >= 2) TutorialDriver.merchantBuy(game, mb[1].toUpperCase());
                    else System.out.println("[merchantbuy] usage: merchantbuy <TYPE>");
                    continue;
                }
                if (low.startsWith("merchantrefresh ")) {        // merchantrefresh <TYPE> → rafraîchit le stock
                    String[] mr = ln.trim().split("[,;\\s]+");
                    if (mr.length >= 2) TutorialDriver.merchantRefresh(game, mr[1].toUpperCase());
                    else System.out.println("[merchantrefresh] usage: merchantrefresh <TYPE>");
                    continue;
                }
                if (low.startsWith("portattack ")) {             // portattack <MODE> → combat mode difficulty (PORT)
                    String[] pa = ln.trim().split("[,;\\s]+");
                    if (pa.length >= 2) TutorialDriver.portAttack(game, pa[1].toUpperCase());
                    else System.out.println("[portattack] usage: portattack <MODE>");
                    continue;
                }
                if (low.equals("codebaseattack") || low.startsWith("codebaseattack ")) {  // CODEBASE → combat (chemin réseau réel)
                    String[] cb = ln.trim().split("[,;\\s]+");
                    TutorialDriver.codebaseAttack(game, cb.length >= 2 ? Long.parseLong(cb[1]) : 500L);
                    continue;
                }
                if (low.startsWith("portraid ")) {               // portraid <MODE> [raids] → RAID mode difficulty (PORT)
                    String[] pr = ln.trim().split("[,;\\s]+");
                    if (pr.length >= 2) TutorialDriver.portRaid(game, pr[1].toUpperCase(), pr.length >= 3 ? Integer.parseInt(pr[2]) : 3);
                    else System.out.println("[portraid] usage: portraid <MODE> [raids]");
                    continue;
                }
                if (low.equals("portdouble") || low.startsWith("portdouble ")) {  // portdouble → CLAIM_DOUBLE_PORT_REWARDS
                    TutorialDriver.portDouble(game);
                    continue;
                }
                if (low.equals("portscreen") || low.startsWith("portscreen ")) {   // portscreen → PortChooserScreen (planning)
                    TutorialDriver.portScreen(game);
                    continue;
                }
                if (low.equals("teamtrialsscreen") || low.startsWith("teamtrialsscreen ")) {  // → TeamTrialsChooserScreen (vitrine)
                    TutorialDriver.teamTrialsScreen(game);
                    continue;
                }
                if (low.equals("trialscreen") || low.startsWith("trialscreen ")) {  // FRANCHISE_TRIALS → vitrine TrialEventSubTrialChooserScreen
                    TutorialDriver.trialScreen(game);
                    continue;
                }
                if (low.startsWith("trialsub")) {                 // trialsub <n> → page d'un sous-trial (nœuds de la franchise)
                    String[] ts = ln.trim().split("[,;\\s]+");
                    TutorialDriver.trialSub(game, ts.length >= 2 ? Integer.parseInt(ts[1]) : 1);
                    continue;
                }
                if (low.startsWith("trialattack")) {              // trialattack <sub> <node> → sélecteur de héros du nœud
                    String[] ta = ln.trim().split("[,;\\s]+");
                    TutorialDriver.trialAttack(game, ta.length >= 2 ? Integer.parseInt(ta[1]) : 1,
                                                     ta.length >= 3 ? Integer.parseInt(ta[2]) : 1);
                    continue;
                }
                if (low.equals("trialteam") || low.startsWith("trialteam ")) {  // sélectionne l'équipe franchise + lance le combat
                    TutorialDriver.trialTeam(game);
                    continue;
                }
                if (low.startsWith("portenter ")) {              // portenter <MODE> → ENTER réel (ModePreviewScreen)
                    String[] pe = ln.trim().split("[,;\\s]+");
                    if (pe.length >= 2) TutorialDriver.portEnter(game, pe[1].toUpperCase());
                    else System.out.println("[portenter] usage: portenter <MODE>");
                    continue;
                }
                if (low.startsWith("portpress ")) {              // portpress <MODE> → clic ENTER réel de la vitrine (handleButtonPress)
                    String[] pp = ln.trim().split("[,;\\s]+");
                    if (pp.length >= 2) TutorialDriver.portPress(game, pp[1].toUpperCase());
                    else System.out.println("[portpress] usage: portpress <MODE>");
                    continue;
                }
                if (low.equals("portpreviewattack") || low.startsWith("portpreviewattack ")) {  // preview → bouton ATTACK réel (doAttack)
                    TutorialDriver.portPreviewAttack(game);
                    continue;
                }
                if (low.equals("portteam") || low.startsWith("portteam ")) {       // sélecteur → équipe + combat
                    TutorialDriver.portTeam(game);
                    continue;
                }
                if (low.startsWith("wishtarget ")) {             // wishtarget <HERO> → SET_WISHING_WELL_TARGET_HERO
                    String[] wt = ln.trim().split("[,;\\s]+");
                    if (wt.length >= 2) TutorialDriver.wishTarget(game, wt[1].toUpperCase());
                    else System.out.println("[wishtarget] usage: wishtarget <HERO>");
                    continue;
                }
                if (low.startsWith("wish ") || low.equals("wish")) { // wish [count] → souhait (BuyChests WISH)
                    String[] ws = ln.trim().split("[,;\\s]+");
                    int wc = ws.length >= 2 ? Integer.parseInt(ws[1].trim()) : 1;
                    TutorialDriver.wishOpen(game, wc);
                    continue;
                }
                if (low.startsWith("openchest")) {               // openchest <TYPE> [count] → BuyChests(TYPE) chemin réel
                    String[] cs = ln.trim().split("[,;\\s]+");
                    String type = cs.length >= 2 ? cs[1].trim().toUpperCase() : "GOLD";
                    int cc = cs.length >= 3 ? Integer.parseInt(cs[2].trim()) : 1;
                    TutorialDriver.openChestPilot(game, type, cc);
                    continue;
                }
                if (low.startsWith("wishscreen")) {              // wishscreen → ouvre le puits aux souhaits
                    TutorialDriver.wishScreen(game);
                    continue;
                }
                if (low.startsWith("expreset")) {                // expreset [diff] — RESET de l'expédition → ResetExpedition
                    String[] pr = ln.trim().split("[,;\\s]+");
                    int diff = pr.length >= 2 ? Integer.parseInt(pr[1].trim()) : 1;
                    TutorialDriver.expReset(game, diff);
                    continue;
                }
                if (low.equals("breakerdump")) {                 // breakerdump — dumper la BreakerQuest côté client
                    TutorialDriver.breakerDump(game);
                    continue;
                }
                if (low.equals("breakerfight")) {                // breakerfight — ouvrir l'aperçu du combat de breaker actif
                    TutorialDriver.breakerFight(game);
                    continue;
                }
                if (low.startsWith("postmerc ")) {               // postmerc <hero> — poster un mercenaire (POST_HERO réel)
                    TutorialDriver.postMerc(game, ln.substring(9).trim());
                    continue;
                }
                if (low.startsWith("guilddonate ")) {            // guilddonate <reqID> <memberID> — aider une demande (GuildDonation réel)
                    String[] gp = ln.substring(12).trim().split("[,;\\s]+");
                    if (gp.length >= 2) TutorialDriver.guildDonate(game,
                        Long.parseLong(gp[0].trim()), Long.parseLong(gp[1].trim()));
                    continue;
                }
                if (low.startsWith("fire ")) {                   // fire x,y — clic robuste (InputEvent scene2d sur l'acteur)
                    String[] f = ln.substring(5).trim().split("[,;\\s]+");
                    if (f.length >= 2) TutorialDriver.fireClick(game,
                        Integer.parseInt(f[0].trim()), Integer.parseInt(f[1].trim()));
                    continue;
                }
                if (low.equals("docheckin")) {                   // docheckin — check-in de guilde (chemin d'envoi réel)
                    TutorialDriver.doGuildCheckIn(game);
                    continue;
                }
                if (low.equals("checkinstate")) {                // checkinstate — diagnostic état check-in client
                    TutorialDriver.checkInState(game);
                    continue;
                }
                // --- Ligne "dump" (sans tap) : juste enregistrer l'écran+acteurs sous un point ---
                boolean dumpOnly = low.startsWith("dump");
                if (dumpOnly) ln = ln.substring(4).trim();
                // --- Ligne "hold x,y[,frames]" : press-relâche RÉEL (touchDown maintenant, touchUp après N frames,
                //     comme un doigt). Certains acteurs (ActorGestureListener.tap, grilles de sélection de héros)
                //     ignorent un tap 0-frame → utiliser hold pour eux. ---
                boolean hold = low.startsWith("hold");
                if (hold) ln = ln.substring(4).trim();
                String[] xy = ln.split("[,;\\s]+");
                if (xy.length >= 2) {
                    int cx = Integer.parseInt(xy[0].trim()), cy = Integer.parseInt(xy[1].trim());
                    // MÉTHODE B-bis : on ENREGISTRE ce que le clic va toucher (acteur+ancêtres+listeners+écran)
                    // AVANT de taper → on sait ce que le clic active et quoi câbler dans l'auto-pilote.
                    TutorialDriver.dumpClickTarget(game, cx, cy);
                    if (hold) {
                        int frames = xy.length >= 3 ? Integer.parseInt(xy[2].trim()) : 8;
                        System.out.println("[manualclick] hold (" + cx + "," + cy + ") " + frames + " frames");
                        input.tapHold(cx, cy, frames);
                    } else if (!dumpOnly) {
                        System.out.println("[manualclick] tap (" + cx + "," + cy + ")");
                        input.tap(cx, cy);
                    }
                }
            }
        } catch (Throwable t) { System.out.println("[manualclick] err " + t); }
    }

    private static boolean autoCombatLogged = false;
    private static void enableAutoCombat(GameMain game) {
        try {
            Object sm = game.getClass().getMethod("getScreenManager").invoke(game);
            Object screen = sm.getClass().getMethod("getScreen").invoke(sm);
            if (screen == null) return;
            java.lang.reflect.Method isAuto = findMethod(screen.getClass(), "isAutoAttack");
            java.lang.reflect.Method setAuto = findMethod(screen.getClass(), "setAutoAttack", boolean.class);
            if (isAuto == null || setAuto == null) return;   // pas un écran de combat
            if (!((Boolean) isAuto.invoke(screen))) {
                setAuto.invoke(screen, true);
                if (!autoCombatLogged) { autoCombatLogged = true;
                    System.out.println("[dev] auto-combat d'origine activé (setAutoAttack) sur "
                        + screen.getClass().getSimpleName()); }
            }
        } catch (Throwable ignore) { /* écran sans auto → no-op */ }
    }

    /** Trouve une méthode (par nom+params) en remontant la hiérarchie. */
    private static java.lang.reflect.Method findMethod(Class<?> c, String name, Class<?>... params) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { java.lang.reflect.Method m = k.getDeclaredMethod(name, params); m.setAccessible(true); return m; }
            catch (NoSuchMethodException e) { /* remonter */ }
        }
        return null;
    }

    /** Nom (simple) de l'écran courant, par réflexion — best-effort pour l'étiquette FPS. */
    private static String currentScreen(GameMain game) {
        try {
            Object sm = game.getClass().getMethod("getScreenManager").invoke(game);
            Object screen = sm.getClass().getMethod("getScreen").invoke(sm);
            return screen == null ? "null" : screen.getClass().getSimpleName();
        } catch (Throwable t) { return "?"; }
    }

    private static void wireBridges(GameMain game) {
        callSetter(game, "setNativeAccess", "com.perblue.heroes.INative");
        callSetter(game, "setAnalytics", "com.perblue.heroes.IAnalytics");
        callSetter(game, "setSocialNetworkManager", "com.perblue.heroes.social.SocialNetworkManager");
        callSetter(game, "setSupportManager", "com.perblue.heroes.ISupport");
        callSetter(game, "setTapjoyOfferwall", "com.perblue.heroes.ITapjoyOfferwall");
        callSetter(game, "setPlaybackRewards", "com.perblue.heroes.IPlaybackRewards");
    }

    private static void callSetter(GameMain game, String setter, String ifaceName) {
        try {
            Class<?> iface = Class.forName(ifaceName);
            Object noop = DhBridges.noop(ifaceName);
            game.getClass().getMethod(setter, iface).invoke(game, noop);
        } catch (Throwable t) {
            System.out.println("[launcher] bridge " + setter + " ignoré (" + t + ")");
        }
    }

    private static void preseedConsent(File prefsDir) {
        try {
            DhPreferences p = new DhPreferences(prefsDir, "rpgPrefs");
            p.putInteger("agreedPrivacyPolicyVersion", 999);
            p.putInteger("agreedTermsOfServiceVersion", 999);
            p.flush();
            System.out.println("[launcher] consentement pré-enregistré (rpgPrefs) — clés à vérifier (#CONSENT)");
        } catch (Throwable t) { System.out.println("[launcher] preseedConsent échec: " + t); }
    }

    private static void capture(int w, int h, String out) throws Exception {
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 3);
        glReadPixels(0, 0, w, h, GL_RGB, GL_UNSIGNED_BYTE, buf);
        File f = new File(out);
        if (f.getParentFile() != null) f.getParentFile().mkdirs();
        try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(f))) {
            os.write(("P6\n" + w + " " + h + "\n255\n").getBytes("US-ASCII"));
            byte[] row = new byte[w * 3];
            for (int y = h - 1; y >= 0; y--) { buf.position(y * w * 3); buf.get(row); os.write(row); }
        }
        System.out.println("[launcher] capture: " + f.getPath());
    }

    /**
     * Réécrit ServerType.LIVE vers notre serveur, par réflexion (SANS patch bytecode) :
     *  - gameHost/gamePort = hôte HTTP de login (le client POST {gameHost}:{gamePort}/login) ;
     *  - contentLocation = notre index.txt.
     * gameHost inclut le protocole (ex. "http://127.0.0.1") car l'URL de login est construite
     * comme {gameHost} + ":" + {gamePort} + "/login". Le serveur de JEU (TCP) est renvoyé par
     * la réponse JSON de /login ("data":"host:port"), pas fixé ici.
     */
    private static void maybeRedirectServer(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) return;
        try {
            String host = hostPort;
            int port = 8080;
            int c = hostPort.lastIndexOf(':');
            if (c > 0) { host = hostPort.substring(0, c); port = Integer.parseInt(hostPort.substring(c + 1)); }

            Class<?> st = Class.forName("com.perblue.heroes.ServerType");
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object live = Enum.valueOf((Class) st, "LIVE");
            set(st, live, "gameHost", "http://" + host);
            setInt(st, live, "gamePort", port);
            set(st, live, "contentLocation", "http://" + hostPort + "/live/index.txt");
            System.out.println("[launcher] ServerType.LIVE -> login http://" + hostPort + "/login, content http://" + hostPort + "/live/index.txt");
        } catch (Throwable t) {
            System.out.println("[launcher] redirection ServerType impossible (" + t + ")");
        }
    }

    private static void set(Class<?> cls, Object inst, String field, String v) throws Exception {
        Field f = cls.getDeclaredField(field); f.setAccessible(true); f.set(inst, v);
    }
    private static void setInt(Class<?> cls, Object inst, String field, int v) throws Exception {
        Field f = cls.getDeclaredField(field); f.setAccessible(true); f.setInt(inst, v);
    }
}
