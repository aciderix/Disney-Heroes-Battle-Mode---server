import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #70 — REPRISE D'UNE GUILDE INACTIVE. Un gradé peut prendre la direction si le CHEF est resté inactif
 * assez longtemps. Le SEUIL vient de la logique du jeu {@code GuildHelper.getClaimLeaderInactiveTime(rôle)} :
 * CHAMPION 7 jours, OFFICER 21 jours, autres rôles interdits (−1). L'inactivité se mesure sur
 * {@code BasicUserInfo.userLastActive} du chef (actualisé à chaque connexion).
 *
 * Prouve : refus si le rôle n'y donne pas droit, refus si le chef est encore actif, seuils DIFFÉRENTS selon le
 * rôle, transfert effectif de la direction (rôles + tête du roster), et persistance.
 */
public final class GuildClaimInactiveTest {
  static final long DAY = 86_400_000L;

  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "InactiveGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    java.io.File tmp = java.io.File.createTempFile("dh-claim-guild", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // Les seuils lus sont bien ceux du jeu.
      long champ = com.perblue.heroes.game.logic.GuildHelper.getClaimLeaderInactiveTime(GuildRole.CHAMPION);
      long offi  = com.perblue.heroes.game.logic.GuildHelper.getClaimLeaderInactiveTime(GuildRole.OFFICER);
      long memb  = com.perblue.heroes.game.logic.GuildHelper.getClaimLeaderInactiveTime(GuildRole.MEMBER);
      if (champ != 7 * DAY) throw new AssertionError("CHAMPION attendu 7 j, lu " + (champ / DAY));
      if (offi != 21 * DAY) throw new AssertionError("OFFICER attendu 21 j, lu " + (offi / DAY));
      if (memb > 0) throw new AssertionError("MEMBER ne devrait pas pouvoir reprendre (lu " + memb + ")");
      System.out.println("[guild] seuils du jeu : CHAMPION " + (champ / DAY) + " j, OFFICER "
          + (offi / DAY) + " j, MEMBER interdit");

      // Guilde : chef (1) + un CHAMPION (2) + un MEMBER (3).
      ServerUser leader = ServerUser.newPlayer(1L, 1);
      leader.grantHero(UnitType.RALPH); leader.giveResource(ResourceType.GOLD, 5000);
      ServerGuild g = leader.createGuild(mk(), store.nextGuildID(1));
      ServerUser champion = ServerUser.newPlayer(2L, 1);
      ServerUser member = ServerUser.newPlayer(3L, 1);
      champion.joinGuildAs(g.guildID, GuildRole.CHAMPION);
      member.joinGuildAs(g.guildID, GuildRole.MEMBER);
      g.memberIDs.add(2L); g.memberIDs.add(3L);
      store.save(champion); store.save(member);

      // Le chef vient de se connecter → aucune reprise possible.
      leader.basicInfo().userLastActive = now;
      String r = champion.claimInactiveGuild(g, leader, now);
      if (r == null) throw new AssertionError("chef actif : la reprise devrait être refusée");
      System.out.println("[guild] chef actif → refusé (" + r + ")");

      // Chef inactif depuis 10 jours : le MEMBER ne peut toujours pas (rôle interdit).
      leader.basicInfo().userLastActive = now - 10 * DAY;
      r = member.claimInactiveGuild(g, leader, now);
      if (r == null) throw new AssertionError("un MEMBER ne doit jamais pouvoir reprendre");
      System.out.println("[guild] MEMBER après 10 j → refusé (rôle sans droit)");

      // …mais le CHAMPION oui (10 j ≥ 7 j).
      r = champion.claimInactiveGuild(g, leader, now);
      if (r != null) throw new AssertionError("CHAMPION après 10 j devrait pouvoir reprendre : " + r);
      if (champion.currentGuildRole() != GuildRole.RULER)
        throw new AssertionError("le demandeur devrait être RULER, obtenu " + champion.currentGuildRole());
      if (leader.currentGuildRole() != GuildRole.MEMBER)
        throw new AssertionError("l'ancien chef devrait être MEMBER, obtenu " + leader.currentGuildRole());
      if (g.memberIDs.isEmpty() || g.memberIDs.get(0) != 2L)
        throw new AssertionError("le nouveau chef doit être en TÊTE du roster, obtenu " + g.memberIDs);
      System.out.println("[guild] CHAMPION après 10 j → reprise ACCORDÉE (RULER, tête du roster ; "
          + "ancien chef rétrogradé MEMBER)");

      // Persistance du transfert.
      store.saveGuild(g); store.save(champion); store.save(leader);
      ServerGuild rg = store.loadGuild(1, g.guildID);
      ServerUser rchamp = store.loadIfExists(2L, 1), rleader = store.loadIfExists(1L, 1);
      if (rg.memberIDs.get(0) != 2L) throw new AssertionError("roster non persisté");
      if (rchamp.currentGuildRole() != GuildRole.RULER) throw new AssertionError("nouveau rôle non persisté");
      if (rleader.currentGuildRole() != GuildRole.MEMBER) throw new AssertionError("rétrogradation non persistée");
      System.out.println("[guild] round-trip DB OK : direction transférée et persistée");

      // Seuil PLUS LONG pour OFFICER : 10 j ne suffisent pas (21 j requis).
      ServerUser officer = ServerUser.newPlayer(4L, 1);
      officer.joinGuildAs(g.guildID, GuildRole.OFFICER);
      g.memberIDs.add(4L);
      // Le nouveau chef (champion) est actif depuis 10 jours seulement.
      champion.basicInfo().userLastActive = now - 10 * DAY;
      r = officer.claimInactiveGuild(g, champion, now);
      if (r == null) throw new AssertionError("OFFICER après 10 j (< 21 j) ne devrait pas pouvoir reprendre");
      System.out.println("[guild] OFFICER après 10 j → refusé (seuil 21 j du jeu respecté)");

      System.out.println("GUILD CLAIM INACTIVE TEST OK");
    }
  }
}
