import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.UnitData;
import dhserver.*;

import java.util.*;

/**
 * ARÈNE — RÉCOMPENSES & COURRIERS (les 3 compléments d'écran). Prouve headless, sur le VRAI code du jeu :
 *  (A) XP d'ARÈNE : {@code resolveArenaAttack} accorde de l'XP aux héros ATTAQUANTS ({@code ArenaHelper.giveArenaEXP})
 *      → l'XP d'un héros AUGMENTE, et c'est PERSISTÉ (round-trip wire) ;
 *  (B) RAPPORT DE DÉFENSE : un courrier {@code FIGHT_PIT_DEFENSE} déposé chez le défenseur survit au round-trip wire ;
 *  (C) COURRIER ADMIN : un {@code SYSTEM_MESSAGE} avec récompense en pièce jointe (diamants) survit + est livré.
 */
public final class ArenaRewardsTest {

  static final int SHARD = 1;
  static final ArenaType TYPE = ArenaType.FIGHT_PIT;
  static final UnitType[] ATT = {UnitType.RALPH, UnitType.HERCULES, UnitType.GENIE, UnitType.STITCH, UnitType.WOODY};

  static ServerUser reload(ServerUser su) throws Exception {
    ServerUser r = ServerUser.fromWire(su.userID, su.shardID, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    r.setMailWire(su.mailWire());
    return r;
  }

  static AttackLineupSummary attackTeam() {
    AttackLineupSummary team = new AttackLineupSummary();
    team.units = new ArrayList<>();
    for (UnitType t : ATT) {
      AttackUnitSummary u = new AttackUnitSummary();
      u.type = t; u.rarity = Rarity.WHITE; u.survived = true; u.power = 100;
      team.units.add(u);
    }
    return team;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // ---------- (A) XP d'arène ----------
    ServerUser attacker = ServerUser.newPlayer(1L, SHARD);
    for (UnitType t : ATT) attacker.grantHero(t);
    ServerUser defender = ServerUser.newPlayer(2L, SHARD);
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.MERIDA, UnitType.MAUI})
      defender.grantHero(t);
    // défense du défenseur (pour un vrai adversaire)
    HeroLineupUpdate d = new HeroLineupUpdate();
    d.type = HeroLineupType.FIGHT_PIT_DEFENSE; d.iD = 0L; d.customName = "";
    d.lineup = new HeroLineup(); d.lineup.heroes = new ArrayList<>(Arrays.asList(
        UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE, UnitType.MERIDA, UnitType.MAUI));
    d.lineup.mercenaryType = UnitType.DEFAULT;
    d.emeraldStatSlotChoices = new HashMap<>(); d.realGearOptions = new HashMap<>();
    defender.applyHeroLineupUpdate(d);

    long xpBefore = ((UnitData) attacker.gameUser().getHero(UnitType.RALPH)).getEXP();
    ServerArenaLadder ladder = attacker.arenaInfoWithLadder(TYPE, null, null).ladder;   // bots only ok pour l'XP
    List<AttackLineupSummary> attackers = new ArrayList<>();
    attackers.add(attackTeam());
    // un défenseur du ladder (bot) pour cibler ; l'XP dépend des ATTAQUANTS, pas du défenseur.
    long defID = ladder.entries().get(0).id;
    attacker.resolveArenaAttack(defID, /*win*/ false, TYPE, ladder, null, attackers);
    long xpAfter = ((UnitData) reload(attacker).gameUser().getHero(UnitType.RALPH)).getEXP();
    System.out.println("[rew] (A) XP RALPH " + xpBefore + " → " + xpAfter + " (persisté)");
    if (!(xpAfter > xpBefore)) throw new AssertionError("l'XP d'arène doit augmenter l'XP du héros attaquant");

    // ---------- (B) rapport de défense ----------
    long mid = defender.deliverMail(MailType.FIGHT_PIT_DEFENSE, "Attacker",
        "Fight Pit Defense", "Attacker attacked your Fight Pit team but your defense held!", null);
    ServerUser defBack = reload(defender);
    MailMessage report = null;
    for (MailMessage m : defBack.mailPersisted()) if (m.iD == mid) report = m;
    if (report == null || report.type != MailType.FIGHT_PIT_DEFENSE)
      throw new AssertionError("rapport de défense FIGHT_PIT_DEFENSE absent après reload");
    System.out.println("[rew] (B) rapport de défense livré+persisté : « " + report.subject + " » ("
        + report.type + ")");

    // ---------- (C) courrier admin avec récompense ----------
    List<RewardDrop> drops = new ArrayList<>();
    drops.add(com.perblue.heroes.game.logic.RewardHelper.createDrop(ResourceType.DIAMONDS, 500L));
    long amid = attacker.deliverMail(MailType.SYSTEM_MESSAGE, "Game Master",
        "Maintenance Gift", "Merci de votre patience !", drops);
    ServerUser attBack = reload(attacker);
    MailMessage admin = null;
    for (MailMessage m : attBack.mailPersisted()) if (m.iD == amid) admin = m;
    if (admin == null || admin.type != MailType.SYSTEM_MESSAGE)
      throw new AssertionError("courrier admin SYSTEM_MESSAGE absent après reload");
    if (admin.extra == null || admin.extra.attachments == null || admin.extra.attachments.isEmpty())
      throw new AssertionError("récompense (pièce jointe) manquante dans le courrier admin");
    System.out.println("[rew] (C) courrier admin livré+persisté : « " + admin.subject + " » + "
        + admin.extra.attachments.size() + " récompense(s)");

    System.out.println("[rew] OK — XP d'arène (héros attaquants) + rapport de défense + courrier admin (récompenses), persistés");
  }
}
