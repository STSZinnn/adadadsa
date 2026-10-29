package com.sovereignsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Warden;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Display;
import org.joml.Matrix4f;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sovereign SMP — Echo Scythe is the first of seven legendary artifacts.
 * The Sovereign Mace is an independent, unique SPECIAL WEAPON (not an artifact slot).
 * One authentic Echo Scythe and one authentic Mace can be owned by separate players.
 */
public final class SovereignSMPPlugin extends JavaPlugin implements Listener, TabCompleter {
    private NamespacedKey artifactKey; // Legacy Mace item ID: MUST NOT change.
    private NamespacedKey royalTooltipKey;
    private NamespacedKey echoKey;
    private NamespacedKey sculkTooltipKey;
    private UUID echoToken;
    private UUID echoOwner;
    private final Map<UUID, Long> trueInvisibleUntil = new HashMap<>();
    private final Map<UUID, Boolean> previousSilent = new HashMap<>();
    private final Map<UUID, PotionEffect> previousInvisibility = new HashMap<>();
    private int passiveTicks = 0;
    private NamespacedKey echoSpeedKey;
    // Local temporary footprint blocks, restored in memory after 40 ticks (2 seconds).
    // Only full, natural blocks are transformed; never tile entities or player structures.
    private record FootstepKey(UUID worldId, int x, int y, int z) { }
    private static final class FootstepState {
        final BlockData original;
        long expiresAt;
        FootstepState(BlockData original, long expiresAt) {
            this.original = original;
            this.expiresAt = expiresAt;
        }
    }
    private final Map<FootstepKey, FootstepState> footprints = new HashMap<>();
    private long passiveClock = 0L;
    private int auraClock = 0;
    private static final String SPRITE_TAG = "sovereignsmp_particle_sprite";
    private final Map<UUID, Long> liveSprites = new LinkedHashMap<>();
    private long spriteTick = 0L;
    // Uses ItemDisplay-based 16x16 sprites, never overrides vanilla particle textures.

    private static final String ECHO_PULSE = "echo-pulse";
    private static final String ECHO_SILENCE = "silence-of-the-deep";
    private static final String ECHO_WRATH = "wardens-wrath";

    private static final String VORTEX_TAG = "sovereignsmp_singularity_visual";
    private final Set<BlockDisplay> spawnedGravityBlocks = new HashSet<>();
    private final Set<BukkitRunnable> activeGravityTasks = new HashSet<>();
    private File stateFile;
    private YamlConfiguration state;
    private UUID maceToken;
    private UUID maceOwner;
    private SovereignExpansion expansion;
    private DragonfangArtifact dragonfang;
    private PaleheartArtifact paleheart;
    private FinalArtifacts finalArtifacts;

    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> hitCombos = new HashMap<>();
    private static final String HORIZON = "event-horizon";
    private static final String COLLAPSE = "collapse";
    private static final String SINGULARITY = "singularity";

