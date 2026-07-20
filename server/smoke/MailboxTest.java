import com.perblue.heroes.network.messages.*;
import com.perblue.heroes.game.objects.User;
import dhserver.ServerContext;
import dhserver.ServerUser;

/**
 * MAILBOX — courrier REÇU du serveur (jamais composé par le joueur). Vérifie le flux complet sur le courrier
 * d'onboarding NEW_USER_WELCOME livré à un compte neuf :
 * <ul>
 *   <li><b>Livraison</b> : BootData.mailMessages contient le courrier de bienvenue (500 diamants en pièce jointe) ;</li>
 *   <li><b>MARK_MAIL_OPENED</b> → marqué lu ;</li>
 *   <li><b>TAKE_MAIL_ATTACHMENTS</b> → récompense créditée (diamants) + pièces jointes vidées ; re-TAKE REFUSÉ ;</li>
 *   <li><b>Persistance</b> : la mailbox survit au reload wire ;</li>
 *   <li><b>DELETE_MAIL_MESSAGE</b> → courrier retiré.</li>
 * </ul>
 */
public final class MailboxTest {

  static Action cmd(CommandType c, long id) {
    Action m = new Action(); m.command = c;
    m.extra = new java.util.EnumMap<>(ActionExtraType.class);
    m.extra.put(ActionExtraType.ID, Long.toString(id));
    return m;
  }

  static long diamonds(ServerUser su) { return su.bootData().userInfo.diamonds; }

  public static void main(String[] a) throws Exception {
    ServerContext.init();
    ServerUser su = ServerUser.newPlayer(1L, 1);

    // 1) LIVRAISON : le compte neuf a 1 courrier NEW_USER_WELCOME avec 500 diamants en pièce jointe.
    BootData bd = su.bootData();
    if (bd.mailMessages == null || bd.mailMessages.isEmpty()) throw new AssertionError("un courrier de bienvenue attendu dans BootData");
    MailMessage wm = (MailMessage) bd.mailMessages.get(0);
    int attach = wm.extra == null || wm.extra.attachments == null ? 0 : wm.extra.attachments.size();
    System.out.println("[mail] livré : type=" + wm.type + " id=" + wm.iD + " subject=\"" + wm.subject
        + "\" opened=" + wm.opened + " piècesJointes=" + attach);
    if (wm.type != MailType.NEW_USER_WELCOME) throw new AssertionError("type NEW_USER_WELCOME attendu");
    if (attach != 1) throw new AssertionError("1 pièce jointe (récompense) attendue");

    long diaBefore = diamonds(su);

    // 2) MARK_MAIL_OPENED
    boolean opened = su.applyAction(cmd(CommandType.MARK_MAIL_OPENED, wm.iD));
    boolean isOpened = ((MailMessage) su.bootData().mailMessages.get(0)).opened;
    System.out.println("[mail] MARK_MAIL_OPENED appliqué=" + opened + " opened=" + isOpened);
    if (!opened || !isOpened) throw new AssertionError("le courrier aurait dû être marqué lu");

    // 3) TAKE_MAIL_ATTACHMENTS → 500 diamants crédités + pièces jointes vidées.
    boolean took = su.applyAction(cmd(CommandType.TAKE_MAIL_ATTACHMENTS, wm.iD));
    long diaAfter = diamonds(su);
    int attachAfter = ((MailMessage) su.bootData().mailMessages.get(0)).extra.attachments.size();
    System.out.println("[mail] TAKE appliqué=" + took + " | diamants " + diaBefore + "→" + diaAfter
        + " | piècesJointes restantes=" + attachAfter);
    if (!took) throw new AssertionError("la récupération des pièces jointes aurait dû réussir");
    if (diaAfter != diaBefore + 500) throw new AssertionError("les 500 diamants auraient dû être crédités");
    if (attachAfter != 0) throw new AssertionError("les pièces jointes auraient dû être vidées");

    // 4) ANTI-RE-CLAIM : re-TAKE → REFUSÉ.
    boolean took2 = su.applyAction(cmd(CommandType.TAKE_MAIL_ATTACHMENTS, wm.iD));
    System.out.println("[mail] RE-TAKE appliqué=" + took2 + " (doit être false : déjà pris)");
    if (took2) throw new AssertionError("re-prendre les pièces jointes aurait dû être REFUSÉ");

    // 5) PERSISTANCE : reload wire (mailbox sérialisée) → courrier toujours là (lu, vidé), diamants persistés.
    ServerUser reloaded = ServerUser.fromWire(1L, 1, su.userInfoWire(), su.userExtraWire(), su.individualWire());
    reloaded.setBattlePassWire(su.battlePassWire());
    reloaded.setMailWire(su.mailWire());
    BootData rb = reloaded.bootData();
    boolean stillThere = rb.mailMessages != null && !rb.mailMessages.isEmpty()
        && ((MailMessage) rb.mailMessages.get(0)).iD == wm.iD;
    boolean stillOpenedEmpty = stillThere && ((MailMessage) rb.mailMessages.get(0)).opened
        && ((MailMessage) rb.mailMessages.get(0)).extra.attachments.isEmpty();
    System.out.println("[mail] après reload → courrier présent=" + stillThere + " lu+vidé=" + stillOpenedEmpty
        + " diamants=" + rb.userInfo.diamonds);
    if (!stillThere || !stillOpenedEmpty) throw new AssertionError("la mailbox (lu, vidé) aurait dû PERSISTER");
    if (rb.userInfo.diamonds != diaBefore + 500) throw new AssertionError("les diamants crédités auraient dû PERSISTER");

    // 6) DELETE_MAIL_MESSAGE → mailbox vide.
    boolean deleted = reloaded.applyAction(cmd(CommandType.DELETE_MAIL_MESSAGE, wm.iD));
    int left = reloaded.bootData().mailMessages == null ? 0 : reloaded.bootData().mailMessages.size();
    System.out.println("[mail] DELETE appliqué=" + deleted + " | courriers restants=" + left);
    if (!deleted || left != 0) throw new AssertionError("le courrier aurait dû être supprimé");

    System.out.println("[mail] OK — livraison NEW_USER_WELCOME + open + take (crédit 500 diamants, anti-re-claim) + persistance + delete");
  }
}
