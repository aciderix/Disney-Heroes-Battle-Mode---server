import com.perblue.heroes.network.messages.*;
import dhserver.*;

/**
 * CHALLENGES (#72) incrément 2 — BOUCLE setup/claim/cancel + PERSISTANCE. Tout par le CODE DU JEU (§3), zéro
 * invention (§4) : {@code StickerHelper.setupStarterChallenges} (auto-population), {@code claimSticker}
 * (récompenses autoritatives depuis challenge_stickers.tab), {@code cancelChallenge}. Vérifie le round-trip
 * client↔message ({@code ServerChallenges.toMessage}), le crédit de récompenses, l'anti-double-réclamation, et la
 * PERSISTANCE DB ({@code UserStore}).
 */
public final class ChallengeLoopTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[challenge-loop] " + m); }

  static long tokens(ServerUser su) { return su.gameUser().getResource(ResourceType.CHALLENGE_TOKENS); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();

    // --- SETUP : le jeu auto-peuple le défi STARTER (gaté Unlockable.CHALLENGES = TL20) ---
    ServerUser su = ServerUser.newPlayer(9001L, 1);
    su.bootData().userInfo.basicInfo.teamLevel = 100;
    boolean setup = ServerChallenges.ensureSetup(su);
    check(setup, "ensureSetup doit peupler le défi STARTER à TL100");
    UserChallengeDataExtra d = su.challengeDataOrNull();
    check(d != null && d.slots != null, "état de défis persisté après setup");
    ChallengeHandleExtra starter = (ChallengeHandleExtra) d.slots.get(ChallengeSlots.STARTER);
    check(starter != null, "slot STARTER peuplé par setupStarterChallenges (obtenu " + d.slots.keySet() + ")");
    check(starter.type != null && starter.maxProgress > 0, "handle STARTER a un type + maxProgress (data du jeu)");
    check(starter.endTime > com.perblue.heroes.util.TimeUtil.serverTimeNow(),
        "endTime dans le futur (serverTime + getDuration)");
    StickerType type = starter.type;
    System.out.println("[challenge-loop] setup: STARTER=" + type + " progress=" + starter.currentProgress + "/"
        + starter.maxProgress + " endTime=" + starter.endTime);

    // idempotent : re-appeler ne change rien
    check(!ServerChallenges.ensureSetup(su), "ensureSetup idempotent (STARTER déjà posé)");

    // --- SETUP gaté : un joueur SOUS le TL d'unlock ne reçoit rien ---
    ServerUser low = ServerUser.newPlayer(9002L, 1);
    low.bootData().userInfo.basicInfo.teamLevel = 5;
    check(!ServerChallenges.ensureSetup(low), "ensureSetup ne fait rien sous le TL d'unlock (gate du jeu)");

    // --- CLAIM : simule la COMPLÉTION (progression = client-autoritatif, patron loot) puis réclame ---
    // Le défi se complète par le gameplay (CampaignStarsChallenge etc.) ; ici on pose currentProgress = maxProgress
    // (l'état qu'aurait poussé la progression) pour exercer la réclamation autoritative du serveur.
    starter.currentProgress = starter.maxProgress;
    su.setChallengeData(d);

    long before = tokens(su);
    boolean claimed = ServerChallenges.applyClaim(su, type, ChallengeSlots.STARTER);
    check(claimed, "applyClaim doit réussir sur un défi prêt");
    long after = tokens(su);
    int reward = com.perblue.heroes.game.logic.StickerHelper.getChallengeSticker(type).getTokenReward();
    check(after - before == reward, "CHALLENGE_TOKENS crédités = getTokenReward()=" + reward + " (obtenu +" + (after - before) + ")");
    UserChallengeDataExtra d2 = su.challengeDataOrNull();
    check(d2.completionTime != null && d2.completionTime.get(type) != null,
        "completionTime posé pour " + type + " après claim");
    // Comportement RÉEL du jeu (claimSticker) : le handle réclamé est RETIRÉ du slot (setHandle null) puis, pour
    // le slot STARTER, setupStarterChallenges ré-avance au défi STARTER suivant (starterChallenge croissant).
    ChallengeHandleExtra next = (ChallengeHandleExtra) d2.slots.get(ChallengeSlots.STARTER);
    check(next != null, "STARTER ré-avance au défi suivant après claim (auto-population du jeu)");
    check(next.type != type, "le slot STARTER contient un NOUVEAU défi (" + next.type + " != " + type + ")");
    check(!next.claimed && next.currentProgress == 0, "le défi STARTER suivant est neuf (non réclamé, progress 0)");
    StickerType nextType = next.type;
    System.out.println("[challenge-loop] claim: +" + (after - before) + " CHALLENGE_TOKENS, completionTime[" + type
        + "] posé, STARTER ré-avancé → " + nextType);

    // --- ANTI-DOUBLE : re-réclamer ne crédite rien ---
    long dup = tokens(su);
    check(!ServerChallenges.applyClaim(su, type, ChallengeSlots.STARTER), "re-claim refusé (déjà réclamé)");
    check(tokens(su) == dup, "aucun token en double sur re-claim");

    // --- PERSISTANCE DB : sauvegarde puis relecture depuis un store neuf ---
    String db = System.getProperty("java.io.tmpdir") + "/dh-challenge-loop-" + System.nanoTime() + ".db";
    UserStore store = new UserStore(db);
    store.save(su);
    ServerUser reloaded = store.loadIfExists(9001L, 1);
    check(reloaded != null, "joueur relu depuis la DB");
    UserChallengeDataExtra dr = reloaded.challengeDataOrNull();
    check(dr != null, "état de défis persisté (colonne challengeData)");
    check(dr.completionTime != null && dr.completionTime.get(type) != null,
        "completionTime survit à la persistance DB");
    ChallengeHandleExtra hr = (ChallengeHandleExtra) dr.slots.get(ChallengeSlots.STARTER);
    check(hr != null && hr.type == nextType, "défi STARTER ré-avancé (" + nextType + ") survit à la persistance DB");
    check(reloaded.gameUser().getResource(ResourceType.CHALLENGE_TOKENS) == after,
        "solde CHALLENGE_TOKENS survit à la persistance DB");
    WireCheck.assertRoundTrips(dr);
    store.close();

    // --- CANCEL : sur un nouveau joueur, annule le défi STARTER en cours ---
    ServerUser cx = ServerUser.newPlayer(9003L, 1);
    cx.bootData().userInfo.basicInfo.teamLevel = 100;
    ServerChallenges.ensureSetup(cx);
    ChallengeHandleExtra cs = (ChallengeHandleExtra) cx.challengeDataOrNull().slots.get(ChallengeSlots.STARTER);
    check(cs != null, "STARTER posé avant cancel");
    boolean cancelled = ServerChallenges.applyCancel(cx, cs.type, ChallengeSlots.STARTER);
    check(cancelled, "applyCancel doit réussir sur un défi en cours");
    System.out.println("[challenge-loop] cancel: défi STARTER annulé (slot=" + cx.challengeDataOrNull().slots.keySet() + ")");

    System.out.println("[challenge-loop] OK — setup/claim/anti-double/persistance/cancel via le code du jeu — #72 incrément 2");
  }
}
