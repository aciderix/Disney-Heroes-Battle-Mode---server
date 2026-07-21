import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.IHero;
import com.perblue.heroes.game.logic.HeroHelper;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * SKILL_UPGRADE — montée du niveau d'une compétence d'un héros. Vérifie le handler serveur (logique du jeu
 * {@code HeroHelper.upgradeSkill}) : le client envoie {@code UPGRADE_SKILL{hero, extra={SKILL=<slot>, COUNT}}}.
 * On prouve : (1) le niveau du skill MONTE ; (2) GOLD + SKILL_POINTS sont DÉBITÉS ; (3) ça PERSISTE au reload
 * wire ; (4) anti-triche RÉEL : sans SKILL_POINTS l'upgrade est REFUSÉ (niveau inchangé).
 */
public final class SkillUpgradeTest {

  static User bind(ServerUser su) {
    BootData bd = su.bootData();
    User u = com.perblue.heroes.game.ClientNetworkStateConverter.getUser(bd.userInfo, bd.userExtra, "sk");
    var iu = com.perblue.heroes.game.ClientNetworkStateConverter.getIndividualUser(
        bd.individualUserExtra, 1L, bd.userInfo.diamonds, "sk");
    ServerContext.bind(u, iu);
    ServerContext.bindBattlePass(su.refreshBattlePass());
    return u;
  }

  static Action upgrade(UnitType hero, SkillSlot slot, int count) {
    Action m = new Action(); m.command = CommandType.UPGRADE_SKILL; m.heroType = hero;
    m.extra = new java.util.EnumMap<>(ActionExtraType.class);
    m.extra.put(ActionExtraType.SKILL, slot.name());
    m.extra.put(ActionExtraType.COUNT, Integer.toString(count));
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);
    // TL65 (le gate SKILL_UPGRADE = TL8) + un héros avec de la marge de niveau (skill plafonné par le niveau).
    BootData bd0 = su.bootData();
    bd0.userInfo.basicInfo.teamLevel = 65;
    UnitType HERO = UnitType.RALPH;
    su.grantHero(HERO, Rarity.ORANGE, 40, 5);

    // Créditer GOLD + SKILL_POINTS (ressources this.extra) pour permettre l'upgrade.
    User u = bind(su);
    if (u.getHero(HERO) == null) throw new AssertionError("héros " + HERO + " non créé");
    u.getIndividual().setResource(u, ResourceType.GOLD, 1_000_000_000L, "test");
    u.getIndividual().setResource(u, ResourceType.SKILL_POINTS, 100_000L, "test");

    // Trouver un slot RÉELLEMENT montable (selon la logique du jeu canUpgradeSkill).
    SkillSlot slot = null;
    for (SkillSlot s : new SkillSlot[]{SkillSlot.WHITE, SkillSlot.GREEN, SkillSlot.BLUE, SkillSlot.PURPLE, SkillSlot.RED}) {
      if (HeroHelper.canUpgradeSkill(HERO, s, u)) { slot = s; break; }
    }
    if (slot == null) throw new AssertionError("aucun slot de compétence montable sur " + HERO + " (état/gate)");

    IHero hero = u.getHero(HERO);
    int s0 = hero.getSkillLevel(slot);
    long gold0 = u.getResource(ResourceType.GOLD);
    long sp0 = u.getResource(ResourceType.SKILL_POINTS);
    System.out.println("[skill] AVANT : " + HERO + " " + slot + " niveau=" + s0 + " GOLD=" + gold0 + " SKILL_POINTS=" + sp0);

    boolean applied = su.applyAction(upgrade(HERO, slot, 1));
    if (!applied) throw new AssertionError("UPGRADE_SKILL aurait dû s'appliquer");

    User u1 = bind(su);
    int s1 = u1.getHero(HERO).getSkillLevel(slot);
    long gold1 = u1.getResource(ResourceType.GOLD);
    long sp1 = u1.getResource(ResourceType.SKILL_POINTS);
    System.out.println("[skill] APRÈS : niveau=" + s1 + " GOLD=" + gold1 + " SKILL_POINTS=" + sp1
        + " (débit GOLD=" + (gold0 - gold1) + " SKILL_POINTS=" + (sp0 - sp1) + ")");
    if (s1 != s0 + 1) throw new AssertionError("le niveau du skill aurait dû passer de " + s0 + " à " + (s0 + 1));
    if (gold1 >= gold0) throw new AssertionError("le GOLD aurait dû être débité");
    if (sp1 >= sp0) throw new AssertionError("les SKILL_POINTS auraient dû être débités");

    // PERSISTANCE : reload wire → niveau conservé.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    int s2 = bind(reloaded).getHero(HERO).getSkillLevel(slot);
    System.out.println("[skill] après reload wire → niveau=" + s2);
    if (s2 != s1) throw new AssertionError("le niveau du skill aurait dû PERSISTER (" + s1 + "), obtenu " + s2);

    // ANTI-TRICHE : sans SKILL_POINTS, l'upgrade est REFUSÉ (niveau inchangé).
    User u2 = bind(su);
    u2.getIndividual().setResource(u2, ResourceType.SKILL_POINTS, 0L, "test");
    boolean refused = su.applyAction(upgrade(HERO, slot, 1));
    int s3 = bind(su).getHero(HERO).getSkillLevel(slot);
    System.out.println("[skill] anti-triche (0 SKILL_POINTS) → appliqué=" + refused + " niveau=" + s3);
    if (refused) throw new AssertionError("l'upgrade sans SKILL_POINTS aurait dû être REFUSÉ");
    if (s3 != s1) throw new AssertionError("le niveau ne devait PAS changer après un refus");

    System.out.println("[skill] OK — UPGRADE_SKILL : niveau monté + GOLD/SKILL_POINTS débités + persiste + anti-triche (NOT_ENOUGH_SKILL_POINTS)");
  }
}
