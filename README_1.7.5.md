# Sovereign SMP v1.7.5

**Paper 1.21.11**, Java 21. Includes all seven unique legendary artifacts plus the separate Sovereign Mace.

## Install/build
Upload the extracted project contents (not the outer folder) to the GitHub repository, including `src/`, `build.gradle.kts`, `settings.gradle.kts`. Create `.github/workflows/build.yml` using the provided YAML, then run the action. Install the compiled JAR in `plugins/` after backing up server data.

Install `FrostPVP_SovereignSMP_Combined_Animated_Tooltips_v1.7.5.zip` under `.minecraft/resourcepacks` to display the two new backgrounds and custom item sprites, preserving all prior Frost and Sovereign assets.

## Artifact commands
`/sovereign mint stormvault <player>` — mint the only Stormvault.
`/sovereign mint hexcaller <player>` — mint the only Hexcaller.
`/sovereign recover <type> <player>` — operator-only, invalidates previous token, use only if lost.
`/sovereign claim <type>` — claim an unowned authentic artifact in inventory.
`/sovereign status`, `/sovereign cooldowns`, `/sovereign config`, `/sovereign restyle`.

**Controls:** Right-click = first ability, Shift+Right-click = second, F (Swap Hands) = ultimate. The artifact must be held in the main hand by its owner. Styled item lore and HUD appear automatically.

## New artifact IDs
- `stormvault`: token key `sovereignsmp:stormvault_token`, tooltip `sovereignsmp:stormvault`, item model `sovereignsmp:stormvault`.
- `hexcaller`: token key `sovereignsmp:hexcaller_token`, tooltip `sovereignsmp:hexcaller`, item model `sovereignsmp:hexcaller`.

## Configuration
All cooldown/damage/area settings for new artifacts are in `stormvault` and `hexcaller` sections of `config.yml` and editable via `/sovereign config`.

## Testing reminder
This version has not been compiled against the Paper API or tested in multiplayer yet. If GitHub Actions reports an error, share the build log to pinpoint it; preserve backups and test each ability in a staging server.