    // OP config menu: each section is backed by live config.yml values.
    private record Setting(String path, String label, Material icon, double step, double min,
                           double max, boolean wholeNumber, boolean toggle) { }
    private static Setting number(String path, String label, Material icon, double step, double min, double max, boolean whole) {
        return new Setting(path, label, icon, step, min, max, whole, false);
    }
    private static Setting flag(String path, String label, Material icon) {
        return new Setting(path, label, icon, 1, 0, 1, false, true);
    }
    private static final Map<String, List<Setting>> CONFIG_PAGES = new LinkedHashMap<>();
    private static final Map<Integer, String> PAGE_SLOTS = new LinkedHashMap<>();
    private static final int[] EDIT_SLOTS = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
    static {
        CONFIG_PAGES.put("mace.passive", List.of(
                number("mace.passive.slowness-ticks", "Gravitational Slowness (ticks)", Material.COBWEB, 5, 0, 200, true)));
        CONFIG_PAGES.put("mace.event-horizon", List.of(
                number("mace.event-horizon.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("mace.event-horizon.target-range-blocks", "Beam range (blocks)", Material.SPYGLASS, 2, 6, 80, false),
                number("mace.event-horizon.beam-width-blocks", "Beam hit width (blocks)", Material.AMETHYST_SHARD, .25, .75, 8, false),
                number("mace.event-horizon.pull-velocity", "Pull strength", Material.LEAD, .1, .3, 4, false),
                number("mace.event-horizon.max-pull-speed", "Maximum pull speed", Material.FEATHER, .1, .5, 5, false),
                number("mace.event-horizon.pull-duration-ticks", "Pull duration (ticks)", Material.REPEATER, 2, 2, 100, true),
                number("mace.event-horizon.pulse-speed", "Pulse speed (blocks/tick)", Material.LIGHTNING_ROD, .25, 1, 8, false),
                number("mace.event-horizon.slowness-ticks", "Affected enemy slow (ticks)", Material.SOUL_SAND, 5, 0, 200, true),
                number("mace.event-horizon.particles-per-ring", "Beam ring particles", Material.PURPLE_DYE, 4, 8, 90, true),
                number("mace.event-horizon.nearby-radius-blocks", "Nearby gravity pull radius", Material.ENDER_PEARL, 0.5, 0, 16, false),
                number("mace.event-horizon.impact-damage-health-points", "Gravity pulse impact damage (HP)", Material.REDSTONE, .5, 0, 15, false)));
        CONFIG_PAGES.put("mace.collapse", List.of(
                number("mace.collapse.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("mace.collapse.radius-blocks", "Shockwave radius", Material.ECHO_SHARD, .5, 2, 35, false),
                number("mace.collapse.damage-health-points", "Shockwave damage (HP)", Material.REDSTONE, 1, 0, 40, false),
                number("mace.collapse.knockback", "Enemy knockback", Material.FEATHER, .1, 0, 4, false)));
        CONFIG_PAGES.put("mace.singularity", List.of(
                number("mace.singularity.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("mace.singularity.target-range-blocks", "Cast range", Material.SPYGLASS, 1, 4, 70, false),
                number("mace.singularity.radius-blocks", "Gravity radius", Material.END_CRYSTAL, .5, 2, 30, false),
                number("mace.singularity.duration-ticks", "Charge duration (ticks)", Material.REPEATER, 5, 20, 200, true),
                number("mace.singularity.burst-damage-health-points", "Burst damage (HP)", Material.REDSTONE, 1, 0, 40, false),
                number("mace.singularity.burst-push-velocity", "Burst push strength", Material.FEATHER, .1, .1, 5, false),
                number("mace.singularity.orbit-block-count", "Floating block count", Material.GRASS_BLOCK, 1, 0, 24, true),
                number("mace.singularity.block-sample-radius", "Sample nearby block types", Material.STONE, .5, 1, 8, false),
                number("mace.singularity.pulse-damage-health-points", "Charge pulse damage (HP)", Material.REDSTONE, .5, 0, 15, false)));
        CONFIG_PAGES.put("echo.passive", List.of(
                number("echo.passive.sense-radius-blocks", "Sculk Awareness radius", Material.SCULK_SENSOR, 1, 2, 80, false),
                number("echo.passive.speed-bonus-percent", "Speed attribute bonus (%)", Material.SUGAR, 1, 0, 50, true),
                flag("echo.passive.warden-immunity", "Wardens ignore the wielder", Material.SCULK_SHRIEKER),
                flag("echo.passive.footprints-enabled", "Temporary sculk footsteps", Material.SCULK),
                number("echo.passive.footprint-duration-ticks", "Footsteps duration (ticks)", Material.CLOCK, 5, 5, 100, true),
                flag("echo.passive.aura-enabled", "Sculk soul holding aura", Material.SOUL_LANTERN)));
        CONFIG_PAGES.put("echo.pulse", List.of(
                number("echo.pulse.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("echo.pulse.radius-blocks", "Echo Pulse radius", Material.SCULK_CATALYST, 1, 4, 60, false),
                number("echo.pulse.glowing-seconds", "Glowing duration (seconds)", Material.GLOW_INK_SAC, 1, 0, 60, true)));
        CONFIG_PAGES.put("echo.silence", List.of(
                number("echo.silence.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("echo.silence.duration-seconds", "True invisibility duration", Material.ENDER_EYE, 1, 1, 60, true),
                number("echo.silence.enemy-radius-blocks", "Darkness/weakness radius", Material.SCULK_SHRIEKER, 1, 2, 40, false)));
        CONFIG_PAGES.put("echo.wrath", List.of(
                number("echo.wrath.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("echo.wrath.range-blocks", "Sonic wave range", Material.ECHO_SHARD, 1, 4, 70, false),
                number("echo.wrath.damage-health-points", "Damage per wave (HP)", Material.REDSTONE, .5, 0, 30, false)));
        CONFIG_PAGES.put("mace.cosmetics", List.of(
                flag("mace.cosmetics.aura-enabled", "Purple gravity holding aura", Material.PURPLE_DYE),
                number("mace.cosmetics.aura-particles", "Aura particles per pulse", Material.AMETHYST_SHARD, 1, 1, 24, true)));
        CONFIG_PAGES.put("combat", List.of(
                flag("combat.abilities-ignore-armor", "Abilities bypass armor and Protection", Material.NETHERITE_CHESTPLATE)));
        CONFIG_PAGES.put("hud", List.of(
                flag("hud.enabled", "Show ability cooldown action bar", Material.LIME_DYE),
                number("hud.update-ticks", "Update interval (ticks)", Material.REPEATER, 5, 5, 40, true),
                flag("hud.show-sculk-awareness", "Show sculk awareness on HUD", Material.SCULK_SENSOR)));
        CONFIG_PAGES.put("visuals", List.of(
                flag("visuals.custom-sprites-enabled", "Enable 16x16 custom sprites (resource pack)", Material.GLOW_INK_SAC),
                number("visuals.max-live-sprites", "Max displayed effect sprites", Material.ITEM_FRAME, 10, 10, 160, true),
                number("visuals.sprite-lifetime-ticks", "Sprite lifetime (ticks)", Material.CLOCK, 2, 4, 40, true),
                number("visuals.ambient-sprites-per-pulse", "Ambient sprites per pulse", Material.GLOWSTONE_DUST, 1, 0, 5, true),
                flag("visuals.ability-sprites-enabled", "Show sprites for abilities", Material.AMETHYST_SHARD)));
        CONFIG_PAGES.put("wither.passive", List.of(
                number("wither.passive.wither-ticks", "Withering Touch duration (ticks)", Material.WITHER_ROSE, 10, 20, 200, true)));
        CONFIG_PAGES.put("wither.barrage", List.of(
                number("wither.barrage.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("wither.barrage.damage-health-points", "Damage per skull (HP)", Material.REDSTONE, .5, 0, 20, false),
                number("wither.barrage.radius-blocks", "Splash radius", Material.SOUL_SAND, .25, 0, 8, false)));
        CONFIG_PAGES.put("wither.devour", List.of(
                number("wither.devour.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("wither.devour.damage-health-points", "Siphon damage (HP)", Material.REDSTONE, .5, 0, 20, false),
                number("wither.devour.radius-blocks", "Siphon radius", Material.SOUL_LANTERN, .5, 2, 20, false)));
        CONFIG_PAGES.put("wither.ascension", List.of(
                number("wither.ascension.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("wither.ascension.final-damage-health-points", "Final blast damage (HP)", Material.NETHER_STAR, .5, 0, 20, false)));
        CONFIG_PAGES.put("tide.passive", List.of(
                number("tide.passive.reflect-chance-percent", "Melee reflection chance (%)", Material.PRISMARINE_SHARD, 1, 0, 100, false),
                number("tide.passive.reflect-damage-health-points", "Reflected damage (HP)", Material.REDSTONE, .5, 0, 20, false)));
        CONFIG_PAGES.put("tide.beam", List.of(
                number("tide.beam.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("tide.beam.duration-ticks", "Channel duration (ticks)", Material.REPEATER, 5, 10, 100, true),
                number("tide.beam.range-blocks", "Beam range", Material.SPYGLASS, 1, 5, 50, false),
                number("tide.beam.damage-per-hit-health-points", "Beam hit damage (HP)", Material.REDSTONE, .25, 0, 12, false)));
        CONFIG_PAGES.put("tide.spikes", List.of(
                number("tide.spikes.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("tide.spikes.range-blocks", "Spike line range", Material.PRISMARINE_BRICKS, 1, 3, 25, false),
                number("tide.spikes.damage-health-points", "Spike damage (HP)", Material.REDSTONE, .5, 0, 20, false)));
        CONFIG_PAGES.put("tide.judgment", List.of(
                number("tide.judgment.cooldown-seconds", "Cooldown (seconds)", Material.CLOCK, 1, 0, 300, true),
                number("tide.judgment.damage-per-strike-health-points", "Guardian strike damage (HP)", Material.HEART_OF_THE_SEA, .5, 0, 15, false)));
        CONFIG_PAGES.put("dragon.passive", List.of(
                number("dragon.passive.mark-burst-damage-health-points", "End Resonance burst (HP)", Material.ENDER_EYE, .5, 0, 10, false)));
        CONFIG_PAGES.put("dragon.breath", List.of(
                number("dragon.breath.cooldown-seconds", "Empowered bolt cooldown", Material.CLOCK, 1, 0, 300, true),
                number("dragon.breath.radius-blocks", "Dragon breath radius", Material.DRAGON_BREATH, .25, 1, 8, false),
                number("dragon.breath.cloud-duration-ticks", "Breath cloud duration (ticks)", Material.REPEATER, 10, 20, 120, true),
                number("dragon.breath.damage-per-pulse-health-points", "Cloud damage per pulse (HP)", Material.REDSTONE, .5, 0, 10, false)));
        CONFIG_PAGES.put("dragon.shift", List.of(
                number("dragon.shift.cooldown-seconds", "Chorus Shift cooldown", Material.CLOCK, 1, 0, 300, true),
                number("dragon.shift.range-blocks", "Chorus Shift range", Material.CHORUS_FRUIT, .5, 3, 20, false)));
        CONFIG_PAGES.put("dragon.wrath", List.of(
                number("dragon.wrath.cooldown-seconds", "Dragon's Wrath cooldown", Material.CLOCK, 1, 0, 300, true),
                number("dragon.wrath.strike-damage-health-points", "Damage per strike (HP)", Material.DRAGON_HEAD, .5, 0, 20, false)));
        CONFIG_PAGES.put("paleheart.passive", List.of(
                number("paleheart.passive.radius-blocks", "Unseen Terror detection range", Material.SPYGLASS, 1, 4, 32, true),
                number("paleheart.passive.melee-bonus-health-points", "Sickle extra melee damage (HP)", Material.NETHERITE_HOE, .5, 0, 10, false)));
        CONFIG_PAGES.put("paleheart.roots", List.of(
                number("paleheart.roots.cooldown-seconds", "Grasping Roots cooldown", Material.CLOCK, 1, 0, 300, true),
                number("paleheart.roots.range-blocks", "Roots aim range", Material.PALE_OAK_LOG, 1, 4, 25, false),
                number("paleheart.roots.damage-health-points", "Root impact damage (HP)", Material.WITHER_ROSE, .5, 0, 15, false),
                number("paleheart.roots.slow-ticks", "Root duration (ticks)", Material.COBWEB, 5, 5, 100, true)));
        CONFIG_PAGES.put("paleheart.step", List.of(
                number("paleheart.step.cooldown-seconds", "Unseen Step cooldown", Material.CLOCK, 1, 0, 300, true),
                number("paleheart.step.range-blocks", "Step range (blocks)", Material.ENDER_PEARL, 1, 2, 14, false),
                number("paleheart.step.decoy-duration-ticks", "Creaking decoy lifespan", Material.CREAKING_HEART, 10, 20, 200, true)));
        CONFIG_PAGES.put("paleheart.grove", List.of(
                number("paleheart.grove.cooldown-seconds", "Haunted Grove cooldown", Material.CLOCK, 1, 0, 300, true),
                number("paleheart.grove.radius-blocks", "Grove radius (blocks)", Material.PALE_OAK_LEAVES, .5, 3, 24, false),
                number("paleheart.grove.duration-ticks", "Grove duration (ticks)", Material.REPEATER, 10, 40, 240, true),
                number("paleheart.grove.pulse-damage-health-points", "Unwatched pulse damage (HP)", Material.REDSTONE, .5, 0, 15, false),
                number("paleheart.grove.touch-damage-health-points", "Creaking contact damage (HP)", Material.WITHER_ROSE, .5, 2, 15, false)));
        CONFIG_PAGES.put("stormvault.passive", List.of(
                number("stormvault.passive.stack-duration-seconds", "Wind Momentum lifespan", Material.FEATHER, 1, 1, 30, true)));
        CONFIG_PAGES.put("stormvault.gale", List.of(
                number("stormvault.gale.cooldown-seconds", "Gale Burst cooldown", Material.CLOCK, 1, 0, 300, true),
                number("stormvault.gale.range-blocks", "Gale range", Material.WIND_CHARGE, 1, 3, 30, false),
                number("stormvault.gale.damage-health-points", "Gale damage HP", Material.IRON_SWORD, .5, 0, 20, false),
                number("stormvault.gale.charged-bonus-health-points", "Full-charge bonus HP", Material.BREEZE_ROD, .5, 0, 10, false)));
        CONFIG_PAGES.put("stormvault.updraft", List.of(
                number("stormvault.updraft.cooldown-seconds", "Updraft cooldown", Material.CLOCK, 1, 0, 300, true)));
        CONFIG_PAGES.put("stormvault.tempest", List.of(
                number("stormvault.tempest.cooldown-seconds", "Tempest cooldown", Material.CLOCK, 1, 0, 300, true),
                number("stormvault.tempest.duration-ticks", "Tornado duration ticks", Material.REPEATER, 10, 20, 240, true),
                number("stormvault.tempest.max-travel-blocks", "Tornado travel", Material.WIND_CHARGE, 1, 2, 35, false),
                number("stormvault.tempest.pull-radius-blocks", "Tornado radius", Material.WIND_CHARGE, .5, 2, 12, false),
                number("stormvault.tempest.pulse-damage-health-points", "Pulse damage HP", Material.DIAMOND_SWORD, .5, 0, 8, false),
                number("stormvault.tempest.final-damage-health-points", "Final damage HP", Material.BREEZE_ROD, .5, 0, 16, false),
                number("stormvault.tempest.charged-bonus-health-points", "Charged bonus HP", Material.FEATHER, .5, 0, 10, false)));
        CONFIG_PAGES.put("hexcaller.passive", List.of(
                number("hexcaller.passive.mark-duration-seconds", "Hex Mark duration", Material.EMERALD, 1, 2, 30, true)));
        CONFIG_PAGES.put("hexcaller.fangs", List.of(
                number("hexcaller.fangs.cooldown-seconds", "Fangs cooldown", Material.CLOCK, 1, 0, 300, true),
                number("hexcaller.fangs.range-blocks", "Fang line range", Material.EVOKER_SPAWN_EGG, 1, 3, 25, true),
                number("hexcaller.fangs.damage-health-points", "Fangs damage HP", Material.IRON_SWORD, .5, 0, 15, false),
                number("hexcaller.fangs.hexbound-ring-damage-health-points", "Hexbound ring damage HP", Material.EMERALD, .5, 0, 15, false)));
        CONFIG_PAGES.put("hexcaller.vexbound", List.of(
                number("hexcaller.vexbound.cooldown-seconds", "Vex cooldown", Material.CLOCK, 1, 0, 300, true),
                number("hexcaller.vexbound.max-damage-health-points", "Vex max damage per hit", Material.IRON_SWORD, .5, 0, 10, false)));
        CONFIG_PAGES.put("hexcaller.dominion", List.of(
                number("hexcaller.dominion.cooldown-seconds", "Dominion cooldown", Material.CLOCK, 1, 0, 300, true),
                number("hexcaller.dominion.radius-blocks", "Ritual radius", Material.EMERALD, .5, 4, 16, false),
                number("hexcaller.dominion.wave-damage-health-points", "Fang wave damage HP", Material.REDSTONE, .5, 0, 12, false),
                number("hexcaller.dominion.final-bonus-health-points", "Final bonus HP", Material.TOTEM_OF_UNDYING, .5, 0, 12, false),
                number("hexcaller.dominion.hexbound-bonus-health-points", "Hexbound bonus HP", Material.VEX_SPAWN_EGG, .5, 0, 10, false)));
        int[] slots={10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        int idx=0;
        for (String page:CONFIG_PAGES.keySet()) {
            if (idx >= slots.length) break; // Per-weapon pages are still directly navigable.
            PAGE_SLOTS.put(slots[idx++],page);
        }
    }

    private static final class SovereignConfigHolder implements InventoryHolder {
        final Inventory inv;
        final String page;
        SovereignConfigHolder(String page) {
            this.page=page;
            this.inv=Bukkit.createInventory(this, 54,
                    Component.text(page.isEmpty()?"Sovereign - Config":"Sovereign - " + page));
        }
        @Override public Inventory getInventory() {return inv;}
    }

    private int hudTimer = 0;
    private final Set<UUID> hudWasVisible = new HashSet<>();
    private final Map<UUID, Integer> sculkSenseDistance = new HashMap<>();
    private final Map<UUID, Long> sculkSenseUntil = new HashMap<>();

    @Override
    public void onEnable() {
        artifactKey = new NamespacedKey(this, "sovereign_mace_token");
        royalTooltipKey = new NamespacedKey(this, "royal");
        echoKey = new NamespacedKey(this, "echo_scythe_token");
        sculkTooltipKey = new NamespacedKey(this, "sculk");
        echoSpeedKey = new NamespacedKey(this, "echo_speed_held");
        saveDefaultConfig();
        // One-time migration: remove the old, now-unused tooltip toggle from existing configs.
        getConfig().set("tooltips.custom-background-enabled", null);
        getConfig().set("tooltips", null);
        // Migrate the original 7-block Haunted Grove default to the requested
        // 15-block radius, while preserving deliberate custom radius values.
        if (getConfig().getDouble("paleheart.grove.radius-blocks", 7.0) == 7.0)
            getConfig().set("paleheart.grove.radius-blocks", 15.0);
        // Preserve server settings while adding newly introduced keys.
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadArtifactState();
        expansion = new SovereignExpansion(this);
        dragonfang = new DragonfangArtifact(this);
        paleheart = new PaleheartArtifact(this);
        finalArtifacts = new FinalArtifacts(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(expansion, this);
        Bukkit.getPluginManager().registerEvents(dragonfang, this);
        Bukkit.getPluginManager().registerEvents(paleheart, this);
        Bukkit.getPluginManager().registerEvents(finalArtifacts, this);
        expansion.begin();
        paleheart.begin();
        finalArtifacts.begin();
        // Items previously minted without a custom tooltip receive it automatically.
        Bukkit.getScheduler().runTaskLater(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) refreshInventory(online);
        }, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickEcho, 10L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickAbilityHud, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickSovereignPassives, 5L, 5L);
        Bukkit.getScheduler().runTaskTimer(this, this::tickSovereignSprites, 2L, 2L);
        for (World w : Bukkit.getWorlds()) for (ItemDisplay display : w.getEntitiesByClass(ItemDisplay.class)) {
            if (display.getScoreboardTags().contains(SPRITE_TAG)) display.remove();
        }
        // Clean up leftover tagged displays from a reload / earlier interrupted cast.
        for (World w : Bukkit.getWorlds()) for (BlockDisplay d : w.getEntitiesByClass(BlockDisplay.class)) {
            if (d.getScoreboardTags().contains(VORTEX_TAG)) d.remove();
        }
        // Additional expiration guard in case a scheduled animation was interrupted.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (BlockDisplay d : new ArrayList<>(spawnedGravityBlocks)) {
                if (!d.isValid()) spawnedGravityBlocks.remove(d);
            }
        }, 40L, 40L);
        if (getCommand("sovereign") != null) {
            getCommand("sovereign").setExecutor(this);
            getCommand("sovereign").setTabCompleter(this);
        }
        getLogger().info("Sovereign SMP enabled: all seven artifacts, including Stormvault and Hexcaller (7/7) + Sovereign Mace.");
    }

    @Override
    public void onDisable() {
        if (expansion != null) expansion.shutdown();
        if (paleheart != null) paleheart.shutdown();
        if (finalArtifacts != null) finalArtifacts.shutdown();
        if (dragonfang != null) dragonfang.shutdown();
        for (Player player : Bukkit.getOnlinePlayers()) removeEchoSpeed(player);
        restoreAllFootsteps();
        for (UUID id : new ArrayList<>(trueInvisibleUntil.keySet())) endTrueInvisibility(id);
        for (BukkitRunnable runnable : new ArrayList<>(activeGravityTasks)) {
            try { runnable.cancel(); } catch (IllegalStateException ignored) { }
        }
        activeGravityTasks.clear();
        for (BlockDisplay entity : new ArrayList<>(spawnedGravityBlocks)) {
            if (entity.isValid()) entity.remove();
        }
        spawnedGravityBlocks.clear();
        removeAllSovereignSprites();
        hudWasVisible.clear();
        sculkSenseDistance.clear();
        sculkSenseUntil.clear();
    }

    private void loadArtifactState() {
        stateFile = new File(getDataFolder(), "artifacts.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        maceToken = readUuid(state.getString("mace.token"));
        maceOwner = readUuid(state.getString("mace.owner"));
        if (maceToken == null) maceOwner = null;
        echoToken = readUuid(state.getString("echo_scythe.token"));
        echoOwner = readUuid(state.getString("echo_scythe.owner"));
        if (echoToken == null) echoOwner = null;
    }

    private UUID readUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException badState) { return null; }
    }

    UUID getArtifactStateUuid(String path) { return readUuid(state.getString(path)); }
    void setArtifactStateUuid(String path, UUID id) { state.set(path, id == null ? null : id.toString()); }

    void saveArtifactState() {
        state.set("mace.token", maceToken == null ? null : maceToken.toString());
        state.set("mace.owner", maceOwner == null ? null : maceOwner.toString());
        state.set("echo_scythe.token", echoToken == null ? null : echoToken.toString());
        state.set("echo_scythe.owner", echoOwner == null ? null : echoOwner.toString());
        try { state.save(stateFile); }
        catch (IOException e) { getLogger().severe("Could not save artifacts.yml: " + e.getMessage()); }
    }

    // Shared formatting utilities for all seven planned artifact types.
    // Adventure RGB component gradients work WITHOUT any resource pack.
    private Component gradient(String text, int startRgb, int endRgb, boolean bold) {
        Component result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            double t = text.length() < 2 ? 0 : (double) i / (text.length() - 1);
            int sr = startRgb >> 16 & 255, sg = startRgb >> 8 & 255, sb = startRgb & 255;
            int er = endRgb >> 16 & 255, eg = endRgb >> 8 & 255, eb = endRgb & 255;
            int r = (int) Math.round(sr + (er - sr) * t);
            int g = (int) Math.round(sg + (eg - sg) * t);
            int b = (int) Math.round(sb + (eb - sb) * t);
            result = result.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r, g, b))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, bold));
        }
        return result;
    }

    private Component plain(String text, int rgb) {
        return Component.text(text, TextColor.color(rgb))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component flavor(String text) {
        return plain(text, 0xA49AAE).decorate(TextDecoration.ITALIC);
    }

    private Component abilityLine(String key, String seconds) {
        return gradient(key, 0x75E1FF, 0xA899FF, true)
                .append(plain("  •  ", 0x7F7A9D))
                .append(gradient(seconds, 0xFAD76A, 0xFE9D5D, false));
    }

    // The Mace uses royal purple/gold; Echo Scythe uses a separately themed sculk gradient.
    private void styleArtifact(ItemMeta meta, String artifactName, List<Component> lines) {
        meta.displayName(gradient(artifactName, 0xE5B4FF, 0xF6B34D, true));
        meta.lore(lines);
        // Custom background is always assigned; client pack supplies the visual sprites.
        meta.setTooltipStyle(royalTooltipKey);
    }

    private void styleMaceMeta(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        lore.add(gradient("✦  SOVEREIGN SPECIAL WEAPON  ✦", 0xF9D47E, 0xB76AFF, true));
        lore.add(flavor("Let the world bend beneath its weight."));
        lore.add(flavor("Even the stars bow to a Sovereign."));
        lore.add(Component.empty());
        lore.add(gradient("PASSIVE  |  GRAVITATIONAL PRESSURE", 0xB97AFF, 0xFFCE73, true));
        lore.add(plain("Every 3rd successful hit increases enemy gravity", 0xDCD7E9));
        lore.add(plain("and briefly slows their movement.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("I  |  EVENT HORIZON", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("RIGHT-CLICK", getConfig().getInt("mace.event-horizon.cooldown-seconds",18) + "s"));
        lore.add(plain("Aim to unleash a violent gravity field,", 0xDCD7E9));
        lore.add(plain("dragging enemies toward the Sovereign.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("II  |  GRAVITATIONAL COLLAPSE", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("SNEAK + RIGHT-CLICK", getConfig().getInt("mace.collapse.cooldown-seconds",25) + "s"));
        lore.add(plain("Send a devastating shockwave through the earth,", 0xDCD7E9));
        lore.add(plain("launching and damaging enemies around you.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("III  |  SINGULARITY", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("F  •  SWAP HANDS", getConfig().getInt("mace.singularity.cooldown-seconds",40) + "s"));
        lore.add(plain("Summon a black hole that bends nearby blocks", 0xDCD7E9));
        lore.add(plain("into orbit, then violently detonates.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("✧ ONLY ONE SOVEREIGN MAY WIELD IT ✧", 0x8CD9FF, 0xBD7EFF, true));
        styleArtifact(meta, "SOVEREIGN MACE  ✦  GRAVITY", lore);
    }

    private void styleEchoMeta(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        lore.add(gradient("✧  LEGENDARY ARTIFACT  I / VII  ✧", 0x48FFE8, 0x155B80, true));
        lore.add(flavor("Below the cities, the silence still listens."));
        lore.add(flavor("Let the sculk remember every footstep."));
        lore.add(Component.empty());
        lore.add(gradient("PASSIVE  |  SCULK AWARENESS", 0x66F8ED, 0x158C9F, true));
        lore.add(plain("Sense nearby moving players while holding the Scythe.", 0xB9DAE3));
        lore.add(plain("Their approximate distance pulses in your action bar.", 0xB9DAE3));
        lore.add(plain("+10% movement speed (attribute) while wielded.", 0x68F5DB));
        lore.add(plain("Wardens ignore the wielder; sculk follows your steps.", 0x68F5DB));
        lore.add(Component.empty());
        lore.add(gradient("I  |  ECHO PULSE", 0x45E5DB, 0x2871AF, true));
        lore.add(gradient("RIGHT-CLICK",0x9BEDFF,0x48C5E6,true)
                .append(plain("   •   ",0x416B7E))
                .append(gradient(getConfig().getInt("echo.pulse.cooldown-seconds",22)+"s",0x75FFD8,0xF2EFAA,false)));
        lore.add(plain("Send an echo through the Deep, exposing nearby foes", 0xB9DAE3));
        lore.add(plain("with Glowing as soul and sculk waves expand.", 0xB9DAE3));
        lore.add(Component.empty());
        lore.add(gradient("II  |  SILENCE OF THE DEEP", 0x45E5DB, 0x2871AF, true));
        lore.add(gradient("SNEAK + RIGHT-CLICK",0x9BEDFF,0x48C5E6,true)
                .append(plain("   •   ",0x416B7E))
                .append(gradient(getConfig().getInt("echo.silence.cooldown-seconds",45)+"s",0x75FFD8,0xF2EFAA,false)));
        lore.add(plain("Gain TRUE INVISIBILITY: vanish completely, including", 0xB9DAE3));
        lore.add(plain("armor, items and your visible potion particles.", 0xB9DAE3));
        lore.add(Component.empty());
        lore.add(gradient("III  |  WARDEN'S WRATH", 0x45E5DB, 0x2871AF, true));
        lore.add(gradient("F  •  SWAP HANDS",0x9BEDFF,0x48C5E6,true)
                .append(plain("   •   ",0x416B7E))
                .append(gradient(getConfig().getInt("echo.wrath.cooldown-seconds",55)+"s",0x75FFD8,0xF2EFAA,false)));
        lore.add(plain("Three waves of sonic sculk energy tear forward,", 0xB9DAE3));
        lore.add(plain("damaging and throwing enemies out of your path.", 0xB9DAE3));
        lore.add(Component.empty());
        lore.add(gradient("✦  THE ANCIENT CITY HAS CHOSEN ITS WIELDER  ✦",0x70FFE5,0x3375BD,true));
        meta.displayName(gradient("ECHO SCYTHE  ✦  THE DEEP",0x74FFED,0x096D9E,true));
        meta.lore(lore);
        meta.setTooltipStyle(sculkTooltipKey);
    }

    private boolean authenticEcho(ItemStack item) {
        if (echoToken == null || item == null || item.getType() != Material.NETHERITE_SWORD || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer().get(echoKey, PersistentDataType.STRING);
        return echoToken.toString().equals(stored);
    }

    private void ensureEchoEnchantments(ItemMeta meta) {
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.removeEnchant(Enchantment.UNBREAKING);
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addEnchant(Enchantment.SWEEPING_EDGE, 3, true);
        meta.addEnchant(Enchantment.LOOTING, 4, true);
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
    }

    private ItemStack restyleEcho(ItemStack item) {
        if (!authenticEcho(item)) return item;
        ItemMeta meta = item.getItemMeta();
        ensureEchoEnchantments(meta);
        styleEchoMeta(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEchoItem() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        ensureEchoEnchantments(meta);
        styleEchoMeta(meta);
        meta.getPersistentDataContainer().set(echoKey, PersistentDataType.STRING, echoToken.toString());
        item.setItemMeta(meta);
        return item;
    }

    private boolean canUseEcho(Player player, ItemStack item) {
        if (!authenticEcho(item)) return false;
        if (echoOwner == null) {
            player.sendMessage(ChatColor.AQUA+"[Sovereign] This Echo Scythe is unclaimed. Pick it up or use /sovereign claim scythe.");
            return false;
        }
        if (!echoOwner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED+"[Sovereign] Only the Echo Scythe's owner can use it.");
            return false;
        }
        return player.getGameMode() != GameMode.SPECTATOR;
    }

    private void ensureMaceEnchantments(ItemMeta meta) {
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.removeEnchant(Enchantment.DENSITY);
        meta.removeEnchant(Enchantment.WIND_BURST);
        meta.removeEnchant(Enchantment.UNBREAKING);
        meta.addEnchant(Enchantment.DENSITY, 1, true);
        // Fire Aspect is not a vanilla Mace enchantment; manually apply its fire on melee hits.
        meta.addEnchant(Enchantment.FIRE_ASPECT, 2, true);
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
    }

    private ItemStack restyleMace(ItemStack item) {
        if (!authentic(item)) return item;
        ItemMeta meta = item.getItemMeta();
        ensureMaceEnchantments(meta);
        styleMaceMeta(meta);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMaceItem() {
        ItemStack item = new ItemStack(Material.MACE, 1);
        ItemMeta meta = item.getItemMeta();
        ensureMaceEnchantments(meta);
        styleMaceMeta(meta);
        meta.getPersistentDataContainer().set(artifactKey, PersistentDataType.STRING, maceToken.toString());
        item.setItemMeta(meta);
        return item;
    }

    private int refreshInventory(Player player) {
        int changed = 0;
        ItemStack[] inventory = player.getInventory().getContents();
        for (int i=0; i<inventory.length; i++) {
            if (authentic(inventory[i])) {
                player.getInventory().setItem(i, restyleMace(inventory[i].clone()));
                changed++;
            } else if (authenticEcho(inventory[i])) {
                player.getInventory().setItem(i, restyleEcho(inventory[i].clone()));
                changed++;
            } else if (expansion != null && expansion.authenticAny(inventory[i])) {
                player.getInventory().setItem(i, expansion.restyle(inventory[i]));
                changed++;
            } else if (dragonfang != null && dragonfang.authentic(inventory[i])) {
                player.getInventory().setItem(i, dragonfang.restyle(inventory[i]));
                changed++;
            } else if (paleheart != null && paleheart.authentic(inventory[i])) {
                player.getInventory().setItem(i, paleheart.restyle(inventory[i]));
                changed++;
            } else if (finalArtifacts != null && finalArtifacts.authenticAny(inventory[i])) {
                player.getInventory().setItem(i, finalArtifacts.restyle(inventory[i]));
                changed++;
            }
        }
        return changed;
    }

    // When an older authentic artifact is taken from a chest or moved into an inventory,
    // restyle it after Minecraft finishes the inventory transaction. No player command needed.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArtifactInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getView().getTopInventory().getHolder() instanceof SovereignConfigHolder) return;
        ItemStack clicked = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (hasSovereignStyle(clicked) || hasSovereignStyle(cursor)) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) refreshInventory(player);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArtifactInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (hasSovereignStyle(event.getOldCursor())) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) refreshInventory(player);
            });
        }
    }

    private boolean hasSovereignStyle(ItemStack stack) {
        return authentic(stack) || authenticEcho(stack) || (expansion != null && expansion.authenticAny(stack))
                || (dragonfang != null && dragonfang.authentic(stack))
                || (paleheart != null && paleheart.authentic(stack))
                || (finalArtifacts != null && finalArtifacts.authenticAny(stack));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) refreshInventory(event.getPlayer());
        }, 10L);
        // Hide players with True Invisibility from late joiners as well.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(event.getPlayer()) && !trueInvisibleUntil.containsKey(event.getPlayer().getUniqueId()))
                viewer.showEntity(this,event.getPlayer());
        }
        for (UUID invisibleId : new ArrayList<>(trueInvisibleUntil.keySet())) {
            Player hidden = Bukkit.getPlayer(invisibleId);
            if (hidden != null && hidden.isOnline()) event.getPlayer().hideEntity(this, hidden);
        }
    }

    private boolean authentic(ItemStack item) {
        if (maceToken == null || item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        String token = item.getItemMeta().getPersistentDataContainer().get(artifactKey, PersistentDataType.STRING);
        return maceToken.toString().equals(token);
    }

    private boolean canUse(Player player, ItemStack item) {
        if (!authentic(item)) return false;
        if (maceOwner == null) {
            player.sendMessage(ChatColor.YELLOW + "[Sovereign] This artifact is unclaimed. Pick it up or use /sovereign claim mace.");
            return false;
        }
        if (!maceOwner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[Sovereign] Only the current owner can use the Sovereign Mace.");
            return false;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) return false;
        return true;
    }

    boolean usableTarget(Player caster, Entity target) {
        if (!(target instanceof LivingEntity living) || living instanceof ArmorStand
                || living.isDead() || !living.isValid() || living.getUniqueId().equals(caster.getUniqueId())) return false;
        if (living instanceof Player other) {
            return other.getGameMode() != GameMode.CREATIVE && other.getGameMode() != GameMode.SPECTATOR;
        }
        return true;
    }

    private List<LivingEntity> targetsIn(Player caster, Location center, double radius, double yRange) {
        List<LivingEntity> result = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, yRange, radius)) {
            if (!usableTarget(caster, entity)) continue;
            LivingEntity target = (LivingEntity) entity;
            if (target.getLocation().toVector().setY(0).distanceSquared(center.toVector().setY(0)) > radius * radius) continue;
            result.add(target);
        }
        return result;
    }

    // ------------ In-game configuration ------------
    private ItemStack guiItem(Material type, String title, String... desc) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + title);
        List<String> lore = new ArrayList<>();
        for (String line : desc) lore.add(ChatColor.GRAY + line);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private void decorateConfig(Inventory inv) {
        ItemStack fill = guiItem(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i=0;i<inv.getSize();i++) inv.setItem(i,fill);
    }

    private Material categoryIcon(String page) {
        if (page.startsWith("mace")) return Material.MACE;
        if (page.startsWith("echo")) return Material.ECHO_SHARD;
        return page.equals("hud")?Material.COMPASS:Material.NAME_TAG;
    }

    // Config navigation: choose a WEAPON first, then its ABILITY, then values.
    private void openConfigHome(Player player) {
        SovereignConfigHolder holder = new SovereignConfigHolder("");
        decorateConfig(holder.inv);
        holder.inv.setItem(20, guiItem(Material.MACE, "Sovereign Mace", "Special Weapon", "Click to configure its passive and abilities"));
        holder.inv.setItem(24, guiItem(Material.NETHERITE_SWORD, "Echo Scythe", "Legendary Artifact I / VII", "Click to configure its passive and abilities"));
        holder.inv.setItem(22, guiItem(Material.NETHERITE_AXE, "Witherborn", "Legendary Artifact II / VII", "Wither attacks and soul energy"));
        holder.inv.setItem(26, guiItem(Material.TRIDENT, "Tidelord", "Legendary Artifact III / VII", "Guardian lasers and prismarine"));
        holder.inv.setItem(28, guiItem(Material.CROSSBOW, "Dragonfang", "Legendary Artifact IV / VII", "Dragon breath and End magic"));
        holder.inv.setItem(30, guiItem(Material.NETHERITE_HOE, "Paleheart", "Legendary Artifact V / VII", "The Pale Garden and the Creaking"));
        holder.inv.setItem(32, guiItem(Material.COPPER_SPEAR, "Stormvault", "Legendary Artifact VI / VII", "Breeze wind and aerial combat"));
        holder.inv.setItem(34, guiItem(Material.BLAZE_ROD, "Hexcaller", "Legendary Artifact VII / VII", "Evoker fangs and Vex magic"));
        holder.inv.setItem(39, guiItem(Material.COMPASS, "Ability Cooldown HUD", "Action bar settings"));
        holder.inv.setItem(43, guiItem(Material.GLOW_INK_SAC, "Custom Particle Sprites", "Optional 16x16 sprite pack settings"));
        holder.inv.setItem(31, guiItem(Material.NETHERITE_CHESTPLATE, "Ability Damage Rules", "Toggle armor bypass for all ability hits"));
        holder.inv.setItem(49, guiItem(Material.BOOK, "How to edit",
                "Weapon -> ability -> setting", "Left click increases, right click decreases",
                "Shift + click changes 5 steps", "Settings save immediately"));
        player.openInventory(holder.inv);
    }

    private void openWeaponAbilities(Player player, String weapon) {
        SovereignConfigHolder holder = new SovereignConfigHolder("weapon:" + weapon);
        decorateConfig(holder.inv);
        if (weapon.equals("mace")) {
            holder.inv.setItem(10, guiItem(Material.ENCHANTED_BOOK, "Gravitational Pressure", "Passive settings"));
            holder.inv.setItem(12, guiItem(Material.ENDER_PEARL, "Event Horizon", "Directional pulse + nearby pull"));
            holder.inv.setItem(14, guiItem(Material.ECHO_SHARD, "Gravitational Collapse", "Shockwave settings"));
            holder.inv.setItem(16, guiItem(Material.END_CRYSTAL, "Singularity", "Vortex settings"));
            holder.inv.setItem(31, guiItem(Material.AMETHYST_SHARD, "Gravity Aura", "Held-item particle settings"));
        } else if (weapon.equals("echo")) {
            holder.inv.setItem(10, guiItem(Material.SCULK_SENSOR, "Sculk Awareness", "Speed, Warden immunity and footsteps"));
            holder.inv.setItem(12, guiItem(Material.SCULK_CATALYST, "Echo Pulse", "Reveal wave settings"));
            holder.inv.setItem(14, guiItem(Material.SCULK_SHRIEKER, "Silence of the Deep", "True Invisibility settings"));
            holder.inv.setItem(16, guiItem(Material.ECHO_SHARD, "Warden's Wrath", "Sonic beam settings"));
        } else if (weapon.equals("wither")) {
            holder.inv.setItem(10, guiItem(Material.WITHER_ROSE, "Withering Touch", "Passive settings"));
            holder.inv.setItem(12, guiItem(Material.WITHER_SKELETON_SKULL, "Wither Barrage", "Miniature skull projectiles"));
            holder.inv.setItem(14, guiItem(Material.SOUL_SAND, "Soul Devour", "Wither energy siphon"));
            holder.inv.setItem(16, guiItem(Material.NETHER_STAR, "Wither Ascension", "Spectral head barrage"));
        } else if (weapon.equals("tide")) {
            holder.inv.setItem(10, guiItem(Material.HEART_OF_THE_SEA, "Guardian's Blessing", "Passive settings"));
            holder.inv.setItem(12, guiItem(Material.PRISMARINE_CRYSTALS, "Guardian Beam", "Guardian laser channel"));
            holder.inv.setItem(14, guiItem(Material.PRISMARINE_BRICKS, "Prismarine Spikes", "Terrain-safe spikes"));
            holder.inv.setItem(16, guiItem(Material.SPONGE, "Elder's Judgment", "Spectral guardian lasers"));
        } else if (weapon.equals("dragon")) {
            holder.inv.setItem(10, guiItem(Material.ENDER_EYE, "Ender Resonance", "Passive mark-burst settings"));
            holder.inv.setItem(12, guiItem(Material.DRAGON_BREATH, "Dragon's Breath", "Empowered crossbow bolts"));
            holder.inv.setItem(14, guiItem(Material.CHORUS_FRUIT, "Chorus Shift", "Safe blink teleport"));
            holder.inv.setItem(16, guiItem(Material.DRAGON_HEAD, "Dragon's Wrath", "Three sweeping strikes"));
        } else if (weapon.equals("stormvault")) {
            holder.inv.setItem(10, guiItem(Material.FEATHER, "Wind Momentum", "Aerial strike stack settings"));
            holder.inv.setItem(12, guiItem(Material.WIND_CHARGE, "Gale Burst", "Directional wind projectile"));
            holder.inv.setItem(14, guiItem(Material.BREEZE_ROD, "Updraft", "Wind jump and charged boost"));
            holder.inv.setItem(16, guiItem(Material.WIND_CHARGE, "Eye of the Tempest", "Moving tornado and shockwave"));
        } else if (weapon.equals("hexcaller")) {
            holder.inv.setItem(10, guiItem(Material.EMERALD, "Dark Evocation", "Hex marks"));
            holder.inv.setItem(12, guiItem(Material.EVOKER_SPAWN_EGG, "Fangs of Judgment", "Evoker fang line"));
            holder.inv.setItem(14, guiItem(Material.VEX_SPAWN_EGG, "Vexbound", "Summon spectral Vexes"));
            holder.inv.setItem(16, guiItem(Material.TOTEM_OF_UNDYING, "Illager's Dominion", "Ritual circle and fang waves"));
        } else if (weapon.equals("paleheart")) {
            holder.inv.setItem(10, guiItem(Material.SPYGLASS, "Unseen Terror", "Passive movement and melee settings"));
            holder.inv.setItem(12, guiItem(Material.PALE_OAK_LOG, "Grasping Roots", "Telegraphed root trap"));
            holder.inv.setItem(14, guiItem(Material.CREAKING_HEART, "Unseen Step", "Teleport and Creaking decoy"));
            holder.inv.setItem(16, guiItem(Material.PALE_OAK_LEAVES, "Haunted Grove", "Watch the Creakings to evade damage"));
        }
        holder.inv.setItem(45, guiItem(Material.ARROW, "Back to weapons"));
        player.openInventory(holder.inv);
    }

    private String pageForWeaponSlot(String weapon, int slot) {
        if (weapon.equals("mace")) {
            return switch (slot) {
                case 10 -> "mace.passive";
                case 12 -> "mace.event-horizon";
                case 14 -> "mace.collapse";
                case 16 -> "mace.singularity";
                case 31 -> "mace.cosmetics";
                default -> null;
            };
        }
        if(weapon.equals("wither")) return switch (slot) {
            case 10 -> "wither.passive";
            case 12 -> "wither.barrage";
            case 14 -> "wither.devour";
            case 16 -> "wither.ascension";
            default -> null;
        };
        if(weapon.equals("tide")) return switch (slot) {
            case 10 -> "tide.passive";
            case 12 -> "tide.beam";
            case 14 -> "tide.spikes";
            case 16 -> "tide.judgment";
            default -> null;
        };
        if(weapon.equals("dragon")) return switch (slot) {
            case 10 -> "dragon.passive";
            case 12 -> "dragon.breath";
            case 14 -> "dragon.shift";
            case 16 -> "dragon.wrath";
            default -> null;
        };
        if(weapon.equals("stormvault")) return switch(slot) {
            case 10 -> "stormvault.passive";
            case 12 -> "stormvault.gale";
            case 14 -> "stormvault.updraft";
            case 16 -> "stormvault.tempest";
            default -> null;
        };
        if(weapon.equals("hexcaller")) return switch(slot) {
            case 10 -> "hexcaller.passive";
            case 12 -> "hexcaller.fangs";
            case 14 -> "hexcaller.vexbound";
            case 16 -> "hexcaller.dominion";
            default -> null;
        };
        if(weapon.equals("paleheart")) return switch (slot) {
            case 10 -> "paleheart.passive";
            case 12 -> "paleheart.roots";
            case 14 -> "paleheart.step";
            case 16 -> "paleheart.grove";
            default -> null;
        };
        return switch (slot) {
            case 10 -> "echo.passive";
            case 12 -> "echo.pulse";
            case 14 -> "echo.silence";
            case 16 -> "echo.wrath";
            default -> null;
        };
    }

    private String displaySettingValue(Setting setting) {
        if(setting.toggle()) return getConfig().getBoolean(setting.path(),false)?"ON":"OFF";
        double value = getConfig().getDouble(setting.path());
        if(setting.wholeNumber()) return Long.toString(Math.round(value));
        return String.format(Locale.US, "%.2f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    private ItemStack configSettingItem(Setting setting) {
        String value=displaySettingValue(setting);
        return guiItem(setting.icon(), setting.label(),
                "Current: " + value,
                "Setting: " + setting.path(),
                setting.toggle()?"Click to toggle":"Left + / Right - ; Shift x5",
                "Min " + setting.min() + ", Max " + setting.max());
    }

    private void openConfigPage(Player player, String page) {
        SovereignConfigHolder holder = new SovereignConfigHolder(page);
        decorateConfig(holder.inv);
        List<Setting> values=CONFIG_PAGES.get(page);
        if(values==null){openConfigHome(player);return;}
        for (int i=0;i<values.size() && i<EDIT_SLOTS.length;i++)
            holder.inv.setItem(EDIT_SLOTS[i],configSettingItem(values.get(i)));
        holder.inv.setItem(45,guiItem(Material.ARROW,
                page.startsWith("mace.")?"Back to Mace abilities":page.startsWith("echo.")?"Back to Echo abilities":
                page.startsWith("wither.")?"Back to Witherborn abilities":page.startsWith("tide.")?"Back to Tidelord abilities":
                page.startsWith("dragon.")?"Back to Dragonfang abilities":
                page.startsWith("paleheart.")?"Back to Paleheart abilities":
                page.startsWith("stormvault.")?"Back to Stormvault abilities":
                page.startsWith("hexcaller.")?"Back to Hexcaller abilities":"Back to weapons"));
        holder.inv.setItem(49,guiItem(Material.BOOK,"Settings save immediately",
                "Left increase, right decrease, Shift x5"));
        player.openInventory(holder.inv);
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onConfigClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SovereignConfigHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !player.hasPermission("sovereign.admin")) return;
        int slot=event.getRawSlot();
        if(slot<0 || slot>=holder.inv.getSize())return;
        if(holder.page.isEmpty()) {
            if(slot==20) openWeaponAbilities(player,"mace");
            else if(slot==24) openWeaponAbilities(player,"echo");
            else if(slot==22) openWeaponAbilities(player,"wither");
            else if(slot==26) openWeaponAbilities(player,"tide");
            else if(slot==28) openWeaponAbilities(player,"dragon");
            else if(slot==30) openWeaponAbilities(player,"paleheart");
            else if(slot==32) openWeaponAbilities(player,"stormvault");
            else if(slot==34) openWeaponAbilities(player,"hexcaller");
            else if(slot==39) openConfigPage(player,"hud");
            else if(slot==43) openConfigPage(player,"visuals");
            else if(slot==31) openConfigPage(player,"combat");
            return;
        }
        if(holder.page.startsWith("weapon:")) {
            if(slot==45) {openConfigHome(player);return;}
            String page=pageForWeaponSlot(holder.page.substring("weapon:".length()),slot);
            if(page!=null) openConfigPage(player,page);
            return;
        }
        if(slot==45) {
            if(holder.page.startsWith("mace.")) openWeaponAbilities(player,"mace");
            else if(holder.page.startsWith("echo.")) openWeaponAbilities(player,"echo");
            else if(holder.page.startsWith("wither.")) openWeaponAbilities(player,"wither");
            else if(holder.page.startsWith("tide.")) openWeaponAbilities(player,"tide");
            else if(holder.page.startsWith("dragon.")) openWeaponAbilities(player,"dragon");
            else if(holder.page.startsWith("paleheart.")) openWeaponAbilities(player,"paleheart");
            else if(holder.page.startsWith("stormvault.")) openWeaponAbilities(player,"stormvault");
            else if(holder.page.startsWith("hexcaller.")) openWeaponAbilities(player,"hexcaller");
            else openConfigHome(player);
            return;
        }
        List<Setting> list=CONFIG_PAGES.get(holder.page);
        if(list==null)return;
        int index=-1;
        for(int i=0;i<list.size() && i<EDIT_SLOTS.length;i++)
            if(EDIT_SLOTS[i]==slot){index=i;break;}
        if(index<0)return;
        Setting setting=list.get(index);
        if(setting.toggle()) {
            getConfig().set(setting.path(), !getConfig().getBoolean(setting.path(),false));
        } else {
            int dir=event.isRightClick()?-1:1;
            double steps=event.isShiftClick()?5:1;
            double next=getConfig().getDouble(setting.path())+dir*steps*setting.step();
            next=Math.max(setting.min(),Math.min(setting.max(),next));
            if(setting.wholeNumber()) getConfig().set(setting.path(),(int)Math.round(next));
            else getConfig().set(setting.path(),Math.round(next*1000.0)/1000.0);
        }
        saveConfig();
        holder.inv.setItem(slot,configSettingItem(setting));
        if(setting.path().startsWith("mace.") || setting.path().startsWith("echo.")
                || setting.path().startsWith("wither.") || setting.path().startsWith("tide.")
                || setting.path().startsWith("dragon.") || setting.path().startsWith("paleheart.")
                || setting.path().startsWith("stormvault.") || setting.path().startsWith("hexcaller.")) {
            for(Player online:Bukkit.getOnlinePlayers()) refreshInventory(online);
        }
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK,.6f,1.25f);
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onConfigDrag(InventoryDragEvent event) {
        if(event.getView().getTopInventory().getHolder() instanceof SovereignConfigHolder)
            event.setCancelled(true);
    }

    // ------------ Persistent attribute, aggro guard and ambient effects ------------
    private boolean wieldingEcho(Player player) {
        return player != null && player.isOnline() && echoOwner != null
                && player.getUniqueId().equals(echoOwner)
                && authenticEcho(player.getInventory().getItemInMainHand());
    }

    private boolean wieldingMace(Player player) {
        return player != null && player.isOnline() && maceOwner != null
                && player.getUniqueId().equals(maceOwner)
                && authentic(player.getInventory().getItemInMainHand());
    }

    private void removeEchoSpeed(Player player) {
        AttributeInstance speed=player.getAttribute(Attribute.MOVEMENT_SPEED);
        if(speed!=null && echoSpeedKey!=null && speed.getModifier(echoSpeedKey)!=null)
            speed.removeModifier(echoSpeedKey);
    }

    private void reconcileEchoSpeed(Player player) {
        AttributeInstance speed=player.getAttribute(Attribute.MOVEMENT_SPEED);
        if(speed==null)return;
        double bonus=wieldingEcho(player)
                ? Math.max(0,Math.min(0.5,getConfig().getDouble("echo.passive.speed-bonus-percent",10)/100.0))
                : 0;
        AttributeModifier current=speed.getModifier(echoSpeedKey);
        if(current!=null && Math.abs(current.getAmount()-bonus)>.000001)
            speed.removeModifier(echoSpeedKey);
        else if(current!=null)return;
        if(bonus>0)speed.addTransientModifier(new AttributeModifier(
                echoSpeedKey,bonus,AttributeModifier.Operation.ADD_SCALAR));
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void wardensIgnoreEcho(EntityTargetLivingEntityEvent event) {
        if(!(event.getEntity() instanceof Warden warden))return;
        if(!(event.getTarget() instanceof Player player) || !wieldingEcho(player)
                || !getConfig().getBoolean("echo.passive.warden-immunity",true))return;
        warden.clearAnger(player);
        event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void guardEchoFromWardenHits(EntityDamageByEntityEvent event) {
        if(event.getDamager() instanceof Warden && event.getEntity() instanceof Player player
                && wieldingEcho(player) && getConfig().getBoolean("echo.passive.warden-immunity",true)) {
            ((Warden)event.getDamager()).clearAnger(player);
            event.setCancelled(true);
        }
    }

    private FootstepKey footprintKey(Block block) {
        return new FootstepKey(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ());
    }

    // Natural, full, plain blocks only: no chests, redstone, fluids, ores or tile entities.
    private boolean canTemporarySculk(Block block) {
        return switch(block.getType()) {
            case GRASS_BLOCK, DIRT, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM,
                    STONE, DEEPSLATE, ANDESITE, DIORITE, GRANITE, TUFF,
                    MOSS_BLOCK, DIRT_PATH, MUD, CLAY -> true;
            default -> false;
        };
    }

    private void layEchoFootstep(Player user, Location walkedTo) {
        if(!wieldingEcho(user) || !getConfig().getBoolean("echo.passive.footprints-enabled",true)
                || trueInvisibleUntil.containsKey(user.getUniqueId()) || !user.isOnGround())return;
        Block below=walkedTo.getBlock().getRelative(0,-1,0);
        if(!below.getWorld().isChunkLoaded(below.getX()>>4, below.getZ()>>4))return;
        FootstepKey key=footprintKey(below);
        int lifespan=Math.max(5,getConfig().getInt("echo.passive.footprint-duration-ticks",40));
        FootstepState already=footprints.get(key);
        if(already!=null){already.expiresAt=passiveClock+lifespan;return;}
        if(footprints.size()>=240 || !canTemporarySculk(below))return;
        BlockData before=below.getBlockData().clone();
        footprints.put(key,new FootstepState(before,passiveClock+lifespan));
        below.setType(Material.SCULK,false);
        abilitySprite(below.getLocation().add(.5,1.04,.5), "sculk_rune", .22f);
        below.getWorld().spawnParticle(Particle.SCULK_CHARGE_POP,
                below.getLocation().add(.5,1.1,.5),5,.35,.08,.35,.015);
    }

    private boolean restoreFootstep(FootstepKey key, FootstepState state, boolean forceChunkLoad) {
        World world=Bukkit.getWorld(key.worldId());
        if(world==null)return false;
        if(!world.isChunkLoaded(key.x()>>4,key.z()>>4)) {
            if(!forceChunkLoad)return false; // Retain record and try again after chunk loads.
            world.getChunkAt(key.x()>>4,key.z()>>4); // Cleanup on normal server shutdown.
        }
        Block block=world.getBlockAt(key.x(),key.y(),key.z());
        // Do not overwrite something else that was placed over the temporary sculk.
        if(block.getType()==Material.SCULK)block.setBlockData(state.original,false);
        return true;
    }

    private void restoreAllFootsteps() {
        for(var entry:new ArrayList<>(footprints.entrySet()))
            restoreFootstep(entry.getKey(),entry.getValue(),true);
        footprints.clear();
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void echoSteps(PlayerMoveEvent event) {
        if(event.getTo()==null || event.getFrom().getBlockX()==event.getTo().getBlockX()
                && event.getFrom().getBlockY()==event.getTo().getBlockY()
                && event.getFrom().getBlockZ()==event.getTo().getBlockZ())return;
        layEchoFootstep(event.getPlayer(),event.getTo());
    }

    private void tickSovereignPassives() {
        passiveClock+=5;
        auraClock+=5;
        for(var iterator=footprints.entrySet().iterator();iterator.hasNext();) {
            var entry=iterator.next();
            if(passiveClock<entry.getValue().expiresAt)continue;
            if(restoreFootstep(entry.getKey(),entry.getValue(),false))iterator.remove();
        }
        for(Player player:Bukkit.getOnlinePlayers()) {
            reconcileEchoSpeed(player);
            if(wieldingEcho(player) && getConfig().getBoolean("echo.passive.warden-immunity",true)) {
                for(Entity candidate:player.getWorld().getNearbyEntities(player.getLocation(),32,24,32)) {
                    if(!(candidate instanceof Warden warden))continue;
                    if(warden.getAnger(player)>0)warden.clearAnger(player);
                    if(player.equals(warden.getTarget()))warden.setTarget(null);
                }
            }
            if(auraClock%10!=0)continue;
            if(wieldingEcho(player) && getConfig().getBoolean("echo.passive.aura-enabled",true)
                    && !trueInvisibleUntil.containsKey(player.getUniqueId())) {
                player.getWorld().spawnParticle(Particle.SCULK_SOUL,
                        player.getLocation().add(0,1.05,0),3,.40,.65,.40,.014);
                if (customSpritesOn()) for (int j=0; j<Math.max(0,Math.min(5,getConfig().getInt("visuals.ambient-sprites-per-pulse",1)));j++) {
                    double th = auraClock * .13 + j * 2.4;
                    emitSovereignSprite(player.getLocation().clone().add(Math.cos(th)*.55,1.1+j*.2,Math.sin(th)*.55),
                            j%2==0?"echo_soul":"sculk_spore", .27f, 12);
                }
                if(auraClock%30==0)player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                        player.getLocation().add(0,.15,0),2,.25,.1,.25,.01);
            } else if(wieldingMace(player) && getConfig().getBoolean("mace.cosmetics.aura-enabled",true)) {
                int amount=Math.max(1,Math.min(24,getConfig().getInt("mace.cosmetics.aura-particles",5)));
                Location at=player.getLocation().add(0,1.1,0);
                double phase=auraClock*.105;
                for(int i=0;i<amount;i++) {
                    double angle=phase+i*2*Math.PI/amount;
                    Location point=at.clone().add(Math.cos(angle)*.85,Math.sin(angle*2)*.2,Math.sin(angle)*.85);
                    player.getWorld().spawnParticle(Particle.DUST,point,1,0,0,0,0,
                            new Particle.DustOptions(Color.fromRGB(151,70,238),.9f));
                }
                if (customSpritesOn()) for (int j=0; j<Math.max(0,Math.min(5,getConfig().getInt("visuals.ambient-sprites-per-pulse",1)));j++) {
                    double th=phase+j*2.3;
                    emitSovereignSprite(at.clone().add(Math.cos(th)*.7, Math.sin(th)*.35, Math.sin(th)*.7),
                            j%2==0?"gravity_star":"gravity_shard", .28f, 12);
                }
                if(auraClock%30==0)player.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                        at,3,.28,.4,.28,.015);
            }
        }
    }

    // ------------ Action-bar cooldown HUD (no resource pack needed) ------------
    private Component hudAbility(UUID playerId, String key, String label, String icon, int activeColor) {
        long remaining=cooldowns.getOrDefault(playerId,Map.of()).getOrDefault(key,0L)-currentTime();
        boolean ready=remaining<=0;
        String time = ready ? "READY" : String.format(Locale.US,"%.0fs", Math.ceil(remaining/1000.0));
        int tint=ready ? 0x64F2AA : activeColor;
        return Component.text(icon+" ",TextColor.color(activeColor))
                .append(Component.text(label+" ",TextColor.color(0xD9D7E8)))
                .append(Component.text(time,TextColor.color(tint)).decorate(TextDecoration.BOLD));
    }

    private void tickAbilityHud() {
        hudTimer+=5;
        int interval=Math.max(5,getConfig().getInt("hud.update-ticks",10));
        if(hudTimer<interval)return;
        hudTimer=0;
        for(Player player:Bukkit.getOnlinePlayers()) {
            UUID id=player.getUniqueId();
            ItemStack held=player.getInventory().getItemInMainHand();
            boolean mace=authentic(held) && id.equals(maceOwner);
            boolean scythe=authenticEcho(held) && id.equals(echoOwner);
            Component extraHud = expansion == null ? null : expansion.hud(player);
            if (extraHud == null && dragonfang != null) extraHud = dragonfang.hud(player);
            if (extraHud == null && paleheart != null) extraHud = paleheart.hud(player);
            if (extraHud == null && finalArtifacts != null) extraHud = finalArtifacts.hud(player);
            if(!getConfig().getBoolean("hud.enabled",true) || (!mace&&!scythe&&extraHud==null)) {
                if(hudWasVisible.remove(id)) player.sendActionBar(Component.empty());
                continue;
            }
            Component bar = mace
                ? hudAbility(id,HORIZON,"EH","✦",0xC68CFF)
                    .append(plain("  │  ",0x77717F))
                    .append(hudAbility(id,COLLAPSE,"GC","◆",0xF0AB65))
                    .append(plain("  │  ",0x77717F))
                    .append(hudAbility(id,SINGULARITY,"SG","◈",0xCB83F7))
                : scythe ? hudAbility(id,ECHO_PULSE,"EP","◉",0x57DED1)
                    .append(plain("  │  ",0x487780))
                    .append(hudAbility(id,ECHO_SILENCE,"SD","✧",0x53ACD4))
                    .append(plain("  │  ",0x487780))
                    .append(hudAbility(id,ECHO_WRATH,"WW","✦",0x42D8FF))
                : extraHud;
            if(scythe && getConfig().getBoolean("hud.show-sculk-awareness",true)
                    && sculkSenseUntil.getOrDefault(id,0L)>currentTime()) {
                bar=bar.append(plain("  │  ",0x487780))
                       .append(plain("◉ "+sculkSenseDistance.getOrDefault(id,0)+"m",0x61FFDA));
            }
            player.sendActionBar(bar);
            hudWasVisible.add(id);
        }
    }

    private long currentTime() { return System.currentTimeMillis(); }

    private boolean ready(Player user, String ability, String configPath) {
        long now = currentTime();
        long last = cooldowns.getOrDefault(user.getUniqueId(), Map.of()).getOrDefault(ability, 0L);
        if (last > now) {
            double seconds = (last - now) / 1000.0;
            user.sendActionBar(Component.text(String.format("§d[Sovereign] §c%s: %.1fs remaining", ability, seconds)));
            user.playSound(user.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.65f);
            return false;
        }
        long ms = Math.max(0, getConfig().getLong(configPath, 20)) * 1000L;
        cooldowns.computeIfAbsent(user.getUniqueId(), unused -> new HashMap<>()).put(ability, now + ms);
        return true;
    }

    // ---------------- Custom 16x16 art sprites (opt-in resource pack) ----------------
    private boolean customSpritesOn() {
        return getConfig().getBoolean("visuals.custom-sprites-enabled", false);
    }

    private void emitSovereignSprite(Location place, String name, float size, int lifetime) {
        if (!customSpritesOn() || place == null || place.getWorld() == null) return;
        int max = Math.max(10, Math.min(160, getConfig().getInt("visuals.max-live-sprites", 90)));
        if (liveSprites.size() >= max) return;
        if (!place.getWorld().isChunkLoaded(place.getBlockX() >> 4, place.getBlockZ() >> 4)) return;
        ItemStack image = new ItemStack(Material.PAPER);
        ItemMeta meta = image.getItemMeta();
        meta.setItemModel(new NamespacedKey("sovereignfx", name));
        image.setItemMeta(meta);
        try {
            ItemDisplay display = place.getWorld().spawn(place, ItemDisplay.class, item -> {
                item.setItemStack(image);
                item.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                item.setBillboard(Display.Billboard.CENTER);
                item.setTransformationMatrix(new Matrix4f().scale(Math.max(.12f, Math.min(2.0f, size))));
                item.setTeleportDuration(0);
                item.setPersistent(false);
                item.setViewRange(1.0f);
                item.addScoreboardTag(SPRITE_TAG);
            });
            int life = Math.max(4, Math.min(40, lifetime));
            liveSprites.put(display.getUniqueId(), spriteTick + life);
        } catch (RuntimeException ex) {
            getLogger().warning("Custom particle display failed: " + ex.getMessage());
        }
    }

    private void abilitySprite(Location place, String name, float size) {
        if (!getConfig().getBoolean("visuals.ability-sprites-enabled", true)) return;
        emitSovereignSprite(place, name, size,
                getConfig().getInt("visuals.sprite-lifetime-ticks", 12));
    }

    private void tickSovereignSprites() {
        spriteTick += 2;
        for (var it = liveSprites.entrySet().iterator(); it.hasNext();) {
            var entry = it.next();
            if (entry.getValue() > spriteTick) continue;
            Entity e = Bukkit.getEntity(entry.getKey());
            if (e != null && e.isValid()) e.remove();
            it.remove();
        }
    }

    private void removeAllSovereignSprites() {
        for (UUID id : new ArrayList<>(liveSprites.keySet())) {
            Entity e = Bukkit.getEntity(id);
            if (e != null && e.isValid()) e.remove();
        }
        liveSprites.clear();
    }

    private void gravityDust(World world, Location center, int amount, double spread) {
        world.spawnParticle(Particle.DUST, center, amount,
                spread, 0.32, spread, 0.0,
                new Particle.DustOptions(Color.fromRGB(140, 65, 225), 1.25f));
        world.spawnParticle(Particle.PORTAL, center, Math.max(5, amount / 3), spread, 0.4, spread, 0.11);
    }

    // -------------------- Right click: directional purple gravity pulse --------------------
    // The wave advances exactly along the caster's look direction. Only enemies struck
    // by the purple beam are pulled TOWARD the caster, not toward the far end of the beam.
    private void castHorizon(Player user) {
        if (!ready(user, HORIZON, "mace.event-horizon.cooldown-seconds")) return;
        World world=user.getWorld();
        Location origin=user.getEyeLocation().clone();
        Vector direction=origin.getDirection().normalize();
        double range=Math.max(6,getConfig().getDouble("mace.event-horizon.target-range-blocks",28));
        double width=Math.max(.75,getConfig().getDouble("mace.event-horizon.beam-width-blocks",2.8));
        double speed=Math.max(1,getConfig().getDouble("mace.event-horizon.pulse-speed",2.8));
        double strength=Math.min(1.5,Math.max(.3,getConfig().getDouble("mace.event-horizon.pull-velocity",.9)));
        double maxPull=Math.min(1.8,Math.max(strength,getConfig().getDouble("mace.event-horizon.max-pull-speed",1.4)));
        int pullTicks=Math.max(2,getConfig().getInt("mace.event-horizon.pull-duration-ticks",30));
        int slowness=Math.max(0,getConfig().getInt("mace.event-horizon.slowness-ticks",45));
        int ringParticles=Math.max(8,getConfig().getInt("mace.event-horizon.particles-per-ring",36));
        // Perpendicular basis vectors make the ring face the direction of travel.
        Vector initialSide=direction.clone().crossProduct(new Vector(0,1,0));
        if(initialSide.lengthSquared()<0.01)
            initialSide=direction.clone().crossProduct(new Vector(1,0,0));
        final Vector side=initialSide.normalize();
        final Vector up=side.clone().crossProduct(direction).normalize();
        Location head=origin.clone();
        world.playSound(origin,Sound.ENTITY_WARDEN_SONIC_BOOM,1.7f,.57f);
        world.playSound(origin,Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,1.4f,.45f);
        world.playSound(origin,Sound.ENTITY_ENDER_DRAGON_GROWL,.55f,1.25f);
        world.spawnParticle(Particle.FLASH,origin,1);
        abilitySprite(origin, "gravity_ring", .63f);
        user.sendActionBar(gradient("EVENT HORIZON  ✦  GRAVITY PULSE",0xB36BFF,0xF7BA65,true));

        final Map<UUID,Integer> pulledUntil=new HashMap<>();
        // The nearby burst shares the SAME cooldown and pull animation as the forward beam.
        double nearbyRadius=Math.max(0,getConfig().getDouble("mace.event-horizon.nearby-radius-blocks",5));
        if(nearbyRadius>0) {
            world.playSound(user.getLocation(),Sound.BLOCK_BEACON_DEACTIVATE,1.2f,.48f);
            gravityDust(world,user.getLocation().clone().add(0,1,0),52,nearbyRadius*.45);
            for(Entity close:world.getNearbyEntities(user.getLocation(),nearbyRadius,nearbyRadius,nearbyRadius)) {
                if(!usableTarget(user,close))continue;
                LivingEntity victim=(LivingEntity)close;
                if(victim.getLocation().distanceSquared(user.getLocation())>nearbyRadius*nearbyRadius)continue;
                pulledUntil.put(victim.getUniqueId(),pullTicks);
                dealSovereignAbilityDamage(victim,user,getConfig().getDouble("mace.event-horizon.impact-damage-health-points",2.0));
                if(slowness>0)victim.addPotionEffect(new PotionEffect(
                        PotionEffectType.SLOWNESS,slowness,1,true,true,true));
                world.spawnParticle(Particle.REVERSE_PORTAL,victim.getLocation().add(0,1,0),18,.4,.5,.4,.07);
            }
        }
        new BukkitRunnable() {
            int age=0;
            double distance=0;
            boolean blocked=false;
            @Override public void run() {
                if(!user.isOnline() || !user.getWorld().equals(world)) {cancel();return;}
                // Move the front of the beam one piece at a time: real traveling pulse.
                if(!blocked && distance<range) {
                    double traveled=0;
                    while(traveled<speed && distance<range) {
                        double step=Math.min(.9,Math.min(speed-traveled,range-distance));
                        distance+=step;
                        traveled+=step;
                        head.setX(origin.getX()+direction.getX()*distance);
                        head.setY(origin.getY()+direction.getY()*distance);
                        head.setZ(origin.getZ()+direction.getZ()*distance);
                        if(head.getBlock().getType().isSolid()) {blocked=true;break;}
                        if(!world.isChunkLoaded(head.getBlockX()>>4,head.getBlockZ()>>4)) {blocked=true;break;}
                        // Sonic-boom-style purple ring with a dense glowing center.
                        for(int i=0;i<ringParticles;i++) {
                            double angle=2*Math.PI*i/ringParticles+age*.09;
                            Vector ring=side.clone().multiply(Math.cos(angle)*width*.85)
                                    .add(up.clone().multiply(Math.sin(angle)*width*.85));
                            Location point=head.clone().add(ring);
                            world.spawnParticle(Particle.DUST,point,1,0,0,0,0,
                                    new Particle.DustOptions(Color.fromRGB(156,63,239),1.6f));
                            if(i%9==0) world.spawnParticle(Particle.REVERSE_PORTAL,point,1,0,0,0,0);
                        }
                        if (age % 3 == 0 && traveled < 1.0) abilitySprite(head.clone(), "gravity_ring", .65f);
                        world.spawnParticle(Particle.DUST,head,8,.18,.18,.18,0,
                                new Particle.DustOptions(Color.fromRGB(228,161,255),1.35f));
                        world.spawnParticle(Particle.REVERSE_PORTAL,head,7,.32,.32,.32,.05);
                        // Target hits are checked against a short cross-section of the beam.
                        for(Entity nearby:world.getNearbyEntities(head,width,width,width)) {
                            if(!usableTarget(user,nearby))continue;
                            LivingEntity victim=(LivingEntity)nearby;
                            Vector delta=victim.getLocation().clone().add(0,victim.getHeight()*.45,0)
                                    .toVector().subtract(head.toVector());
                            double along=delta.dot(direction);
                            Vector radial=delta.subtract(direction.clone().multiply(along));
                            if(Math.abs(along)>1.8 || radial.lengthSquared()>width*width)continue;
                            if(pulledUntil.containsKey(victim.getUniqueId()))continue;
                            pulledUntil.put(victim.getUniqueId(),age+pullTicks);
                            dealSovereignAbilityDamage(victim,user,getConfig().getDouble("mace.event-horizon.impact-damage-health-points",2.0));
                            if(slowness>0)victim.addPotionEffect(new PotionEffect(
                                    PotionEffectType.SLOWNESS,slowness,1,true,true,true));
                            gravityDust(world,victim.getLocation().add(0,1,0),26,.6);
                            world.playSound(victim.getLocation(),Sound.BLOCK_RESPAWN_ANCHOR_CHARGE,.9f,.8f);
                        }
                    }
                    if(age%3==0) world.playSound(head,Sound.BLOCK_BEACON_DEACTIVATE,.9f,.65f+age*.02f);
                }
                // Keep pulling those who WERE hit, even after the beam passes them.
                for(var iterator=pulledUntil.entrySet().iterator();iterator.hasNext();) {
                    var entry=iterator.next();
                    if(age>=entry.getValue()){iterator.remove();continue;}
                    Entity found=Bukkit.getEntity(entry.getKey());
                    if(!(found instanceof LivingEntity victim) || !victim.isValid() || victim.isDead()
                            || !victim.getWorld().equals(world)) {iterator.remove();continue;}
                    Vector toCaster=user.getLocation().clone().add(0,.65,0).toVector()
                            .subtract(victim.getLocation().toVector());
                    double d=toCaster.length();
                    if(d<1.4)continue;
                    Vector pull=toCaster.normalize().multiply(Math.min(maxPull,strength+d*.014));
                    // Allow downward tug, but don't violently slam grounded targets into blocks.
                    pull.setY(Math.max(-.8,Math.min(1.1,pull.getY()+.16)));
                    victim.setVelocity(pull);
                    if(age%4==0)world.spawnParticle(Particle.REVERSE_PORTAL,
                            victim.getLocation().add(0,1,0),7,.26,.3,.26,.06);
                }
                age++;
                if((blocked || distance>=range) && pulledUntil.isEmpty())cancel();
                if(age>Math.ceil(range/speed)+pullTicks+10)cancel();
            }
        }.runTaskTimer(this,0L,1L);
    }

    // Use a genuine damage source so deaths, kill attribution and totems behave normally.
    // SONIC_BOOM bypasses armor; normal damage is available through /sovereign config.
    void dealSovereignAbilityDamage(LivingEntity target, Player caster, double amount) {
        if (amount <= 0 || !target.isValid() || target.isDead()) return;
        if (getConfig().getBoolean("combat.abilities-ignore-armor", true)) {
            DamageSource source = DamageSource.builder(DamageType.SONIC_BOOM)
                    .withCausingEntity(caster).withDirectEntity(caster).build();
            target.setNoDamageTicks(0);
            target.damage(amount, source);
        } else {
            target.setNoDamageTicks(0);
            target.damage(amount, caster);
        }
    }

    // -------------------- Sneak + right click: Gravitational Collapse --------------------
    private void castCollapse(Player user) {
        if (!ready(user, COLLAPSE, "mace.collapse.cooldown-seconds")) return;
        Location origin = user.getLocation().clone();
        double radius = Math.max(3.0, getConfig().getDouble("mace.collapse.radius-blocks", 9.0));
        double damage = getConfig().getDouble("mace.collapse.damage-health-points", 5.0);
        double knockback = getConfig().getDouble("mace.collapse.knockback", 1.2);
        World world = origin.getWorld();
        world.playSound(origin, Sound.ITEM_MACE_SMASH_GROUND, 1.7f, 0.55f);
        abilitySprite(origin.clone().add(0,.7,0), "collapse_spark", .85f);
        world.spawnParticle(Particle.CLOUD, origin.clone().add(0, 0.3, 0), 60, 1, 0.1, 1, 0.08);
        Set<UUID> hit = new HashSet<>();
        int waves = Math.max(5, (int) Math.ceil(radius));
        for (int wave = 1; wave <= waves; wave++) {
            final int step = wave;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!world.equals(origin.getWorld())) return;
                double ring = radius * step / waves;
                for (int i = 0; i < 72; i++) {
                    double angle = 2 * Math.PI * i / 72.0;
                    Location p = origin.clone().add(Math.cos(angle) * ring, 0.2, Math.sin(angle) * ring);
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(154, 83, 236), 1.8f));
                }
                for (LivingEntity target : targetsIn(user, origin, ring + 0.7, 4.0)) {
                    if (!hit.add(target.getUniqueId())) continue;
                    Location targetAt = target.getLocation();
                    double dist = Math.hypot(targetAt.getX() - origin.getX(), targetAt.getZ() - origin.getZ());
                    if (dist < Math.max(0, ring - radius / waves - 1.3) || dist > ring + 0.8) {
                        hit.remove(target.getUniqueId());
                        continue;
                    }
                    dealSovereignAbilityDamage(target, user, damage);
                    Vector out = target.getLocation().toVector().subtract(origin.toVector());
                    if (out.lengthSquared() < 0.001) out = new Vector(0.2, 0, 0.2);
                    out.setY(0).normalize().multiply(knockback).setY(0.45);
                    target.setVelocity(out);
                }
            }, (long) step * 2L);
        }
        user.sendActionBar(Component.text("§dGravitational Collapse!"));
    }

    // -------------------- F (cancel swap): Singularity --------------------
    // BlockDisplay is visual only: NEVER changes or drops real world blocks.
    private List<BlockDisplay> createOrbitingBlocks(Location center, int requestedCount, double scanRadius) {
        World world = center.getWorld();
        List<org.bukkit.block.data.BlockData> palettes = new ArrayList<>();
        int radius = Math.min(8, Math.max(1, (int)Math.ceil(scanRadius)));
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x*x + z*z > radius*radius) continue;
                for (int y = -4; y <= 1; y++) {
                    Block b = world.getBlockAt(cx+x, cy+y, cz+z);
                    if (!b.getType().isSolid() || b.getState() instanceof org.bukkit.block.TileState) continue;
                    if (b.getType() == Material.BEDROCK || b.getType() == Material.BARRIER) continue;
                    palettes.add(b.getBlockData().clone());
                }
            }
        }
        Collections.shuffle(palettes);
        int count = Math.max(4, Math.min(20, requestedCount));
        List<BlockDisplay> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final org.bukkit.block.data.BlockData data = i < palettes.size()
                    ? palettes.get(i) : Material.CRYING_OBSIDIAN.createBlockData();
            final double a = 2*Math.PI*i/count;
            Location at = center.clone().add(Math.cos(a)*2.4, .5+(i%4)*.30, Math.sin(a)*2.4);
            BlockDisplay display = world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setTeleportDuration(0);  // Do not restart interpolation on every tick.
                d.setInterpolationDuration(0);
                d.setPersistent(false);
                d.setViewRange(2.0f);
                d.addScoreboardTag(VORTEX_TAG);
            });
            result.add(display);
            spawnedGravityBlocks.add(display);
        }
        return result;
    }

    private void removeGravityVisuals(List<BlockDisplay> list) {
        for (BlockDisplay display : list) {
            spawnedGravityBlocks.remove(display);
            if (display != null && display.isValid()) display.remove();
        }
        list.clear();
    }

    // Guard against world errors: all animation paths call finish() and remove the visuals.
    private void castSingularity(Player caster) {
        if (!ready(caster, SINGULARITY, "mace.singularity.cooldown-seconds")) return;
        double range = Math.max(3, getConfig().getDouble("mace.singularity.target-range-blocks", 20.0));
        Location eye = caster.getEyeLocation();
        Vector aim = eye.getDirection().normalize();
        org.bukkit.util.RayTraceResult rayHit = caster.getWorld().rayTraceBlocks(
                eye, aim, range, FluidCollisionMode.NEVER, true);
        // Put the gravity core in OPEN space next to the hit face, not inside terrain.
        Location center = rayHit == null || rayHit.getHitBlockFace() == null
                ? eye.clone().add(aim.clone().multiply(range * .75))
                : rayHit.getHitPosition().toLocation(caster.getWorld())
                    .add(rayHit.getHitBlockFace().getDirection().multiply(1.35));
        World world = center.getWorld();
        int chargeTicks = Math.max(20, getConfig().getInt("mace.singularity.duration-ticks", 80));
        double radius = Math.max(2.0, getConfig().getDouble("mace.singularity.radius-blocks", 6.5));
        double damage = getConfig().getDouble("mace.singularity.burst-damage-health-points", 6.0);
        double push = getConfig().getDouble("mace.singularity.burst-push-velocity", 2.1);
        int count = getConfig().getInt("mace.singularity.orbit-block-count", 12);
        List<BlockDisplay> orbiters;
        try {
            orbiters = createOrbitingBlocks(center, count,
                    getConfig().getDouble("mace.singularity.block-sample-radius", 5.0));
        } catch (Exception ex) {
            getLogger().warning("Singularity failed to create visuals: " + ex.getMessage());
            // Refund an ability that couldn't start.
            Map<String, Long> casterCooldowns = cooldowns.get(caster.getUniqueId());
            if (casterCooldowns != null) casterCooldowns.remove(SINGULARITY);
            caster.sendMessage(ChatColor.RED + "[Sovereign] Singularity could not start. Check the console.");
            return;
        }

        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, .50f);
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.25f, .40f);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 65, 1.1, .8, 1.1, .2);
        caster.sendActionBar(gradient("SINGULARITY  ✦  THE VOID AWAKENS", 0x985EFF, 0xF4C15A, true));
        abilitySprite(center.clone(), "void_core", 1.15f);

        final BukkitRunnable effect = new BukkitRunnable() {
            int age = 0;
            int blastAge = 0;
            boolean burst = false;
            boolean ended = false;
            final List<Vector> ejection = new ArrayList<>();

            void finish() {
                if (ended) return;
                ended = true;
                removeGravityVisuals(orbiters);
                activeGravityTasks.remove(this);
                cancel();
            }

            @Override public void run() {
                if (ended) return;
                try {
                    // Continue the explosion even if the caster logs off during its charge.
                    if (!burst && age >= chargeTicks) {
                        burst = true;
                        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.9f, .55f);
                        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.7f, .65f);
                        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.4f, .85f);
                        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
                        abilitySprite(center, "gravity_ring", 1.5f);
                        for(int j=0;j<5;j++) {
                            double a=j*Math.PI*2/5;
                            abilitySprite(center.clone().add(Math.cos(a)*2,.45+Math.sin(a)*.4,Math.sin(a)*2),"collapse_spark",.6f);
                        }
                        world.spawnParticle(Particle.REVERSE_PORTAL, center, 180, 2.4, 1.6, 2.4, .52);
                        gravityDust(world, center, 180, 2.4);
                        if (caster.isOnline() && caster.getWorld().equals(world)) {
                            for (LivingEntity victim : targetsIn(caster, center, radius + 1.0, radius + 1.0)) {
                                // Strong guaranteed detonation damage, with vanilla kill attribution.
                                dealSovereignAbilityDamage(victim, caster, damage);
                                Vector away = victim.getLocation().toVector().subtract(center.toVector());
                                if (away.lengthSquared() < .05) away = new Vector(.3,.1,.3);
                                away.normalize().multiply(push).setY(.55);
                                victim.setVelocity(away);
                            }
                        }
                        for (int i=0; i<orbiters.size(); i++) {
                            BlockDisplay display = orbiters.get(i);
                            Vector outward = display.getLocation().toVector().subtract(center.toVector());
                            if (outward.lengthSquared() < .01) outward = new Vector(Math.cos(i), .15, Math.sin(i));
                            outward.normalize().multiply(1.0 + ThreadLocalRandom.current().nextDouble(.55));
                            outward.setY(outward.getY() + .20);
                            ejection.add(outward);
                        }
                    }
                    if (burst) {
                        if (blastAge >= 14) { finish(); return; }
                        for (int i=0; i<orbiters.size(); i++) {
                            BlockDisplay display = orbiters.get(i);
                            if (!display.isValid()) continue;
                            Vector velocity = ejection.get(i).clone().multiply(1 + blastAge*.08);
                            Location next = display.getLocation().add(velocity);
                            display.teleport(next);
                            world.spawnParticle(Particle.PORTAL, next, 3, .08,.08,.08,.04);
                        }
                        if (blastAge % 3 == 0)
                            world.spawnParticle(Particle.CLOUD, center, 18, 2.5, 1, 2.5, .08);
                        blastAge++;
                        return;
                    }

                    double progress = age/(double)chargeTicks;
                    for (int i=0; i<orbiters.size(); i++) {
                        BlockDisplay display = orbiters.get(i);
                        if (!display.isValid()) continue;
                        double angle = age*.105 + 2*Math.PI*i/orbiters.size();
                        double r = 3.7 - progress*2.0 + (i%3)*.18;
                        double height = Math.sin(angle*2+i*.8)*.8+(i%4)*.26;
                        Location next = center.clone().add(Math.cos(angle)*r, height, Math.sin(angle)*r);
                        display.teleport(next);
                        if (i%3==0) world.spawnParticle(Particle.REVERSE_PORTAL, next, 2,.1,.1,.1,.03);
                    }
                    // Core made of dense, spiraling purple particles, not solid blocks.
                    if (age % 2 == 0) {
                        if (age % 6 == 0) abilitySprite(center.clone().add(0,.1,0),"void_core",1.0f);
                        if (age % 12 == 0) for (int j=0;j<3;j++) {
                            double a=age*.15+j*2*Math.PI/3;
                            abilitySprite(center.clone().add(Math.cos(a)*1.6,Math.sin(a*1.5)*.7,Math.sin(a)*1.6),"gravity_shard",.38f);
                        }
                        gravityDust(world, center, 30, .85);
                        world.spawnParticle(Particle.REVERSE_PORTAL, center, 18, .65, .65, .65, .06);
                        for (int j=0; j<16; j++) {
                            double a = age*.16 + j*Math.PI/8;
                            double r = .85+(j%4)*.42;
                            Location p = center.clone().add(Math.cos(a)*r, Math.sin(a*2)*.65, Math.sin(a)*r);
                            world.spawnParticle(Particle.DUST, p, 1, 0,0,0,0,
                                    new Particle.DustOptions(Color.fromRGB(146,65,234),1.8f));
                        }
                    }
                    if (age % 12 == 0) {
                        world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, .8f,
                                .45f+(float)progress*.8f);
                        world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, .6f, .6f);
                    }
                    if (caster.isOnline() && caster.getWorld().equals(world)) {
                        for (LivingEntity victim : targetsIn(caster, center, radius, radius)) {
                            Vector toward = center.toVector().subtract(victim.getLocation().toVector());
                            double distance = toward.length();
                            if (distance > .8) {
                                // Pull reaches the center without unsafe teleport or extreme velocity.
                                Vector velocity = toward.normalize().multiply(Math.min(1.0, .30 + distance*.06));
                                velocity.setY(Math.max(-.25, Math.min(.42, velocity.getY())));
                                victim.setVelocity(velocity);
                            }
                            if (age > 0 && age % 20 == 0) {
                                dealSovereignAbilityDamage(victim, caster,
                                        getConfig().getDouble("mace.singularity.pulse-damage-health-points", 1.0));
                                world.spawnParticle(Particle.DAMAGE_INDICATOR,
                                        victim.getLocation().add(0, 1, 0), 6, .25, .3, .25, .05);
                            }
                        }
                    }
                    age++;
                } catch (Exception ex) {
                    getLogger().warning("Singularity runtime error (cleaning visuals): " + ex.getMessage());
                    finish();
                }
            }
        };
        activeGravityTasks.add(effect);
        try {
            effect.runTaskTimer(this, 1L, 1L);
        } catch (RuntimeException ex) {
            activeGravityTasks.remove(effect);
            removeGravityVisuals(orbiters);
            getLogger().warning("Unable to schedule Singularity: " + ex.getMessage());
        }
    }

    // -------------------- ECHO SCYTHE: WARDEN & ANCIENT CITY --------------------
    private void echoRitual(World w, Location at, int burst) {
        abilitySprite(at.clone(), "sculk_rune", .7f);
        w.spawnParticle(Particle.SCULK_SOUL, at, burst, .65, .7, .65, .045);
        w.spawnParticle(Particle.SCULK_CHARGE, at, Math.max(3,burst/5), .65, .35, .65, .01, Float.valueOf(.6f));
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, at, burst, .7, .4, .7, .015);
        w.spawnParticle(Particle.TRIAL_OMEN, at, Math.max(2, burst/4), .5, .5, .5, .04);
        w.spawnParticle(Particle.SCULK_CHARGE_POP, at, Math.max(4,burst/3), .5, .5, .5, .02);
    }

    private void castEchoPulse(Player user) {
        if (!ready(user,ECHO_PULSE,"echo.pulse.cooldown-seconds")) return;
        double range = Math.max(4,getConfig().getDouble("echo.pulse.radius-blocks",18));
        int glow = Math.max(20,getConfig().getInt("echo.pulse.glowing-seconds",6)*20);
        Location origin = user.getLocation().clone().add(0,.65,0);
        World world = origin.getWorld();
        world.playSound(origin, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,1.35f,.9f);
        world.playSound(origin, Sound.ENTITY_WARDEN_HEARTBEAT,1.6f,1.15f);
        world.playSound(origin, Sound.BLOCK_SCULK_CATALYST_BLOOM,1.25f,.8f);
        echoRitual(world,origin,75);
        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable(){
            int tick=0;
            @Override public void run(){
                if(tick>=16){cancel();return;}
                double r=range*((double)(tick+1)/16.0);
                for(int n=0;n<60;n++){
                    double a=n*2*Math.PI/60;
                    Location p=origin.clone().add(Math.cos(a)*r,.2,Math.sin(a)*r);
                    if(tick%3==0 && n%20==0) abilitySprite(p.clone().add(0,.35,0),"echo_ring",.38f);
                    world.spawnParticle(Particle.SCULK_SOUL,p,1,0,0,0,0);
                    if(n%8==0) {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME,p,1,0,0,0,0);
                        world.spawnParticle(Particle.SCULK_CHARGE,p,1,0,0,0,0,Float.valueOf((float)a));
                    }
                }
                for(LivingEntity entity:targetsIn(user,origin,r+1,Math.max(5,range*.5))){
                    if(!hit.add(entity.getUniqueId()))continue;
                    entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,glow,0,true,false,true));
                    world.spawnParticle(Particle.SCULK_CHARGE_POP,entity.getLocation().add(0,1,0),18,.45,.5,.45,.06);
                    world.playSound(entity.getLocation(),Sound.BLOCK_SCULK_SENSOR_CLICKING,.6f,1.3f);
                }
                tick++;
            }
        }.runTaskTimer(this,0L,1L);
    }

    // hideEntity affects the entire client-side entity: armor, weapons, name tag, and body.
    // This deliberately does NOT manipulate other players' render settings or player lists.
    private void beginTrueInvisibility(Player user){
        if(!ready(user,ECHO_SILENCE,"echo.silence.cooldown-seconds"))return;
        int duration=Math.max(20,getConfig().getInt("echo.silence.duration-seconds",7)*20);
        Location origin=user.getLocation().clone();
        echoRitual(user.getWorld(),origin.clone().add(0,1,0),55);
        user.getWorld().playSound(origin,Sound.ENTITY_WARDEN_AMBIENT,1.3f,.5f);
        user.getWorld().playSound(origin,Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,.8f,.6f);
        for(LivingEntity enemy:targetsIn(user,origin,
                getConfig().getDouble("echo.silence.enemy-radius-blocks",9),6)){
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,4*20,0,true,false,true));
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,4*20,0,true,false,true));
        }
        if(!trueInvisibleUntil.containsKey(user.getUniqueId())){
            previousSilent.put(user.getUniqueId(),user.isSilent());
            previousInvisibility.put(user.getUniqueId(),user.getPotionEffect(PotionEffectType.INVISIBILITY));
        }
        trueInvisibleUntil.put(user.getUniqueId(),System.currentTimeMillis()+duration*50L);
        user.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,duration,0,true,false,false));
        user.setSilent(true);
        for(Player viewer:Bukkit.getOnlinePlayers()){
            if(!viewer.equals(user)) viewer.hideEntity(this,user);
        }
        user.sendActionBar(gradient("TRUE INVISIBILITY  ✦  YOU ARE UNSEEN",0x49FFE5,0x158CB0,true));
    }

    private void endTrueInvisibility(UUID id){
        if(trueInvisibleUntil.remove(id)==null)return;
        Player user=Bukkit.getPlayer(id);
        if(user!=null && user.isOnline()){
            for(Player viewer:Bukkit.getOnlinePlayers()) if(!viewer.equals(user))viewer.showEntity(this,user);
            user.setSilent(previousSilent.getOrDefault(id,false));
            user.removePotionEffect(PotionEffectType.INVISIBILITY);
            PotionEffect old=previousInvisibility.get(id);
            if(old!=null && old.getDuration()>0) user.addPotionEffect(old);
            user.sendActionBar(gradient("THE DEEP RELEASES YOU",0x55F6E6,0x205F85,true));
            user.getWorld().playSound(user.getLocation(),Sound.BLOCK_SCULK_CATALYST_BLOOM,.55f,1.3f);
        }
        previousSilent.remove(id);
        previousInvisibility.remove(id);
    }

    private void castWardenWrath(Player user){
        if(!ready(user,ECHO_WRATH,"echo.wrath.cooldown-seconds"))return;
        World w=user.getWorld();
        Location origin=user.getEyeLocation().clone();
        Vector direction=origin.getDirection().normalize();
        double range=Math.max(6,getConfig().getDouble("echo.wrath.range-blocks",22));
        double damage=Math.max(0,getConfig().getDouble("echo.wrath.damage-health-points",5));
        w.playSound(origin,Sound.ENTITY_WARDEN_ROAR,1.65f,.7f);
        w.spawnParticle(Particle.SHRIEK,origin,2,0,0,0,0,0);
        for(int wave=0;wave<3;wave++){
            final int step=wave;
            Bukkit.getScheduler().runTaskLater(this,()->{
                if(!user.isOnline() || !user.getWorld().equals(w))return;
                w.playSound(origin,Sound.ENTITY_WARDEN_SONIC_BOOM,1.3f,.82f+step*.12f);
                Set<UUID> struck=new HashSet<>();
                for(double t=.8;t<=range;t+=.8){
                    Location p=origin.clone().add(direction.clone().multiply(t));
                    if(p.getBlock().getType().isSolid())break;
                    if(step==0 && ((int)(t*10))%32<8) abilitySprite(p.clone(),"sonic_arc",.45f);
                    w.spawnParticle(Particle.SONIC_BOOM,p,1,0,0,0,0);
                    if(((int)t)%3==0){
                        w.spawnParticle(Particle.SCULK_SOUL,p,3,.35,.35,.35,.01);
                        w.spawnParticle(Particle.SOUL_FIRE_FLAME,p,2,.25,.25,.25,.005);
                        w.spawnParticle(Particle.SCULK_CHARGE_POP,p,3,.18,.18,.18,.015);
                    }
                    for(LivingEntity target:targetsIn(user,p,1.8,2.4)){
                        if(!struck.add(target.getUniqueId()))continue;
                        dealSovereignAbilityDamage(target,user,damage);
                        Vector shove=direction.clone().multiply(1.15);
                        shove.setY(.35);
                        target.setVelocity(shove);
                    }
                }
            },wave*10L);
        }
    }

    private void tickEcho(){
        long now=System.currentTimeMillis();
        for(UUID id:new ArrayList<>(trueInvisibleUntil.keySet())){
            Player player=Bukkit.getPlayer(id);
            if(player==null || !player.isOnline() || now>=trueInvisibleUntil.get(id)) endTrueInvisibility(id);
        }
        passiveTicks++;
        if(passiveTicks%4!=0)return; // every 2 seconds
        if(echoOwner==null)return;
        Player wielder=Bukkit.getPlayer(echoOwner);
        if(wielder==null || !wielder.isOnline() || !authenticEcho(wielder.getInventory().getItemInMainHand()))return;
        if(trueInvisibleUntil.containsKey(wielder.getUniqueId()))return;
        double radius=Math.max(5,getConfig().getDouble("echo.passive.sense-radius-blocks",18));
        Player nearest=null;
        double nearestDist=Double.MAX_VALUE;
        for(Player player:wielder.getWorld().getPlayers()){
            if(player.equals(wielder) || player.getGameMode()==GameMode.SPECTATOR)continue;
            if(!player.isSprinting() && player.getVelocity().getY()<=.12)continue;
            double squared=player.getLocation().distanceSquared(wielder.getLocation());
            if(squared<radius*radius && squared<nearestDist){nearest=player;nearestDist=squared;}
        }
        if(nearest!=null){
            int distance=(int)Math.sqrt(nearestDist);
            sculkSenseDistance.put(wielder.getUniqueId(),distance);
            sculkSenseUntil.put(wielder.getUniqueId(),currentTime()+2500L);
            if(!getConfig().getBoolean("hud.enabled",true))
                wielder.sendActionBar(gradient("SCULK AWARENESS  ✦  MOVEMENT ",0x40F5E4,0x126E9F,true)
                        .append(plain("  ~"+distance+" blocks",0xA6E6DD)));
            wielder.playSound(wielder.getLocation(),Sound.BLOCK_SCULK_SENSOR_CLICKING,.35f,1.5f);
        }
    }

    @EventHandler(priority=EventPriority.NORMAL)
    public void onEchoClick(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND)return;
        Action action=event.getAction();
        if(action!=Action.RIGHT_CLICK_AIR && action!=Action.RIGHT_CLICK_BLOCK)return;
        ItemStack item=event.getItem();
        if(!authenticEcho(item))return;
        event.setCancelled(true);
        Player user=event.getPlayer();
        if(!canUseEcho(user,item))return;
        if(user.isSneaking())beginTrueInvisibility(user);
        else castEchoPulse(user);
    }

    @EventHandler
    public void onEchoQuit(PlayerQuitEvent event){
        UUID id=event.getPlayer().getUniqueId();
        endTrueInvisibility(id);
        hudWasVisible.remove(id);
        removeEchoSpeed(event.getPlayer());
        sculkSenseDistance.remove(id);
        sculkSenseUntil.remove(id);
        // Ensure the old invisible-view flag cannot survive a disconnect/reconnect.
        for(Player viewer:Bukkit.getOnlinePlayers()) {
            if(!viewer.equals(event.getPlayer()))viewer.showEntity(this,event.getPlayer());
        }
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void blockUnauthorizedEchoDamage(EntityDamageByEntityEvent event){
        if(!(event.getDamager() instanceof Player attacker))return;
        if(authenticEcho(attacker.getInventory().getItemInMainHand()) &&
                !attacker.getUniqueId().equals(echoOwner)) event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void releaseScytheAfterDeath(PlayerDeathEvent event){
        Player dead=event.getEntity();
        if(trueInvisibleUntil.containsKey(dead.getUniqueId()))endTrueInvisibility(dead.getUniqueId());
        removeEchoSpeed(dead);
        if(echoOwner==null || !echoOwner.equals(dead.getUniqueId()))return;
        echoOwner=null;
        saveArtifactState();
        cooldowns.remove(dead.getUniqueId());
        if(event.getKeepInventory()){
            ItemStack lost=null;
            for(int i=0;i<dead.getInventory().getSize();i++){
                ItemStack stack=dead.getInventory().getItem(i);
                if(!authenticEcho(stack))continue;
                if(lost==null)lost=stack.clone();
                dead.getInventory().setItem(i,null);
            }
            event.getDrops().removeIf(this::authenticEcho);
            if(lost!=null){
                final ItemStack dropped=lost;
                final Location at=dead.getLocation().clone();
                Bukkit.getScheduler().runTask(this,()->at.getWorld().dropItemNaturally(at,dropped));
            }
        }
        Bukkit.broadcastMessage(ChatColor.DARK_AQUA+"[Sovereign] "+ChatColor.AQUA+
                "The Echo Scythe has fallen! It can now be claimed by a new wielder.");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMaceClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!authentic(item)) return;
        event.setCancelled(true);
        Player user = event.getPlayer();
        if (!canUse(user, item)) return;
        if (user.isSneaking()) castCollapse(user);
        else castHorizon(user);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player user = event.getPlayer();
        ItemStack item = user.getInventory().getItemInMainHand();
        if (authenticEcho(item)) {
            event.setCancelled(true);
            if (canUseEcho(user,item)) castWardenWrath(user);
            return;
        }
        if (!authentic(item)) return;
        event.setCancelled(true); // F casts an ability; it does not swap the item.
        if (!canUse(user, item)) return;
        castSingularity(user);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void maceFireAspect(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity victim)) return;
        if (!authentic(attacker.getInventory().getItemInMainHand()) || !attacker.getUniqueId().equals(maceOwner)) return;
        if (event.getFinalDamage() > 0 && usableTarget(attacker, victim))
            victim.setFireTicks(Math.max(victim.getFireTicks(), 80));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void preventUnauthorizedMaceDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!authentic(attacker.getInventory().getItemInMainHand())) return;
        if (maceOwner == null || !maceOwner.equals(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player user) || !usableTarget(user, event.getEntity())) return;
        if (!authentic(user.getInventory().getItemInMainHand()) || !user.getUniqueId().equals(maceOwner)) return;
        if (event.getFinalDamage() <= 0) return;
        LivingEntity victim = (LivingEntity) event.getEntity();
        UUID id = user.getUniqueId();
        int hits = hitCombos.getOrDefault(id, 0) + 1;
        if (hits < 3) { hitCombos.put(id, hits); return; }
        hitCombos.put(id, 0);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                getConfig().getInt("mace.passive.slowness-ticks", 45), 1, true, true, true));
        // Heavier pull toward ground for airborne targets; no extra attack damage.
        Vector old = victim.getVelocity();
        if (!victim.isOnGround()) victim.setVelocity(old.setY(Math.max(-0.75, old.getY() - 0.55)));
        gravityDust(victim.getWorld(), victim.getLocation().add(0, 0.9, 0), 24, 0.4);
        user.sendActionBar(Component.text("§5Gravitational Pressure §7• Heavy gravity applied"));
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65f, 0.75f);
    }

    // Ownership is assigned after inventory transfer, not during a potentially failed pickup.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player picker)) return;
        ItemStack pickup = event.getItem().getItemStack();
        boolean echo = authenticEcho(pickup), mace = authentic(pickup);
        if(!echo && !mace)return;
        Bukkit.getScheduler().runTask(this, () -> {
            if(!picker.isOnline())return;
            boolean received = false;
            for(int slot=0;slot<picker.getInventory().getSize();slot++){
                ItemStack stack = picker.getInventory().getItem(slot);
                if(echo && authenticEcho(stack)){
                    received=true;
                    picker.getInventory().setItem(slot,restyleEcho(stack.clone()));
                } else if(mace && authentic(stack)){
                    received=true;
                    picker.getInventory().setItem(slot,restyleMace(stack.clone()));
                }
            }
            if(!received)return;
            if(echo && echoOwner==null){
                echoOwner=picker.getUniqueId();saveArtifactState();
                Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+picker.getName()+" claimed Echo Scythe (I / VII)!");
            } else if(mace && maceOwner==null){
                maceOwner=picker.getUniqueId();saveArtifactState();
                Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+picker.getName()+" claimed the Sovereign Mace!");
            }
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOwnerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (maceOwner == null || !maceOwner.equals(dead.getUniqueId())) return;
        maceOwner = null;
        saveArtifactState();
        hitCombos.remove(dead.getUniqueId());
        cooldowns.remove(dead.getUniqueId());
        // Vanilla drops work normally. When keepInventory is enabled, drop only the artifact,
        // so it becomes claimable instead of being locked to a defeated player.
        if (event.getKeepInventory()) {
            ItemStack lost = null;
            for (int slot = 0; slot < dead.getInventory().getSize(); slot++) {
                ItemStack stack = dead.getInventory().getItem(slot);
                if (!authentic(stack)) continue;
                if (lost == null) lost = stack.clone();
                dead.getInventory().setItem(slot, null);
            }
            event.getDrops().removeIf(this::authentic);
            if (lost != null) {
                final ItemStack itemToDrop = lost;
                final Location at = dead.getLocation().clone();
                Bukkit.getScheduler().runTask(this, () -> at.getWorld().dropItemNaturally(at, itemToDrop));
            }
        }
        String killer = dead.getKiller() == null ? "someone else" : dead.getKiller().getName();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + ChatColor.GOLD
                + dead.getName() + " was defeated! The Sovereign Mace is now claimable by " + killer + " or another player.");
    }

    private ItemStack findArtifact(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (authentic(item)) return item;
        }
        return null;
    }

    private void handItemTo(Player player, ItemStack item) {
        var leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (ItemStack left : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), left);
            player.sendMessage(ChatColor.YELLOW + "[Sovereign] Your inventory was full, so the Mace dropped at your feet.");
        }
    }

    private void claim(Player player) {
        if (maceToken == null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] The Sovereign Mace has not been minted yet.");
        } else if (maceOwner != null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] The artifact already has an owner.");
        } else if (findArtifact(player) == null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] You must physically possess the authentic mace to claim it.");
        } else {
            maceOwner = player.getUniqueId();
            saveArtifactState();
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + player.getName()
                    + " claimed the Sovereign Mace of Gravity!");
        }
    }

    private ItemStack findEcho(Player player) {
        for(ItemStack item:player.getInventory().getContents()) if(authenticEcho(item))return item;
        return null;
    }

    private void claimEcho(Player user){
        if(echoToken==null){user.sendMessage(ChatColor.RED+"[Sovereign] Echo Scythe has not been minted.");return;}
        if(echoOwner!=null){user.sendMessage(ChatColor.RED+"[Sovereign] Echo Scythe already has an owner.");return;}
        if(findEcho(user)==null){user.sendMessage(ChatColor.RED+"[Sovereign] You must hold the authentic Echo Scythe in your inventory.");return;}
        echoOwner=user.getUniqueId();
        saveArtifactState();
        Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+user.getName()+" claimed Artifact I / VII: Echo Scythe!");
    }

    private String echoOwnerName(){
        if(echoOwner==null)return "Unclaimed";
        String name=Bukkit.getOfflinePlayer(echoOwner).getName();
        return name==null?echoOwner.toString():name;
    }

    private String ownerName() {
        if (maceOwner == null) return "Unclaimed";
        String n = Bukkit.getOfflinePlayer(maceOwner).getName();
        return n == null ? maceOwner.toString() : n;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sovereign")) return false;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.AQUA + "[Sovereign SMP] Legendary artifacts: 7/7 | Special weapons: 1");
            sender.sendMessage(ChatColor.GRAY + "/sovereign status — artifact ownership");
            sender.sendMessage(ChatColor.GRAY + "/sovereign claim scythe — claim the unowned Echo Scythe");
            sender.sendMessage(ChatColor.GRAY + "/sovereign claim mace — claim the unowned special Mace");
            sender.sendMessage(ChatColor.GRAY + "/sovereign claim <witherborn|tidelord|dragonfang|paleheart|stormvault|hexcaller> — claim an artifact");
            sender.sendMessage(ChatColor.GRAY + "/sovereign cooldowns — your artifact and Mace ability cooldowns");
            sender.sendMessage(ChatColor.GRAY + "Ability HUD appears automatically when holding your weapon (no pack needed).");
            sender.sendMessage(ChatColor.GRAY + "/sovereign restyle — refresh authentic item descriptions (usually automatic)");
            if (sender.hasPermission("sovereign.admin")) {
                sender.sendMessage(ChatColor.GOLD + "/sovereign mint scythe <player> — create artifact I / VII once");
                sender.sendMessage(ChatColor.GOLD + "/sovereign recover scythe <player> — emergency artifact replacement");
                sender.sendMessage(ChatColor.GOLD + "/sovereign mint mace <player> — create unique SPECIAL WEAPON once");
                sender.sendMessage(ChatColor.GOLD + "/sovereign recover mace <player> — emergency Mace replacement");
                sender.sendMessage(ChatColor.GOLD + "/sovereign mint <witherborn|tidelord|dragonfang|paleheart|stormvault|hexcaller> <player> — create each artifact once");
                sender.sendMessage(ChatColor.GOLD + "/sovereign recover <witherborn|tidelord|dragonfang|paleheart|stormvault|hexcaller> <player> — replace a lost artifact");
                sender.sendMessage(ChatColor.GOLD + "/sovereign config — live ability settings GUI");
                sender.sendMessage(ChatColor.GOLD + "/sovereign cleanup — remove tagged Singularity displays near you");
                sender.sendMessage(ChatColor.RED + "/sovereign cleanup legacy — remove *all* nearby NON-PERSISTENT block displays");
            }
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "config" -> {
                if (!sender.hasPermission("sovereign.admin")) {
                    sender.sendMessage(ChatColor.RED+"Only operators can edit Sovereign settings.");return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.RED+"Use /sovereign config in-game.");return true;
                }
                openConfigHome(player);
                return true;
            }
            case "cleanup" -> {
                if (!sender.hasPermission("sovereign.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(sender instanceof Player player)) { sender.sendMessage("Run this command in-game near the stuck blocks."); return true; }
                boolean legacy = args.length >= 2 && args[1].equalsIgnoreCase("legacy");
                int count = 0;
                // Legacy mode is explicit because it may affect temporary displays from other plugins.
                for (Entity entity : player.getNearbyEntities(40, 40, 40)) {
                    if (!(entity instanceof BlockDisplay block)) continue;
                    if (block.getScoreboardTags().contains(VORTEX_TAG) || (legacy && !block.isPersistent())) {
                        block.remove();
                        spawnedGravityBlocks.remove(block);
                        count++;
                    }
                }
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Removed " + count + " gravity block displays." +
                        (legacy ? " (Legacy cleanup may affect another plugin's temporary displays.)" : ""));
                return true;
            }
            case "restyle" -> {
                if (!(sender instanceof Player player)) {sender.sendMessage("Players only."); return true;}
                int changed=refreshInventory(player);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Restyled " + changed + " authentic artifacts.");
                return true;
            }
            case "status", "artifacts" -> {
                sender.sendMessage(ChatColor.AQUA + "Sovereign Artifacts (7 / 7)");
                sender.sendMessage(ChatColor.AQUA + "I. Echo Scythe of the Deep: " + echoOwnerName()
                        + (echoToken == null ? " (not minted)" : ""));
                sender.sendMessage(ChatColor.AQUA + "II. Witherborn: " + expansion.ownerName(SovereignExpansion.WITHER)
                        + (expansion.minted(SovereignExpansion.WITHER) ? "" : " (not minted)"));
                sender.sendMessage(ChatColor.AQUA + "III. Tidelord: " + expansion.ownerName(SovereignExpansion.TIDE)
                        + (expansion.minted(SovereignExpansion.TIDE) ? "" : " (not minted)"));
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "IV. Dragonfang: " + dragonfang.ownerName()
                        + (dragonfang.minted() ? "" : " (not minted)"));
                sender.sendMessage(ChatColor.GOLD + "V. Paleheart: " + paleheart.ownerName()
                        + (paleheart.minted() ? "" : " (not minted)"));
                sender.sendMessage(ChatColor.AQUA + "VI. Stormvault: " + finalArtifacts.ownerName(FinalArtifacts.STORM)
                        + (finalArtifacts.minted(FinalArtifacts.STORM) ? "" : " (not minted)"));
                sender.sendMessage(ChatColor.GREEN + "VII. Hexcaller: " + finalArtifacts.ownerName(FinalArtifacts.HEX)
                        + (finalArtifacts.minted(FinalArtifacts.HEX) ? "" : " (not minted)"));
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Special Weapon: Sovereign Mace of Gravity: " + ownerName()
                        + (maceToken == null ? " (not minted)" : ""));
                return true;
            }
            case "claim" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                if(args.length < 2) {sender.sendMessage("Usage: /sovereign claim <mace|scythe|witherborn|tidelord|dragonfang|paleheart|stormvault|hexcaller>");return true;}
                String type=args[1].toLowerCase(Locale.ROOT);
                if(type.equals("scythe")) claimEcho(player);
                else if(type.equals("mace")) claim(player);
                else if(expansion.isNewType(type)) expansion.claim(player,type);
                else if(type.equals("dragonfang")) dragonfang.claim(player);
                else if(type.equals("paleheart")) paleheart.claim(player);
                else if(finalArtifacts.isType(type)) finalArtifacts.claim(type,player);
                else sender.sendMessage("Usage: /sovereign claim <mace|scythe|witherborn|tidelord|dragonfang|paleheart|stormvault|hexcaller>");
                return true;
            }
            case "cooldowns" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Special Weapon & Artifact cooldowns:");
                for (String ability : List.of(HORIZON, COLLAPSE, SINGULARITY, ECHO_PULSE, ECHO_SILENCE, ECHO_WRATH)) {
                    long until = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(ability, 0L);
                    String remaining = until <= currentTime() ? "READY" : String.format("%.1fs", (until - currentTime()) / 1000.0);
                    sender.sendMessage(ChatColor.GRAY + ability + ": " + ChatColor.AQUA + remaining);
                }
                for(String line:expansion.cooldownLines(player)) sender.sendMessage(line);
                for(String line:dragonfang.cooldownLines(player)) sender.sendMessage(line);
                for(String line:paleheart.cooldownLines(player)) sender.sendMessage(line);
                for(String line:finalArtifacts.cooldownLines(player)) sender.sendMessage(line);
                return true;
            }
            case "mint", "recover" -> {
                if (!sender.hasPermission("sovereign.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 3 || !List.of("mace","scythe","witherborn","tidelord","dragonfang","paleheart","stormvault","hexcaller").contains(args[1].toLowerCase())) {
                    sender.sendMessage(ChatColor.RED + "Usage: /sovereign " + args[0] + " <mace|scythe|witherborn|tidelord|dragonfang|paleheart|stormvault|hexcaller> <player>");
                    return true;
                }
                Player recipient = Bukkit.getPlayerExact(args[2]);
                if (recipient == null) { sender.sendMessage(ChatColor.RED + "That player must be online."); return true; }
                boolean recovery = args[0].equalsIgnoreCase("recover");
                String type=args[1].toLowerCase(Locale.ROOT);
                if(type.equals("dragonfang")) {
                    dragonfang.mint(recipient,recovery);
                    return true;
                }
                if(type.equals("paleheart")) {
                    paleheart.mint(recipient,recovery);
                    return true;
                }
                if(finalArtifacts.isType(type)) {
                    finalArtifacts.mint(type,recipient,recovery);
                    return true;
                }
                if(expansion.isNewType(type)) {
                    if(expansion.minted(type) && !recovery) {
                        sender.sendMessage(ChatColor.RED + "[Sovereign] Already minted: "+type+". Use recover ONLY if truly lost.");
                        return true;
                    }
                    expansion.mint(recipient,type,recovery);
                    return true;
                }
                if(args[1].equalsIgnoreCase("scythe")) {
                    if(!recovery && echoToken!=null){
                        sender.sendMessage(ChatColor.RED+"Echo Scythe already exists! Use /sovereign recover scythe <player> if lost.");
                        return true;
                    }
                    echoToken=UUID.randomUUID();
                    echoOwner=recipient.getUniqueId();
                    saveArtifactState();
                    handItemTo(recipient,createEchoItem());
                    Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+recipient.getName()+
                            " received Artifact I / VII — Echo Scythe of the Deep!");
                } else {
                    if (!recovery && maceToken != null) {
                        sender.sendMessage(ChatColor.RED + "The Sovereign Mace already exists! Use /sovereign recover mace <player> only if lost.");
                        return true;
                    }
                    maceToken = UUID.randomUUID();
                    maceOwner = recipient.getUniqueId();
                    saveArtifactState();
                    handItemTo(recipient, createMaceItem());
                    Bukkit.broadcastMessage(ChatColor.GOLD + "[Sovereign] " + recipient.getName()
                            + (recovery ? " now owns the recovered " : " has received the first ")
                            + "Sovereign Mace of Gravity (Special Weapon)!");
                }
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /sovereign help.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("help", "status", "artifacts", "claim", "cooldowns", "restyle"));
            if (sender.hasPermission("sovereign.admin")) { opts.add("mint"); opts.add("recover"); opts.add("cleanup"); opts.add("config"); }
            return opts.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cleanup") && sender.hasPermission("sovereign.admin")) return List.of("legacy");
        if (args.length == 2 && List.of("mint", "recover", "claim").contains(args[0].toLowerCase()))
            return List.of("mace","scythe","witherborn","tidelord","dragonfang","paleheart","stormvault","hexcaller").stream().filter(x->x.startsWith(args[1].toLowerCase())).toList();
        if (args.length == 3 && List.of("mint", "recover").contains(args[0].toLowerCase())) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}
