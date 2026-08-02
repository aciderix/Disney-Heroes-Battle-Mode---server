package dhserver;

import com.perblue.heroes.game.data.misc.Unlockable;
import com.perblue.heroes.game.data.misc.Unlockables;
import com.perblue.heroes.game.logic.GuildHelper;
import com.perblue.heroes.game.logic.WarHelper;
import com.perblue.heroes.network.messages.AttackStageResult;
import com.perblue.heroes.network.messages.CombatOutcome;
import com.perblue.heroes.network.messages.WarCarInfo;
import com.perblue.heroes.network.messages.WarCarType;
import com.perblue.heroes.network.messages.WarGuildInfo;
import com.perblue.heroes.network.messages.WarHeroSummary;
import com.perblue.heroes.network.messages.WarLineupSummary;
import com.perblue.heroes.network.messages.WarLogAttack;
import com.perblue.heroes.network.messages.WarLogAttackWave;
import com.perblue.heroes.network.messages.WarMemberInfo;
import com.perblue.heroes.network.messages.WarSummaryState;

import java.util.List;

/**
 * GUILD WAR (#68) — LES ATTAQUES : ouverture, enregistrement, conséquences.
 *
 * <p><b>Les validations sont celles du client, ré-exécutées.</b> {@code WarClientHelper.doStartWarAttack}
 * énonce la règle complète, reprise ici telle quelle : mode débloqué ({@code Unlockable.WAR}), le joueur
 * doit figurer parmi les membres de la guerre (sinon {@code WAR_JOINED_LATE}), le compteur
 * {@code UserFlag.WAR_ATTACKS_USED} est remis à zéro quand {@code TimeType.WAR_START_TIME_LAST_ATTACK}
 * diffère du début de la guerre, <b>deux attaques au maximum</b>, et la seconde exige à la fois
 * {@code GuildHelper.canUseExtraWarAttacks} et {@code extraAttacksRemaining > 0}.
 *
 * <p>La remise à zéro utilise la méthode DU JEU {@code WarHelper.tryResetUserWarState}, qui fait aussi le
 * ménage des données de combat persistantes du mode — on ne la réécrit pas.
 *
 * <p><b>Le combat, lui, reste client-autoritatif</b>, comme la campagne et l'arène : le client joue les
 * trois vagues et renvoie {@code WarAttack.battles} = {@code List<AttackStageResult{outcome, stars}>}. Le
 * serveur applique ce verdict à l'état partagé (héros défenseurs mis KO, étoiles, portes, score) et le
 * JOURNALISE. Ce qui est autoritatif ici, c'est le DROIT d'attaquer, la cible, et toutes les conséquences.
 */
public final class ServerWarAttack {

  private ServerWarAttack() {}

  /** Nombre maximal d'attaques par joueur et par guerre — relevé dans {@code doStartWarAttack}. */
  public static final int MAX_ATTACKS_PER_WAR = 2;

  /** Le résultat d'une demande d'attaque : soit un refus motivé, soit le feu vert. */
  public static final class StartResult {
    public final String error;
    public final boolean usesExtraAttack;
    StartResult(String error, boolean extra) { this.error = error; this.usesExtraAttack = extra; }
    public boolean ok() { return error == null; }
  }

  private static StartResult refuse(String why) { return new StartResult(why, false); }

