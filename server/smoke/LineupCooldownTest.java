import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.util.TimeUtil;
import dhserver.*;
import java.util.*;

/**
 * SAVED_LINEUPS (#72) incrément 3 — COOLDOWN de défense PvP. Sauver une DÉFENSE arène (FIGHT_PIT_DEFENSE) ou coliseum
 * (COLISEUM_DEFENSE_3) doit poser le cooldown anti-abus DU JEU ({@code ArenaHelper.setHeroLineupCooldown} →
 * {@code CooldownType.FIGHT_PIT_LINEUP_UPDATE}/{@code COLISEUM_LINEUP_UPDATE}), autoritatif + persisté
 * ({@code individualUserExtra.cooldowns}, write-through). Un lineup NORMAL (SAVED_*) n'en pose PAS.
 */
public final class LineupCooldownTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[cooldown] " + m); }

  static HeroLineupUpdate upd(HeroLineupType type) {
    HeroLineupUpdate u = new HeroLineupUpdate();
    u.type = type; u.iD = 0; u.customName = "";
    HeroLineup l = new HeroLineup();
    l.heroes = new ArrayList<>(Arrays.asList(UnitType.RALPH, UnitType.VANELLOPE));
    l.mercenaryType = UnitType.DEFAULT;
    u.lineup = l;
    u.emeraldStatSlotChoices = new HashMap<>(); u.realGearOptions = new HashMap<>();
    return u;
  }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9600L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    long now = TimeUtil.serverTimeNow();

    // --- FIGHT_PIT_DEFENSE → cooldown FIGHT_PIT_LINEUP_UPDATE posé ---
    check(su.applyHeroLineupUpdate(upd(HeroLineupType.FIGHT_PIT_DEFENSE)), "FIGHT_PIT_DEFENSE sauvé");
    long fp = su.gameUser().getCooldownEnd(CooldownType.FIGHT_PIT_LINEUP_UPDATE);
    check(fp > now, "cooldown FIGHT_PIT posé dans le futur (=" + fp + " > now=" + now + ")");
    System.out.println("[cooldown] FIGHT_PIT_LINEUP_UPDATE = " + fp + " (dans " + ((fp - now) / 1000) + "s)");

    // --- COLISEUM_DEFENSE_3 → cooldown COLISEUM_LINEUP_UPDATE posé ---
    check(su.applyHeroLineupUpdate(upd(HeroLineupType.COLISEUM_DEFENSE_3)), "COLISEUM_DEFENSE_3 sauvé");
    long col = su.gameUser().getCooldownEnd(CooldownType.COLISEUM_LINEUP_UPDATE);
    check(col > now, "cooldown COLISEUM posé dans le futur (=" + col + ")");
    System.out.println("[cooldown] COLISEUM_LINEUP_UPDATE = " + col + " (dans " + ((col - now) / 1000) + "s)");

    // --- Un lineup NORMAL (SAVED_1) ne pose AUCUN cooldown de défense ---
    ServerUser plain = ServerUser.newPlayer(9601L, 1);
    plain.bootData().userInfo.basicInfo.teamLevel = 100;
    check(plain.applyHeroLineupUpdate(upd(HeroLineupType.SAVED_1)), "SAVED_1 sauvé");
    check(plain.gameUser().getCooldownEnd(CooldownType.FIGHT_PIT_LINEUP_UPDATE) == 0, "SAVED_1 ne pose PAS de cooldown FIGHT_PIT");
    check(plain.gameUser().getCooldownEnd(CooldownType.COLISEUM_LINEUP_UPDATE) == 0, "SAVED_1 ne pose PAS de cooldown COLISEUM");
    System.out.println("[cooldown] SAVED_1 (normal) : aucun cooldown de défense ✔");

    // --- Persistance : write-through individualUserExtra.cooldowns → survit au round-trip wire + DB ---
    ServerUser rl = ServerUser.fromWire(9600L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    check(rl.gameUser().getCooldownEnd(CooldownType.FIGHT_PIT_LINEUP_UPDATE) == fp, "cooldown FIGHT_PIT survit au round-trip wire (=" + fp + ")");
    check(rl.gameUser().getCooldownEnd(CooldownType.COLISEUM_LINEUP_UPDATE) == col, "cooldown COLISEUM survit au round-trip wire");

    java.io.File db = java.io.File.createTempFile("cooldown", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(9600L, 1);
    check(fromDb != null, "compte relu DB");
    check(fromDb.gameUser().getCooldownEnd(CooldownType.FIGHT_PIT_LINEUP_UPDATE) == fp, "DB : cooldown FIGHT_PIT persisté");
    check(fromDb.gameUser().getCooldownEnd(CooldownType.COLISEUM_LINEUP_UPDATE) == col, "DB : cooldown COLISEUM persisté");
    store.close(); db.delete();
    System.out.println("[cooldown] persistance (wire + DB) des cooldowns de défense ✔");

    System.out.println("[cooldown] OK — cooldowns de défense PvP (FIGHT_PIT/COLISEUM) posés + persistés, normaux exempts (headless).");
  }
}
