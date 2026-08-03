import com.perblue.heroes.network.messages.Rarity;
import com.perblue.heroes.network.messages.UnitType;

/**
 * PREUVE ANTI-RÉGRESSION R1 (#74 B3 — cf. docs/HEADLESS_VERIFICATION.md).
 *
 * <p>Le crash g55 : à l'ère de contenu R1 (horloge de jeu en 2016), un héros dont le nombre d'ÉTOILES dépasse
 * le PLAFOND de l'ère fait planter le hub — {@code QuestHelper.getUnlockedDailyQuests}/{@code hasUnclaimedDailyQuests}
 * évalue la requête de quête {@code HasEnoughCollectionHeroes.isSatisfied}, qui bâtit une liste de taille
 * {@code UnitStats.getMaxStars(user)+1} puis fait {@code list.get(hero.getStars())} → {@code IndexOutOfBounds}
 * quand {@code getStars() > getMaxStars(user)}. C'est une incohérence de RÉTROGRADATION d'ère (un compte joué
 * en R102 garde des héros au-delà du plafond R1), pas un bug du port.
 *
 * <p>Ce test prouve que l'ORACLE CLIENT headless ({@link ClientOracle}) ATTRAPE ce crash SANS passer par l'in-game :
 * on construit l'état exact (héros 6★ + horloge R1) et on exige que {@code assertClientRenders} LÈVE. S'il ne
 * lève pas, c'est une régression de l'oracle (le filet headless ne couvre plus R1).
 */
public final class ClientOracleR1Test {

  public static void main(String[] a) throws Exception {
    dhserver.ServerContext.init();
    dhserver.ServerUser u = dhserver.ServerUser.newPlayer(1L, 1);

    // Héros 6★ (grantHero(type, rarity, level, stars) — le 4ᵉ arg est le nombre d'étoiles) : dépasse le
    // plafond d'étoiles de l'ère R1.
    u.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 6);

    // Ère R1 : régler l'HORLOGE DE JEU sur 2016 (comme AdminClock --set-date). getServerColumn(serverTimeNow)
    // → R1 → plafond d'étoiles bas (< 6). serverTimeNow = now − OFFSET → OFFSET = now − cible.
    long target = new org.joda.time.LocalDate(2016, 9, 6)
        .toDateTimeAtStartOfDay(com.perblue.heroes.util.TimeUtil.getServerDateTimeZone()).getMillis();
    dhserver.ServerContext.setClockOffsetMillis(System.currentTimeMillis() - target);

    boolean caught = false;
    try {
      ClientOracle.assertClientRenders(u.gameUser());
    } catch (AssertionError e) {
      caught = true;
      String msg = e.getMessage() == null ? "" : e.getMessage().replaceAll("\\s+", " ");
      System.out.println("[oracle-r1] ✅ l'oracle a FLAGUÉ le crash R1 headless : "
          + msg.substring(0, Math.min(200, msg.length())));
    } finally {
      dhserver.ServerContext.setClockOffsetMillis(0L);   // restaurer l'horloge (ère courante)
    }

    if (!caught)
      throw new AssertionError("[oracle-r1] RÉGRESSION : l'oracle n'a PAS attrapé le crash R1 "
          + "(héros 6★ + ère R1) — assertClientRenders aurait dû lever");

    // Contrôle : la MÊME batterie sur un compte NEUF (ère courante, roster de départ) reste VERTE — le flag
    // vient bien de l'état R1, pas d'un faux positif systématique.
    dhserver.ServerUser fresh = dhserver.ServerUser.newPlayer(2L, 1);
    ClientOracle.assertClientRenders(fresh.gameUser());

    System.out.println("[oracle-r1] anti-régression OK — l'oracle attrape le crash R1 (g55) SANS in-game, "
        + "et laisse passer un compte neuf (#74 B3)");
  }
}
