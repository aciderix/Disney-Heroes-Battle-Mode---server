import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 2 — état per-user SERVEUR-AUTORITATIF ({@code TrialEventData}).
 *
 * <p>Prouve : (1) {@code ServerTrials.freshData} = état vierge (0 chance, aucun sous-trial) ; (2) {@code getData} sert le frais
 * puis le PERSISTÉ (keyé par eventID) ; (3) l'état survit au round-trip wire + DB (colonne BLOB {@code trialEventData}) ;
 * (4) un AUTRE eventID (nouvelle saison) repart d'un état frais. Handler {@code GetTrialEventData} = ce blob (cf. LoginServer).
 */
public final class ServerTrialsDataTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[servertrials] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 990111L;
    ServerUser su = ServerUser.newPlayer(8790L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;

    // (1) frais
    TrialEventData fresh = ServerTrials.freshData(EV);
    check(fresh.eventID == EV && fresh.chancesUsed == 0 && fresh.dailyResetsUsed == 0, "freshData vierge");
    check(fresh.subtrials != null && fresh.subtrials.isEmpty(), "freshData subtrials vide");

    // (2) getData sert le frais et le POSE sur su
    TrialEventData d = ServerTrials.getData(su, EV);
    check(d.eventID == EV && d.chancesUsed == 0, "getData frais");
    check(su.trialEventDataOrNull() != null && su.trialEventDataOrNull().eventID == EV, "état posé sur su");
    // getData redemandé = MÊME état (pas re-frais)
    d.chancesUsed = 2;
    TrialEventData d2 = ServerTrials.getData(su, EV);
    check(d2.chancesUsed == 2, "getData renvoie l'état COURANT (pas re-frais)");

    // (3) persistance wire + DB
    check(su.trialEventWire() != null && su.trialEventWire().length > 0, "trialEventWire non vide");
    TrialEventData wback = (TrialEventData) WireCheck.roundTrip(su.trialEventDataOrNull());
    check(wback.eventID == EV && wback.chancesUsed == 2, "TrialEventData survit au round-trip wire");
    java.io.File db = java.io.File.createTempFile("servertrials", ".db"); db.delete();
    UserStore store = new UserStore(db.getPath());
    store.save(su);
    ServerUser fromDb = store.loadIfExists(8790L, 1);
    check(fromDb != null && fromDb.trialEventDataOrNull() != null, "état de trial rechargé de la DB");
    check(fromDb.trialEventDataOrNull().eventID == EV && fromDb.trialEventDataOrNull().chancesUsed == 2,
        "eventID + chancesUsed persistés en DB");
    System.out.println("[servertrials] persistance (wire + DB) : eventID=" + EV + " chancesUsed=2 ✔");

    // (4) autre eventID → état frais (nouvelle saison)
    long EV2 = 990222L;
    TrialEventData d3 = ServerTrials.getData(fromDb, EV2);
    check(d3.eventID == EV2 && d3.chancesUsed == 0, "nouvel eventID → état frais (chancesUsed=0)");
    check(fromDb.trialEventDataOrNull().eventID == EV2, "état remplacé par le nouvel event");
    store.close(); db.delete();

    System.out.println("[servertrials] OK — TrialEventData serveur-autoritatif (frais/persisté, keyé eventID) + persistance wire+DB [headless].");
  }
}
