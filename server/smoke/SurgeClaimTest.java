import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * SURGE (#72) incrément 6 — RÉCOMPENSES & BASCULE : à la fin d'un surge, le serveur fige les récompenses par
 * membre (or stocké + tokens calculés par le jeu) dans un registre, crédite l'influence de guilde, et les livre
 * via {@code GetSurge} (unclaimedRewards). {@code SurgeClaimRewards} crédite CRYPT_TOKENS + GOLD de façon
 * autoritative, une seule fois. Vérifie : montants (code du jeu), crédit persistant, anti-double-réclamation,
 * influence de guilde, round-trip wire.
 */
public final class SurgeClaimTest {

  static void check(boolean c, String m) { if (!c) throw new AssertionError("[surge-claim] " + m); }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    java.io.File tmp = java.io.File.createTempFile("dh-surge-claim", ".db"); tmp.deleteOnExit();
    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      ServerUser ruler = ServerUser.newPlayer(1L, 1);
      ruler.giveResource(ResourceType.GOLD, 5000);
      ruler.grantHero(UnitType.RALPH, Rarity.ORANGE, 40, 5);
      ServerGuild g = ruler.createGuild(mk("Surge Reward Corps"), store.nextGuildID(1));
      store.save(ruler); store.saveGuild(g);

      long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
      long curID = ServerSurge.currentSurgeID(now);
      long OLD = (curID != 0 ? curID : 424242L) + 1L;   // surgeID « terminé » ≠ surge courant → force la bascule

      // 1. État d'un surge TERMINÉ : 2 vagues + 1 région (= 7 régions → 14×7+30 = 128 tokens) et de l'or stocké.
      SurgeData old = ServerSurgeState.loadOrReset(store, g, now);
      SurgeMemberSummary me = ServerSurgeState.memberSummary(old, ruler.userID);
      check(me != null, "le membre RULER doit exister dans l'état du surge");
      me.storedGold = 12345L;
      old.surgeID = OLD;                                  // champ = wrapper (comme en production)
      old.wavesCompleted = 2;
      old.waveRegionsCleared = new java.util.ArrayList<>();
      old.waveRegionsCleared.add(RegionType.values()[0]);
      ServerSurgeState.save(store, g, OLD, old);          // persiste sous le surgeID TERMINÉ

      long inflBefore = g.info.influence;

      // 2. BASCULE : loadOrReset voit un surgeID différent → fige le registre + crédite l'influence de guilde.
      SurgeData fresh = ServerSurgeState.loadOrReset(store, g, now);
      check(fresh.previousResults != null && fresh.previousResults.surgeID == OLD,
          "previousResults doit référencer le surge terminé, obtenu " + (fresh.previousResults == null ? "null"
              : fresh.previousResults.surgeID));
      long expInfl = ServerSurgeRewards.guildInfluenceFor(old);   // 1350×7 + 5000 = 14450 (code du jeu)
      check(g.info.influence > inflBefore, "l'influence de guilde doit être créditée à la bascule (avant="
          + inflBefore + " après=" + g.info.influence + " attendu +" + expInfl + ")");

      // 3. PERSONNALISATION (GetSurge) : la récompense NON réclamée du surge terminé est livrée au viewer.
      SurgeData served = ServerSurgeState.loadOrReset(store, g, now);
      ServerSurgeState.personalize(store, g, served, ruler.userID);
      check(served.unclaimedRewards != null && served.unclaimedRewards.containsKey(OLD),
          "unclaimedRewards doit contenir le surge terminé (clé " + OLD + ")");
      SurgeRewards shown = (SurgeRewards) served.unclaimedRewards.get(OLD);
      check(shown.totalTokens == 128, "tokens attendus = 128 (14×7+30, code du jeu), obtenu " + shown.totalTokens);
      check(shown.totalGold == 12345L, "or attendu = 12345 (storedGold), obtenu " + shown.totalGold);
      check(shown.baseTokens == com.perblue.heroes.game.data.surge.SurgeStats.getBaseTokens(),
          "baseTokens doit venir du jeu");
      WireCheck.assertRoundTrips(served);                 // SurgeData personnalisé (map SurgeRewards) round-trip

      // 4. RÉCLAMATION autoritative : crédite CRYPT_TOKENS + GOLD, persiste, une seule fois.
      long tokBefore = ruler.resourceAmount(ResourceType.CRYPT_TOKENS);
      long goldBefore = ruler.resourceAmount(ResourceType.GOLD);
      SurgeRewards paid = ServerSurgeState.claimRewards(store, g, ruler, OLD);
      check(paid.totalTokens == 128 && paid.totalGold == 12345L, "la réclamation doit renvoyer 128 tokens + 12345 or");
      WireCheck.assertRoundTrips(paid);
      check(ruler.resourceAmount(ResourceType.CRYPT_TOKENS) == tokBefore + 128,
          "CRYPT_TOKENS doit augmenter de 128 (obtenu " + (ruler.resourceAmount(ResourceType.CRYPT_TOKENS) - tokBefore) + ")");
      check(ruler.resourceAmount(ResourceType.GOLD) == goldBefore + 12345L,
          "GOLD doit augmenter de 12345 (obtenu " + (ruler.resourceAmount(ResourceType.GOLD) - goldBefore) + ")");

      // 5. Crédit PERSISTANT : relire le joueur du store → le solde tient.
      ServerUser reloaded = store.loadIfExists(ruler.userID, 1);
      check(reloaded != null && reloaded.resourceAmount(ResourceType.CRYPT_TOKENS) == tokBefore + 128,
          "le crédit de tokens doit survivre au round-trip DB");

      // 6. ANTI-DOUBLE-RÉCLAMATION : une 2e réclamation ne crédite rien.
      SurgeRewards again = ServerSurgeState.claimRewards(store, g, ruler, OLD);
      check(again.totalTokens == 0 && again.totalGold == 0, "la 2e réclamation doit être vide (anti-triche)");
      check(ruler.resourceAmount(ResourceType.CRYPT_TOKENS) == tokBefore + 128,
          "aucun crédit supplémentaire après la 2e réclamation");

      // 7. Après réclamation, personalize ne propose plus la récompense.
      SurgeData served2 = ServerSurgeState.loadOrReset(store, g, now);
      ServerSurgeState.personalize(store, g, served2, ruler.userID);
      check(served2.unclaimedRewards == null || !served2.unclaimedRewards.containsKey(OLD),
          "la récompense réclamée ne doit plus apparaître dans unclaimedRewards");

      System.out.println("[surge-claim] OK — bascule + registre + influence guilde (+" + expInfl
          + ") + réclamation autoritative (128 tokens/12345 or) + anti-double + persistance — #72 incrément 6");
    }
  }
}
