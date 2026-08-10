package dhserver;

import com.perblue.heroes.network.messages.ExpeditionRunData;
import com.perblue.heroes.network.messages.ExpeditionWeeklyInfo;
import com.perblue.heroes.network.messages.GetExpeditionResponse;

/**
 * EXPEDITION (#72) — mode « Expédition » (solo progression + combat). Cf. docs/EXPEDITION.md.
 *
 * <p><b>Incrément 1</b> — BOOT / RENDU. Le client ({@code GameMain}) envoie {@code GetExpedition} à l'ouverture de
 * l'écran ; le serveur répond {@code GetExpeditionResponse} (patron {@code GetSurge}). Pour un état FRAIS (aucun run
 * actif), on renvoie {@code currentExpedition=null} (→ le client montre la sélection de difficulté), l'{@code expeditionID}
 * PERSISTÉ ({@code individualUserExtra.expeditionID}) et un {@code weeklyWardInfo} NON-NULL sûr (wards vides = pas de
 * modificateur cette semaine, baseline fidèle ; les wards réels = incrément 5).
 *
 * <p>Le gate {@code Unlockable.EXPEDITION} (TL25) est un verrou CLIENT (le client n'envoie pas {@code GetExpedition}
 * s'il est gaté) — le serveur RÉPOND, ne désactive rien (§8). La génération du run (nœuds/defenders) + le combat +
 * le raid + le reset = incréments 2-6.
 */
public final class ServerExpedition {

  private ServerExpedition() {}

  /**
   * Réponse à {@code GetExpedition} pour l'état courant du joueur. Incrément 1 : aucun run actif → sélection de
   * difficulté côté client. Ne persiste pas (lecture seule).
   */
  public static GetExpeditionResponse response(ServerUser su) {
    ServerContext.init();
    GetExpeditionResponse r = new GetExpeditionResponse();
    r.expeditionID = su.expeditionIDPersisted();
    // Le codec écrit currentExpedition.writeSingle SANS garde null → il DOIT être non-null. Un ExpeditionRunData
    // vide (difficulty=0, nodesDefeated=0, listes vides) = « aucun run actif » → le client montre la sélection de
    // difficulté (incr. 2 : génération du run à la sélection). Baseline fidèle sans invention (§4).
    r.currentExpedition = new ExpeditionRunData();
    r.wasReset = false;
    r.weeklyWardInfo = new ExpeditionWeeklyInfo();   // ctor du jeu = listes non-null vides (baseline sans ward)
    return r;
  }
}
