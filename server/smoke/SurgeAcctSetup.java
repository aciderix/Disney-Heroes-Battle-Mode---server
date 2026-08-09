import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * OUTIL DEV (#72) — reconstruit un compte APTE À SURGE dans une DB serveur : TL100, un roster de 5 héros, et une
 * GUILDE (le compte en devient le chef). Reproduit l'état des anciens snapshots (perdus car {@code /server/data/}
 * est git-ignoré) pour la vérif EN JEU de SURGE. Usage : {@code SurgeAcctSetup [chemin.db]}
 * (défaut {@code server/data/dh-server.db}). Idempotent-ish : réutilise le compte/guilde s'ils existent.
 */
public final class SurgeAcctSetup {
  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = "Surge test"; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    try (UserStore store = new UserStore(db)) {
      ServerUser u = store.loadOrCreate(1L, 1);
      BootData bd = u.bootData();
      bd.userInfo.basicInfo.teamLevel = 100;                 // débloque SURGE_OBJECTIVES (TL32)
      UnitType[] team = { UnitType.MAUI, UnitType.STITCH, UnitType.HERCULES, UnitType.GENIE, UnitType.SULLEY };
      for (UnitType t : team) {
        try { if (u.gameUser().getHero(t) == null) u.grantHero(t, Rarity.ORANGE, 40, 5); }
        catch (Throwable ex) { System.out.println("[setup] héros " + t + " refusé : " + ex); }
      }
      u.giveResource(ResourceType.GOLD, 50000);              // pour createGuild

      // TUTORIEL COMPLET : sans ça le client refuse de naviguer (canNavigateTo=false, verrou tuto). Un compte
      // frais a tous les actes NEW_USER à step 0 ; on les porte à leur step MAX du registre du jeu
      // (TutorialHelper.getMaxStep) → isActCompleted=true, comme un compte ayant joué l'intro. §3 (registre du jeu).
      com.perblue.heroes.game.objects.User gu = u.gameUser();
      int done = 0;
      for (Object o : bd.individualUserExtra.tutorialActs) {
        TutorialAct t = (TutorialAct) o;
        try {
          com.perblue.heroes.game.objects.IUserTutorialAct ita = gu.getTutorialAct(t.type);
          int max = com.perblue.heroes.game.tutorial.TutorialHelper.getMaxStep(ita);
          t.step = max; t.maxStep = max;
          if (com.perblue.heroes.game.tutorial.TutorialHelper.isActCompleted(gu, t.type)) done++;
        } catch (Throwable ex) { /* acte hors registre → ignoré */ }
      }
      System.out.println("[setup] tutoriel : " + done + "/" + bd.individualUserExtra.tutorialActs.size() + " actes complétés");
      store.save(u);

      if (!u.inGuild()) {
        ServerGuild g = u.createGuild(mk("Surge Testers"), store.nextGuildID(1));
        store.save(u); store.saveGuild(g);
        System.out.println("[setup] guilde créée id=" + g.guildID);
      } else {
        System.out.println("[setup] déjà en guilde id=" + u.currentGuildID());
      }
      ServerUser v = store.loadOrCreate(1L, 1);
      System.out.println("[setup] OK db=" + db + " TL=" + v.bootData().userInfo.basicInfo.teamLevel
          + " héros=" + v.heroCount() + " inGuild=" + v.inGuild() + " guildID=" + v.currentGuildID());
    }
  }
}
