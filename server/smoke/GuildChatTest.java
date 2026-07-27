import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * GUILD #59 — CHAT de guilde : un SendChat du salon GUILD produit un Chat autoritatif (id/expéditeur/guilde),
 * archivé dans la guilde (octets wire) et renvoyé au client ; l'historique survit au round-trip DB et alimente
 * le ChatRoomResync de boot. On vérifie aussi le bornage de l'historique.
 */
public final class GuildChatTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "ChatGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  static SendChat say(String msg) {
    SendChat s = new SendChat();
    s.message = msg; s.room = ChatRoomType.GUILD;
    s.time = new java.util.Date(); s.toUserID = 0L;
    return s;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-guild-chat", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.giveResource(ResourceType.GOLD, 5000);
      long gid = store.nextGuildID(1);
      ServerGuild g = su.createGuild(mk(), gid);
      store.saveGuild(g); store.save(su);

      // 1) Envoi d'un message → Chat autoritatif construit + archivé.
      Chat c1 = su.buildAndStoreGuildChat(g, say("hello guild"));
      if (c1 == null) throw new AssertionError("Chat non construit");
      if (c1.chatID <= 0) throw new AssertionError("chatID non assigné");
      if (!"hello guild".equals(c1.message)) throw new AssertionError("message altéré");
      if (c1.room != ChatRoomType.GUILD) throw new AssertionError("salon incorrect");
      if (c1.sender == null || c1.sender.iD != 1L) throw new AssertionError("expéditeur incorrect");
      if (c1.guildInfo == null || c1.guildInfo.iD != gid) throw new AssertionError("guilde de l'expéditeur incorrecte");
      if (g.guildChatWire.size() != 1) throw new AssertionError("message non archivé");
      System.out.println("[guild] chat #" + c1.chatID + " « " + c1.message + " » archivé (expéditeur "
          + c1.sender.iD + ", guilde " + c1.guildInfo.iD + ")");

      // 2) Message vide → refusé.
      if (su.buildAndStoreGuildChat(g, say("   ")) != null) throw new AssertionError("message vide devrait être refusé");

      // 3) Deuxième message → chatID croissant, historique ordonné.
      Chat c2 = su.buildAndStoreGuildChat(g, say("second"));
      if (c2.chatID <= c1.chatID) throw new AssertionError("chatID devrait croître");
      if (g.guildChatWire.size() != 2) throw new AssertionError("second message non archivé");

      // 4) Round-trip DB : historique + nextChatID persistés.
      store.saveGuild(g);
      ServerGuild rg = store.loadGuild(1, gid);
      if (rg.guildChatWire.size() != 2) throw new AssertionError("historique non persisté");
      if (rg.nextChatID != g.nextChatID) throw new AssertionError("nextChatID non persisté");
      java.util.List<Chat> hist = rg.chatHistory();
      if (hist.size() != 2) throw new AssertionError("relecture historique échouée");
      if (!"hello guild".equals(hist.get(0).message) || !"second".equals(hist.get(1).message))
        throw new AssertionError("ordre/contenu de l'historique incorrect après relecture");
      System.out.println("[guild] round-trip DB OK : " + hist.size() + " message(s), nextChatID=" + rg.nextChatID);

      // 5) SocialHistory de boot contient l'historique du salon GUILD (message dédié, mis en tampon côté client).
      SocialHistory sh = su.buildGuildSocialHistory(rg);
      Object listObj = sh.chatLists.get(ChatRoomType.GUILD);
      if (!(listObj instanceof ChatList)) throw new AssertionError("SocialHistory sans ChatList GUILD");
      ChatList list = (ChatList) listObj;
      if (list.chats.size() != 2) throw new AssertionError("SocialHistory : historique incomplet");
      System.out.println("[guild] SocialHistory(GUILD) OK : " + list.chats.size() + " message(s)");

      // 6) Bornage de l'historique (MAX_CHAT_HISTORY).
      for (int i = 0; i < ServerGuild.MAX_CHAT_HISTORY + 20; i++) su.buildAndStoreGuildChat(rg, say("m" + i));
      if (rg.guildChatWire.size() != ServerGuild.MAX_CHAT_HISTORY)
        throw new AssertionError("historique non borné à " + ServerGuild.MAX_CHAT_HISTORY);
      System.out.println("[guild] bornage historique OK : " + rg.guildChatWire.size());

      System.out.println("GUILD CHAT TEST OK");
    }
  }
}
