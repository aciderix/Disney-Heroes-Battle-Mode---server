#!/usr/bin/env bash
# RÉGRESSION serveur — compile toutes les sources serveur + smoke tests, puis exécute la SUITE de tests
# assertifs (self-contained, sans argument). Un test PASSE s'il termine avec code 0 ET sans AssertionError.
# Les OUTILS/SONDES paramétrés (SkillSetup, MakeRoster, SetTeamLevel, DbInspect, *Probe, TutoState…) sont
# EXCLUS (ils exigent des arguments/un état). Classpath complet (game-framed + sqlite/slf4j/joda), comme
# run-online.sh, pour exécuter la VRAIE logique du jeu.
set -uo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
export JAVA_TOOL_OPTIONS=
FRAMED="$ROOT/libs/game-framed.jar"; [ -f "$FRAMED" ] || FRAMED="$ROOT/libs/game.jar"
CPF="$FRAMED:$ROOT/libs/commons-logging.jar:$ROOT/libs/sqlite-jdbc.jar:$ROOT/libs/slf4j-api.jar:$ROOT/libs/joda-time.jar"
SMOKE="$ROOT/server/smoke"
# Répertoire de classes ISOLÉ par exécution. Historiquement c'était "$SMOKE/out" — le même dossier que celui
# utilisé pour les compilations manuelles pendant le développement. Une compilation manuelle lancée PENDANT une
# régression écrasait ses .class en cours de route et produisait une avalanche d'échecs FANTÔMES (16 tests
# « rouges » sans même de fichier de log). L'isolation rend ce faux positif impossible.
OUT="$(mktemp -d "${TMPDIR:-/tmp}/dh-reg-XXXXXX")"
trap 'rm -rf "$OUT"' EXIT

