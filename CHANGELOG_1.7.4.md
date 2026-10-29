# Sovereign SMP v1.7.4 Beta

- Dragonfang: first special shot replaces the loaded crossbow arrow with an actual Ender Dragon fireball via EntityShootBowEvent.setProjectile; fireball clouds still follow trusted-target restrictions.
- Dragon's Wrath: spawns a real, temporary Ender Dragon visual above the spell path, with AI disabled and terrain-damage protections. Still performs three damage sweeps. Visual is removed on cast end, plugin disable, or startup cleanup.
- Paleheart Haunted Grove: 15-block default radius, 6 Creaking visual figures, Blindness for hostile players inside ring, and 2 or more health points for contact with a figure (rate-limited per figure).
- Config GUI now supports 24-block maximum radius and contact damage; default-radius migration converts legacy 7-block setting to 15.
- Existing artifact ownership data and tooltip style IDs unchanged; previous combined animated resource pack remains compatible.

Source-only beta: Paper build and server behavior are not yet confirmed.
