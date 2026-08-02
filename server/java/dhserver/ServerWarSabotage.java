package dhserver;

import com.perblue.heroes.game.data.misc.Unlockable;
import com.perblue.heroes.game.data.misc.Unlockables;
import com.perblue.heroes.game.data.war.WarStats;
import com.perblue.heroes.game.logic.GuildHelper;
import com.perblue.heroes.game.logic.GuildPerkHelper;
import com.perblue.heroes.game.logic.WarHelper;
import com.perblue.heroes.network.messages.GuildPerkType;
import com.perblue.heroes.network.messages.ResourceType;
import com.perblue.heroes.network.messages.UnitType;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarHeroSummary;
import com.perblue.heroes.network.messages.WarLineupSummary;
import com.perblue.heroes.network.messages.WarMemberInfo;
import com.perblue.heroes.network.messages.WarSabotageType;
import com.perblue.heroes.network.messages.WarSummaryState;

import java.util.ArrayList;
import java.util.List;

/**
 * GUILD WAR (#68) — JOUR 1 : sabotages, bans, protections, spars.
 *
 * <p>Toutes les validations sont celles du client, ré-exécutées :
 * <ul>
 *   <li><b>sabotage</b> — {@code WarClientHelper.doSabotageWarDefender} : mode débloqué, membre présent
 *       (sinon {@code WAR_JOINED_LATE}), coût {@code WarStats.getSabotageCost(n)} débité sur la monnaie de
 *       sabotage, puis {@code incDailyUses("war_activity")} ;</li>
 *   <li><b>bans / protections</b> — {@code WarClientHelper.tryEditWarBanProtect} : fonctionnalité active,
 *       même guilde, {@code GuildHelper.canEditWarBanProtect}, taille ≤ {@code maxBanAmt}/{@code maxProtectAmt},
 *       et aucun héros en cooldown ;</li>
 *   <li><b>spars</b> — {@code WarClientHelper.trySpar} : fonctionnalité active, membre présent, et
 *       {@code sparsDealt < GuildPerkHelper.getPerkValue(user, WAR_SPARS)}.</li>
 * </ul>
 *
 * <p><b>Le rang de coût est RECALCULÉ par le serveur.</b> Le client envoie un {@code INDEX}, mais le prix
 * monte avec le nombre de sabotages déjà posés sur la MÊME cible (« The more your Guild sabotages a single
 * player, the more the price goes up »). {@code WarClientHelper.getSabotageNumber} le compte depuis les
 * défenses de la cible ; on refait ce calcul ici plutôt que de faire confiance au client — sinon n'importe
 * qui paierait toujours le premier palier.
 */
public final class ServerWarSabotage {

  private ServerWarSabotage() {}

  /**
   * Monnaie de sabotage par défaut.
   *
   * <p><b>⚠️ Inférence assumée, isolée ici.</b> {@code WarGuildInfo.sabotageCurrency} est renseigné par le
   * SERVEUR — aucune classe cliente ne l'écrit, et il n'existe pas de {@code ResourceType.GUILD_TOKENS}
   * malgré la formule de l'aide « Sabotage uses Guild Tokens ». Parmi les {@code ResourceType} du jeu,
   * {@code WAR_TOKENS} est la monnaie de guerre (« Use War Tokens to buy items from the War Shop! ») et
   * c'est elle que paient les boîtes de guerre — d'où ce choix. Ce n'est pas une valeur inventée (elle vient
   * de l'enum du jeu) mais une SÉLECTION à un seul endroit, modifiable si un fait la contredit.
   */
  public static final ResourceType DEFAULT_SABOTAGE_CURRENCY = ResourceType.WAR_TOKENS;

  /**
   * Types de sabotage proposés à une guilde.
   *
   * <p>On offre exactement ceux que le jeu déclare VALIDES ({@code WarHelper.isValidSabotage} : tout sauf
   * {@code DEFAULT} et {@code DELAY_ARRIVAL}). L'aide dit « As your Guild ranks up, more sabotages will be
   * available », mais <b>aucune table ne relie un niveau de guilde à un sous-ensemble</b> : restreindre
   * reviendrait à inventer la progression. On expose donc l'ensemble valide complet, et on le documente.
   */
  public static List<WarSabotageType> availableSabotageTypes() {
    List<WarSabotageType> out = new ArrayList<>();
    for (WarSabotageType t : WarSabotageType.values()) {
      if (WarHelper.isValidSabotage(t)) out.add(t);
    }
    return out;
  }