# Suite de régression (tests assertifs, exécutables sans argument).
TESTS=(
  ResourceTest RosterTest
  SigninTest SigninMultiDayTest SigninAllRewardsTest
  EquipTest CampaignAttackTest CampaignPersistTest EliteCampaignRaidTest EliteRaidAuthorityTest
  ChestWireTest ChestChargeTest ChestPaidDebitTest ChestValidateTest FreeChestTest ViewedChestsTest
  BattlePassTest BattlePassClaimTest BattlePassPointsTest BattlePassRolloverTest
  CompleteQuestTest WeeklyBoxTest WeeklyQuestTest
  MailboxTest ItemsTest SkillUpgradeTest AlchemyTest SetFlagTest UnlockHeroTest
  UpdateTimeTest SetNameTest SetLanguageTest TeamLevelPersistTest
  LootAuthoritativeTest LootEquipTest LootPersistTest LootDeterminismTest LootCreditAuthoritativeTest LootParityMultiCombatTest LootSeedChainTest SeedTest
  ArenaInfoTest ArenaDefenseTest ArenaLadderTest ArenaAttackTest ArenaRealPvPTest ArenaConcurrencyTest ArenaFightResetTest
  ArenaRewardsTest
  GuildCreateTest GuildManageTest GuildCheckInTest GuildMembersTest GuildChatTest GuildDonationTest GuildDonateTest
  GuildMercenaryTest GuildInfluenceTest GuildAvatarTest GuildMercRewardTest GuildSkillDonationTest GuildGiftTest GuildContestTest GuildHeroXPDonationTest GuildContestSeasonTest InvasionScheduleTest InvasionBossTest BreakerQuestTest GuildClaimInactiveTest
  WarSeasonTest WarStateTest WarMatchmakingTest WarCarsTest WarAttackTest WarEndTest WarSabotageTest
  WarSchedulerTest
  ClockAnchorTest
  WireCheck
  ClientOracle ClientOracleR1Test SendValidationTest
  SurgeScheduleTest SurgeStateTest SurgeCombatTest SurgeMapTest SurgeAttackFlowTest SurgeClaimTest SurgeRaidTest
  ChallengeBootTest ChallengeLoopTest ChallengeShopTest ChallengeViewTest
  FriendshipBootTest FriendshipShopTest FriendshipEmpowerTest FriendshipCampaignTest MissionLoopTest MissionSpeedupTest
  ExpeditionBootTest ExpeditionCombatTest ExpeditionRaidTest ExpeditionWardTest ExpeditionResetTest ExpeditionChestTest
  EnchantApplyTest EnchantGuardTest EnchantMaxUpgradeTest
  LineupSaveTest LineupCooldownTest LineupFieldsTest
  CollectionClaimTest CollectionMasteryTest CollectionAvatarTest
  WishingWellTargetTest WishingWellWishTest
  MerchantGenTest MerchantPurchaseTest MerchantRefreshTest MerchantLimitedTest
  PortAttackTest
  PortRaidTest
  PortDoubleRewardTest
  PortWarehouseTest
  SpecialEventsModesOpenTest
  SpecialEventsRotationTest
  TrialsWireTest
  TeamTrialsAttackTest
  SpotlightTrialTest
  FranchiseTrialStructTest
  FranchiseTrialContentTest
  ServerTrialsDataTest
  TrialEventRecordTest
  TrialResetTest
  TrialGatingTest
  TrialCompletionTest
  TrialAdminPushTest
  TrialRewardsTest
  SeasonAnchorTest
  ChestDiscountTest
  IncreasedChancesTest
  MerchantDiscountTest
  MiscMultipliersTest
  FlagUserOnLoginTest
  TeamLevelTest
  ExtraChestTest
  ContestTest
  ContestDataTest
  ContestCreditTest
  ContestRewardTest
  ContestRankTest
  ContestRankingsTest
  ContestGuildTest
  ContestCampaignRecordTest
  ContestEndTest
  WiringGapsTest
  CodebaseTest
  ReleaseOffsetTest
  MnemonicIdentityTest
  AccountStoreTest
  SessionAuthTest
  AuthServiceTest
  AuthFlowTest
  LauncherLoginTest
  LauncherServersTest
  AuthMintTest
  StrictSingleLoginTest
  HostLifecycleTest
  PlayLifecycleTest
  SettingsLifecycleTest
  AdminMonitorTest
  AdminProxyTest
  AdminRemoteTargetTest
  BuildDataGenTest
  ServerBundleTest
)

echo "[reg] compilation (serveur + ${#TESTS[@]} tests) ..."
SRC=(); for t in "${TESTS[@]}"; do SRC+=("$SMOKE/$t.java"); done
if ! javac -cp "$CPF" -d "$OUT" $(find "$ROOT/server/java" -name '*.java') "${SRC[@]}" "$SMOKE/BatchRunner.java" 2>"$OUT/javac.log"; then
  grep -v 'Picked up' "$OUT/javac.log" | grep -iE 'error|\.java:'; echo "[reg] ✖ COMPILATION ÉCHOUÉE"; exit 1
fi

# ─── STRATÉGIE D'EXÉCUTION ───────────────────────────────────────────────────────────────────────────────────────────────
# Coût DOMINANT mesuré = `ServerContext.init` (~1,7 s : parse ~274 `.tab` + charge game-framed.jar), payé À CHAQUE process `java`.
# 157 × 1,7 s ≈ 267 s de pur init redondant → la PARALLÉLISATION de process (4 cœurs) EMPIRE les choses (contention IO/mémoire/GC :
# mesuré 427 s). Le vrai levier = AMORTIR l'init : lancer la majorité des tests DANS UN SEUL PROCESS (BatchRunner) → 1 seul init
# (~8 s pour 149 tests, vs ~300 s). L'isolation est préservée en RÉINITIALISANT l'état statique mutable partagé (offset d'horloge +
# événements opérateur) avant chaque test.
#   • BATCH (défaut) : tests exécutables en process partagé → BatchRunner (un seul JVM).
#   • ISOLÉS : tests qui démarrent un VRAI serveur/socket ou appellent System.exit (tueraient le JVM partagé, ports/threads) →
#     AUTO-DÉTECTÉS (motif `LoginServer|System.exit|ServerSocket|new Socket`) et lancés en process SÉPARÉS (parallèles).
#   • DH_REG_ISOLATED=1 : force l'ancien mode 100 % process-par-test (isolation JVM totale, filet de sécurité de débogage).
# ─────────────────────────────────────────────────────────────────────────────────────────────────────────────────────────
PAR="${DH_REG_PAR:-$(nproc 2>/dev/null || echo 4)}"
run_one() {   # $1 = nom du test ; process SÉPARÉ → $OUT/$t.log + $OUT/$t.status (PASS|FAIL)
  local t="$1" log="$OUT/$t.log"
  if java -cp "$CPF:$OUT" "$t" >"$log" 2>&1 && ! grep -q 'Exception in thread\|AssertionError' "$log"; then
    echo PASS >"$OUT/$t.status"; else echo FAIL >"$OUT/$t.status"; fi
}
export -f run_one; export OUT CPF

