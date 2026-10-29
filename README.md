# Sovereign SMP v1.7.5 — All Seven Legendary Artifacts

**New:** Stormvault (Breeze Glaive) and Hexcaller (Evoker Staff), both with 3 abilities + 1 passive, unique ownership, configs, animated tooltip styles and custom item models. See `README_1.7.5.md` and `CHANGELOG_1.7.5.md`. This source is an **uncompiled test release**; compile with the included GitHub Actions workflow before testing on Paper 1.21.11.

---

# Sovereign SMP v1.7.4 — Dragonfang and combat fixes

Source project for **Paper 1.21.11 / Java 21**. It contains four of the seven one-of-a-kind legendary artifacts, plus the **Sovereign Mace (special weapon; not an artifact)**. Based on Sovereign SMP v1.7.1, keeping the existing UUID token keys and owner state.

## Important upgrade notes

1. Back up `plugins/SovereignSMP/artifacts.yml` and `config.yml` **before** replacing your previous JAR. Keeping the existing `artifacts.yml` preserves previous ownership and minted item UUIDs.
2. Build with the included `.github/workflows/build.yml` through GitHub Actions (or `gradle clean build` using Java 21 and a Paper API repository connection). Install the JAR from `build/libs/` on Paper 1.21.11.
3. Install the provided **SovereignSMP_Complete_ResourcePack_v1.7.4.zip** on clients to see all five tooltip *backgrounds*. Without the pack, styled names and lore still work. No `/sovereign tooltip` command exists.
4. The plugin automatically refreshes enchantments and tooltip styles on old genuine artifacts when they enter a player's inventory or a player joins. You can also use `/sovereign restyle`.
5. Four artifact slots are now in use: Echo Scythe (I), Witherborn (II), Tidelord (III), Dragonfang (IV). The Mace remains a separate special weapon.

## Dragonfang — Artifact IV / VII

**Base item:** CROSSBOW (Power III, Quick Charge I, Piercing IV, Unbreakable). Crossbows do not natively apply Power; the source explicitly adds the equivalent +2 HP arrow damage for its Power III. Its other two enchantments work like regular crossbow enchantments.

- Passive **Ender Resonance**: Dragon breath bolts apply temporary End marks. A subsequent empowered cloud can consume the existing mark for bonus damage once per cloud.
- Right-click **Dragon's Breath**, 18s: load and fire normal crossbow arrows. If the ability is ready when an arrow fires, that shot becomes a dragon breath bolt, making a temporary dragon-breath cloud on first projectile impact. Ordinary shots continue working during the cooldown and still use arrows.
- Sneak + Right-click **Chorus Shift**, 28s: try to teleport along your look direction onto a solid, safe landing spot within 8 blocks. If there is no safe landing, the ability refunds its cooldown.
- F (swap hands) **Dragon's Wrath**, 60s: a purple spectral-dragon particle path telegraphs three waves of End breath explosions. These deal configurable damage to hostile nearby targets without block grief. There is no real summoned Dragon entity.
- Tooltip: purple / End-magenta gradient with `sovereignsmp:dragon` nine-slice border and background, included in the combined pack.
- Commands: `/sovereign mint dragonfang <player>`, `/sovereign recover dragonfang <player>` (OP-only), `/sovereign claim dragonfang` (if currently unclaimed).

## Requested enchantments

All these items are set Unbreakable, including authentic older copies on inventory refresh.

| Item | Enchantments |
| --- | --- |
| Sovereign Mace (special weapon) | Density I, Fire Aspect II |
| Echo Scythe | Sharpness V, Sweeping Edge III, Looting IV, Fire Aspect II |
| Tidelord | Sharpness V, Impaling V |
| Witherborn | Sharpness V, Efficiency VI, Silk Touch I |
| Dragonfang | Power III, Quick Charge I, Piercing IV |

For non-vanilla enchant-item combinations, unsafe enchantments are stored and their attack effects are supplemented by plugin logic where appropriate: Mace Fire Aspect II sets fire on direct melee hits; Tidelord Sharpness V adds +3 health points on direct melee damage; Dragonfang Power III adds +2 arrow damage.

## Existing items and ability adjustments

- **Mace / Event Horizon (RMB):** safer velocity caps to reduce rubber-banding; 2 HP configurable initial damage for caught targets; traveling gravity pulse and nearby pull still work together. The pull cap is 1.8 blocks/tick regardless of legacy extreme config values. The GUI has an impact-damage setting.
- **Mace / Singularity (F):** improved placement to keep the core outside a targeted solid block; deals configurable 1 HP per 20 ticks to enemies while pulling, then a default 6 HP detonation when charged. Burst radius has a little more tolerance. All orbiting blocks are temporary display entities; real blocks are never removed.
- **Tidelord / Guardian's Blessing:** Guardians and Elder Guardians cannot target or directly damage the current authentic holder **while Tidelord is held in the main hand**. Persistent Guardian target locks are cleared, and the nearby Elder Guardian's ambient Mining Fatigue curse is suppressed while wielding. It does not stop unrelated mobs or other players.
- Existing Mace, Echo Scythe, Witherborn, Tidelord abilities and their original PDC item IDs remain present.

## Build / test status

The three Java source files, resources, workflows and resource-pack structure have been inspected, and the test archive has been created. **A full Java compile against Paper's 1.21.11 API and live server tests could not run locally** because the Paper API dependency and Gradle CLI are unavailable here. Run GitHub Actions and review its compilation log, then test Dragonfang's native shot event, teleport safe spots, guardian protection, Event Horizon pull and Singularity explosion on a non-production Paper server. This is a candidate test version, not a verified release JAR.

## GitHub Actions workflow

Path: `.github/workflows/build.yml`. In your GitHub repository, choose Add file → Create new file, then enter `.github/workflows/build.yml` as the file name. Paste the workflow YAML inside and commit. The workflow is also shipped in the source ZIP. The compiled JAR should appear under the Actions run's Artifacts section.


## Artifact V / VII: Paleheart (Creaking Sickle)

- Netherite Hoe, Sharpness V and Unbreakable, unique persistent Paleheart token and owner.
- Passive Unseen Terror: Speed I briefly when near an opponent who is not watching you.
- Right click Grasping Roots: dodgeable roots against an aimed target.
- Sneak + right click Unseen Step: short safe teleport and a temporary decorative Creaking.
- F Haunted Grove: temporary Creaking apparitions; look at them to avoid pulse damage.
- OP-only /sovereign mint paleheart <player>, /sovereign recover paleheart <player>.
- /sovereign claim paleheart after holder is defeated.
- /sovereign config -> Paleheart contains all balance values.
- Install the updated combined Frost × Sovereign resource pack for animated Paleheart tooltips.

**Testing:** This is a SOURCE release for Paper 1.21.11, Java 21. Run GitHub Actions build and verify on a test server; back up artifacts.yml first.

## v1.7.4 fixes

- Dragonfang Dragon's Breath now fires an actual `DragonFireball` as the crossbow projectile, consuming a normal loaded shot and creating the configured breath cloud on impact.
- Dragon's Wrath summons a short-lived, invulnerable no-AI Ender Dragon above the battlefield, accompanying the three telegraphed breath strikes; removes it on completion/reload/disable. No terrain destruction from tagged visual.
- Paleheart Haunted Grove default radius is 15 blocks (old default 7 auto-migrates); 6 Creakings now surround the grove, apply 1 heart minimum per contact (20-tick cooldown per Creaking/player), and inflict Blindness on hostile players within its radius.

These changes have not undergone full Paper compilation or in-game testing.
