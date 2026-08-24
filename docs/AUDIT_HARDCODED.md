# AUDIT A3 — valeurs en dur côté serveur (candidats)

> AUTO-GÉNÉRÉ par `tools/audit/audit.sh a3` — 2026-08-24T21:13Z. **Heuristique** (littéraux « métier » : cost/limit/max/percent/…).
> **Beaucoup de faux positifs attendus** (défauts admin légitimes, valeurs de test, bornes techniques). CHAQUE candidat
> se tranche à la main : (a) **RÉEL** = doit venir de `.tab`/code du jeu/param admin → à corriger (§4) ; (b) **OK** =
> défaut opérateur/param admin/technique légitime → marquer `[OK]` avec justification (ne plus re-signaler).
>
> Rappel §4 : une VALEUR DE RÈGLE (coût, chance, palier, barème) ne s'invente pas — elle s'extrait de `.tab`/bytecode.
> Une valeur de CONFIG opérateur (défaut d'un flag AdminEvents) EST légitime en dur (c'est un défaut, pas une règle).

**0 candidat.** ✅ Aucun littéral métier (cost/limit/max/percent/…) ni `return <n>` codé en dur détecté dans
`server/java` par l'heuristique → cohérent avec §4 (le serveur lit ses valeurs depuis `.tab`/code du jeu ; les
défauts de config restent dans les outils `Admin*`, pas dans la logique serveur). Élargir l'heuristique si besoin.
