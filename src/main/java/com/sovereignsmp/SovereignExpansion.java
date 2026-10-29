package com.sovereignsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ElderGuardian;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Artifacts II and III of VII. Separate class so existing Mace/Echo ability behavior stays untouched.
 * Shares the existing artifacts.yml via the parent plugin's persisted YAML state.
 */
final class SovereignExpansion implements Listener {
    static final String WITHER = "witherborn";
    static final String TIDE = "tidelord";
    private static final String SKULL_TAG = "sovereign_wither_skull";
    private static final String VISUAL_TAG = "sovereign_expansion_visual";
    private final SovereignSMPPlugin plugin;
    private final NamespacedKey witherId;
    private final NamespacedKey tideId;
    private final NamespacedKey witherTooltip;
    private final NamespacedKey tideTooltip;
    private UUID witherToken, tideToken, witherOwner, tideOwner;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> witherHits = new HashMap<>();
    private final Map<UUID, Integer> soulEnergy = new HashMap<>();
    private final Map<UUID, Long> tideReflectionCooldown = new HashMap<>();
    private final Set<Entity> visuals = new HashSet<>();
    private final Set<WitherSkull> skulls = new HashSet<>();
    private boolean reflecting = false;
    private long passiveClock = 0L;

    SovereignExpansion(SovereignSMPPlugin plugin) {
        this.plugin = plugin;
        witherId = new NamespacedKey(plugin, "witherborn_token");
        tideId = new NamespacedKey(plugin, "tidelord_token");
        witherTooltip = new NamespacedKey(plugin, "wither");
        tideTooltip = new NamespacedKey(plugin, "guardian");
        witherToken = plugin.getArtifactStateUuid("witherborn.token");
        witherOwner = plugin.getArtifactStateUuid("witherborn.owner");
        tideToken = plugin.getArtifactStateUuid("tidelord.token");
        tideOwner = plugin.getArtifactStateUuid("tidelord.owner");
        if (witherToken == null) witherOwner = null;
        if (tideToken == null) tideOwner = null;
    }

    void begin() {
        // Only remove visuals created by THIS class; never touch other plugins' displays.
        for (World world : Bukkit.getWorlds()) {
            for (BlockDisplay display : world.getEntitiesByClass(BlockDisplay.class))
                if (display.getScoreboardTags().contains(VISUAL_TAG)) display.remove();
            for (ElderGuardian guardian : world.getEntitiesByClass(ElderGuardian.class))
                if (guardian.getScoreboardTags().contains(VISUAL_TAG)) guardian.remove();
            for (WitherSkull skull : world.getEntitiesByClass(WitherSkull.class))
                if (skull.getScoreboardTags().contains(SKULL_TAG)) skull.remove();
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickPassive, 10L, 10L);
    }

    void shutdown() {
        for (Entity entity : new HashSet<>(visuals)) if (entity.isValid()) entity.remove();
        for (WitherSkull skull : new HashSet<>(skulls)) if (skull.isValid()) skull.remove();
        visuals.clear();
        skulls.clear();
        cooldowns.clear();
    }

    private void persist() {
        plugin.setArtifactStateUuid("witherborn.token", witherToken);
        plugin.setArtifactStateUuid("witherborn.owner", witherOwner);
        plugin.setArtifactStateUuid("tidelord.token", tideToken);
        plugin.setArtifactStateUuid("tidelord.owner", tideOwner);
        plugin.saveArtifactState();
    }

