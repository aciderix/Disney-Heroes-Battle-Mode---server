package com.perblue.heroes.cspine;

/**
 * Coquille qui SHADOW {@code com.perblue.heroes.cspine.Native} du jeu (dont le
 * {@code <clinit>} chargeait {@code libspine-native64.so}). Ici : aucun chargement de natif.
 *
 * Le module dhcspine réimplémente toutes les classes {@code cspine} au-dessus du runtime Spine
 * JAVA (spine-libgdx) → plus aucune méthode native n'est appelée. Cette classe existe seulement
 * au cas où du code y ferait référence directement, pour éviter le crash de chargement du .so.
 * Voir desktop-port/SPINE_FEASIBILITY.md (option A). Fidélité : RÉEL (natif remplacé par Java).
 */
public final class Native {
    private Native() {}
}
