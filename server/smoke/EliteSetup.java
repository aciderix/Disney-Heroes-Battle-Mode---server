import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.logic.CampaignHelper;
import com.perblue.heroes.game.logic.RewardHelper;
import com.perblue.heroes.game.logic.RewardSourceType;
import dhserver.*;

import java.util.*;

/**
 * OUTIL DEV (préparation de compte pour la vérif EN JEU d'ELITE_CAMPAIGN) — débloque l'ÉLITE <b>légitimement</b>
 * en 3-étoilant le chapitre 1 NORMAL via le VRAI chemin de combat du jeu ({@code ServerUser.recordCampaignAttack}
 * → {@code CampaignHelper.recordOutcome}), donne des {@code RAID_TICKET} (raids sans VIP) et de l'énergie, puis
 * PERSISTE. Décision de PRÉPARATION de compte (§3 opérateur), pas de la logique de jeu réécrite. Non inclus en
 * régression (outil paramétré). Usage : {@code EliteSetup [db] [shard] [userID]}.
 */
public final class EliteSetup {

  static AttackUnitSummary unit(UnitType t) {
    AttackUnitSummary s = new AttackUnitSummary();
    s.type = t; s.rarity = Rarity.WHITE; s.survived = true; s.power = 100; s.startingHP = 1000; s.startingEnergy = 0;
    return s;
  }

  static void win3(ServerUser su, CampaignType type, int ch, int lvl) {
    CampaignAttack ca = new CampaignAttack();
    ca.campaignType = type; ca.chapter = ch; ca.level = lvl; ca.stagesCleared = 1;
    ca.lootEarned = new ArrayList<>(); ca.memoryChanges = new ArrayList<>();
    AttackBase base = new AttackBase();
    base.outcome = CombatOutcome.WIN; base.stars = 3;
    AttackLineupSummary lu = new AttackLineupSummary();
    lu.units = new ArrayList<>();
    for (UnitType t : new UnitType[]{UnitType.RALPH, UnitType.ELASTIGIRL, UnitType.FROZONE}) lu.units.add(unit(t));
    base.attackers = new ArrayList<>(Collections.singletonList(lu));
    base.defenders = new ArrayList<>();
    ca.base = base;
    su.recordCampaignAttack(ca);
  }

  public static void main(String[] a) throws Exception {
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    int shard = a.length > 1 ? Integer.parseInt(a[1]) : 1;
    long uid = a.length > 2 ? Long.parseLong(a[2]) : 1L;
    ServerContext.init();
    try (UserStore st = new UserStore(db)) {
      ServerUser su = st.loadIfExists(uid, shard);
      if (su == null) { System.out.println("[elite-setup] joueur introuvable"); return; }

      // 3★ le chapitre 1 NORMAL, niveau par niveau (chaque victoire débloque le suivant), jusqu'à débloquer ELITE 1-1.
      int won = 0;
      for (int lvl = 1; lvl <= 20; lvl++) {
        User u = su.gameUser();
        if (!CampaignHelper.isLevelUnlocked(u, CampaignType.NORMAL, 1, lvl)) {
          System.out.println("[elite-setup] NORMAL 1-" + lvl + " pas (encore) débloqué → stop");
          break;
        }
        try { win3(su, CampaignType.NORMAL, 1, lvl); won++; }
        catch (Throwable t) { System.out.println("[elite-setup] NORMAL 1-" + lvl + " échec : " + t); break; }
        if (CampaignHelper.isLevelUnlocked(su.gameUser(), CampaignType.ELITE, 1, 1)) {
          System.out.println("[elite-setup] ELITE 1-1 débloqué après NORMAL 1-" + lvl);
          break;
        }
      }

      // Tickets de raid + énergie (raids sans VIP).
      User u = su.gameUser();
      RewardHelper.giveReward(u, RewardHelper.createDrop(ItemType.RAID_TICKET, 50L), RewardSourceType.NORMAL, "elite-setup");
      st.save(su);

      User f = su.gameUser();
      System.out.println("[elite-setup] TERMINÉ — 3★ NORMAL 1-1..1-" + won
          + " | ELITE 1-1 unlocked=" + CampaignHelper.isLevelUnlocked(f, CampaignType.ELITE, 1, 1)
          + " | RAID_TICKET=" + f.getItemAmount(ItemType.RAID_TICKET)
          + " | stamina=" + f.getResource(ResourceType.STAMINA));
    }
  }
}