  /**
   * Le joueur a-t-il le droit d'attaquer {@code defenderUserID} maintenant ?
   *
   * <p>Applique, dans l'ordre, exactement les contrôles de {@code doStartWarAttack}, puis deux contrôles
   * d'état que seul le serveur peut faire (phase de bataille en cours, salle visée ouverte) — le client ne
   * peut pas les garantir puisque l'état est partagé entre deux guildes.
   */
  public static StartResult validateStart(ServerWarState w, ServerGuild g, ServerUser attacker,
      long defenderUserID, long now) {
    if (!Unlockables.isUnlocked(Unlockable.WAR, attacker.gameUser())) {
      return refuse("la guerre se débloque au niveau d'équipe "
          + Unlockables.getTeamLevelReq(Unlockable.WAR, attacker.gameUser()));
    }
    if (w.state != WarSummaryState.ACTIVE) {
      return refuse("les attaques n'ouvrent qu'en phase de bataille (état " + w.state + ")");
    }
    if (now >= w.endTime) return refuse("la guerre est terminée");

    WarGuildInfo mine = w.sideOf(g.guildID);
    if (mine == null || mine.members == null || !mine.members.containsKey(attacker.userID)) {
      return refuse("vous n'étiez pas dans la guilde au début de cette guerre");
    }
    WarGuildInfo theirs = w.enemySideOf(g.guildID);
    WarMemberInfo target = theirs.members == null ? null
        : (WarMemberInfo) theirs.members.get(defenderUserID);
    if (target == null) return refuse("ce défenseur ne participe pas à cette guerre");
    if (target.assignedCar == WarCarType.DEFAULT) return refuse("ce défenseur n'occupe aucune salle");
    if (!ServerWarCars.isCarOpen(theirs, target.assignedCar, w.state)) {
      return refuse("la salle " + target.assignedCar + " n'est pas encore ouverte");
    }

    // Compteur d'attaques. Il passe par ServerUser, qui détient la discipline de re-synchronisation :
    // les compteurs UserFlag vivent HORS `this.extra` et se perdent au round-trip sans resyncCounts.
    int used = attacker.warAttacksUsed(w.startTime);
    if (used >= MAX_ATTACKS_PER_WAR) return refuse("vous avez déjà utilisé vos attaques de cette guerre");

    boolean extra = used == 1;
    if (extra) {
      if (!GuildHelper.canUseExtraWarAttacks(attacker.currentGuildRole(), g.info)) {
        return refuse("votre rôle (" + attacker.currentGuildRole()
            + ") n'a pas droit aux attaques supplémentaires");
      }
      if (mine.extraAttacksRemaining <= 0) {
        return refuse("la guilde n'a plus d'attaque supplémentaire");
      }
    }
    return new StartResult(null, extra);
  }

  /**
   * Consomme l'attaque : incrémente {@code WAR_ATTACKS_USED} et, si c'est une attaque supplémentaire,
   * décrémente le crédit de la guilde. À appeler APRÈS un {@link #validateStart} accepté.
   */
  public static void consumeAttack(ServerWarState w, ServerGuild g, ServerUser attacker, boolean extra) {
    attacker.consumeWarAttack(w.startTime);
    if (extra) {
      WarGuildInfo mine = w.sideOf(g.guildID);
      if (mine != null && mine.extraAttacksRemaining > 0) {
        mine.extraAttacksRemaining--;
        w.putSide(g.guildID, mine);
      }
    }
  }

  /** Attaques déjà utilisées par ce joueur dans CETTE guerre. */
  public static int attacksUsed(ServerWarState w, ServerUser attacker) {
    return attacker.warAttacksUsed(w.startTime);
  }

