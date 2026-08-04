package dhserver;

import com.perblue.heroes.game.logic.SurgeHelper;
import com.perblue.heroes.game.data.surge.SurgeStats;

/**
 * SURGE (#72) — SOCLE : CALENDRIER & IDENTITÉ, calculés par le CODE DU JEU (PRINCIPLES §3, jamais réinventé).
 *
 * <p>SURGE est un mode de GUILDE saisonnier (districts/vagues/régions/paliers/objectifs/raids/QG), gaté par
 * l'{@code Unlockable.SURGE_OBJECTIVES} + des perks de guilde. La logique riche est CLIENTE
 * ({@code SurgeHelper}/{@code SurgeClientHelper}/{@code SurgeStats}) ; le serveur EXÉCUTE ses helpers et tient
 * l'état partagé. Comme l'arène/invasion, l'info n'est PAS dans le BootData : le client la demande via
 * {@code GetSurge} (à implémenter, incrément 2).
 *
 * <p><b>Calendrier — 100 % code du jeu.</b> {@code SurgeHelper.getNextSurgeStartTime(now)} et
 * {@code getSurgeEndTime(now)} donnent la fenêtre ; {@code SurgeStats.getIntermission()} l'entracte (15 min mesuré).
 * <b>Fait établi (§8, sonde headless)</b> : un surge est ACTIF à {@code now} ⟺ {@code nextStart > end}
 * (pendant un surge actif, le PROCHAIN départ tombe APRÈS la fin courante ; pendant l'entracte, le prochain
 * départ précède la fin du surge suivant). Vérifié : sonde à {@code now} → {@code nextStart 16:15 > end 16:00}
 * ⇒ actif. Aucune date inventée : tout vient de {@code SurgeHelper}/{@code SurgeStats}.
 *
 * <p>Ce fichier ne porte QUE le calendrier/identité (incrément 1). L'état partagé de guilde (membres, adversaires,
 * régions, paliers, objectifs, récompenses) + les handlers {@code GetSurge}/{@code StartSurgeAttack}/… viennent
 * aux incréments suivants (cf. docs/SURGE.md).
 */
public final class ServerSurge {

  private ServerSurge() {}

  /** Un surge est-il ACTIF à {@code now} ? (⟺ prochain départ après la fin courante — cf. en-tête, prouvé §8). */
  public static boolean isActive(long now) {
    ServerContext.init();
    return SurgeHelper.getNextSurgeStartTime(now) > SurgeHelper.getSurgeEndTime(now);
  }

  /** Fin du surge courant (si actif) — {@code SurgeHelper.getSurgeEndTime}. */
  public static long surgeEndTime(long now) {
    ServerContext.init();
    return SurgeHelper.getSurgeEndTime(now);
  }

  /** Début du PROCHAIN surge — {@code SurgeHelper.getNextSurgeStartTime}. */
  public static long nextSurgeStartTime(long now) {
    ServerContext.init();
    return SurgeHelper.getNextSurgeStartTime(now);
  }

  /** Entracte entre deux surges (ms) — {@code SurgeStats.getIntermission} (15 min mesuré). */
  public static long intermission() {
    ServerContext.init();
    return SurgeStats.getIntermission();
  }

  /**
   * Identité du surge courant : id opaque STABLE par fenêtre, assigné par le SERVEUR (le client ne fait que
   * relire {@code SurgeData.surgeID} pour corréler ses actions). On prend la FIN de la fenêtre active (unique par
   * surge, déterministe) ; {@code 0} si aucun surge actif. Pas une règle du jeu — une clé d'état serveur.
   */
  public static long currentSurgeID(long now) {
    return isActive(now) ? surgeEndTime(now) : 0L;
  }

  /** Le surge est-il activé côté SERVEUR pour cette difficulté ? — {@code SurgeHelper.isEnabledOnServer}. */
  public static boolean isEnabledOnServer(int difficulty) {
    ServerContext.init();
    return SurgeHelper.isEnabledOnServer(difficulty);
  }
}
