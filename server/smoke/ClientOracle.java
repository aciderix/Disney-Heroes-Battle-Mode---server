import com.perblue.heroes.game.logic.QuestHelper;
import com.perblue.heroes.game.objects.IUser;

/**
 * ORACLE CLIENT HEADLESS (#74, levier B — cf. docs/HEADLESS_VERIFICATION.md).
 *
 * <p>Exécute, sur NOTRE {@code User} (l'état/réponse que le serveur produit), des vérifications que le CLIENT
 * ferait lui-même — SANS GL, en appelant le VRAI code du jeu (§3 : on exécute, on n'invente pas). But : attraper
 * AVANT l'in-game les états serveur qui feraient **planter ou refuser** le client.
 *
 * <p>Exemple : le crash R1 (g55) était `QuestHelper.hasUnclaimedDailyQuests` →
 * `HasEnoughCollectionHeroes.isSatisfied` → `list.get(hero.getStars())` hors bornes. Ces méthodes prennent un
 * {@code IUser} et tournent headless → l'oracle les exécute et signale le crash sans passer par le rendu.
 *
 * <p>Usage test : {@code ClientOracle.assertClientRenders(serverUser.gameUser());}
 */
public final class ClientOracle {

  /** Une vérif cliente : un nom + une action qui LÈVE si le client planterait/refuserait sur cet état. */
  interface Check { String name(); void run(IUser u); }

  /** Vérifs « le hub rend sans planter » qui tournent PROPREMENT sur un User headless (B2). VRAI code client.
   *  Les vérifs de DAILY QUESTS ({@code getUnlockedDailyQuests}/{@code hasUnclaimedDailyQuests}) — LA voie du
   *  crash R1 (g55 : {@code HasEnoughCollectionHeroes.isSatisfied} → {@code list.get(hero.getStars())} hors
   *  bornes) — lisent les {@code IUserChallengeData} ; elles NPEaient tant que notre User headless n'avait pas
   *  ce conteneur. Depuis la fixture challenge-data (#74 B2b : {@code ServerContext} pose
   *  {@code DH.app.userChallengeData}), elles tournent → l'oracle attrape le crash R1 headless. */
  static final Check[] HUB_RENDER = {
    check("QuestHelper.getUnlockedAchievements", u -> QuestHelper.getUnlockedAchievements(u)),
    check("QuestHelper.getWeeklyDailyQuestsComplete", u -> QuestHelper.getWeeklyDailyQuestsComplete(u)),
    check("QuestHelper.getUnlockedDailyQuests", u -> QuestHelper.getUnlockedDailyQuests(u)),   // ← voie du crash R1
    check("QuestHelper.hasUnclaimedDailyQuests", u -> QuestHelper.hasUnclaimedDailyQuests(u)),
  };

  /** Simule « je suis le thread principal » : les stats du jeu ({@code QuestStats.getDailyQuestIDs}…) ont un
   *  garde `currentThread == DH.app.getMainThread()` (renvoie le static {@code GameMain.MAIN_THREAD}). Headless,
   *  ce champ est nul → garde toujours violé → repli cassé. On pose le champ sur le thread courant : shim de
   *  HARNAIS (couche plateforme, §4), pas de logique de jeu ; n'affecte que l'exécution de l'oracle. */
  static void becomeMainThread() {
    try {
      java.lang.reflect.Field f = com.perblue.heroes.GameMain.class.getDeclaredField("MAIN_THREAD");
      f.setAccessible(true); f.set(null, Thread.currentThread());
    } catch (Throwable ignore) {}
  }