  /**
   * Enregistre le résultat d'une attaque et en applique TOUTES les conséquences :
   * héros défenseurs mis KO vague par vague, étoiles et portes recalculées, score des DEUX camps rafraîchi,
   * et attaque journalisée ({@link WarLogAttack}, objet du jeu).
   *
   * <p>« Defeat defending Heroes to knock them out for the rest of the War » : une vague GAGNÉE met KO le
   * lineup correspondant du défenseur, définitivement.
   *
   * @param battles les {@code AttackStageResult} envoyés par le client, une par vague
   * @return le journal créé
   */
  public static WarLogAttack recordAttack(ServerWarState w, long attackerGuildID,
      com.perblue.heroes.network.messages.BasicUserInfo attackerInfo, long defenderUserID,
      List<?> battles, boolean usedExtraAttack, long now) {
    long defenderGuildID = w.opponentOf(attackerGuildID);
    WarGuildInfo defSide = w.sideOf(defenderGuildID);
    if (defSide == null) throw new IllegalArgumentException("guerre sans adversaire");
    WarMemberInfo defender = defSide.members == null ? null
        : (WarMemberInfo) defSide.members.get(defenderUserID);
    if (defender == null) throw new IllegalArgumentException("défenseur absent de la guerre");

    WarLogAttack log = new WarLogAttack();
    log.attackTime = now;
    log.attacker = attackerInfo;
    log.defender = defender.userInfo;
    log.warCar = defender.assignedCar;
    log.usedExtraAttack = usedExtraAttack;

    int waveCount = battles == null ? 0 : battles.size();
    for (int i = 0; i < waveCount; i++) {
      Object o = battles.get(i);
      AttackStageResult res = o instanceof AttackStageResult ? (AttackStageResult) o : null;
      boolean victory = ServerWarScoring.waveWon(res);
      boolean retreat = res != null && res.outcome == CombatOutcome.RETREAT;

      WarLogAttackWave wave = new WarLogAttackWave();
      wave.victory = victory;
      wave.skipped = res == null;
      wave.timeOut = retreat;
      log.waves.add(wave);

      // Une vague gagnée met le lineup correspondant KO pour le reste de la guerre.
      if (victory && defender.defenses != null && i < defender.defenses.size()) {
        WarLineupSummary lineup = (WarLineupSummary) defender.defenses.get(i);
        if (lineup.heroes != null) {
          for (Object h : lineup.heroes) ((WarHeroSummary) h).defeated = true;
        }
      }
    }

    // Conséquences : étoiles/portes du défenseur, puis score des deux camps.
    ServerWarCars.rebuildCars(defSide);
    w.putSide(defenderGuildID, defSide);          // instantané : indispensable après mutation
    w.addAttack(attackerGuildID, log);
    ServerWarScoring.refreshTotalPoints(w, attackerGuildID);
    ServerWarScoring.refreshTotalPoints(w, defenderGuildID);
    return log;
  }

  /**
   * Réponse à {@code START_WAR_ATTACK} : les trois lineups de défense de la cible, la salle visée et les
   * salles encore actives. Le client s'en sert pour monter le combat.
   */
  public static com.perblue.heroes.network.messages.StartWarAttackResponse buildStartResponse(
      ServerWarState w, long attackerGuildID, long defenderUserID, ServerGuild defenderGuild) {
    com.perblue.heroes.network.messages.StartWarAttackResponse r =
        new com.perblue.heroes.network.messages.StartWarAttackResponse();
    r.defenderUserID = defenderUserID;
    WarGuildInfo theirs = w.enemySideOf(attackerGuildID);
    WarMemberInfo target = theirs.members == null ? null
        : (WarMemberInfo) theirs.members.get(defenderUserID);
    if (target == null) return r;

    r.currentCar = target.assignedCar;
    if (target.combatModifiers != null) r.combatModifiers = target.combatModifiers;

    com.perblue.heroes.network.messages.WarDefense[] slots = {
        toDefense(target, 0), toDefense(target, 1), toDefense(target, 2)};
    r.lineup0 = slots[0];
    r.lineup1 = slots[1];
    r.lineup2 = slots[2];

    // Salles encore DEBOUT chez le défenseur : ce sont elles qui accordent leurs bonus au combat.
    //
    // ⚠️ CORRECTIF (2026-08-02, 2ᵉ défaut de typage trouvé EN JEU, même famille que `toDefense`) :
    // `activeCars` attend des {@link com.perblue.heroes.network.messages.WarAttackCarBonus}
    // ({type, bonusPerkLevel}), PAS des `WarCarType`. Y mettre l'enum compilait — les listes du wire sont
    // brutes après dex2jar — mais levait `ClassCastException` à l'ÉCRITURE du message, donc le client ne
    // recevait jamais la réponse et l'attaque était impossible. Le niveau de perk est celui de la guilde
    // DÉFENSEUR (c'est son garage), lu par la logique du jeu comme le fait `ServerWarCars.maxCarSize`.
    @SuppressWarnings("unchecked")
    java.util.List<Object> activeCars = (java.util.List<Object>) r.activeCars;
    for (WarCarType car : ServerWarCars.GARAGE_ORDER) {
      WarCarInfo info = theirs.cars == null ? null : (WarCarInfo) theirs.cars.get(car);
      if (info == null || ServerWarCars.carDefeated(info)) continue;
      com.perblue.heroes.network.messages.WarAttackCarBonus bonus =
          new com.perblue.heroes.network.messages.WarAttackCarBonus();
      bonus.type = car;
      bonus.bonusPerkLevel = ServerWarCars.carBonusPerkLevel(defenderGuild, car);
      activeCars.add(bonus);
    }
    return r;
  }

