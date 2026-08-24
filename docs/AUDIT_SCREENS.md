# AUDIT A1 — inventaire des écrans (aucun oublié)

> AUTO-GÉNÉRÉ par `tools/audit/audit.sh a1` — 2026-08-24T21:09Z. Ne pas éditer à la main (sauf colonne « note » de triage).
>
> **179 écrans** `*Screen` (hors classes internes) dans le jar client. Un écran « couvert » = son MODE est ✅/🟢 dans
> `EXPLORATION.md`, OU c'est un widget/composant non navigable. Les autres = **à auditer** (candidats oubli).

## Écrans par package

### `com/perblue/heroes/ui/Contests/` (3 écran(s))
- [ ] `ContestProgressBarTestScreen`
- [ ] `ContestsScreen`
- [ ] `HallOfFameScreen`

### `com/perblue/heroes/ui/campaign/` (3 écran(s))
- [ ] `CampaignPreviewScreen`
- [ ] `CampaignScreen`
- [ ] `FriendCampaignPreviewScreen`

### `com/perblue/heroes/ui/challenges/` (4 écran(s))
- [ ] `BooksViewScreen`
- [ ] `ChallengesChaptersViewScreen`
- [ ] `ChallengesMainScreen`
- [ ] `ChallengesViewScreen`

### `com/perblue/heroes/ui/collections/` (8 écran(s))
- [ ] `CollectionsCombatEnvironmentScreen`
- [ ] `CollectionsDetailScreen`
- [ ] `CollectionsScreen`
- [ ] `CollectionsShopScreen`
- [ ] `CollectionsTierDetailScreen`
- [ ] `CosmeticCollectionInnerScreen`
- [ ] `CosmeticCollectionScreen`
- [ ] `HeroCollectionScreen`

### `com/perblue/heroes/ui/costumes/` (1 écran(s))
- [ ] `CostumeUnlockedScreen`

### `com/perblue/heroes/ui/donations/` (1 écran(s))
- [ ] `DonationHelpScreen`

### `com/perblue/heroes/ui/expedition/` (1 écran(s))
- [ ] `ExpeditionScreen`

### `com/perblue/heroes/ui/friendship/` (1 écran(s))
- [ ] `MissionsMainScreen`

### `com/perblue/heroes/ui/guildperks/` (1 écran(s))
- [ ] `PerkTestScreen`

### `com/perblue/heroes/ui/heist/` (7 écran(s))
- [ ] `CreateHeistHeroChooserScreen`
- [ ] `HeistAttackScreen`
- [ ] `HeistLobbyScreen`
- [ ] `HeistScreen`
- [ ] `IHeistWatchingScreen`
- [ ] `JoinHeistHeroChooserScreen`
- [ ] `JoinHeistScreen`

### `com/perblue/heroes/ui/herochooser/` (12 écran(s))
- [ ] `ArenaAttackHeroChooserScreen`
- [ ] `ArenaDefenseHeroChooserScreen`
- [ ] `CampaignHeroChooserScreen`
- [ ] `ChatSparHeroChooserScreen`
- [ ] `ColiseumDefenseChooserScreen`
- [ ] `ColiseumHeroChooserScreen`
- [ ] `DifficultyModeHeroChooserScreen`
- [ ] `ExpeditionHeroChooserScreen`
- [ ] `FriendCampaignHeroChooserScreen`
- [ ] `HeroChooserScreen`
- [ ] `SavedLineupHeroChooserScreen`
- [ ] `TestHeroChooserScreen`

### `com/perblue/heroes/ui/herodetails/` (1 écran(s))
- [ ] `HeroDetailScreen`

### `com/perblue/heroes/ui/herolist/` (1 écran(s))
- [ ] `HeroListScreen`

### `com/perblue/heroes/ui/invasion/` (11 écran(s))
- [ ] `InvasionAutoBreakerHeroChooserScreen`
- [ ] `InvasionBossHeroChooserScreen`
- [ ] `InvasionBossPreviewScreen`
- [ ] `InvasionBossScreen`
- [ ] `InvasionBreakerAttackScreen`
- [ ] `InvasionBreakerHeroChooserScreen`
- [ ] `InvasionBreakerScreen`
- [ ] `InvasionRankingsScreen`
- [ ] `InvasionScreen`
- [ ] `InvasionSupplyCrateScreen`
- [ ] `InvasionWardTestScreen`

