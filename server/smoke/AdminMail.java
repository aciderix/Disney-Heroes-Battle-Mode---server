import com.perblue.heroes.network.messages.MailType;
import com.perblue.heroes.network.messages.ResourceType;
import com.perblue.heroes.network.messages.RewardDrop;
import com.perblue.heroes.game.logic.RewardHelper;
import dhserver.ServerContext;
import dhserver.ServerUser;
import dhserver.UserStore;

import java.util.*;

/**
 * PANNEAU ADMIN (opérateur) — envoi de COURRIER serveur, CIBLÉ (un joueur) ou GLOBAL (tous les comptes d'un shard),
 * avec texte + récompenses ARBITRAIRES en pièces jointes. C'est l'interface OPÉRATEUR du serveur ré-hébergé (le
 * client n'a pas d'écran admin) : elle réutilise le courrier au format wire du jeu + la mailbox persistée (#35/#36),
 * livré dans la {@code BootData} à la prochaine connexion. Multi-serveur : tout passe par la base (PRINCIPLES §5/§6).
 *
 * Usage :
 *   AdminMail --db server/data/dh-server.db --shard 1 (--to &lt;userID&gt; | --all)
 *             --subject "Titre" --body "Texte" [--from "Expéditeur"] [--type SYSTEM_MESSAGE]
 *             [--reward DIAMONDS:500] [--reward SOFT_CURRENCY:100000] ...
 *
 * Exemples :
 *   AdminMail --shard 1 --all      --subject "Maintenance" --body "Merci de votre patience !" --reward DIAMONDS:200
 *   AdminMail --shard 1 --to 1     --subject "Cadeau"       --body "Pour toi." --reward SOFT_CURRENCY:50000
 */
public final class AdminMail {

  public static void main(String[] a) throws Exception {
    Map<String, String> opt = new HashMap<>();
    List<String> rewards = new ArrayList<>();
    for (int i = 0; i < a.length; i++) {
      String k = a[i];
      if ("--all".equals(k)) { opt.put("all", "1"); continue; }
      if ("--reward".equals(k) && i + 1 < a.length) { rewards.add(a[++i]); continue; }
      if (k.startsWith("--") && i + 1 < a.length) opt.put(k.substring(2), a[++i]);
    }
    String db = opt.getOrDefault("db", "server/data/dh-server.db");
    int shard = Integer.parseInt(opt.getOrDefault("shard", "1"));
    boolean all = opt.containsKey("all");
    String subject = opt.getOrDefault("subject", "");
    String body = opt.getOrDefault("body", "");
    String from = opt.getOrDefault("from", "Game Master");
    MailType type = MailType.valueOf(opt.getOrDefault("type", "SYSTEM_MESSAGE"));
    if (!all && !opt.containsKey("to")) {
      System.out.println("Usage: AdminMail --shard <s> (--to <userID> | --all) --subject S --body B "
          + "[--from F] [--type T] [--reward RES:amt ...]");
      return;
    }

    // Récompenses arbitraires → RewardDrop (format/logique du jeu).
    List<RewardDrop> drops = new ArrayList<>();
    for (String r : rewards) {
      String[] kv = r.split(":");
      if (kv.length != 2) { System.out.println("[admin] récompense ignorée (format RES:amt) : " + r); continue; }
      try {
        drops.add(RewardHelper.createDrop(ResourceType.valueOf(kv[0].trim()), Long.parseLong(kv[1].trim())));
        System.out.println("[admin] pièce jointe : " + kv[0].trim() + " × " + kv[1].trim());
      } catch (Throwable t) { System.out.println("[admin] récompense invalide : " + r + " (" + t + ")"); }
    }

    ServerContext.init();
    try (UserStore store = new UserStore(db)) {
      List<Long> targets = new ArrayList<>();
      if (all) targets.addAll(store.listUserIDs(shard, -1L));      // TOUS les comptes du shard
      else targets.add(Long.parseLong(opt.get("to")));

      int sent = 0;
      for (Long id : targets) {
        ServerUser su = store.loadIfExists(id, shard);
        if (su == null) { System.out.println("[admin] joueur " + id + " introuvable (shard " + shard + ")"); continue; }
        long mailID = su.deliverMail(type, from, subject, body, drops);
        store.save(su);
        System.out.println("[admin] → joueur " + id + " : courrier #" + mailID + " (" + type + ") livré+persisté");
        sent++;
      }
      System.out.println("[admin] TERMINÉ — " + sent + " courrier(s) envoyé(s) (" + (all ? "GLOBAL shard " + shard : "ciblé") + ")");
    }
  }
}
