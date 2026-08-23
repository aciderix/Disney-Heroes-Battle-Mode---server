import dhserver.*;
import com.perblue.common.specialevent.SpecialEventInfo;
import com.perblue.heroes.game.objects.User;
import com.perblue.heroes.game.objects.UserFlag;
import com.perblue.heroes.game.ClientNetworkStateConverter;
import com.perblue.heroes.network.messages.*;
import java.util.*;

/**
 * SPECIAL_EVENTS live-ops — composant <b>FLAG_USER_ON_LOGIN</b> (pose/retire des flags joueur au login).
 *
 * <p>Action SERVEUR-autoritative (le jar client ne consomme pas le snapshot). {@code ServerEvents.applyLoginFlags} écrit dans la
 * MAP WIRE {@code userExtra.flags} ({@code Map<UserFlag,Boolean>}) : POSE {@code set} (TRUE), RETIRE {@code clear} (FALSE). Prouvé :
 * (1) applyLoginFlags pose/retire les bons flags ; (2) round-trip WIRE via {@code User.setFlags} → {@code hasFlag} cohérent ;
 * (3) round-trip de la spec. Flags = <b>params ADMIN</b> ({@code AdminEvents --flag-login --set-flag <F> --clear-flag <F>}).
 */
public final class FlagUserOnLoginTest {
  static void check(boolean c, String m) { if (!c) throw new AssertionError("[flaglogin] " + m); }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(9290L, 1);
    ServerContext.bind(su.gameUser(), su.gameUser().getIndividual());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();

    UserFlag SET = UserFlag.BATTLE_PASS_V2_SHOW_UPSELL;   // à POSER
    UserFlag CLR = UserFlag.CAN_CLAIM_FREE_HERO;           // à RETIRER (pré-posé)

    // Event : set SET, clear CLR.
    SpecialEventInfo ev = ServerEvents.buildFlagUserOnLoginEvent(700_090L, Collections.singletonList(SET), Collections.singletonList(CLR), now - 1000, now + 86_400_000L);
    ServerEvents.install(Collections.singletonList(ev));

    // Map wire (clé = NOM du flag, String ; valeur Boolean) avec CLR déjà posé (pour prouver le RETRAIT).
    Map<Object, Object> flags = new HashMap<>();
    flags.put(CLR.name(), Boolean.TRUE);
    int changed = ServerEvents.applyLoginFlags(flags);
    check(changed == 2, "2 flags modifiés (set + clear) (" + changed + ")");
    check(Boolean.TRUE.equals(flags.get(SET.name())), "SET posé (TRUE) : " + flags.get(SET.name()));
    check(Boolean.FALSE.equals(flags.get(CLR.name())), "CLR retiré (FALSE) : " + flags.get(CLR.name()));
    System.out.println("[flaglogin] applyLoginFlags : " + SET + "=TRUE, " + CLR + "=FALSE (" + changed + " modifs) ✔");

    // Round-trip WIRE : User.setFlags(map) → hasFlag cohérent (fidélité §3).
    UserExtra ux = su.bootData().userExtra;
    ux.flags.clear();
    ux.flags.putAll((Map) flags);
    User u = ClientNetworkStateConverter.getUser(su.bootData().userInfo, ux, "flag");
    check(u.hasFlag(SET), "round-trip : hasFlag(SET)=true");
    check(!u.hasFlag(CLR), "round-trip : hasFlag(CLR)=false");
    System.out.println("[flaglogin] round-trip wire : hasFlag(" + SET + ")=true, hasFlag(" + CLR + ")=false ✔");

    // Round-trip de la spec.
    String spec = ServerEvents.specJsonFlagUserOnLogin(700_090L, Collections.singletonList(SET), Collections.singletonList(CLR), now - 1000, now + 86_400_000L);
    List<SpecialEventInfo> rebuilt = ServerEvents.eventsFromConfig(ServerEvents.writeConfig(Collections.singletonList(spec)));
    check(rebuilt.size() == 1 && rebuilt.get(0).getID() == 700_090L, "spec FLAG_USER_ON_LOGIN round-trip");
    ServerEvents.install(rebuilt);
    Map<Object, Object> flags2 = new HashMap<>();
    check(ServerEvents.applyLoginFlags(flags2) == 1 && Boolean.TRUE.equals(flags2.get(SET.name())), "event reconstruit → pose SET");
    System.out.println("[flaglogin] spec round-trip → event reconstruit pose le flag ✔");

    ServerEvents.install(new ArrayList<>());
    System.out.println("[flaglogin] OK — FLAG_USER_ON_LOGIN objet du jeu, flags set/clear = params admin, appliqué serveur au login. [headless]");
  }
}