### `com/perblue/heroes/ui/mainscreen/` (1 écran(s))
- [ ] `MainScreen`

### `com/perblue/heroes/ui/map/` (1 écran(s))
- [ ] `MapTestScreen`

### `com/perblue/heroes/ui/mods/` (1 écran(s))
- [ ] `CodeFragmentTestScreen`

### `com/perblue/heroes/ui/prizewall/` (1 écran(s))
- [ ] `PrizeWallScreen`

### `com/perblue/heroes/ui/pvp/` (2 écran(s))
- [ ] `ChallengerChampionScreen`
- [ ] `PVPRewardsScreen`

### `com/perblue/heroes/ui/screens/` (70 écran(s))
- [ ] `ArenaAttackScreen`
- [ ] `ArenaChallengerScreen`
- [ ] `ArenaLeagueScreen`
- [ ] `AttackScreen`
- [ ] `BaseSceneScreen`
- [ ] `BaseScreen`
- [ ] `CampaignAttackScreen`
- [ ] `ChatSparAttackScreen`
- [ ] `ChestDetailScreen`
- [ ] `ChestsScreen`
- [ ] `CityMapScreen`
- [ ] `CodebaseAttackLogScreen`
- [ ] `CodebaseAttackScreen`
- [ ] `CodebaseDetailScreen`
- [ ] `CodebaseHeroChooserScreen`
- [ ] `ColiseumAttackScreen`
- [ ] `CombatPerfTestScreen`
- [ ] `CombatUITestScreen`
- [ ] `CommonUITestScreen`
- [ ] `CoreAttackScreen`
- [ ] `CreateGuildScreen`
- [ ] `DailyDealsScreen`
- [ ] `DebugPrizeWallScreen`
- [ ] `DebugScreen`
- [ ] `DifficultyModeAttackScreen`
- [ ] `EmptyScreen`
- [ ] `EnchantingScreen`
- [ ] `EventChestDetailScreen`
- [ ] `ExpeditionAttackScreen`
- [ ] `FranchiseHeroChooserScreen`
- [ ] `GoldChestDetailScreen`
- [ ] `GradientTestScreen`
- [ ] `GuildCheckInScreen`
- [ ] `GuildGiftingScreen`
- [ ] `GuildMembersScreen`
- [ ] `GuildPerksScreen`
- [ ] `GuildScreen`
- [ ] `GuildSearchScreen`
- [ ] `GuildSettingsScreen`
- [ ] `HeroChipChestDetailScreen`
- [ ] `HeroLoadingTestScreen`
- [ ] `HeroUnlockedScreen`
- [ ] `ICampaignScreen`
- [ ] `InvasionBossAttackScreen`
- [ ] `ItemsScreen`
- [ ] `JobBoardScreen`
- [ ] `LimitedDebugScreen`
- [ ] `LoadingScreen`
- [ ] `LootAttackScreen`
- [ ] `MerchantScreen`
- [ ] `ModePreviewScreen`
- [ ] `MonthlyPurchasingScreen`
- [ ] `PerformanceTestScreen`
- [ ] `PortChooserScreen`
- [ ] `PromosScreen`
- [ ] `PurchasingScreen`
- [ ] `QuestsScreen`
- [ ] `RaidExpeditionHeroChooserScreen`
- [ ] `RankingScreen`
- [ ] `SilverChestDetailScreen`
- [ ] `SimulationScreen`
- [ ] `SocialChestDetailScreen`
- [ ] `SoulChestDetailScreen`
- [ ] `TeamRoleChestDetailScreen`
- [ ] `TeamTrialsChooserScreen`
- [ ] `TutorialAttackScreen`
- [ ] `UIParticleTestScreen`
- [ ] `UIScreen`
- [ ] `VIPBenefitsScreen`
- [ ] `WarAttackScreen`

### `com/perblue/heroes/ui/surge/` (5 écran(s))
- [ ] `DebugCompleteSurgeScreen`
- [ ] `DebugSurgeStatsScreen`
- [ ] `SurgeAttackScreen`
- [ ] `SurgeHeroChooserScreen`
- [ ] `SurgeScreen`

