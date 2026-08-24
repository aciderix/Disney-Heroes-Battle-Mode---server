# AUDIT A5 — couverture des `.tab` (données du jeu)

> AUTO-GÉNÉRÉ par `tools/audit/audit.sh a5` — 2026-08-24T21:36Z. Carte `.tab → classe Stats` (le code du jeu associe CHAQUE
> `.tab` à une classe `Stats` = la « partie du jeu » concernée ; le PACKAGE = le mode/feature). "Nommée serveur" =
> la classe apparaît dans `server/java` (⚠️ approximation : une classe NON nommée peut être chargée par la LOGIQUE
> du jeu que le serveur exécute — ex. CampaignStats via CampaignHelper — donc « non nommée » ≠ « inutilisée »).

**273 `.tab` sur disque · 265 référencées par le code · 67 classes Stats (26 nommées serveur).**

## .tab SUR DISQUE mais NON référencées par une classe (orphelines / chargées par nom dynamique)
- `content.1.tab`
- `content.13.tab`
- `content.14.tab`
- `content.21.tab`
- `content.23.tab`
- `content.25.tab`
- `content.99.tab`
- `invasion_boss_rewards.tab`

> NB : les `content.N.tab` sont chargées par nom CONSTRUIT (`content.<shard>.tab`, ContentStats) → non orphelines.

## .tab RÉFÉRENCÉES par le code mais ABSENTES du disque (à extraire ? gap d'extraction)

## Carte par FEATURE (package) — classe Stats + nb .tab + nommée serveur

| Feature (package) | Classe Stats | .tab | Nommée serveur |
|---|---|---|---|
| `DeepLinkStats` | `DeepLinkStats` | 1 | — |
| `airdrop` | `AirDropStats` | 2 | — |
| `arena` | `ArenaStats` | 16 | ✅ |
| `battlepass` | `BattlePassStats` | 3 | — |
| `battlepass` | `BattlePassV2Stats` | 4 | ✅ |
| `campaign` | `CampaignReinfectionStats` | 4 | — |
| `campaign` | `CampaignStats` | 8 | — |
| `chest` | `ChestStats` | 14 | ✅ |
| `chest` | `ChestUpgradeStats` | 3 | — |
| `codebase` | `CodebaseStats` | 4 | — |
| `collections` | `CollectionStats` | 4 | — |
| `combat` | `CombatStats` | 3 | — |
| `content` | `StarterDealStats` | 1 | — |
| `cosmetics` | `CosmeticCollectionStats` | 4 | — |
| `emerald` | `EmeraldStats` | 4 | — |
| `expedition` | `ExpeditionStats` | 6 | ✅ |
| `friendships` | `FriendshipCampaignStats` | 4 | — |
| `friendships` | `FriendshipStats` | 3 | — |
| `guild` | `GuildCheckInStats` | 1 | — |
| `guild` | `GuildStats` | 7 | ✅ |
| `heist` | `HeistStats` | 4 | — |
| `herospotlight` | `HeroSpotlightStats` | 1 | — |
| `invasion` | `InvasionStats` | 15 | ✅ |
| `item` | `CraftingStats` | 1 | — |
| `item` | `ItemAssetStats` | 1 | — |
| `item` | `ItemRarityStats` | 2 | — |
| `item` | `ItemStats` | 1 | ✅ |
| `item` | `ResourceStats` | 1 | — |
| `item.enchanting` | `EnchantingStats` | 10 | ✅ |
| `marketing` | `MarketingStats` | 1 | — |
| `misc` | `DisneyEmojiStats` | 1 | — |
| `misc` | `EventStats` | 1 | — |
| `misc` | `GameModeRefreshStats` | 1 | — |
| `misc` | `GoldDrop` | 1 | — |
| `misc` | `MapDistrictStats` | 1 | ✅ |
| `misc` | `MerchantStats` | 16 | ✅ |
| `misc` | `MidasStats` | 2 | ✅ |
| `misc` | `OfferwallStats` | 1 | — |
| `misc` | `SocialBuckStats` | 1 | ✅ |
| `misc` | `StaminaStats` | 2 | — |
| `misc` | `SupportLinks` | 1 | — |
| `misc` | `TeamLevelStats` | 1 | ✅ |
| `misc` | `Unlockables` | 2 | ✅ |
| `misc` | `UserValues` | 1 | ✅ |
| `misc` | `VIPStats` | 2 | ✅ |
| `missions` | `MissionStats` | 3 | ✅ |
| `mods` | `ModStats` | 10 | — |
| `patchedheroes` | `PatchStats` | 11 | ✅ |
| `port` | `PortStats` | 4 | — |
| `primebadge` | `PrimeBadgeStats` | 7 | — |
| `prizewall` | `PrizeWallStats` | 2 | — |
| `quests` | `QuestStats` | 4 | ✅ |
| `realgear` | `RealGearStats` | 4 | — |
| `signin` | `SigninStats` | 1 | ✅ |
| `stickerbook` | `StickerChallengeStats` | 3 | ✅ |
| `surge` | `SurgeStats` | 4 | ✅ |
| `teamtrials` | `EventTrialStats` | 3 | ✅ |
| `teamtrials` | `SpotlightTrialStats` | 6 | — |
| `teamtrials` | `TeamTrialsStats` | 6 | — |
| `tutorial` | `TutorialStats` | 2 | — |
| `unit` | `UnitStats` | 11 | ✅ |
| `unit.ability` | `AbilityStats` | 1 | — |
| `unit.normalgear` | `NormalGearStats` | 1 | — |
| `unit.redskill` | `RedSkillStats` | 3 | — |
| `video` | `VideoStats` | 2 | — |
| `war` | `WarStats` | 8 | ✅ |
| `wishingwell` | `WishingWellStats` | 2 | ✅ |
