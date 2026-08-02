package dhserver;

import com.perblue.heroes.game.data.war.WarStats;
import com.perblue.heroes.network.messages.RewardDrop;
import com.perblue.heroes.network.messages.WarBoxInfo;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarLeague;
import com.perblue.heroes.network.messages.WarSummaryState;

import java.util.ArrayList;
import java.util.List;

/**
 * GUILD WAR (#68) — CLÔTURE D'UNE GUERRE : issue, variation de MMR, boîtes.
 *
 * <p>Une guerre se termine à {@code endTime}. Le serveur compare les totaux (barème de
 * {@link ServerWarScoring}, celui qu'écrit le client), en déduit l'issue, applique la variation de MMR de
 * chaque camp ({@link ServerWar#ratingChange}), tient à jour le plancher de ligue de la saison, libère les
 * deux guildes et persiste. Idempotent : une guerre déjà close ne se recompte pas.
 */
public final class ServerWarEnd {

  private ServerWarEnd() {}

  /** Une guerre est-elle déjà clôturée ? (les états terminaux du jeu) */
  public static boolean isFinished(ServerWarState w) {
    return w.state == WarSummaryState.VICTORY || w.state == WarSummaryState.DEFEAT
        || w.state == WarSummaryState.DRAW || w.state == WarSummaryState.BYE;
  }

  /** Ce qu'a produit la clôture, pour journaliser et pousser aux clients. */
  public static final class Result {
    public WarSummaryState outcomeA, outcomeB;
    public int mmrDeltaA, mmrDeltaB;
    public int pointsA, pointsB;
    public boolean alreadyFinished;
  }

  /**
   * Clôture la guerre si son échéance est passée.
   *
   * <p>Un BYE ne se compare à personne : le camp encaisse {@code BYE_RATING_GAIN}, comme le prévoient les
   * données. Sinon, l'issue vient des totaux et la variation de MMR de l'Elo du serveur.
   *
   * @return le résultat, ou {@code null} si la guerre n'est pas encore finie
   */
  public static Result finishWar(UserStore store, ServerWarState w, ServerGuild ga, ServerGuild gb, long now)
      throws java.sql.SQLException {
    if (now < w.endTime && !isFinished(w)) return null;

    Result r = new Result();
    if (isFinished(w) && w.state != WarSummaryState.BYE) {
      r.alreadyFinished = true;                     // idempotence : rien à recompter
      r.outcomeA = w.state;
      return r;
    }

    if (w.isBye()) {
      r.outcomeA = WarSummaryState.BYE;
      r.mmrDeltaA = ServerWar.byeRatingGain();
      applySide(w, ga, w.guildAID, r.outcomeA, r.mmrDeltaA);
      w.state = WarSummaryState.BYE;
      finalizeGuild(ga, w);
      store.saveWar(w);
      store.saveGuild(ga);
      return r;
    }

    r.pointsA = ServerWarScoring.totalPoints(ServerWarScoring.summaryFor(w, w.guildAID));
    r.pointsB = ServerWarScoring.totalPoints(ServerWarScoring.summaryFor(w, w.guildBID));
    r.outcomeA = ServerWarScoring.outcomeFor(w, w.guildAID);
    r.outcomeB = ServerWarScoring.outcomeFor(w, w.guildBID);

    int mmrA = ServerWar.currentMMR(ga), mmrB = ServerWar.currentMMR(gb);
    // Les deux variations se calculent sur les MMR d'AVANT : sinon le second camp serait noté contre un
    // adversaire déjà mis à jour, et l'échange ne serait plus symétrique.
    r.mmrDeltaA = ServerWar.ratingChange(mmrA, mmrB, r.outcomeA);
    r.mmrDeltaB = ServerWar.ratingChange(mmrB, mmrA, r.outcomeB);

    applySide(w, ga, w.guildAID, r.outcomeA, r.mmrDeltaA);
    applySide(w, gb, w.guildBID, r.outcomeB, r.mmrDeltaB);

    // L'état de la guerre porte l'issue du camp A (chaque client lit sa propre vue via toWarInfo).
    w.state = r.outcomeA;
    w.stateEndTime = w.endTime;
    finalizeGuild(ga, w);
    finalizeGuild(gb, w);

    store.saveWar(w);
    store.saveGuild(ga);
    store.saveGuild(gb);
    return r;
  }

  /** Écrit l'issue d'un camp : MMR, delta affiché, ligue, et bilan de saison de la guilde. */
  private static void applySide(ServerWarState w, ServerGuild g, long guildID,
      WarSummaryState outcome, int delta) {
    ServerWar.applyWarResult(g, delta, outcome);
    WarGuildInfo side = w.sideOf(guildID);
    if (side != null) {
      side.mmr = g.warMMR;
      side.mmrDelta = delta;
      side.league = ServerWar.effectiveLeague(g.warMMR, g.warPromotionMask);
      w.putSide(guildID, side);
    }
  }