  /** Assertion : le CLIENT rendrait cet état sans planter. Lève un AssertionError listant les vérifs en échec. */
  public static void assertClientRenders(IUser u) {
    becomeMainThread();
    // Pose les structures de RENDU CLIENT du hub (conteneur de défis + catalogue IAP, vides) que le serveur
    // n'a pas — cf. ServerContext.installClientHubRenderFixtures (RÉSERVÉ à l'oracle, jamais au bind serveur).
    dhserver.ServerContext.installClientHubRenderFixtures();
    java.util.List<String> failures = new java.util.ArrayList<>();
    for (Check c : HUB_RENDER) {
      try { c.run(u); }
      catch (Throwable t) { failures.add(c.name() + " → " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
    }
    if (!failures.isEmpty())
      throw new AssertionError("ClientOracle : le CLIENT planterait/refuserait sur cet état serveur :\n  - "
          + String.join("\n  - ", failures));
  }

  interface Body { void run(IUser u); }
  static Check check(String name, Body b) {
    return new Check() { public String name() { return name; } public void run(IUser u) { b.run(u); } };
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────────────
  // Levier B4 — MIROIR DES VALIDATIONS D'ENVOI (cf. docs/HEADLESS_VERIFICATION.md).
  //
  // Avant d'émettre une action, le CLIENT exécute une validation ; si elle LÈVE (typiquement
  // ClientErrorCodeException), le client REFUSE d'envoyer. L'oracle rejoue CE MÊME prédicat du jeu sur NOTRE
  // état reconstruit → répond « le client enverrait-il / planterait-il ? » SANS in-game. Deux défauts attrapés :
  //   • un état serveur qui ferait REFUSER une action LÉGITIME (le joueur honnête serait bloqué) ;
  //   • une faille ANTI-TRICHE (le serveur accepte ce que le client aurait refusé).
  // ⚠️ N'utiliser que des PRÉDICATS PURS (sans effet de bord). JAMAIS un rappel d'action qui CONSOMME l'état
  //    (ex. WarClientHelper.doStartWarAttack — g45 : le pré-appeler cassait le vrai envoi).
  // ─────────────────────────────────────────────────────────────────────────────────────────────────────

  /** Une validation d'envoi cliente : lève si le client refuserait/planterait sur cet état. */
  public interface SendValidation { void run(IUser u); }

  /** Le client ENVERRAIT (la validation passe sur cet état). Lève un AssertionError sinon (blocage indu). */
  public static void assertClientWouldSend(String action, IUser u, SendValidation v) {
    becomeMainThread();
    try { v.run(u); }
    catch (Throwable t) {
      throw new AssertionError("SendValidation : le client REFUSERAIT/planterait un envoi LÉGITIME « " + action
          + " » sur cet état serveur → " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  /** Le client REFUSERAIT (la validation LÈVE), la raison contenant {@code expectReason} (vide = n'importe
   *  quel refus). Un envoi qui PASSE ici = faille anti-triche (état serveur trop permissif). */
  public static void assertClientWouldRefuse(String action, IUser u, String expectReason, SendValidation v) {
    becomeMainThread();
    try { v.run(u); }
    catch (Throwable t) {
      String msg = String.valueOf(t.getMessage());
      if (expectReason != null && !expectReason.isEmpty() && !msg.contains(expectReason))
        throw new AssertionError("SendValidation : « " + action + " » refusé mais pour une MAUVAISE raison — "
            + "attendu contient « " + expectReason + " », obtenu « " + msg + " »");
      return;   // refus attendu (le client n'enverrait pas) — comportement correct
    }
    throw new AssertionError("SendValidation : le client aurait REFUSÉ « " + action
        + " » mais la validation a PASSÉ → faille anti-triche (état serveur trop permissif)");
  }

  /** Self-test (régression) : un compte NEUF doit passer toutes les vérifs de rendu du hub. */
  public static void main(String[] a) throws Exception {
    dhserver.ServerContext.init();
    dhserver.ServerUser u = dhserver.ServerUser.newPlayer(1L, 1);
    u.bindGameContext();
    assertClientRenders(u.gameUser());
    System.out.println("[clientoracle] vérifs de rendu du hub OK (compte neuf) — oracle opérationnel (#74 B1/B2)");
  }
}
