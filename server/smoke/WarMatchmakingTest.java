import com.perblue.heroes.network.messages.*;
import dhserver.ServerContext;
import dhserver.ServerGuild;
import dhserver.ServerUser;
import dhserver.ServerWar;
import dhserver.ServerWarMatchmaker;
import dhserver.ServerWarState;
import dhserver.UserStore;

import java.util.List;

/**
 * GUILD WAR #68 — ÉTAPE 3 : appariement et ouverture des guerres.
 *
 * <p>Prouve : (a) l'appariement rapproche les MMR voisins, (b) l'anti-rematch écarte un adversaire trop
 * récent et se dégrade avec l'ancienneté, (c) un effectif impair produit un BYE, (d) l'ouverture pose la
 * chronologie de phases EXACTEMENT comme le client la lit ({@code WarHelper.isBanPhase},
 * {@code WarClientHelper.checkForEndOfSabotage}), (e) l'inscription simple sort de la file et la
 * persistante y reste, (f) tout persiste et les deux guildes voient la MÊME guerre.
 */
public final class WarMatchmakingTest {

  static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
  }

  static CreateGuild mk(String name) {
    CreateGuild m = new CreateGuild();
    m.name = name; m.motto = ""; m.minLevel = 1;
    m.newMemberPolicy = GuildNewMemberPolicy.OPEN; m.country = "US"; m.timeZone = "UTC";
    return m;
  }

  /** Crée une guilde inscrite, au MMR voulu. */
  static ServerGuild seed(UserStore store, long userID, String name, int mmr, WarQueueState q, long queuedAt)
      throws Exception {
    ServerUser ruler = ServerUser.newPlayer(userID, 1);
    ruler.giveResource(ResourceType.GOLD, 5000);
    ServerGuild g = ruler.createGuild(mk(name), store.nextGuildID(1));
    ServerWar.rollOverSeason(g, ServerWar.seasonIDAt(com.perblue.heroes.util.TimeUtil.serverTimeNow()), 0);
    g.warMMR = mmr;
    g.warQueueState = q;
    g.warQueuedTime = queuedAt;
    store.saveGuild(g);
    store.save(ruler);
    return g;
  }

  public static void main(String[] argv) throws Exception {
    ServerContext.init();
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    java.io.File tmp = java.io.File.createTempFile("dh-war-mm", ".db");
    tmp.deleteOnExit();

    try (UserStore store = new UserStore(tmp.getAbsolutePath())) {
      // ---------------------------------------------------------------------------------------
      // 1. APPARIEMENT PAR MMR — quatre guildes, deux paires évidentes.
      // ---------------------------------------------------------------------------------------
      ServerGuild g1000 = seed(store, 1L, "Mille", 1000, WarQueueState.QUEUED_SINGLE, now);
      ServerGuild g990 = seed(store, 2L, "NeufCentQuatreVingtDix", 990, WarQueueState.QUEUED_SINGLE, now + 1);
      ServerGuild g400 = seed(store, 3L, "QuatreCents", 400, WarQueueState.QUEUED_SINGLE, now + 2);
      ServerGuild g410 = seed(store, 4L, "QuatreCentDix", 410, WarQueueState.QUEUED_PERSISTENT, now + 3);

      List<ServerGuild> queued = ServerWarMatchmaker.queuedGuilds(store, 1);
      check(queued.size() == 4, "les 4 guildes inscrites doivent être candidates, obtenu " + queued.size());

      List<ServerWarMatchmaker.Pairing> pairs = ServerWarMatchmaker.pair(queued);
      check(pairs.size() == 2, "4 guildes → 2 appariements, obtenu " + pairs.size());
      for (ServerWarMatchmaker.Pairing p : pairs) {
        check(!p.isBye(), "un effectif pair ne doit produire aucun BYE");
        long lo = Math.min(p.a.guildID, p.b.guildID), hi = Math.max(p.a.guildID, p.b.guildID);
        boolean hautes = lo == g1000.guildID && hi == g990.guildID;
        boolean basses = lo == g400.guildID && hi == g410.guildID;
        check(hautes || basses, "appariement incohérent : " + p.a.guildID + " vs " + p.b.guildID
            + " (les MMR voisins doivent se retrouver ensemble)");
        System.out.println("[war] apparié " + p.a.info.basicInfo.name + " (" + ServerWar.currentMMR(p.a)
            + ") vs " + p.b.info.basicInfo.name + " (" + ServerWar.currentMMR(p.b) + ") coût=" + p.cost);
      }

      // ---------------------------------------------------------------------------------------
      // 2. ANTI-REMATCH — la pénalité décroît avec l'ancienneté et s'annule au-delà du seuil.
      // ---------------------------------------------------------------------------------------
      int threshold = ServerWar.rematchThreshold();
      int full = ServerWarMatchmaker.rematchPenalty(0);
      check(full == ServerWar.rematchCost(),
          "l'adversaire de la guerre PRÉCÉDENTE doit coûter REMATCH_COST plein (" + ServerWar.rematchCost()
              + "), obtenu " + full);
      check(ServerWarMatchmaker.rematchPenalty(-1) == 0, "un inconnu ne coûte rien");
      check(ServerWarMatchmaker.rematchPenalty(threshold) == 0,
          "au-delà de REMATCH_THRESHOLD (" + threshold + ") la pénalité disparaît");
      check(ServerWarMatchmaker.rematchPenalty(threshold - 1) == 0,
          "le plus ANCIEN dans le seuil correspond à BEST_REMATCH_SCALE (pénalité nulle)");
      for (int i = 1; i < threshold; i++) {
        check(ServerWarMatchmaker.rematchPenalty(i) <= ServerWarMatchmaker.rematchPenalty(i - 1),
            "la pénalité doit décroître avec l'ancienneté (rang " + i + ")");
      }
      System.out.println("[war] anti-rematch : rang 0 → " + full + " · rang 1 → "
          + ServerWarMatchmaker.rematchPenalty(1) + " · rang " + (threshold - 1) + " → "
          + ServerWarMatchmaker.rematchPenalty(threshold - 1) + " · au-delà → 0");

      // Effet RÉEL sur l'appariement : deux guildes de MMR identiques, dont l'une vient d'affronter l'autre.
      ServerGuild a = seed(store, 11L, "AlphaMM", 500, WarQueueState.QUEUED_SINGLE, now);
      ServerGuild b = seed(store, 12L, "BetaMM", 500, WarQueueState.QUEUED_SINGLE, now + 1);
      ServerGuild c = seed(store, 13L, "GammaMM", 480, WarQueueState.QUEUED_SINGLE, now + 2);
      a.rememberWarOpponent(b.guildID, ServerWar.maxPreviousWars());   // A vient d'affronter B
      b.rememberWarOpponent(a.guildID, ServerWar.maxPreviousWars());
      List<ServerWarMatchmaker.Pairing> p3 = ServerWarMatchmaker.pair(java.util.Arrays.asList(a, b, c));
      // A (500) préfère C (480, coût 20) à B (500, coût 0 + 2×200 de pénalité).
      ServerWarMatchmaker.Pairing forA = null;
      for (ServerWarMatchmaker.Pairing p : p3) if (p.a == a || p.b == a) forA = p;
      check(forA != null, "A doit être apparié ou en BYE");
      check(!forA.isBye() && (forA.a == c || forA.b == c),
          "malgré un MMR identique, A doit ÉVITER B (re-match récent) et prendre C");
      System.out.println("[war] anti-rematch effectif : A(500) évite B(500, affronté juste avant) → C(480)");

      // ---------------------------------------------------------------------------------------
      // 3. EFFECTIF IMPAIR → BYE.
      // ---------------------------------------------------------------------------------------
      check(p3.size() == 2, "3 guildes → 1 paire + 1 BYE, obtenu " + p3.size() + " appariements");
      int byes = 0;
      for (ServerWarMatchmaker.Pairing p : p3) if (p.isBye()) byes++;
      check(byes == 1, "un effectif impair doit produire exactement 1 BYE, obtenu " + byes);
      System.out.println("[war] effectif impair → 1 BYE (BYE_RATING_GAIN=" + ServerWar.byeRatingGain() + ")");

      // ---------------------------------------------------------------------------------------
      // 4. OUVERTURE — chronologie de phases telle que le CLIENT la lit.
      // ---------------------------------------------------------------------------------------
      ServerWarMatchmaker.Pairing top = pairs.get(0);
      ServerWarState w = ServerWarMatchmaker.openWar(store, 1, top, now);
      check(w.warID > 0, "la guerre doit recevoir un identifiant");
      check(w.state == WarSummaryState.SABOTAGE, "une guerre s'ouvre en phase de SABOTAGE, obtenu " + w.state);
      long sab = com.perblue.heroes.game.data.war.WarStats.getSabotagePhaseLength();
      long ban = com.perblue.heroes.game.data.war.WarStats.getSabotageBanPhaseLenght();
      check(w.stateEndTime == now + sab, "la phase de sabotage dure SABOTAGE_PHASE_LENGTH");
      check(w.extraStateEndTime == now + ban, "la fenêtre de ban dure SABOTAGE_BAN_PHASE_LENGTH");
      check(w.endTime == now + ServerWarMatchmaker.warDuration(), "la guerre dure deux jours");
      check(ServerWarMatchmaker.warDuration() == 2 * sab, "deux jours = 2 × la longueur du jour 1");
      check(ServerWarMatchmaker.isBanPhase(w, now), "on doit être en fenêtre de ban à l'ouverture");
      check(!ServerWarMatchmaker.isBanPhase(w, now + ban + 1), "la fenêtre de ban doit se refermer");
      System.out.println("[war] ouverture : SABOTAGE " + (sab / 3600000) + " h (ban les "
          + (ban / 3600000) + " premières), puis ACTIVE jusqu'à +"
          + (ServerWarMatchmaker.warDuration() / 3600000) + " h");

      // Transition SABOTAGE → ACTIVE, à l'identique de WarClientHelper.checkForEndOfSabotage.
      check(!ServerWarMatchmaker.advancePhase(w, now), "aucune transition avant l'échéance");
      check(ServerWarMatchmaker.advancePhase(w, now + sab), "la phase doit basculer à l'échéance");
      check(w.state == WarSummaryState.ACTIVE, "la phase suivante est ACTIVE, obtenu " + w.state);
      check(w.stateEndTime == w.endTime, "en ACTIVE, stateEndTime doit valoir endTime (règle du client)");
      check(!ServerWarMatchmaker.advancePhase(w, now + sab + 1), "la transition ne doit pas se rejouer");
      check(!ServerWarMatchmaker.isBanPhase(w, now), "hors SABOTAGE, il n'y a plus de fenêtre de ban");
      System.out.println("[war] transition SABOTAGE → ACTIVE conforme au client (stateEndTime = endTime)");

      // ---------------------------------------------------------------------------------------
      // 5. EFFET SUR LES GUILDES — file, guerre en cours, adversaires mémorisés, persistance.
      // ---------------------------------------------------------------------------------------
      ServerGuild ra = store.loadGuild(1, top.a.guildID);
      ServerGuild rb = store.loadGuild(1, top.b.guildID);
      check(ra.currentWarID == w.warID && rb.currentWarID == w.warID,
          "les deux guildes doivent pointer sur la guerre ouverte");
      check(ra.warsSinceOpponent(rb.guildID) == 0 && rb.warsSinceOpponent(ra.guildID) == 0,
          "chaque camp doit mémoriser l'autre comme adversaire le plus récent");
      check(ra.warQueuedTime == 0 && rb.warQueuedTime == 0, "l'horodatage de file doit être effacé");
      for (ServerGuild g : new ServerGuild[]{ra, rb}) {
        WarQueueState before = g.guildID == g1000.guildID ? WarQueueState.QUEUED_SINGLE
            : g.guildID == g990.guildID ? WarQueueState.QUEUED_SINGLE
            : g.guildID == g400.guildID ? WarQueueState.QUEUED_SINGLE : WarQueueState.QUEUED_PERSISTENT;
        check(g.warQueueState == ServerWar.queueStateAfterMatch(before),
            "l'état de file après appariement doit suivre la règle SIMPLE/PERSISTANT pour " + g.guildID);
      }
      System.out.println("[war] après appariement : file A=" + ra.warQueueState + " B=" + rb.warQueueState);

      // Les deux guildes lisent la MÊME guerre, chacune de son côté.
      ServerWarState rw = store.loadWar(1, w.warID);
      check(rw != null && rw.involves(ra.guildID) && rw.involves(rb.guildID), "la guerre doit être relue");
      WarInfo va = rw.toWarInfo(ra.guildID), vb = rw.toWarInfo(rb.guildID);
      check(va.yourGuild.guildInfo.iD == ra.guildID && va.enemyGuild.guildInfo.iD == rb.guildID,
          "la vue de A doit la placer en yourGuild");
      check(vb.yourGuild.guildInfo.iD == rb.guildID && vb.enemyGuild.guildInfo.iD == ra.guildID,
          "la vue de B doit la placer en yourGuild");
      check(va.yourGuild.mmr == ServerWar.currentMMR(ra), "le MMR du camp doit être celui de la guilde");
      check(va.yourGuild.league == ServerWar.effectiveLeague(ra.warMMR, ra.warPromotionMask),
          "la ligue du camp doit tenir compte du plancher de saison");
      System.out.println("[war] guerre #" + rw.warID + " relue : " + va.yourGuild.guildInfo.name + " ("
          + va.yourGuild.league + ") vs " + va.enemyGuild.guildInfo.name + " (" + va.enemyGuild.league + ")");

      // Une guilde déjà en guerre n'est plus candidate.
      check(!ServerWarMatchmaker.queuedGuilds(store, 1).contains(ra), "une guilde en guerre sort du vivier");

      // ---------------------------------------------------------------------------------------
      // 6. BYE — enregistré comme une vraie guerre, sans adversaire.
      // ---------------------------------------------------------------------------------------
      ServerGuild solo = seed(store, 21L, "Solitaire", 300, WarQueueState.QUEUED_SINGLE, now);
      ServerWarState byeWar = ServerWarMatchmaker.openWar(
          store, 1, ServerWarMatchmaker.pair(java.util.Arrays.asList(solo)).get(0), now);
      check(byeWar.isBye() && byeWar.state == WarSummaryState.BYE, "un BYE doit être en état BYE");
      check(byeWar.opponentOf(solo.guildID) == 0, "un BYE n'a pas d'adversaire");
      ServerGuild rsolo = store.loadGuild(1, solo.guildID);
      check(rsolo.currentWarID == byeWar.warID, "la guilde doit pointer sur son BYE");
      check(rsolo.previousWarOpponents.isEmpty(), "un BYE ne doit mémoriser aucun adversaire");
      check(store.loadWar(1, byeWar.warID) != null, "le BYE doit être persisté comme une guerre");
      System.out.println("[war] BYE persisté (guerre #" + byeWar.warID + ", aucun adversaire)");

      System.out.println("WAR MATCHMAKING TEST OK");
    }
  }
}