  /**
   * Rang de coût du PROCHAIN sabotage contre {@code target} : nombre de héros de sa défense portant déjà un
   * sabotage valide, plus un. Miroir de {@code WarClientHelper.getSabotageNumber}.
   */
  public static int sabotageNumber(WarMemberInfo target) {
    int already = 0;
    if (target != null && target.defenses != null) {
      for (Object ol : target.defenses) {
        WarLineupSummary lineup = (WarLineupSummary) ol;
        if (lineup.heroes == null) continue;
        for (Object oh : lineup.heroes) {
          if (WarHelper.isValidSabotage(((WarHeroSummary) oh).sabotage)) already++;
        }
      }
    }
    return already + 1;
  }

  /** Coût du prochain sabotage contre cette cible, d'après {@code war_sabotage_cost.tab}. */
  public static int nextSabotageCost(WarMemberInfo target) {
    return WarStats.getSabotageCost(sabotageNumber(target));
  }

  /** Résultat d'une tentative de sabotage. */
  public static final class SabotageResult {
    public final String error;
    public final int cost;
    public final int number;
    SabotageResult(String error, int cost, int number) { this.error = error; this.cost = cost; this.number = number; }
    public boolean ok() { return error == null; }
  }

  /**
   * Sabote un héros de la défense adverse.
   *
   * <p>Le sabotage se pose pendant la phase {@code SABOTAGE} (jour 1) : c'est ce que dit l'aide
   * (« Sabotage enemy Heroes throughout the full 24 hours ») et ce que reflète l'état de la guerre.
   *
   * @param hero le héros visé dans la défense de {@code targetUserID}
   * @return le coût débité, ou le motif de refus
   */
  public static SabotageResult sabotage(ServerWarState w, ServerGuild g, ServerUser actor,
      long targetUserID, UnitType hero, WarSabotageType type, long now) {
    if (!WarHelper.isValidSabotage(type)) return new SabotageResult("type de sabotage invalide", 0, 0);
    if (!Unlockables.isUnlocked(Unlockable.WAR, actor.gameUser())) {
      return new SabotageResult("la guerre se débloque au niveau d'équipe "
          + Unlockables.getTeamLevelReq(Unlockable.WAR, actor.gameUser()), 0, 0);
    }
    if (w.state != WarSummaryState.SABOTAGE) {
      return new SabotageResult("le sabotage n'est ouvert que le jour 1 (état " + w.state + ")", 0, 0);
    }
    WarGuildInfo mine = w.sideOf(g.guildID);
    if (mine == null || mine.members == null || !mine.members.containsKey(actor.userID)) {
      return new SabotageResult("vous n'étiez pas dans la guilde au début de cette guerre", 0, 0);
    }
    WarGuildInfo theirs = w.enemySideOf(g.guildID);
    WarMemberInfo target = theirs.members == null ? null
        : (WarMemberInfo) theirs.members.get(targetUserID);
    if (target == null) return new SabotageResult("cette cible ne participe pas à la guerre", 0, 0);

    // Le héros visé doit exister dans SA défense et ne pas être déjà saboté.
    WarHeroSummary victim = null;
    if (target.defenses != null) {
      for (Object ol : target.defenses) {
        WarLineupSummary lineup = (WarLineupSummary) ol;
        if (lineup.heroes == null) continue;
        for (Object oh : lineup.heroes) {
          WarHeroSummary h = (WarHeroSummary) oh;
          if (h.hero != null && h.hero.type == hero && !WarHelper.isValidSabotage(h.sabotage)) {
            victim = h;
            break;
          }
        }
        if (victim != null) break;
      }
    }
    if (victim == null) {
      return new SabotageResult("ce héros n'est pas sabotable (absent, ou déjà saboté)", 0, 0);
    }

    // Rang et coût RECALCULÉS côté serveur — jamais l'INDEX du client.
    int number = sabotageNumber(target);
    int cost = WarStats.getSabotageCost(number);
    ResourceType currency = mine.sabotageCurrency == null || mine.sabotageCurrency == ResourceType.DEFAULT
        ? DEFAULT_SABOTAGE_CURRENCY : mine.sabotageCurrency;
    if (actor.resourceAmount(currency) < cost) {
      return new SabotageResult("pas assez de " + currency + " (" + cost + " requis)", cost, number);
    }
    actor.giveResource(currency, -cost);

    victim.sabotage = type;
    victim.sabotagedByUser = actor.userID;
    WarMemberInfo me = (WarMemberInfo) mine.members.get(actor.userID);
    if (me != null) me.sabotagesDealt++;

    w.putSide(w.opponentOf(g.guildID), theirs);
    w.putSide(g.guildID, mine);
    w.addSabotageFee(g.guildID, actor.userID, cost);
    return new SabotageResult(null, cost, number);
  }

