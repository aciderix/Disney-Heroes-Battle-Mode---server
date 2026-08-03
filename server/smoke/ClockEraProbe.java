import dhserver.ServerContext;

/** Sonde : l'ÈRE de contenu (R1…R102) suit-elle l'horloge SERVEUR ? Règle {@code -Ddh.clock.offset.hours} puis
 *  lit la colonne de contenu pour l'heure de jeu courante et imprime la R (via Max TL) — preuve que décaler
 *  l'horloge décale l'ère (démarrer R1, avancer, etc.). AUCUNE valeur inventée : tout vient de content.<shard>.tab. */
public final class ClockEraProbe {
  public static void main(String[] a) throws Exception {
    ServerContext.init();   // applique -Ddh.clock.offset.hours
    int shard = Integer.getInteger("dh.shard", 1);
    com.perblue.heroes.game.data.content.ContentHelper.get().setShardID(shard, new java.util.HashMap<>());
    long now = com.perblue.heroes.util.TimeUtil.serverTimeNow();
    String date = new org.joda.time.DateTime(now, com.perblue.heroes.util.TimeUtil.getServerDateTimeZone())
        .toString("yyyy-MM-dd HH:mm");
    com.perblue.heroes.game.data.content.ContentStats.ContentColumn col =
        com.perblue.heroes.game.data.content.ContentHelper.getCurrent(now);
    System.out.println("[era] shard " + shard + " | heure de JEU = " + date
        + " | Max Team Level = " + (col == null ? "?" : col.getMaxTeamLevel()));
  }
}