  /** Libère la guilde : plus de guerre en cours, fenêtre effacée côté objet du jeu. */
  private static void finalizeGuild(ServerGuild g, ServerWarState w) {
    g.currentWarID = 0L;
    // `warEndTime` est ce que le client lit pour dire « une guerre est en cours » ({@code isWarActive}).
    // On le laisse à l'échéance atteinte : la guerre est finie, donc `warEndTime <= maintenant`.
    g.setWarWindow(w.startTime, w.endTime);
  }

  // ---------------------------------------------------------------------------------------------
  // BOÎTES
  // ---------------------------------------------------------------------------------------------

  /**
   * ⚠️ <b>GARDE-FOU, mesuré et documenté.</b> Les expressions de récompense de guerre ont pour variable
   * <b>{@code L} = le NIVEAU D'ÉQUIPE du joueur</b> ({@code RewardDropExpression.convert0} fait
   * {@code setVariable("L", n)}). Les lignes de PROMOTION sont protégées par des {@code max(…,1)} et ne
   * descendent jamais à zéro — vérifié sur toutes les ligues à TL 1/45/100/565. Les lignes de SAISON, elles,
   * n'ont <b>aucune protection</b> et produisent des quantités <b>NÉGATIVES</b> en dessous d'un niveau
   * d'équipe élevé — mesuré : positives seulement à partir de <b>TL 289</b> (COPPER et GOLD) et
   * <b>TL 282</b> (LEGENDARY). Ce barème a manifestement été calibré pour la population du jeu live.
   *
   * <p>Passer une telle quantité à {@code RewardHelper.giveReward} <b>RETIRERAIT</b> des ressources au
   * joueur — l'inverse d'une « End-of-Season Reward ». On écarte donc les quantités non strictement
   * positives, ce qui est la seule lecture compatible avec l'intention (et avec les {@code max(…,1)} que les
   * concepteurs ont mis partout ailleurs). Décision isolée ici, jamais une valeur inventée : on ne fabrique
   * aucune récompense, on refuse seulement d'en retirer.
   */
  public static List<RewardDrop> keepPositive(List<?> drops) {
    List<RewardDrop> out = new ArrayList<>();
    if (drops == null) return out;
    for (Object o : drops) {
      RewardDrop d = (RewardDrop) o;
      if (d.quantity > 0) out.add(d);
    }
    return out;
  }

  /**
   * Boîtes de PROMOTION accordées quand la guilde atteint une nouvelle ligue.
   * {@code NUM_PROMOTION_BOXES} boîtes, chacune offrant plusieurs options au choix du joueur.
   *
   * @param teamLevel niveau d'équipe du joueur — c'est lui qui échelonne les montants
   */
  public static List<WarBoxInfo> promotionBoxes(WarLeague league, int teamLevel, int seasonID, long now,
      long firstBoxID) {
    List<WarBoxInfo> out = new ArrayList<>();
    List<?> boxes = WarStats.getPromotionRewardPreviews(league, teamLevel, now);
    long id = firstBoxID;
    for (Object boxObj : boxes) {
      WarBoxInfo box = new WarBoxInfo();
      box.iD = id++;
      box.league = league;
      box.seasonID = seasonID;
      for (Object option : (List<?>) boxObj) {
        box.rewardOptions.addAll(keepPositive((List<?>) option));
      }
      if (!box.rewardOptions.isEmpty()) out.add(box);
    }
    return out;
  }

  /**
   * Boîtes de FIN DE SAISON, selon la ligue atteinte et le rang final.
   * « All participating Guilds will receive End-of-Season Rewards based on their League. »
   */
  public static List<WarBoxInfo> seasonBoxes(WarLeague league, int finalRank, int teamLevel, int seasonID,
      long now, long firstBoxID) {
    List<WarBoxInfo> out = new ArrayList<>();
    List<?> options = WarStats.getSeasonRewardsPreview(league, finalRank, teamLevel, now);
    WarBoxInfo box = new WarBoxInfo();
    box.iD = firstBoxID;
    box.league = league;
    box.seasonID = seasonID;
    for (Object option : options) {
      box.rewardOptions.addAll(keepPositive((List<?>) option));
    }
    if (!box.rewardOptions.isEmpty()) out.add(box);
    return out;
  }

  /** Nombre de boîtes de fin de saison prévu par les données ({@code NUM_SEASON_BOXES}). */
  public static int numSeasonBoxes() { return ServerWar.numSeasonBoxes(); }
}
