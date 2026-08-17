import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.logic.DifficultyModeHelper;
import dhserver.*;
import java.util.*;

/**
 * PORT (#72) — preuve de la logique serveur du 2ᵉ mode PORT, **THE WAREHOUSE** (PORT_WAREHOUSE).
 *
 * <p><b>Fait établi (§8, bytecode + en jeu)</b> : l'OUVERTURE des modes PORT est pilotée par le planning d'ÉVÉNEMENTS
 * SPÉCIAUX — {@code DifficultyModeHelper.isOpen} (ordinaux PORT) → {@code BaseEventSnapshot.isModeOpen} →
 * {@code ModesOpenSnapshot.getOpenModes()}. Notre serveur ré-hébergé n'a PAS d'événements live → seul PORT_DOCKS est
 * ouvert par défaut ; PORT_WAREHOUSE est **event-gaté** (comme FRANCHISE_TRIALS). En jeu, THE DOCKS a été joué de bout
 * en bout (entrée → sélection héros → combat → VICTOIRE → récompenses). THE WAREHOUSE utilise la MÊME logique serveur
 * (même {@code recordOutcome}, le mode n'est qu'un paramètre) — seul son GATE d'ouverture diffère.
 *
 * <p>Ici on PROUVE la logique serveur de WAREHOUSE en LEVANT le gate d'événement via le flag debug DU JEU
 * {@code BaseEventSnapshot.debugAllModesOpen} (réservé au test, remis à false ; JAMAIS en prod). On vérifie alors
 * {@code isOpen(PORT_WAREHOUSE)=true} puis un combat WAREHOUSE (WIN → butin + cooldown), round-trip wire + DB.
 */
public final class PortWarehouseTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[portwh] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(8730L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    GameMode mode = GameMode.PORT_WAREHOUSE;
    var NONE = com.perblue.heroes.game.specialevent.SpecialEventSnapshot.NONE;

    // Sans lever le gate : WAREHOUSE fermé (event-gaté, pas d'events live) — on le DOCUMENTE par assertion.
    check(!DifficultyModeHelper.isOpen(mode, su.gameUser(), NONE), "WAREHOUSE fermé par défaut (event-gaté, no live events)");

    boolean prev = com.perblue.common.specialevent.BaseEventSnapshot.debugAllModesOpen;
    try {
      // Lève le gate d'événement (flag debug DU JEU) pour prouver la logique serveur du mode. Réservé au test.
      com.perblue.common.specialevent.BaseEventSnapshot.debugAllModesOpen = true;
      check(DifficultyModeHelper.isOpen(mode, su.gameUser(), NONE), "WAREHOUSE ouvert avec debugAllModesOpen (gate levé)");

      CooldownType cd = DifficultyModeHelper.getCooldownType(mode);
      check(su.gameUser().getCooldownEnd(cd) <= com.perblue.heroes.util.TimeUtil.serverTimeNow(), "pas de cooldown au départ");
      long goldBefore = su.gameUser().getResource(ResourceType.GOLD);

      // Combat WAREHOUSE (WIN, butin 7000 GOLD client-reporté) → recordOutcome (même chemin que DOCKS).
      RewardDrop loot = new RewardDrop();
      loot.resourceType = ResourceType.GOLD; loot.itemType = ItemType.DEFAULT; loot.quantity = 7000;
      DifficultyModeAttack m = new DifficultyModeAttack();
      m.gameMode = mode; m.modeDifficulty = 1; m.stagesCleared = 3;
      m.attackEndTime = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      m.lootEarned = new ArrayList<>(Collections.singletonList(loot));
      m.base = new AttackBase();
      m.base.outcome = CombatOutcome.WIN; m.base.stars = 3;
      m.base.attackers = new ArrayList<>(); m.base.defenders = new ArrayList<>();
      su.recordDifficultyModeAttack(m);

      long goldAfter = su.gameUser().getResource(ResourceType.GOLD);
      check(goldAfter == goldBefore + 7000, "butin WAREHOUSE crédité (+7000) : " + goldBefore + "→" + goldAfter);
      long cdEnd = su.gameUser().getCooldownEnd(cd);
      check(cdEnd > com.perblue.heroes.util.TimeUtil.serverTimeNow(), "cooldown WAREHOUSE posé (" + cd + ")");
      System.out.println("[portwh] WAREHOUSE WIN : +7000 GOLD + cooldown " + cd + " posé ✔");

      // Persistance round-trip wire + DB.
      ServerUser rl = ServerUser.fromWire(8730L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
      check(rl.gameUser().getResource(ResourceType.GOLD) == goldAfter, "GOLD survit au round-trip wire");
      check(rl.gameUser().getCooldownEnd(cd) == cdEnd, "cooldown survit au round-trip wire");
      java.io.File db = java.io.File.createTempFile("portwh", ".db"); db.delete();
      UserStore store = new UserStore(db.getPath());
      store.save(su);
      ServerUser fromDb = store.loadIfExists(8730L, 1);
      check(fromDb != null && fromDb.gameUser().getResource(ResourceType.GOLD) == goldAfter
          && fromDb.gameUser().getCooldownEnd(cd) == cdEnd, "GOLD + cooldown persistés en DB");
      store.close(); db.delete();
      System.out.println("[portwh] persistance (wire + DB) ✔");
    } finally {
      com.perblue.common.specialevent.BaseEventSnapshot.debugAllModesOpen = prev;  // remise à l'état d'origine (hors prod)
    }
    check(!DifficultyModeHelper.isOpen(mode, su.gameUser(), NONE), "flag debug remis → WAREHOUSE re-fermé (état propre)");

    System.out.println("[portwh] OK — logique serveur PORT_WAREHOUSE prouvée (gate événement levé pour le test) + persistance (headless).");
  }
}
