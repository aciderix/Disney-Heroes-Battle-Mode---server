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
OUT="$SMOKE/out"; rm -rf "$OUT"; mkdir -p "$OUT"

# Suite de régression (tests assertifs, exécutables sans argument).
TESTS=(
  ResourceTest RosterTest
  SigninTest SigninMultiDayTest SigninAllRewardsTest
  EquipTest CampaignAttackTest CampaignPersistTest
  ChestWireTest ChestChargeTest ChestPaidDebitTest ChestValidateTest FreeChestTest ViewedChestsTest
  BattlePassTest BattlePassClaimTest BattlePassPointsTest BattlePassRolloverTest
  CompleteQuestTest WeeklyBoxTest WeeklyQuestTest
  MailboxTest ItemsTest SkillUpgradeTest AlchemyTest SetFlagTest UnlockHeroTest
  UpdateTimeTest SetNameTest TeamLevelPersistTest
  LootAuthoritativeTest LootEquipTest LootPersistTest SeedTest
  ArenaInfoTest ArenaDefenseTest ArenaLadderTest
)

echo "[reg] compilation (serveur + ${#TESTS[@]} tests) ..."
SRC=(); for t in "${TESTS[@]}"; do SRC+=("$SMOKE/$t.java"); done
if ! javac -cp "$CPF" -d "$OUT" $(find "$ROOT/server/java" -name '*.java') "${SRC[@]}" 2>"$OUT/javac.log"; then
  grep -v 'Picked up' "$OUT/javac.log" | grep -iE 'error|\.java:'; echo "[reg] ✖ COMPILATION ÉCHOUÉE"; exit 1
fi

pass=0; fail=0; failed=()
for t in "${TESTS[@]}"; do
  log="$OUT/$t.log"
  if java -cp "$CPF:$OUT" "$t" >"$log" 2>&1 && ! grep -q 'Exception in thread\|AssertionError' "$log"; then
    pass=$((pass+1)); printf '  ✓ %s\n' "$t"
  else
    fail=$((fail+1)); failed+=("$t"); printf '  �ö %s\n' "$t"
    grep -iE 'AssertionError|Exception in thread' "$log" | head -2 | sed 's/^/      /'
  fi
done
echo "[reg] RÉSULTAT : $pass/${#TESTS[@]} verts"
[ "$fail" -eq 0 ] || { echo "[reg] ÉCHECS : ${failed[*]}"; exit 1; }