    boolean authenticAny(ItemStack item) { return authentic(WITHER, item) || authentic(TIDE, item); }
    private boolean authentic(String type, ItemStack item) {
        UUID token = type.equals(WITHER) ? witherToken : tideToken;
        Material material = type.equals(WITHER) ? Material.NETHERITE_AXE : Material.TRIDENT;
        NamespacedKey key = type.equals(WITHER) ? witherId : tideId;
        return token != null && item != null && item.getType() == material && item.hasItemMeta()
                && token.toString().equals(item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING));
    }

    boolean wielding(String type, Player player) {
        return player.isOnline() && (type.equals(WITHER) ? witherOwner : tideOwner) != null
                && player.getUniqueId().equals(type.equals(WITHER) ? witherOwner : tideOwner)
                && authentic(type, player.getInventory().getItemInMainHand());
    }

    private boolean authorized(String type, Player user) {
        UUID owner = type.equals(WITHER) ? witherOwner : tideOwner;
        if (owner == null) user.sendMessage(ChatColor.YELLOW + "[Sovereign] This artifact is unclaimed. Pick it up or use /sovereign claim " + type + ".");
        else if (!owner.equals(user.getUniqueId())) user.sendMessage(ChatColor.RED + "[Sovereign] Only the current artifact holder can use this weapon.");
        return owner != null && owner.equals(user.getUniqueId()) && user.getGameMode() != GameMode.SPECTATOR;
    }

    private Component gradient(String text, int start, int end, boolean bold) {
        Component component = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            double p = text.length() < 2 ? 0 : (double)i/(text.length()-1);
            int r = (int)Math.round(((start >> 16)&255)*(1-p) + ((end >> 16)&255)*p);
            int g = (int)Math.round(((start >> 8)&255)*(1-p) + ((end >> 8)&255)*p);
            int b = (int)Math.round((start&255)*(1-p) + (end&255)*p);
            component = component.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r,g,b))
                    .decoration(TextDecoration.BOLD,bold).decoration(TextDecoration.ITALIC,false));
        }
        return component;
    }
    private Component text(String line, int rgb) {
        return Component.text(line, TextColor.color(rgb)).decoration(TextDecoration.ITALIC,false);
    }
    private void loreAbility(List<Component> lore, String title, String control, int cd, int colorA, int colorB,
                             String... desc) {
        lore.add(Component.empty());
        lore.add(gradient(title,colorA,colorB,true));
        lore.add(gradient(control,colorB,colorA,true).append(text("  •  " + cd + "s",0xFFE5A1)));
        for (String line : desc) lore.add(text(line,0xD0D9DC));
    }
    private void style(ItemMeta meta, String type) {
        List<Component> lore = new ArrayList<>();
        if (type.equals(WITHER)) {
            meta.displayName(gradient("WITHERBORN  ✦  NETHER STAR AXE",0x56F4FF,0x363D46,true));
            lore.add(gradient("✧  LEGENDARY ARTIFACT  II / VII  ✧",0x51E7FE,0x647176,true));
            lore.add(text("Born of soul sand, blackstone and forgotten stars.",0x9FB4BA));
            lore.add(text("Its hunger will never truly fade.",0x9FB4BA));
            lore.add(Component.empty());
            lore.add(gradient("PASSIVE  |  WITHERING TOUCH",0x62F3FF,0x567A88,true));
            lore.add(text("Every 3rd hit inflicts Wither I for 3 seconds.",0xD0D9DC));
            lore.add(text("Hitting withered foes gathers up to 5 Soul Energy.",0xB2EAF1));
            loreAbility(lore,"I  |  WITHER BARRAGE","RIGHT-CLICK",
                    plugin.getConfig().getInt("wither.barrage.cooldown-seconds",22),0x58E8FB,0x778A91,
                    "Fire three miniature Wither skulls; their impacts", "burst with soul flame and corrupt nearby foes.");
            loreAbility(lore,"II  |  SOUL DEVOUR","SNEAK + RIGHT-CLICK",
                    plugin.getConfig().getInt("wither.devour.cooldown-seconds",32),0x58E8FB,0x778A91,
                    "Drain nearby withered enemies to gain Absorption.","Stored Soul Energy empowers the siphon.");
            loreAbility(lore,"III  |  WITHER ASCENSION","F  •  SWAP HANDS",
                    plugin.getConfig().getInt("wither.ascension.cooldown-seconds",60),0x58E8FB,0x778A91,
                    "Summon three spectral skulls that launch curses,", "then unleash a final soul-fire detonation.");
            lore.add(Component.empty());
            lore.add(gradient("✦  THE NETHER STAR CHOOSES ONE WIELDER  ✦",0x82EDFF,0x65757C,true));
            meta.setTooltipStyle(witherTooltip);
        } else {
            meta.displayName(gradient("TIDELORD  ✦  MONUMENT TRIDENT",0x4AF0E5,0xF4C86B,true));
            lore.add(gradient("✧  LEGENDARY ARTIFACT  III / VII  ✧",0x2FDED3,0xF1C96A,true));
            lore.add(text("Before the drowned temples, the ocean was king.",0xA1CCCA));
            lore.add(text("The monument's oldest guardian still obeys.",0xA1CCCA));
            lore.add(Component.empty());
            lore.add(gradient("PASSIVE  |  GUARDIAN'S BLESSING",0x47EDE3,0xEFC96D,true));
            lore.add(text("Water Breathing and swift swimming while wielded.",0xD0D9DC));
            lore.add(text("Guardians and Elder Guardians cannot target you.",0x9BE9DF));
            lore.add(text("Occasionally reflects damage from melee attacks.",0x9BE9DF));
            loreAbility(lore,"I  |  GUARDIAN BEAM","RIGHT-CLICK",
                    plugin.getConfig().getInt("tide.beam.cooldown-seconds",24),0x3BE7DB,0xDBCD74,
                    "Channel a guardian laser from your eyes.","Holding your aim increases repeated beam damage.");
            loreAbility(lore,"II  |  PRISMARINE SPIKES","SNEAK + RIGHT-CLICK",
                    plugin.getConfig().getInt("tide.spikes.cooldown-seconds",30),0x3BE7DB,0xDBCD74,
                    "Raise a rushing line of spectral prismarine", "spikes that damage and launch enemies upward.");
            loreAbility(lore,"III  |  ELDER'S JUDGMENT","F  •  SWAP HANDS",
                    plugin.getConfig().getInt("tide.judgment.cooldown-seconds",60),0x3BE7DB,0xDBCD74,
                    "Summon the Elder Guardian's apparition to mark", "foes with Mining Fatigue and searing lasers.");
            lore.add(Component.empty());
            lore.add(gradient("✦  THE OCEAN MONUMENT HAS CHOSEN ITS WIELDER  ✦",0x64F9E9,0xE4BE6B,true));
            meta.setTooltipStyle(tideTooltip);
        }
        meta.lore(lore);
    }

    private void ensureEnchantments(ItemMeta meta, String type) {
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.removeEnchant(Enchantment.UNBREAKING);
        meta.removeEnchant(Enchantment.LOYALTY);
        if (type.equals(WITHER)) {
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.addEnchant(Enchantment.EFFICIENCY, 6, true);
            meta.addEnchant(Enchantment.SILK_TOUCH, 1, true);
        } else {
            meta.addEnchant(Enchantment.SHARPNESS, 5, true);
            meta.addEnchant(Enchantment.IMPALING, 5, true);
        }
        meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
    }
    ItemStack restyle(ItemStack item) {
        if (!authenticAny(item)) return item;
        ItemStack clone = item.clone();
        ItemMeta meta = clone.getItemMeta();
        String type=authentic(WITHER,item) ? WITHER : TIDE;
        ensureEnchantments(meta,type);
        style(meta,type);
        clone.setItemMeta(meta);
        return clone;
    }

    private ItemStack create(String type) {
        ItemStack stack = new ItemStack(type.equals(WITHER)?Material.NETHERITE_AXE:Material.TRIDENT);
        ItemMeta meta = stack.getItemMeta();
        ensureEnchantments(meta,type);
        style(meta,type);
        meta.getPersistentDataContainer().set(type.equals(WITHER)?witherId:tideId,
                PersistentDataType.STRING,(type.equals(WITHER)?witherToken:tideToken).toString());
        stack.setItemMeta(meta);
        return stack;
    }

    String ownerName(String type) {
        UUID owner = type.equals(WITHER)?witherOwner:tideOwner;
        if (owner == null) return "Unclaimed";
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name == null?owner.toString():name;
    }
    boolean minted(String type) { return (type.equals(WITHER)?witherToken:tideToken)!=null; }
    boolean isNewType(String type) { return type.equals(WITHER)||type.equals(TIDE); }

    void mint(Player recipient, String type, boolean recovery) {
        if (minted(type) && !recovery) {
            recipient.sendMessage(ChatColor.RED+"[Sovereign] "+type+" already exists. Ask an OP to use /sovereign recover if it was lost.");
            return;
        }
        if (type.equals(WITHER)) {
            witherToken=UUID.randomUUID(); witherOwner=recipient.getUniqueId();
        } else {
            tideToken=UUID.randomUUID(); tideOwner=recipient.getUniqueId();
        }
        persist(); // Rotates the token on recovery: all old copies become invalid.
        ItemStack item = create(type);
        for (ItemStack overflow : recipient.getInventory().addItem(item).values())
            recipient.getWorld().dropItemNaturally(recipient.getLocation(),overflow);
        Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+recipient.getName()+
                " now wields Artifact "+(type.equals(WITHER)?"II":"III")+" / VII — "+(type.equals(WITHER)?"Witherborn":"Tidelord")+"!");
    }

    void claim(Player user, String type) {
        if (!minted(type)) {user.sendMessage(ChatColor.RED+"[Sovereign] This artifact has not been minted.");return;}
        if ((type.equals(WITHER)?witherOwner:tideOwner)!=null) {
            user.sendMessage(ChatColor.RED+"[Sovereign] This artifact already has an owner.");return;
        }
        boolean hasItem = false;
        for (ItemStack item : user.getInventory().getContents()) if (authentic(type,item)) {hasItem=true;break;}
        if (!hasItem) {user.sendMessage(ChatColor.RED+"[Sovereign] You must possess the authentic artifact.");return;}
        setOwner(type,user);
    }

    private void setOwner(String type, Player user) {
        if (type.equals(WITHER)) witherOwner=user.getUniqueId(); else tideOwner=user.getUniqueId();
        persist();
        Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+user.getName()+" has claimed "+
                (type.equals(WITHER)?"Witherborn (II / VII)":"Tidelord (III / VII)")+"!");
    }

    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player picker)) return;
        ItemStack stack=event.getItem().getItemStack();
        String type=authentic(WITHER,stack)?WITHER:authentic(TIDE,stack)?TIDE:null;
        if (type==null)return;
        // Pickup events are fired BEFORE inventory transfer. Verify it actually arrived next tick.
        Bukkit.getScheduler().runTask(plugin,()->{
            if (!picker.isOnline())return;
            for (int i=0;i<picker.getInventory().getSize();i++) {
                ItemStack current=picker.getInventory().getItem(i);
                if (!authentic(type,current))continue;
                picker.getInventory().setItem(i,restyle(current));
            }
            if ((type.equals(WITHER)?witherOwner:tideOwner)!=null)return;
            for(ItemStack item:picker.getInventory().getContents()) if(authentic(type,item)) {setOwner(type,picker);return;}
        });
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        Player victim=event.getEntity();
        UUID id=victim.getUniqueId();
        List<String> lost=new ArrayList<>();
        if (id.equals(witherOwner)){witherOwner=null;lost.add(WITHER);}
        if (id.equals(tideOwner)){tideOwner=null;lost.add(TIDE);}
        if (lost.isEmpty())return;
        persist();
        cooldowns.remove(id); soulEnergy.remove(id); witherHits.remove(id);
        for(String type:lost){
            if(event.getKeepInventory()){
                ItemStack found=null;
                for(int slot=0;slot<victim.getInventory().getSize();slot++){
                    ItemStack stack=victim.getInventory().getItem(slot);
                    if(!authentic(type,stack))continue;
                    if(found==null)found=stack.clone();
                    victim.getInventory().setItem(slot,null);
                }
                event.getDrops().removeIf(s->authentic(type,s));
                if(found!=null){
                    final ItemStack drop=found;
                    final Location at=victim.getLocation().clone();
                    Bukkit.getScheduler().runTask(plugin,()->at.getWorld().dropItemNaturally(at,drop));
                }
            }
            Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+victim.getName()+" has fallen! "+
                    (type.equals(WITHER)?"Witherborn":"Tidelord")+" is claimable again.");
        }
    }

    @EventHandler(priority=EventPriority.NORMAL)
    public void onClick(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND)return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        ItemStack stack=event.getItem();
        String type=authentic(WITHER,stack)?WITHER:authentic(TIDE,stack)?TIDE:null;
        if(type==null)return;
        event.setCancelled(true); // Prevent thrown Tridents, offhand duplicates and block interaction.
        Player player=event.getPlayer();
        if(!authorized(type,player))return;
        if(type.equals(WITHER)){
            if(player.isSneaking())soulDevour(player);else witherBarrage(player);
        } else {
            if(player.isSneaking())prismarineSpikes(player);else guardianBeam(player);
        }
    }

    @EventHandler(priority=EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player=event.getPlayer();
        ItemStack stack=player.getInventory().getItemInMainHand();
        String type=authentic(WITHER,stack)?WITHER:authentic(TIDE,stack)?TIDE:null;
        if(type==null)return;
        event.setCancelled(true);
        if(!authorized(type,player))return;
        if(type.equals(WITHER))witherAscension(player);else eldersJudgment(player);
    }

    // Natural Guardians and Elder Guardians never attack a true Tidelord wielder.
    // This is intentionally only active while the trident is in the main hand.
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void protectFromGuardianTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Guardian guardian)) return;
        if (!(event.getTarget() instanceof Player player) || !wielding(TIDE,player)) return;
        event.setCancelled(true);
        guardian.setTarget(null);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void protectFromGuardianDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player) || !wielding(TIDE,player)) return;
        if (event.getDamager() instanceof Guardian) {event.setCancelled(true);return;}
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Guardian) event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)
    public void onHit(EntityDamageByEntityEvent event) {
        // Custom impacts below apply damage exactly once; suppress vanilla Wither skull hits.
        if(event.getDamager() instanceof WitherSkull skull && skull.getScoreboardTags().contains(SKULL_TAG)) {
            event.setCancelled(true);
            return;
        }
        if(reflecting)return;
        if(event.getDamager() instanceof Player attacker){
            ItemStack weapon=attacker.getInventory().getItemInMainHand();
            String type=authentic(WITHER,weapon)?WITHER:authentic(TIDE,weapon)?TIDE:null;
            if(type!=null && !authorizedSilently(type,attacker)) {event.setCancelled(true);return;}
            if(type!=null && type.equals(TIDE) && plugin.usableTarget(attacker,event.getEntity()) && event.getFinalDamage()>0) {
                // Sharpness V is a non-vanilla trident enchant. Reproduce its +3 HP melee bonus.
                event.setDamage(event.getDamage()+3.0);
            }
            if(type!=null && type.equals(WITHER) && plugin.usableTarget(attacker,event.getEntity()) && event.getFinalDamage()>0){
                LivingEntity target=(LivingEntity)event.getEntity();
                UUID id=attacker.getUniqueId();
                int hits=witherHits.getOrDefault(id,0)+1;
                witherHits.put(id,hits%3);
                if(target.hasPotionEffect(PotionEffectType.WITHER))
                    soulEnergy.put(id,Math.min(5,soulEnergy.getOrDefault(id,0)+1));
                if(hits%3==0){
                    target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,plugin.getConfig().getInt("wither.passive.wither-ticks",60),0,true,true,true));
                    target.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,target.getLocation().add(0,1,0),16,.35,.5,.35,.03);
                }
            }
        }
        if(event.getEntity() instanceof Player defender && wielding(TIDE,defender)
                && event.getDamager() instanceof LivingEntity attacker && plugin.usableTarget(defender,attacker)
                && event.getFinalDamage()>0){
            double chance=plugin.getConfig().getDouble("tide.passive.reflect-chance-percent",18)/100.0;
            long now=System.currentTimeMillis();
            if(ThreadLocalRandom.current().nextDouble()<chance && tideReflectionCooldown.getOrDefault(defender.getUniqueId(),0L)<now){
                tideReflectionCooldown.put(defender.getUniqueId(),now+2000L);
                defender.getWorld().spawnParticle(Particle.DUST,defender.getLocation().add(0,1,0),18,.5,.4,.5,0,
                        new Particle.DustOptions(Color.fromRGB(60,235,214),1.15f));
                reflecting=true;
                try{plugin.dealSovereignAbilityDamage(attacker,defender,
                        plugin.getConfig().getDouble("tide.passive.reflect-damage-health-points",2.0));}
                finally{reflecting=false;}
                defender.playSound(defender.getLocation(),Sound.ENTITY_GUARDIAN_HURT,.6f,1.3f);
            }
        }
    }

    private boolean authorizedSilently(String type,Player player){
        UUID owner=type.equals(WITHER)?witherOwner:tideOwner;
        return owner!=null && owner.equals(player.getUniqueId());
    }

    private boolean ready(Player user,String ability,String path,int fallback){
        Map<String,Long> userCooldowns=cooldowns.computeIfAbsent(user.getUniqueId(),x->new HashMap<>());
        long now=System.currentTimeMillis(),until=userCooldowns.getOrDefault(ability,0L);
        if(until>now){user.sendActionBar(text(ability+": "+String.format(Locale.US,"%.1fs",(until-now)/1000.0),0xF0B2B2));
            user.playSound(user.getLocation(),Sound.BLOCK_NOTE_BLOCK_BASS,.5f,.8f);return false;}
        userCooldowns.put(ability,now+1000L*Math.max(0,plugin.getConfig().getInt(path,fallback)));
        return true;
    }

    Component hud(Player player){
        String type=wielding(WITHER,player)?WITHER:wielding(TIDE,player)?TIDE:null;
        if(type==null)return null;
        String prefix=type.equals(WITHER)?"wither.":"tide.";
        String[] keys=type.equals(WITHER)?new String[]{"barrage","devour","ascension"}:new String[]{"beam","spikes","judgment"};
        String[] labels=type.equals(WITHER)?new String[]{"WB","SD","WA"}:new String[]{"GB","PS","EJ"};
        Component result=Component.empty();
        for(int i=0;i<3;i++){
            if(i>0)result=result.append(text("  │  ",0x777D8D));
            long until=cooldowns.getOrDefault(player.getUniqueId(),Map.of()).getOrDefault(prefix+keys[i],0L);
            boolean up=until<=System.currentTimeMillis();
            String time=up?"READY":(long)Math.ceil((until-System.currentTimeMillis())/1000.0)+"s";
            result=result.append(text("✦ "+labels[i]+" ",type.equals(WITHER)?0x6DE5ED:0x54E2CF))
                    .append(text(time,up?0x65F2AC:0xF0D28C));
        }
        if(type.equals(WITHER))result=result.append(text("  │  SOUL "+soulEnergy.getOrDefault(player.getUniqueId(),0)+"/5",0x8DDBEE));
        return result;
    }

    List<String> cooldownLines(Player user){
        List<String> lines=new ArrayList<>();
        for(String key:List.of("wither.barrage","wither.devour","wither.ascension","tide.beam","tide.spikes","tide.judgment")){
            long remain=cooldowns.getOrDefault(user.getUniqueId(),Map.of()).getOrDefault(key,0L)-System.currentTimeMillis();
            lines.add(ChatColor.GRAY+key+": "+ChatColor.AQUA+(remain<=0?"READY":String.format(Locale.US,"%.1fs",remain/1000.0)));
        }
        return lines;
    }

    private void tickPassive(){
        passiveClock+=10;
        for(Player player:Bukkit.getOnlinePlayers()){
            if(wielding(WITHER,player)){
                player.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,player.getLocation().add(0,1,0),3,.38,.55,.38,.012);
                if(passiveClock%30==0)player.getWorld().spawnParticle(Particle.SMOKE,player.getLocation().add(0,.8,0),5,.4,.5,.4,.008);
            }else if(wielding(TIDE,player)){
                // Elder Guardian curse is an ambient aura rather than a targeted attack.
                // Remove the curse while close to an Elder Guardian and wielding Tidelord.
                for(Entity nearby:player.getWorld().getNearbyEntities(player.getLocation(),48,32,48)) {
                    if (!(nearby instanceof ElderGuardian guardian)) continue;
                    if (guardian.isDead() || !guardian.isValid()) continue;
                    player.removePotionEffect(PotionEffectType.MINING_FATIGUE);
                    break;
                }
                // Stop Guardians from persisting a target acquired before the player equipped Tidelord.
                for(Entity nearby:player.getWorld().getNearbyEntities(player.getLocation(),24,18,24)) {
                    if (nearby instanceof Guardian guardian && player.equals(guardian.getTarget())) guardian.setTarget(null);
                }
                player.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING,45,0,true,false,false));
                if(player.isInWater())player.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE,35,0,true,false,false));
                player.getWorld().spawnParticle(Particle.BUBBLE,player.getLocation().add(0,.9,0),3,.4,.5,.4,.02);
            }
        }
    }

    private boolean validTarget(Player caster,Entity candidate){return plugin.usableTarget(caster,candidate);}
    private List<LivingEntity> nearby(Player caster,Location center,double radius){
        List<LivingEntity> result=new ArrayList<>();
        for(Entity entity:center.getWorld().getNearbyEntities(center,radius,radius,radius))
            if(validTarget(caster,entity) && entity.getLocation().distanceSquared(center)<=radius*radius)result.add((LivingEntity)entity);
        return result;
    }
    private void dust(Location at,int count,Color color,double spread){
        at.getWorld().spawnParticle(Particle.DUST,at,count,spread,spread,spread,0,new Particle.DustOptions(color,1.2f));
    }
    private void damage(LivingEntity target,Player caster,double amount){plugin.dealSovereignAbilityDamage(target,caster,amount);}

    // WITHERBORN I — real, non-griefing projectiles; impact handled below.
    private void launchSkull(Player user,Vector direction){
        if(!user.isOnline() || !wielding(WITHER,user))return;
        Location eye=user.getEyeLocation().add(direction.clone().multiply(1.1));
        WitherSkull skull=user.getWorld().spawn(eye,WitherSkull.class);
        skull.setShooter(user);
        skull.setIsIncendiary(false);
        skull.setYield(0.0f);
        skull.setVelocity(direction.clone().normalize().multiply(1.15));
        skull.addScoreboardTag(SKULL_TAG);
        skull.setPersistent(false);
        skulls.add(skull);
        user.getWorld().playSound(user.getLocation(),Sound.ENTITY_WITHER_SHOOT,.55f,1.3f);
        Bukkit.getScheduler().runTaskLater(plugin,()->{if(skull.isValid())skull.remove();skulls.remove(skull);},45L);
    }
    private void witherBarrage(Player caster){
        if(!ready(caster,"wither.barrage","wither.barrage.cooldown-seconds",22))return;
        for(int n=0;n<3;n++){
            int index=n;
            Bukkit.getScheduler().runTaskLater(plugin,()->{
                if(!wielding(WITHER,caster))return;
                Vector dir=caster.getEyeLocation().getDirection().normalize();
                if(index==0)dir.rotateAroundY(-.09);
                else if(index==2)dir.rotateAroundY(.09);
                launchSkull(caster,dir);
            },n*5L);
        }
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onSkullHit(ProjectileHitEvent event){
        if(!(event.getEntity() instanceof WitherSkull skull) || !skull.getScoreboardTags().contains(SKULL_TAG))return;
        Location hit=skull.getLocation();
        if(!(skull.getShooter() instanceof Player caster)){skull.remove();return;}
        hit.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,hit,38,.7,.7,.7,.07);
        hit.getWorld().spawnParticle(Particle.SMOKE,hit,20,.6,.6,.6,.035);
        hit.getWorld().playSound(hit,Sound.ENTITY_GENERIC_EXPLODE,.5f,1.5f);
        double radius=plugin.getConfig().getDouble("wither.barrage.radius-blocks",2.3);
        double amount=plugin.getConfig().getDouble("wither.barrage.damage-health-points",3.0);
        for(LivingEntity target:nearby(caster,hit,radius)){
            damage(target,caster,amount);
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,60,0));
        }
        skull.remove(); skulls.remove(skull);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onSkullExplode(EntityExplodeEvent event){
        if(event.getEntity()!=null && event.getEntity().getScoreboardTags().contains(SKULL_TAG))event.setCancelled(true);
    }

    // WITHERBORN II — consume collected energy to enhance a short-range siphon.
    private void soulDevour(Player caster){
        if(!ready(caster,"wither.devour","wither.devour.cooldown-seconds",32))return;
        int stored=soulEnergy.getOrDefault(caster.getUniqueId(),0);
        double radius=plugin.getConfig().getDouble("wither.devour.radius-blocks",8.0);
        double amount=plugin.getConfig().getDouble("wither.devour.damage-health-points",3.0)+stored*.35;
        int drained=0;
        for(LivingEntity target:nearby(caster,caster.getLocation(),radius)){
            if(!target.hasPotionEffect(PotionEffectType.WITHER))continue;
            damage(target,caster,amount);
            target.getWorld().spawnParticle(Particle.SOUL,target.getLocation().add(0,.8,0),18,.35,.5,.35,.04);
            drained++;
        }
        if(drained>0){
            caster.setAbsorptionAmount(Math.min(8.0,caster.getAbsorptionAmount()+Math.min(5,drained)*1.5+stored*.3));
            caster.getWorld().playSound(caster.getLocation(),Sound.ENTITY_WITHER_AMBIENT,.65f,1.55f);
        } else caster.sendMessage(ChatColor.GRAY+"[Sovereign] Soul Devour found no nearby withered targets.");
        soulEnergy.put(caster.getUniqueId(),0);
        dust(caster.getLocation().add(0,1,0),35,Color.fromRGB(55,206,235),1.1);
    }

    // WITHERBORN III — visible orbiting wither-skeleton heads represented by temporary block displays.
    private void witherAscension(Player caster){
        if(!ready(caster,"wither.ascension","wither.ascension.cooldown-seconds",60))return;
        World world=caster.getWorld();
        List<BlockDisplay> heads=new ArrayList<>();
        for(int i=0;i<3;i++){
            BlockDisplay display=world.spawn(caster.getLocation().add(0,2,0),BlockDisplay.class);
            display.setBlock(Material.WITHER_SKELETON_SKULL.createBlockData());
            display.setPersistent(false);
            display.addScoreboardTag(VISUAL_TAG);
            heads.add(display); visuals.add(display);
        }
        world.playSound(caster.getLocation(),Sound.ENTITY_WITHER_SPAWN,0.9f,1.5f);
        new BukkitRunnable(){int tick=0;
            @Override public void run(){
                if(!caster.isOnline() || !wielding(WITHER,caster) || tick>140){finish();return;}
                for(int i=0;i<heads.size();i++){
                    BlockDisplay head=heads.get(i);
                    if(!head.isValid())continue;
                    double angle=tick*.11+i*(Math.PI*2/3);
                    Location at=caster.getLocation().add(Math.cos(angle)*1.35,2.2+Math.sin(tick*.08)*.2,Math.sin(angle)*1.35);
                    head.teleport(at);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME,at,3,.13,.13,.13,.005);
                }
                if(tick%20==0){
                    List<LivingEntity> candidates=nearby(caster,caster.getLocation(),15);
                    if(!candidates.isEmpty()){
                        LivingEntity target=candidates.get((tick/20)%candidates.size());
                        Location start=caster.getEyeLocation().add(0,1.1,0);
                        Vector towards=target.getLocation().add(0,1,0).toVector().subtract(start.toVector());
                        if(towards.lengthSquared()>.001){
                            WitherSkull skull=world.spawn(start,WitherSkull.class);
                            skull.setShooter(caster);skull.setIsIncendiary(false);skull.setYield(0f);
                            skull.setVelocity(towards.normalize().multiply(.9));
                            skull.addScoreboardTag(SKULL_TAG);skull.setPersistent(false);skulls.add(skull);
                            Bukkit.getScheduler().runTaskLater(plugin,()->{if(skull.isValid())skull.remove();skulls.remove(skull);},40L);
                        }
                    }
                }
                tick+=5;
            }
            private void finish(){
                for(BlockDisplay head:heads){if(head.isValid())head.remove();visuals.remove(head);}
                if(caster.isOnline() && wielding(WITHER,caster)){
                    Location at=caster.getLocation().add(0,1,0);
                    world.spawnParticle(Particle.SOUL_FIRE_FLAME,at,80,2,1,2,.09);
                    for(LivingEntity target:nearby(caster,caster.getLocation(),5.0))
                        damage(target,caster,plugin.getConfig().getDouble("wither.ascension.final-damage-health-points",4.0));
                    world.playSound(at,Sound.ENTITY_WITHER_BREAK_BLOCK,0.8f,0.8f);
                }
                cancel();
            }
        }.runTaskTimer(plugin,0L,5L);
    }

    // TIDELORD I — an aim-dependent beam with discrete, bounded damage pulses.
    private LivingEntity beamTarget(Player caster,double range){
        Location eye=caster.getEyeLocation();
        Vector direction=eye.getDirection().normalize();
        LivingEntity chosen=null;
        double nearest=range;
        for(Entity entity:caster.getWorld().getNearbyEntities(eye,range,range,range)){
            if(!validTarget(caster,entity))continue;
            LivingEntity living=(LivingEntity)entity;
            Vector delta=living.getLocation().add(0,living.getHeight()*.5,0).toVector().subtract(eye.toVector());
            double forward=delta.dot(direction);
            if(forward<0 || forward>nearest)continue;
            double offAxis=delta.clone().subtract(direction.clone().multiply(forward)).length();
            if(offAxis>Math.max(.8,living.getWidth()*.6))continue;
            if(!caster.hasLineOfSight(living))continue;
            chosen=living;nearest=forward;
        }
        return chosen;
    }
    private void ray(Location start,Location end,Color color){
        Vector vec=end.toVector().subtract(start.toVector());
        double dist=vec.length();
        if(dist<.01)return;
        Vector step=vec.normalize().multiply(.42);
        Location point=start.clone();
        for(double v=0;v<Math.min(30,dist);v+=.42){
            point.getWorld().spawnParticle(Particle.DUST,point,1,0,0,0,0,new Particle.DustOptions(color,1.05f));
            point.add(step);
        }
    }
    private void guardianBeam(Player caster){
        if(!ready(caster,"tide.beam","tide.beam.cooldown-seconds",24))return;
        int duration=Math.max(10,Math.min(100,plugin.getConfig().getInt("tide.beam.duration-ticks",40)));
        double reach=plugin.getConfig().getDouble("tide.beam.range-blocks",22);
        caster.getWorld().playSound(caster.getLocation(),Sound.ENTITY_GUARDIAN_ATTACK,.85f,1.0f);
        new BukkitRunnable(){int tick=0; UUID last=null;int sustained=0;
            @Override public void run(){
                if(!caster.isOnline() || !wielding(TIDE,caster) || tick>=duration){cancel();return;}
                LivingEntity target=beamTarget(caster,reach);
                Location start=caster.getEyeLocation();
                Location end=target!=null?target.getLocation().add(0,target.getHeight()*.5,0):start.clone().add(start.getDirection().multiply(reach));
                ray(start,end,Color.fromRGB(62,232,216));
                if(target!=null){
                    if(!target.getUniqueId().equals(last)){last=target.getUniqueId();sustained=0;}
                    if(tick%10==0){
                        double base=plugin.getConfig().getDouble("tide.beam.damage-per-hit-health-points",1.5);
                        damage(target,caster,Math.min(base+1.5,base+sustained*.35));
                        sustained++;
                        target.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,end,12,.25,.3,.25,.05);
                    }
                }else{last=null;sustained=0;}
                tick+=5;
            }
        }.runTaskTimer(plugin,0L,5L);
    }

    // TIDELORD II — display-only prismarine; never changes real blocks or terrain.
    private void prismarineSpikes(Player caster){
        if(!ready(caster,"tide.spikes","tide.spikes.cooldown-seconds",30))return;
        Location base=caster.getLocation().clone();
        Vector direction=base.getDirection().setY(0);
        if(direction.lengthSquared()<.01)direction=new Vector(0,0,1);
        direction.normalize();
        final Vector forward=direction.clone();
        Set<UUID> hit=new HashSet<>();
        double range=plugin.getConfig().getDouble("tide.spikes.range-blocks",10.0);
        int steps=Math.max(1,(int)Math.ceil(range));
        new BukkitRunnable(){int step=0;
            @Override public void run(){
                if(!caster.isOnline() || step>=steps){cancel();return;}
                Location at=base.clone().add(forward.clone().multiply(step+1)).add(0,.15,0);
                BlockDisplay display=at.getWorld().spawn(at,BlockDisplay.class);
                display.setBlock((step%2==0?Material.PRISMARINE:Material.DARK_PRISMARINE).createBlockData());
                display.setPersistent(false);display.addScoreboardTag(VISUAL_TAG);visuals.add(display);
                at.getWorld().spawnParticle(Particle.SPLASH,at,16,.6,.4,.6,.08);
                dust(at,14,Color.fromRGB(62,231,204),.65);
                at.getWorld().playSound(at,Sound.BLOCK_CONDUIT_ACTIVATE,.35f,1.35f);
                for(LivingEntity target:nearby(caster,at,2.0)){
                    if(!hit.add(target.getUniqueId()))continue;
                    damage(target,caster,plugin.getConfig().getDouble("tide.spikes.damage-health-points",4.0));
                    target.setVelocity(target.getVelocity().add(new Vector(0,.55,0)));
                }
                Bukkit.getScheduler().runTaskLater(plugin,()->{if(display.isValid())display.remove();visuals.remove(display);},22L);
                step++;
            }
        }.runTaskTimer(plugin,0L,3L);
    }

    // TIDELORD III — a temporary harmless elder guardian apparition and telegraphed laser strikes.
    private void eldersJudgment(Player caster){
        if(!ready(caster,"tide.judgment","tide.judgment.cooldown-seconds",60))return;
        Location center=caster.getLocation().clone().add(0,4.5,0);
        World world=caster.getWorld();
        ElderGuardian guardian=world.spawn(center,ElderGuardian.class);
        guardian.setAI(false);guardian.setSilent(true);guardian.setInvulnerable(true);
        guardian.setGravity(false);guardian.setCollidable(false);guardian.setPersistent(false);
        guardian.addScoreboardTag(VISUAL_TAG);visuals.add(guardian);
        world.playSound(center,Sound.ENTITY_ELDER_GUARDIAN_CURSE,1f,1f);
        new BukkitRunnable(){int tick=0;
            @Override public void run(){
                if(!caster.isOnline() || !guardian.isValid() || tick>=100){finish();return;}
                Location from=guardian.getLocation().add(0,guardian.getHeight()*.5,0);
                dust(from,12,Color.fromRGB(49,215,200),1.3);
                if(tick%20==0){
                    List<LivingEntity> enemies=nearby(caster,guardian.getLocation(),15);
                    int count=0;
                    for(LivingEntity enemy:enemies){
                        if(count++>=3)break;
                        Location to=enemy.getLocation().add(0,enemy.getHeight()*.5,0);
                        ray(from,to,Color.fromRGB(78,240,222));
                        enemy.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE,100,0,true,true,true));
                        damage(enemy,caster,plugin.getConfig().getDouble("tide.judgment.damage-per-strike-health-points",3.0));
                        enemy.getWorld().spawnParticle(Particle.NAUTILUS,to,16,.45,.4,.45,.06);
                    }
                    world.playSound(from,Sound.ENTITY_GUARDIAN_ATTACK,.8f,.78f);
                }
                tick+=10;
            }
            private void finish(){if(guardian.isValid())guardian.remove();visuals.remove(guardian);cancel();}
        }.runTaskTimer(plugin,0L,10L);
    }
}