# Partition BATCH / ISOLÉS (auto-détection sur le source du test).
BATCH=(); ISOLATED=()
for t in "${TESTS[@]}"; do
  if grep -qE 'System\.exit|LoginServer|ServerSocket|new Socket' "$SMOKE/$t.java" 2>/dev/null; then ISOLATED+=("$t"); else BATCH+=("$t"); fi
done

if [ -n "${DH_REG_ISOLATED:-}" ]; then
  echo "[reg] mode ISOLÉ forcé : ${#TESTS[@]} tests en process séparés (concurrence=$PAR) ..."
  BATCH=(); ISOLATED=("${TESTS[@]}")
else
  echo "[reg] mode RAPIDE : ${#BATCH[@]} tests en process partagé (BatchRunner) + ${#ISOLATED[@]} isolés (concurrence=$PAR) ..."
fi

declare -A STATUS
# 1) BATCH en un seul JVM (init amorti). BatchRunner imprime `PASS <t>` / `FAIL <t> :: <cause>` + un résumé.
if [ "${#BATCH[@]}" -gt 0 ]; then
  java -cp "$CPF:$OUT" BatchRunner "${BATCH[@]}" >"$OUT/batch.log" 2>&1 || true
  while read -r verdict t rest; do
    [ "$verdict" = PASS ] && STATUS["$t"]=PASS
    [ "$verdict" = FAIL ] && { STATUS["$t"]=FAIL; echo "$t :: ${rest#:: }" >>"$OUT/batch.fail"; }
  done < <(grep -E '^(PASS|FAIL) ' "$OUT/batch.log")
fi
# 2) ISOLÉS en process séparés (parallèles).
if [ "${#ISOLATED[@]}" -gt 0 ]; then
  printf '%s\n' "${ISOLATED[@]}" | xargs -P "$PAR" -I{} bash -c 'run_one "$@"' _ {}
  for t in "${ISOLATED[@]}"; do STATUS["$t"]="$(cat "$OUT/$t.status" 2>/dev/null)"; done
fi

pass=0; fail=0; failed=()
for t in "${TESTS[@]}"; do   # agrégation DANS L'ORDRE de la suite (affichage déterministe)
  if [ "${STATUS[$t]:-}" = PASS ]; then
    pass=$((pass+1)); printf '  ✓ %s\n' "$t"
  else
    fail=$((fail+1)); failed+=("$t"); printf '  ✖ %s\n' "$t"
    grep -iE 'AssertionError|Exception in thread' "$OUT/$t.log" 2>/dev/null | head -2 | sed 's/^/      /'
    grep -E "^$t :: " "$OUT/batch.fail" 2>/dev/null | sed 's/^/      /'
  fi
done
echo "[reg] RÉSULTAT : $pass/${#TESTS[@]} verts"
[ "$fail" -eq 0 ] || { echo "[reg] ÉCHECS : ${failed[*]}"; exit 1; }
