import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerInvasion;
import dhserver.ServerUser;
import dhserver.UserStore;

/**
 * INVASION #69 — BOSS partagés de guilde. Un boss est « trouvé » par un membre puis attaquable par TOUTE la
 * guilde jusqu'à {@code BOSS_FIGHT_TIME_LIMIT} (24 h). Les règles viennent des DONNÉES DU JEU
 * ({@code invasion_constants.tab}) : coût en clés {@code BOSS_FIGHT_1X/5X_KEY_COST}, verrou d'attaque
 * {@code ATTACK_LOCK_DURATION} (un attaquant à la fois), niveau initial {@code BOSS_FIGHT_INITAL_LEVEL}.
 *
 * Prouve : apparition, dégâts CUMULÉS par joueur (état partagé), verrou exclusif, refus si clés insuffisantes,
 * expiration au-delà de la limite, et persistance (ServerGuild v7).
 */
public final class InvasionBossTest {
  static CreateGuild mk() {
    CreateGuild m = new CreateGuild();
    m.name = "BossGuild"; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }
  /** Dégâts cumulés d'un joueur : la carte du jeu est typée Map<Long, InvasionBossDamageData>. */
  static long dmg(InvasionBossInfo b, long uid) {
    Object v = b.damageDone == null ? null : b.damageDone.get(uid);
    return v instanceof InvasionBossDamageData ? ((InvasionBossDamageData) v).damage : 0L;
  }
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long wed = java.time.Instant.parse("2026-07-01T08:00:00Z").toEpochMilli();
    java.io.File tmp = java.io.File.createTempFile("dh-inv-boss", ".db");
    tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser founder = ServerUser.newPlayer(1L, 1);
      founder.grantHero(UnitType.RALPH); founder.giveResource(ResourceType.GOLD, 5000);
      ServerGuild g = founder.createGuild(mk(), store.nextGuildID(1));

      // Les constantes lues sont bien celles du jeu.
      if (ServerInvasion.bossInitialLevel() != 450)
        throw new AssertionError("BOSS_FIGHT_INITAL_LEVEL attendu 450, lu " + ServerInvasion.bossInitialLevel());
      if (ServerInvasion.attackLockDuration() != 300_000L)
        throw new AssertionError("ATTACK_LOCK_DURATION attendu 5 min, lu " + ServerInvasion.attackLockDuration());
      System.out.println("[invasion] constantes boss : niveau initial " + ServerInvasion.bossInitialLevel()
          + ", verrou " + (ServerInvasion.attackLockDuration() / 60000) + " min, limite "
          + (ServerInvasion.bossTimeLimit() / 3600000) + " h");

      // APPARITION : le boss est trouvé par le fondateur, avec le niveau et l'échéance des données.
      InvasionBossInfo boss = ServerInvasion.spawnBoss(g, founder, 0, wed);
      if (boss == null || boss.bossLevel != ServerInvasion.bossInitialLevel())
        throw new AssertionError("boss mal initialisé");
      if (boss.endTime != wed + ServerInvasion.bossTimeLimit())
        throw new AssertionError("échéance du boss incorrecte");
      if (ServerInvasion.activeBosses(g, wed).size() != 1) throw new AssertionError("boss non actif");
      System.out.println("[invasion] boss #" + boss.bossID + " trouvé (niveau " + boss.bossLevel
          + ", expire dans " + (ServerInvasion.bossTimeLimit() / 3600000) + " h)");

      // ATTAQUE d'un membre : clés débitées, dégâts cumulés.
      ServerUser m1 = ServerUser.newPlayer(2L, 1);
      m1.giveResource(ResourceType.BREAKER, 10);
      long keys0 = m1.resourceAmount(ResourceType.BREAKER);
      UserInvasionData ud1 = ServerInvasion.newUserData(2L, g.guildID, 249);
      ServerInvasion.BossOutcome o1 = ServerInvasion.attackBoss(g, m1, ud1, boss.bossID, 1, 5000L, wed);
      if (!o1.accepted) throw new AssertionError("attaque refusée : " + o1.refusal);
      if (m1.resourceAmount(ResourceType.BREAKER) != keys0 - o1.keyCost)
        throw new AssertionError("clés non débitées");
      // 2ᵉ attaque du même joueur → dégâts CUMULÉS.
      ServerInvasion.BossOutcome o1b = ServerInvasion.attackBoss(g, m1, ud1, boss.bossID, 1, 3000L, wed);
      if (o1b.totalDamage != 8000L) throw new AssertionError("dégâts non cumulés : " + o1b.totalDamage);
      System.out.println("[invasion] membre 2 attaque ×2 → " + o1b + " (clés " + keys0 + "→"
          + m1.resourceAmount(ResourceType.BREAKER) + ")");

      // Coût 5× supérieur au coût 1× (données du jeu).
      ServerInvasion.BossOutcome o5 = ServerInvasion.attackBoss(g, m1, ud1, boss.bossID, 5, 1000L, wed);
      if (o5.keyCost <= o1.keyCost)
        throw new AssertionError("le coût 5× (" + o5.keyCost + ") doit dépasser le 1× (" + o1.keyCost + ")");
      System.out.println("[invasion] attaque 5× → " + o5.keyCost + " clés (vs " + o1.keyCost + " en 1×)");

