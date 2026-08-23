import com.perblue.heroes.network.messages.*;
import dhserver.*;
import java.util.*;

/**
 * FRANCHISE_TRIALS (EVENT/FRANCHISE) incr. 7 — PUSH ADMIN de l'event trial (patron SPECIAL_EVENTS `AdminEvents`).
 *
 * <p>Un opérateur active le franchise trial : la spec {@code TRIAL_FRANCHISE{id,start,end}} est persistée (config
 * {@code operator_events}) et reconstruite au boot ({@code eventsFromConfig} → {@code buildFranchiseTrialEvent}). On prouve :
 * (1) round-trip config (spec → blob → spec) ; (2) reconstruction en un event TRIAL data-driven ({@code ClientEventTrial}
 * subtrials>0, eventID = l'id de la spec) ; (3) {@code toRaw} sérialise l'event pour le PUSH client (REFRESH_SPECIAL_EVENTS).
 */
public final class TrialAdminPushTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[trialpush] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    long EV = 900_001L;
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    long start = now - 86_400_000L, end = now + 30L * 86_400_000L;

    // (1) spec → blob → specs (round-trip config persistée).
    String spec = ServerEvents.specJsonTrialFranchise(EV, start, end, 10, "FRANCHISE TRIALS", 0, 0);
    byte[] blob = ServerEvents.writeConfig(java.util.Collections.singletonList(spec));
    List<com.badlogic.gdx.utils.JsonValue> specs = ServerEvents.configSpecs(blob);
    check(specs.size() == 1, "1 spec persistée (" + specs.size() + ")");
    check("TRIAL_FRANCHISE".equals(specs.get(0).getString("kind", "")), "kind TRIAL_FRANCHISE");
    check(specs.get(0).getLong("id", -1) == EV, "id (eventID) préservé");
    System.out.println("[trialpush] config round-trip : " + specs.get(0) + " ✔");

    // (2) reconstruction data-driven → event TRIAL, ClientEventTrial subtrials>0, eventID = id de la spec.
    List<com.perblue.common.specialevent.SpecialEventInfo> events = ServerEvents.eventsFromConfig(blob);
    check(events.size() == 1, "1 event reconstruit (" + events.size() + ")");
    com.perblue.common.specialevent.SpecialEventInfo info = events.get(0);
    check(info.getID() == EV, "event.id == eventID (" + info.getID() + ")");
    ServerUser su = ServerUser.newPlayer(8861L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 200;
    com.perblue.heroes.game.objects.trials.ClientEventTrial t =
        new com.perblue.heroes.game.objects.trials.ClientEventTrial(su.gameUser(), info);
    check(t.getSubtrials() != null && t.getSubtrials().size() > 0,
        "ClientEventTrial reconstruit avec sous-trials (" + (t.getSubtrials() == null ? "null" : t.getSubtrials().size()) + ")");
    System.out.println("[trialpush] event reconstruit : id=" + info.getID() + ", sous-trials=" + t.getSubtrials().size() + " ✔");

    // (3) toRaw : sérialise l'event pour le PUSH client (REFRESH_SPECIAL_EVENTS → SpecialEventsRaw).
    SpecialEventsRaw raw = ServerEvents.toRaw(events);
    check(raw != null, "SpecialEventsRaw produit (push client)");
    System.out.println("[trialpush] toRaw (push client) OK ✔");

    System.out.println("[trialpush] OK — push admin du franchise trial : spec persistée → event data-driven → push client [headless].");
  }
}
