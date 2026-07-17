import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.game.objects.*;
import com.perblue.heroes.game.logic.CampaignLootHelper;
import com.perblue.heroes.game.specialevent.SpecialEventSnapshot;
import dhserver.*;

import java.util.*;

/**
 * #25 — Certification du LOOT AUTORITAIRE (docs/SERVER_PLAN §E, PRINCIPLES §3/§4bis). Prouve que le serveur
 * REPRODUIT à l'identique le butin que le CLIENT roule, à partir de la SEULE graine LOOT (flux RNG séparé du
 * combat) — donc <b>sans simuler le combat</b>. Patron oracle-certification (comme spine) :
 * <ol>
 *   <li><b>Référence CLIENT</b> : on rejoue la séquence EXACTE relevée au bytecode de
 *       {@code CampaignAttackScreen} (2ᵉ ctor) : {@code user.resetRandom(LOOT)} puis
 *       {@code CampaignLootHelper.getLoot(user, type, 0, chapter, level, NONE, guildPerks, true).combinedLoot}.</li>
 *   <li><b>Serveur</b> : on annonce la graine via {@code Action SET_SEED{LOOT}} (comme le vrai client), puis
 *       {@code ServerUser.computeAuthoritativeLoot} roule le butin.</li>
 *   <li>Les deux multisets (item/ressource → quantité) doivent être <b>IDENTIQUES</b> pour plusieurs graines.</li>
 * </ol>
 * Match ⇒ le serveur peut créditer SON tirage (autoritaire, anti-triche) sans faire confiance à
 * {@code m.lootEarned}, et sans le coût d'une re-simulation de combat.
 */
public final class LootAuthoritativeTest {

  static Map<String, Long> multiset(List drops) {
    Map<String, Long> map = new TreeMap<>();
    if (drops == null) return map;
    for (Object o : drops) {
      RewardDrop d = (RewardDrop) o;
      map.merge("I:" + d.itemType + "/R:" + d.resourceType, d.quantity, Long::sum);
    }
    return map;
  }

  /** Rejoue la séquence CLIENT (CampaignAttackScreen) : graine LOOT → getLoot(...).combinedLoot. */
  @SuppressWarnings("unchecked")
  static List clientLoot(ServerUser su, CampaignType type, int chapter, int level, long seed) {
    BootData bd = su.bootData();
    User user = ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "ref");
    IndividualUser iu = ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "ref");
    ServerContext.bind(user, iu);
    iu.setSeed(RandomSeedType.LOOT, seed, "");
    user.resetRandom(RandomSeedType.LOOT);
    GuildInfoPerkProvider perks = new GuildInfoPerkProvider(com.perblue.heroes.DH.app.getYourGuildInfo());
    CampaignLootHelper.CampaignLoot cl =
        CampaignLootHelper.getLoot(user, type, 0, chapter, level, SpecialEventSnapshot.NONE, perks, true);
    return cl == null ? new ArrayList() : cl.combinedLoot;
  }

  static void setServerSeed(ServerUser su, long seed) {
    Action a = new Action();
    a.command = CommandType.SET_SEED;
    a.extra = new EnumMap<>(ActionExtraType.class);
    a.extra.put(ActionExtraType.ID, Long.toString(seed));
    a.extra.put(ActionExtraType.TYPE, RandomSeedType.LOOT.name());
    su.applyAction(a);
  }

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    ServerContext.init();
    CampaignType type = CampaignType.NORMAL;
    int chapter = 1, level = 1;

    int matches = 0;
    long[] seeds = { 123456789L, 42L, 999L, 20260717L, -777L };
    for (long seed : seeds) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH);
      su.grantHero(UnitType.ELASTIGIRL);
      su.grantHero(UnitType.FROZONE);

      // 1) Référence CLIENT (séquence bytecode exacte).
      List client = clientLoot(su, type, chapter, level, seed);

      // 2) Serveur : annonce la graine (Action SET_SEED), puis roule le loot autoritaire.
      setServerSeed(su, seed);
      CampaignAttack m = new CampaignAttack();
      m.campaignType = type; m.chapter = chapter; m.level = level; m.stagesCleared = 1;
      List server = su.computeAuthoritativeLoot(m);

      Map<String, Long> cs = multiset(client), ss = multiset(server);
      boolean ok = cs.equals(ss);
      System.out.println("[#25] graine=" + seed + " : " + (ok ? "MATCH ✅" : "DIVERGE ❌")
          + "  serveur=" + ss + "  client=" + cs);
      if (ok) matches++;
      else throw new AssertionError("loot autoritaire DIVERGE du client (graine " + seed + ") : "
          + "serveur=" + ss + " client=" + cs);
    }

    // Discrimination : au moins 2 graines doivent donner des butins DIFFÉRENTS (sinon le test ne prouve rien).
    Set<String> distinct = new HashSet<>();
    for (long seed : seeds) {
      ServerUser su = ServerUser.newPlayer(1L, 1);
      su.grantHero(UnitType.RALPH); su.grantHero(UnitType.ELASTIGIRL); su.grantHero(UnitType.FROZONE);
      distinct.add(multiset(clientLoot(su, type, chapter, level, seed)).toString());
    }
    System.out.println("[#25] " + matches + "/" + seeds.length + " graines MATCH ; "
        + distinct.size() + " butins distincts (sensibilité à la graine)");
    if (distinct.size() < 2)
      throw new AssertionError("toutes les graines donnent le même butin — le test ne discrimine pas");

    System.out.println("LOOT AUTHORITATIVE TEST OK (#25) — le serveur reproduit le loot client à partir de la "
        + "seule graine LOOT, sans simuler le combat → butin AUTORITAIRE anti-triche pour un coût nul.");
  }
}