      // ÉTAT PARTAGÉ : un second membre attaque le MÊME boss, dégâts suivis séparément.
      ServerUser m2 = ServerUser.newPlayer(3L, 1);
      m2.giveResource(ResourceType.BREAKER, 5);
      ServerInvasion.BossOutcome o2 =
          ServerInvasion.attackBoss(g, m2, ServerInvasion.newUserData(3L, g.guildID, 249), boss.bossID, 1, 2500L, wed);
      if (!o2.accepted || o2.totalDamage != 2500L) throw new AssertionError("dégâts du 2ᵉ membre incorrects");
      InvasionBossInfo shared = ServerInvasion.activeBosses(g, wed).get(0);
      if (dmg(shared, 2L) != 9000L || dmg(shared, 3L) != 2500L)
        throw new AssertionError("dégâts par membre incorrects : " + shared.damageDone);
      System.out.println("[invasion] état partagé : membre 2 → " + dmg(shared, 2L)
          + " dégâts, membre 3 → " + dmg(shared, 3L));

      // VERROU : tant qu'un membre détient le verrou, un autre est refusé.
      if (!g.lockBoss(boss.bossID, 2L, wed, ServerInvasion.attackLockDuration()))
        throw new AssertionError("le membre 2 devrait obtenir le verrou");
      ServerInvasion.BossOutcome blocked =
          ServerInvasion.attackBoss(g, m2, null, boss.bossID, 1, 100L, wed);
      if (blocked.accepted) throw new AssertionError("attaque simultanée : elle devrait être refusée");
      System.out.println("[invasion] verrou exclusif → 2ᵉ attaquant refusé (" + blocked.refusal + ")");
      // Le verrou EXPIRE : après ATTACK_LOCK_DURATION, un autre membre peut attaquer.
      long later = wed + ServerInvasion.attackLockDuration() + 1000L;
      ServerInvasion.BossOutcome afterLock =
          ServerInvasion.attackBoss(g, m2, null, boss.bossID, 1, 100L, later);
      if (!afterLock.accepted) throw new AssertionError("après expiration du verrou : " + afterLock.refusal);
      System.out.println("[invasion] verrou expiré → attaque de nouveau permise");

      // CLÉS INSUFFISANTES → refus fidèle.
      ServerUser broke = ServerUser.newPlayer(4L, 1);
      ServerInvasion.BossOutcome poor =
          ServerInvasion.attackBoss(g, broke, null, boss.bossID, 1, 100L, later);
      if (poor.accepted) throw new AssertionError("sans clés, l'attaque doit être refusée");
      System.out.println("[invasion] sans clés → refusé (" + poor.refusal + ")");

      // PERSISTANCE (ServerGuild v7) : boss + dégâts survivent au round-trip DB.
      store.saveGuild(g);
      ServerGuild rg = store.loadGuild(1, g.guildID);
      java.util.List<InvasionBossInfo> rb = ServerInvasion.activeBosses(rg, later);
      if (rb.size() != 1) throw new AssertionError("boss non persisté (v7)");
      if (dmg(rb.get(0), 2L) != 9000L) throw new AssertionError("dégâts non persistés");
      System.out.println("[invasion] round-trip DB v7 OK : boss + dégâts par membre persistés");

      // EXPIRATION : au-delà de BOSS_FIGHT_TIME_LIMIT, le boss disparaît.
      long expired = wed + ServerInvasion.bossTimeLimit() + 1000L;
      if (!ServerInvasion.activeBosses(rg, expired).isEmpty())
        throw new AssertionError("le boss devrait avoir expiré");
      System.out.println("[invasion] au-delà de la limite → boss expiré et retiré");

      // ---- RÉCOMPENSES DE BOSS : déléguées aux tables du jeu (invasion_boss_rewards*) ----
      {
        dhserver.ServerInvasionObject inv = dhserver.ServerInvasionObject.at(wed);
        ServerUser p = ServerUser.newPlayer(50L, 1);
        java.util.List<?> loot = p.rollInvasionBossRewards(
            inv, 450, 1, InvasionBossRewardType.PARTICIPANT, InvasionBossType.MEGA_VIRUS);
        if (loot.isEmpty())
          throw new AssertionError("récompenses PARTICIPANT vides (snapshot d'évènements null ?)");
        // Les quantités doivent être CELLES DE LA TABLE (lues via l'objet de récompense du jeu).
        Object rw = com.perblue.heroes.game.data.invasion.InvasionStats.getBossReward(
            InvasionBossRewardType.PARTICIPANT, InvasionBossType.MEGA_VIRUS);
        int expStam = (Integer) rw.getClass().getMethod("getInvasionStamina").invoke(rw);
        int expTech = (Integer) rw.getClass().getMethod("getBossTech").invoke(rw);
        long stam = 0, tech = 0;
        for (Object o : loot) {
          RewardDrop d = (RewardDrop) o;
          if (d.resourceType == ResourceType.INVASION_STAMINA) stam = d.quantity;
          if (d.resourceType == ResourceType.BOSS_TECH) tech = d.quantity;
        }
        if (stam != expStam) throw new AssertionError("INVASION_STAMINA " + stam + " ≠ table " + expStam);
        if (tech != expTech) throw new AssertionError("BOSS_TECH " + tech + " ≠ table " + expTech);
        // Le rôle change les récompenses (FINDER ≠ PARTICIPANT selon la table).
        java.util.List<?> finder = p.rollInvasionBossRewards(
            inv, 450, 1, InvasionBossRewardType.FINDER, InvasionBossType.MEGA_VIRUS);
        if (finder.isEmpty()) throw new AssertionError("récompenses FINDER vides");
        System.out.println("[invasion] récompenses de boss (tables du jeu) : PARTICIPANT → " + loot.size()
            + " drops (" + stam + " STAMINA, " + tech + " BOSS_TECH), FINDER → " + finder.size() + " drops");
      }

      System.out.println("INVASION BOSS TEST OK");
    }
  }
}