  /**
   * Une équipe de défense telle que l'ATTAQUANT doit la recevoir.
   *
   * <p><b>⚠️ CORRECTIF (2026-08-02, défaut trouvé EN JEU).</b> {@code WarDefense.defenders} attend des
   * {@link com.perblue.heroes.network.messages.WarHeroData} (le héros COMPLET, pour que le client puisse le
   * faire combattre), pas les {@code WarHeroSummary} de l'état de guerre. Recopier la liste telle quelle
   * compilait (les listes du wire sont brutes après dex2jar) mais faisait échouer la SÉRIALISATION de
   * {@code StartWarAttackResponse} — {@code ClassCastException: WarHeroSummary cannot be cast to
   * WarHeroData} dans {@code WarHeroData.writeListed}. Conséquence : le client n'a JAMAIS reçu la réponse, et
   * <b>aucune attaque n'était possible en jeu</b> alors que le serveur, lui, journalisait « [persisté] ».
   * Un test headless ne pouvait pas le voir : il n'écrit pas le message sur le fil.
   *
   * <p>On convertit donc chaque défenseur : {@code HeroData} bâti par la logique du jeu
   * ({@code ClientNetworkStateConverter.getHeroData} sur l'{@code UnitData} reconstruit depuis le résumé),
   * en reportant le sabotage subi ({@code sabotageType}/{@code sabotagedByUserID}) — c'est exactement ce que
   * {@code WarCombatHelper} applique ensuite côté client.
   */
  @SuppressWarnings("unchecked")
  private static com.perblue.heroes.network.messages.WarDefense toDefense(WarMemberInfo m, int index) {
    com.perblue.heroes.network.messages.WarDefense d =
        new com.perblue.heroes.network.messages.WarDefense();
    if (m.defenses == null || index >= m.defenses.size()) {
      d.defeated = true;                      // pas de lineup posté = rien à battre
      return d;
    }
    WarLineupSummary lineup = (WarLineupSummary) m.defenses.get(index);
    d.defeated = ServerWarCars.lineupDefeated(lineup);
    if (lineup.heroes != null) {
      java.util.List<Object> defenders = (java.util.List<Object>) d.defenders;
      for (Object o : lineup.heroes) {
        com.perblue.heroes.network.messages.WarHeroSummary hs =
            (com.perblue.heroes.network.messages.WarHeroSummary) o;
        com.perblue.heroes.network.messages.WarHeroData hd =
            new com.perblue.heroes.network.messages.WarHeroData();
        try {
          hd.hero = com.perblue.heroes.game.ClientNetworkStateConverter.getHeroData(
              com.perblue.heroes.game.ClientNetworkStateConverter.getHero(hs.hero));
        } catch (Throwable t) {
          System.out.println("[war] défenseur illisible (" + t + ") — ignoré");
          continue;
        }
        hd.sabotageType = hs.sabotage;
        hd.sabotagedByUserID = hs.sabotagedByUser;
        defenders.add(hd);
      }
    }
    return d;
  }
}
