import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.IUser;
import dhserver.*;
import java.util.*;

/** OUTIL DEV : prépare un compte pour vérifier la MAÎTRISE DE COMBAT en jeu — héros DAMAGE FRAIS à 6★ (maîtrise 0)
 *  + lineup NORMAL_CAMPAIGN posé sur eux (pour que le quick fight de campagne les utilise). Après un combat gagné,
 *  leur maîtrise DAMAGE/BRONZE doit passer 0→1. Usage : ExpAdminCollectionFight [db] [userID] [shard]. */
public final class ExpAdminCollectionFight {
  static final UnitType[] TEAM = { UnitType.MOANA, UnitType.MERIDA, UnitType.JACK_SPARROW, UnitType.BEAST, UnitType.BELLE };
  public static void main(String[] a) throws Exception {
    ServerContext.init();
    String db = a.length > 0 ? a[0] : "server/data/dh-server.db";
    long uid = a.length > 1 ? Long.parseLong(a[1]) : 1L;
    int shard = a.length > 2 ? Integer.parseInt(a[2]) : 1;
    UserStore s = new UserStore(db);
    ServerUser su = s.loadIfExists(uid, shard);
    if (su == null) { System.out.println("[collfight-adm] aucun compte"); return; }
    IUser u = su.gameUser();
    int lvl = Math.max(1, su.bootData().userInfo.basicInfo.teamLevel);
    for (UnitType h : TEAM) if (u.getHero(h) == null) su.grantHero(h, Rarity.RED, lvl, 6);
    // BASELINE PROPRE : zéro la maîtrise DAMAGE/BRONZE de TOUS les héros DAMAGE possédés (l'état du compte peut être
    // pollué par des setups/claims précédents) → un delta 0→N après combat est NET.
    for (UnitType h : (List<UnitType>) com.perblue.heroes.game.logic.CollectionHelper.getHeroesInCollection(u, CollectionType.DAMAGE))
      u.getIndividual().setCollectionHeroMasteryUses(CollectionType.DAMAGE, CollectionTier.BRONZE, h, 0);
    // lineup NORMAL_CAMPAIGN = ces 5 héros (le quick fight de campagne l'utilisera).
    HeroLineup lu = new HeroLineup();
    lu.heroes = new ArrayList<>(Arrays.asList(TEAM));
    lu.mercenaryType = UnitType.DEFAULT;
    u.setHeroLineup(HeroLineupType.NORMAL_CAMPAIGN, 0L, lu, Long.MAX_VALUE, "", new HashMap<>(), new HashMap<>());
    // resync lineups (via un HeroLineupUpdate serveur — réutilise le chemin persistant)
    HeroLineupUpdate hlu = new HeroLineupUpdate();
    hlu.type = HeroLineupType.NORMAL_CAMPAIGN; hlu.iD = 0; hlu.lineup = lu; hlu.customName = "";
    hlu.realGearOptions = new HashMap<>(); hlu.emeraldStatSlotChoices = new HashMap<>();
    su.applyHeroLineupUpdate(hlu);

    // Pré-3★ du niveau 1-1 (pour débloquer le QUICK FIGHT en jeu) avec un héros JETABLE 1★ (< MIN_HERO_STARS_REQUIRED
    // → maîtrise non accumulée → la maîtrise DAMAGE reste 0, prémisse du test préservée).
    if (u.getHero(UnitType.OLAF) == null) su.grantHero(UnitType.OLAF, Rarity.WHITE, Math.min(10, lvl), 1);
    AttackUnitSummary tu = new AttackUnitSummary();
    tu.type = UnitType.OLAF; tu.rarity = Rarity.WHITE; tu.survived = true; tu.power = 100; tu.startingHP = 1000; tu.startingEnergy = 0;
    AttackLineupSummary tl = new AttackLineupSummary(); tl.units = new ArrayList<>(Arrays.asList(tu));
    AttackBase base = new AttackBase();
    base.attackers = new ArrayList<>(Arrays.asList(tl)); base.defenders = new ArrayList<>();
    base.outcome = CombatOutcome.WIN; base.stars = 3;
    CampaignAttack ca = new CampaignAttack();
    ca.base = base; ca.campaignType = CampaignType.NORMAL; ca.chapter = 1; ca.level = 1;
    ca.lootEarned = new ArrayList<>(); ca.memoryChanges = new ArrayList<>(); ca.stagesCleared = 1;
    su.recordCampaignAttack(ca);
    s.save(su);
    var st = u.getCampaignLevel(CampaignType.NORMAL, 1, 1);
    System.out.println("[collfight-adm] niveau 1-1 pré-3★ (via OLAF 1★, maîtrise non touchée) : statut="
        + (st == null ? "null" : st.getStars() + "★"));
    StringBuilder sb = new StringBuilder();
    for (UnitType h : TEAM) sb.append(h).append("=")
        .append(u.getIndividual().getCollectionHeroMasteryUses(CollectionType.DAMAGE, CollectionTier.BRONZE, h)).append(" ");
    System.out.println("[collfight-adm] compte " + uid + " : team DAMAGE 6★ + lineup NORMAL_CAMPAIGN posé. maîtrise BRONZE avant : "
        + sb + "[persisté]");
    s.close();
  }
}