  // ---------------------------------------------------------------------------------------------
  // BANS / PROTECTIONS
  // ---------------------------------------------------------------------------------------------

  /**
   * Pose la liste de bans ou de protections — miroir de {@code WarClientHelper.tryEditWarBanProtect}.
   *
   * <p>Fenêtre : les protections se posent pendant la file d'attente et les bans pendant les 12 premières
   * heures du jour 1 — c'est ce que lisent {@code WarHelper.isProtectPhase} (= {@code isQueued}) et
   * {@code isBanPhase} ({@code SABOTAGE} + {@code extraStateEndTime} non dépassé).
   *
   * @return {@code null} si accepté, sinon le motif de refus
   */
  public static String editBanProtect(ServerWarState w, ServerGuild g, ServerUser actor,
      List<UnitType> heroes, boolean isBan, long now) {
    if (!WarStats.isActiveGuildWarBanProtect()) return "bans et protections désactivés sur ce shard";
    if (actor.currentGuildID() != g.guildID) return "on ne modifie pas les bans d'une autre guilde";
    if (!GuildHelper.canEditWarBanProtect(actor.currentGuildRole())) {
      return "votre rôle (" + actor.currentGuildRole() + ") ne permet pas d'éditer bans et protections";
    }
    WarGuildInfo mine = w.sideOf(g.guildID);
    if (mine == null) return "cette guilde n'est pas dans cette guerre";

    if (isBan && !ServerWarMatchmaker.isBanPhase(w, now)) {
      return "la fenêtre de ban est fermée";
    }
    if (!isBan && w.state != WarSummaryState.NOT_QUEUED && w.state != WarSummaryState.QUEUED
        && w.state != WarSummaryState.QUEUED_AUTO && w.state != WarSummaryState.SABOTAGE) {
      return "les protections se posent avant la bataille";
    }

    List<UnitType> list = heroes == null ? new ArrayList<UnitType>() : heroes;
    if (!list.isEmpty()) {
      int max = isBan ? mine.maxBanAmt : mine.maxProtectAmt;
      if (list.size() > max) {
        return "trop de héros (" + list.size() + " > " + max + ")";
      }
      java.util.Map<?, ?> cooldowns = isBan ? mine.banCooldowns : mine.protectCooldowns;
      if (cooldowns != null) {
        for (UnitType u : list) {
          if (cooldowns.containsKey(u)) {
            return "le héros " + u + " est en cooldown de " + (isBan ? "ban" : "protection");
          }
        }
      }
    }

    if (isBan) {
      mine.bannedHeroes.clear();
      mine.bannedHeroes.addAll(list);
    } else {
      mine.protectedHeroes.clear();
      mine.protectedHeroes.addAll(list);
    }
    w.putSide(g.guildID, mine);
    return null;
  }

  // ---------------------------------------------------------------------------------------------
  // SPARS
  // ---------------------------------------------------------------------------------------------

  /**
   * Consomme un spar — miroir de {@code WarClientHelper.trySpar}. Un spar est un entraînement : il ne
   * consomme PAS d'attaque de guerre (« Spars do not consume your War attack »), seulement le quota du
   * perk de guilde {@code WAR_SPARS}.
   *
   * @return {@code null} si accepté, sinon le motif de refus
   */
  public static String spar(ServerWarState w, ServerGuild g, ServerUser actor, long targetUserID) {
    if (!WarStats.isActiveGuildWarSpars()) return "les spars sont désactivés sur ce shard";
    WarGuildInfo mine = w.sideOf(g.guildID);
    if (mine == null || mine.members == null) return "cette guilde n'est pas dans cette guerre";
    WarMemberInfo me = (WarMemberInfo) mine.members.get(actor.userID);
    if (me == null) return "vous n'étiez pas dans la guilde au début de cette guerre";

    long max = GuildPerkHelper.getPerkValue(
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info), GuildPerkType.WAR_SPARS);
    if (me.sparsDealt >= max) {
      return "quota de spars épuisé (" + me.sparsDealt + "/" + max + ")";
    }
    WarGuildInfo theirs = w.enemySideOf(g.guildID);
    if (theirs.members == null || !theirs.members.containsKey(targetUserID)) {
      return "cette cible ne participe pas à la guerre";
    }
    me.sparsDealt++;
    w.putSide(g.guildID, mine);
    return null;
  }

  /** Quota de spars de la guilde (perk {@code WAR_SPARS}). */
  public static long sparQuota(ServerGuild g) {
    return GuildPerkHelper.getPerkValue(
        new com.perblue.heroes.game.objects.GuildInfoPerkProvider(g.info), GuildPerkType.WAR_SPARS);
  }
}
