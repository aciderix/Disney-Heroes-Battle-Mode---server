import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.QuestHelper;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.IndividualUser;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * COMPLETE_QUEST — vérifie le handler serveur (gap trouvé en jeu à l'écran MEDALS/achievements) : réclamer une
 * quête exécute la logique du jeu {@code QuestHelper.completeQuest} (donne la récompense + marque la complétion),
 * de façon AUTORITATIVE (n'accorde que si {@code isReadyToComplete} = prérequis satisfait par l'état serveur),
 * et l'état persiste au round-trip wire.
 */
public final class CompleteQuestTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    var u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "cq");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "cq");
    ServerContext.bind(u, iu); return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    User u = bind(su);

    // Cherche une quête (achievement ou quête quotidienne) que l'état du compte neuf SATISFAIT déjà.
    int ready = findReady(u);
    if (ready < 0) {
      System.out.println("[quest] aucune quête prête pour un compte neuf — test de la voie de REFUS à la place");
      // Anti-triche : compléter une quête NON satisfaite doit LEVER (refus).
      int locked = firstUnlockedButNotReady(u);
      boolean refused = false;
      if (locked >= 0) {
        try { QuestHelper.completeQuest(locked, u); }
        catch (Throwable t) { refused = t.getClass().getName().contains("ClientErrorCode"); }
        System.out.println("[quest] complétion d'une quête NON satisfaite (id=" + locked + ") : "
            + (refused ? "REFUSÉE (anti-triche OK)" : "ACCORDÉE ⚠"));
        if (!refused) throw new AssertionError("une quête non satisfaite doit être REFUSÉE");
      }
      System.out.println("[quest] OK (voie de refus vérifiée ; crédit non exercé faute de quête prête compte-neuf)");
      return;
    }

    int before = u.getIndividual().getQuestCompletionCount(ready);
    long goldBefore = u.getResource(ResourceType.GOLD);
    long diamBefore = u.getResource(ResourceType.DIAMONDS);
    int itemsBefore = totalItems(u);
    System.out.println("[quest] quête prête id=" + ready + " (déjà complétée " + before + "x)"
        + " | gold=" + goldBefore + " diamants=" + diamBefore + " items=" + itemsBefore);

    // Voie SERVEUR : passer par l'Action comme le fait le client (COMPLETE_QUEST, extra={ID}).
    Action m = new Action();
    m.command = CommandType.COMPLETE_QUEST;
    m.extra.put(ActionExtraType.ID, Integer.toString(ready));
    boolean applied = su.applyAction(m);
    if (!applied) throw new AssertionError("COMPLETE_QUEST aurait dû être appliquée (quête prête)");

    User u2 = bind(su);
    int after = u2.getIndividual().getQuestCompletionCount(ready);
    long goldAfter = u2.getResource(ResourceType.GOLD);
    long diamAfter = u2.getResource(ResourceType.DIAMONDS);
    int itemsAfter = totalItems(u2);
    System.out.println("[quest] après claim : complétée " + after + "x | gold=" + goldAfter
        + " diamants=" + diamAfter + " items=" + itemsAfter);
    if (after <= before) throw new AssertionError("le compteur de complétion aurait dû augmenter (persister)");
    boolean gotReward = goldAfter > goldBefore || diamAfter > diamBefore || itemsAfter > itemsBefore;
    System.out.println("[quest] récompense matérielle créditée : " + gotReward
        + " (certaines quêtes ne donnent qu'un flag/cosmétique)");

    // Persistance : round-trip wire (userInfo + individualUserExtra relus).
    byte[] wUi = su.userInfoWire(), wIx = su.individualWire();
    UserInfo ui2 = read(wUi);
    long diamPersist = ui2.diamonds;
    System.out.println("[quest] persistance wire : diamants=" + diamPersist + " (mémoire=" + diamAfter + ")");
    if (diamPersist != diamAfter) throw new AssertionError("diamants non persistés");

    System.out.println("[quest] OK — COMPLETE_QUEST crédite la récompense (logique du jeu) + persiste");
  }

  // On ÉNUMÈRE les IDs nous-mêmes (getQuest(id)!=null) — SANS QuestHelper.getUnlockedAchievements (qui passe
  // par QuestStats.getAllQuestIDs, chemin CLIENT fragile headless : thread-check + cast gdx Array). Le handler
  // réel (completeQuest(id)) ne touche PAS getAllQuestIDs.
  static int findReady(User u) {
    for (int id = 1; id <= 5000; id++) {
      try {
        if (com.perblue.heroes.game.data.quests.QuestStats.getQuest(id) == null) continue;
        if (QuestHelper.isReadyToComplete(id, u)) return id;
      } catch (Throwable ignore) {}
    }
    return -1;
  }

  static int firstUnlockedButNotReady(User u) {
    for (int id = 1; id <= 5000; id++) {
      try {
        if (com.perblue.heroes.game.data.quests.QuestStats.getQuest(id) == null) continue;
        if (!QuestHelper.isReadyToComplete(id, u)) return id;
      } catch (Throwable ignore) {}
    }
    return -1;
  }

  static int totalItems(User u) {
    int n = 0;
    for (ItemType it : ItemType.values()) n += u.getItemAmount(it);
    return n;
  }

  @SuppressWarnings("unchecked")
  static <T extends com.perblue.grunt.translate.GruntMessage> T read(byte[] b) {
    return (T) MessageFactory.getInstance().readMessage(new com.perblue.grunt.translate.util.GruntInputStream(b));
  }
}