### `com/perblue/heroes/ui/trials/` (4 écran(s))
- [ ] `TrialEventAttackScreen`
- [ ] `TrialEventHeroChooserScreen`
- [ ] `TrialEventSubTrialChooserScreen`
- [ ] `TrialEventSubTrialScreen`

### `com/perblue/heroes/ui/war/` (11 écran(s))
- [ ] `WarAttackHeroChooserScreen`
- [ ] `WarBanProtectScreen`
- [ ] `WarBattlefieldScreen`
- [ ] `WarCarScreen`
- [ ] `WarCarTestScreen`
- [ ] `WarDefenseHeroChooserScreen`
- [ ] `WarLeaguesScreen`
- [ ] `WarListScreen`
- [ ] `WarOtherGuildSeasonsScreen`
- [ ] `WarRankingsScreen`
- [ ] `WarSabotageScreen`

### `com/perblue/heroes/ui/windows/` (2 écran(s))
- [ ] `DailyVideosScreen`
- [ ] `SignInScreen`

### `com/perblue/heroes/ui/wishingwell/` (3 écran(s))
- [ ] `WishingWellChestScreen`
- [ ] `WishingWellHeroChooserScreen`
- [ ] `WishingWellJackpotScreen`

## Écrans à la racine `ui/screens/`
- [ ] `ArenaAttackScreen`
- [ ] `ArenaChallengerScreen`
- [ ] `ArenaLeagueScreen`
- [ ] `AttackScreen`
- [ ] `BaseSceneScreen`
- [ ] `BaseScreen`
- [ ] `CampaignAttackScreen`
- [ ] `ChatSparAttackScreen`
- [ ] `ChestDetailScreen`
- [ ] `ChestsScreen`
- [ ] `CityMapScreen`
- [ ] `CodebaseAttackLogScreen`
- [ ] `CodebaseAttackScreen`
- [ ] `CodebaseDetailScreen`
- [ ] `CodebaseHeroChooserScreen`
- [ ] `ColiseumAttackScreen`
- [ ] `CombatPerfTestScreen`
- [ ] `CombatUITestScreen`
- [ ] `CommonUITestScreen`
- [ ] `CoreAttackScreen`
- [ ] `CreateGuildScreen`
- [ ] `DailyDealsScreen`
- [ ] `DebugPrizeWallScreen`
- [ ] `DebugScreen`
- [ ] `DifficultyModeAttackScreen`
- [ ] `EmptyScreen`
- [ ] `EnchantingScreen`
- [ ] `EventChestDetailScreen`
- [ ] `ExpeditionAttackScreen`
- [ ] `FranchiseHeroChooserScreen`
- [ ] `GoldChestDetailScreen`
- [ ] `GradientTestScreen`
- [ ] `GuildCheckInScreen`
- [ ] `GuildGiftingScreen`
- [ ] `GuildMembersScreen`
- [ ] `GuildPerksScreen`
- [ ] `GuildScreen`
- [ ] `GuildSearchScreen`
- [ ] `GuildSettingsScreen`
- [ ] `HeroChipChestDetailScreen`
- [ ] `HeroLoadingTestScreen`
- [ ] `HeroUnlockedScreen`
- [ ] `ICampaignScreen`
- [ ] `InvasionBossAttackScreen`
- [ ] `ItemsScreen`
- [ ] `JobBoardScreen`
- [ ] `LimitedDebugScreen`
- [ ] `LoadingScreen`
- [ ] `LootAttackScreen`
- [ ] `MerchantScreen`
- [ ] `ModePreviewScreen`
- [ ] `MonthlyPurchasingScreen`
- [ ] `PerformanceTestScreen`
- [ ] `PortChooserScreen`
- [ ] `PromosScreen`
- [ ] `PurchasingScreen`
- [ ] `QuestsScreen`
- [ ] `RaidExpeditionHeroChooserScreen`
- [ ] `RankingScreen`
- [ ] `SilverChestDetailScreen`
- [ ] `SimulationScreen`
- [ ] `SocialChestDetailScreen`
- [ ] `SoulChestDetailScreen`
- [ ] `TeamRoleChestDetailScreen`
- [ ] `TeamTrialsChooserScreen`
- [ ] `TutorialAttackScreen`
- [ ] `UIParticleTestScreen`
- [ ] `UIScreen`
- [ ] `VIPBenefitsScreen`
- [ ] `WarAttackScreen`
