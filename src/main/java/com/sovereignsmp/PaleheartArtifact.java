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
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
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

/** Artifact V / VII: Pale Garden / Creaking, built as a separate, persistent, single-owner artifact. */
final class PaleheartArtifact implements Listener {
    static final String TYPE = "paleheart";
    private static final String DECOY_TAG = "sovereign_paleheart_decoy_v1";
    private final SovereignSMPPlugin plugin;
    private final NamespacedKey tokenKey;
    private final NamespacedKey tooltipKey;
    private UUID token;
    private UUID owner;
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Set<UUID> activeDecoys = new HashSet<>();
    private final Particle.DustOptions amber = new Particle.DustOptions(Color.fromRGB(241, 157, 64), 1.3f);
    private final Particle.DustOptions pale = new Particle.DustOptions(Color.fromRGB(199, 217, 176), 1.15f);

    PaleheartArtifact(SovereignSMPPlugin plugin) {
        this.plugin = plugin;
        this.tokenKey = new NamespacedKey(plugin, "paleheart_token");
        this.tooltipKey = new NamespacedKey(plugin, "paleheart");
        this.token = plugin.getArtifactStateUuid("paleheart.token");
        this.owner = plugin.getArtifactStateUuid("paleheart.owner");
        if (token == null) owner = null;
    }

    void begin() {
        // Clean up decoys left by a server crash/reload; never touch normal Creakings.
        for (World world : Bukkit.getWorlds())
            for (Entity entity : world.getEntities())
                if (entity.getScoreboardTags().contains(DECOY_TAG)) entity.remove();
        Bukkit.getScheduler().runTaskTimer(plugin, this::passiveTick, 10L, 10L);
    }

    void shutdown() {
        for (World world : Bukkit.getWorlds())
            for (Entity entity : world.getEntities())
                if (entity.getScoreboardTags().contains(DECOY_TAG)) entity.remove();
        activeDecoys.clear();
    }

    private void persist() {
        plugin.setArtifactStateUuid("paleheart.token", token);
        plugin.setArtifactStateUuid("paleheart.owner", owner);
        plugin.saveArtifactState();
    }

    boolean minted() { return token != null; }
    String ownerName() {
        if (owner == null) return "Unclaimed";
        String name = Bukkit.getOfflinePlayer(owner).getName();
        return name == null ? owner.toString() : name;
    }
    boolean authentic(ItemStack item) {
        return token != null && item != null && item.getType() == Material.NETHERITE_HOE
                && item.hasItemMeta() && token.toString().equals(item.getItemMeta()
                        .getPersistentDataContainer().get(tokenKey, PersistentDataType.STRING));
    }
    private boolean wielding(Player player) {
        return owner != null && owner.equals(player.getUniqueId()) && player.isOnline()
                && player.getGameMode() != GameMode.SPECTATOR
                && authentic(player.getInventory().getItemInMainHand());
    }
    private boolean authorized(Player player) {
        if (owner == null) player.sendMessage(ChatColor.YELLOW + "[Sovereign] Paleheart is unclaimed. Pick it up or use /sovereign claim paleheart.");
        else if (!owner.equals(player.getUniqueId())) player.sendMessage(ChatColor.RED + "[Sovereign] Only Paleheart's owner may wield it.");
        return owner != null && owner.equals(player.getUniqueId()) && player.getGameMode() != GameMode.SPECTATOR;
    }

    private Component gradient(String str, int start, int end, boolean bold) {
        Component component = Component.empty();
        for (int i=0; i<str.length(); i++) {
            double a = str.length() < 2 ? 0 : i/(double)(str.length()-1);
            int r=(int)(((start>>16)&255)*(1-a)+((end>>16)&255)*a);
            int g=(int)(((start>>8)&255)*(1-a)+((end>>8)&255)*a);
            int b=(int)((start&255)*(1-a)+(end&255)*a);
            component=component.append(Component.text(String.valueOf(str.charAt(i)),TextColor.color(r,g,b))
                    .decoration(TextDecoration.BOLD,bold).decoration(TextDecoration.ITALIC,false));
        }
        return component;
    }
    private Component text(String str,int rgb) {
        return Component.text(str,TextColor.color(rgb)).decoration(TextDecoration.ITALIC,false);
    }
    private void ability(List<Component> lore,String title,String control,String path,int fallback,String desc1,String desc2){
        lore.add(Component.empty());
        lore.add(gradient(title,0xE7DDBB,0xDC9141,true));
        lore.add(gradient(control,0xF8C66D,0xA9C69A,true)
                .append(text("  •  "+plugin.getConfig().getInt(path,fallback)+"s",0xDADEC5)));
        lore.add(text(desc1,0xD9DECE));
        lore.add(text(desc2,0xC9D4BF));
    }
    private void style(ItemMeta meta) {
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        // Hoe is the physical sickle; sharpness and bonus melee damage make it viable in PvP.
        meta.addEnchant(Enchantment.SHARPNESS,5,true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        meta.displayName(gradient("PALEHEART  ✦  CREAKING SICKLE",0xF2E6C8,0xDD8738,true));
        List<Component> lore = new ArrayList<>();
        lore.add(gradient("✧  LEGENDARY ARTIFACT  V / VII  ✧",0xE8D7B4,0xD9A05B,true));
        lore.add(text("The Pale Garden remembers every unseen step.",0xC3CFB7));
        lore.add(text("Watch the shadows. Never blink.",0xC3CFB7));
        lore.add(Component.empty());
        lore.add(gradient("PASSIVE  |  UNSEEN TERROR",0xF0C777,0xA0C7A7,true));
        lore.add(text("Move faster when nearby players look away.",0xD9DECE));
        lore.add(text("The Creaking strikes where eyes cannot follow.",0xBAC5B4));
        ability(lore,"I  |  GRASPING ROOTS","RIGHT-CLICK","paleheart.roots.cooldown-seconds",22,
                "Pale-oak roots erupt beneath a target,","damaging and briefly pinning them in place.");
        ability(lore,"II  |  UNSEEN STEP","SNEAK + RIGHT-CLICK","paleheart.step.cooldown-seconds",30,
                "Slip to a safe spot and leave a Creaking","decoy behind to distract pursuers.");
        ability(lore,"III  |  HAUNTED GROVE","F  •  SWAP HANDS","paleheart.grove.cooldown-seconds",65,
                "Raise a 15-block Creaking ring with Blindness;","touching a Creaking damages intruders.");
        lore.add(Component.empty());
        lore.add(gradient("✦  ONLY ONE MAY RULE THE PALE GARDEN  ✦",0xB8D3AB,0xE9AD69,true));
        meta.lore(lore);
        meta.setTooltipStyle(tooltipKey);
    }
    ItemStack restyle(ItemStack source) {
        if (!authentic(source)) return source;
        ItemStack copy = source.clone();
        ItemMeta meta = copy.getItemMeta();
        style(meta);
        copy.setItemMeta(meta);
        return copy;
    }
    private ItemStack create() {
        ItemStack item = new ItemStack(Material.NETHERITE_HOE);
        ItemMeta meta = item.getItemMeta();
        style(meta);
        meta.getPersistentDataContainer().set(tokenKey, PersistentDataType.STRING, token.toString());
        item.setItemMeta(meta);
        return item;
    }
    void mint(Player recipient,boolean recovery) {
        if (minted() && !recovery) {recipient.sendMessage(ChatColor.RED+"[Sovereign] Paleheart already exists. Use recover only if lost.");return;}
        token=UUID.randomUUID();owner=recipient.getUniqueId();persist();
        for(ItemStack overflow:recipient.getInventory().addItem(create()).values())
            recipient.getWorld().dropItemNaturally(recipient.getLocation(),overflow);
        Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+recipient.getName()+" now wields Artifact V / VII — Paleheart!");
    }
    void claim(Player player) {
        if (!minted()) {player.sendMessage(ChatColor.RED+"[Sovereign] Paleheart has not been minted.");return;}
        if (owner != null) {player.sendMessage(ChatColor.RED+"[Sovereign] Paleheart already has a holder.");return;}
        for(ItemStack item:player.getInventory().getContents()) if(authentic(item)) {
            owner=player.getUniqueId();persist();
            Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+player.getName()+" claimed Paleheart (V / VII)!");return;
        }
        player.sendMessage(ChatColor.RED+"[Sovereign] The genuine Paleheart must be in your inventory to claim it.");
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void pickup(EntityPickupItemEvent event) {
        if(!(event.getEntity() instanceof Player player)||!authentic(event.getItem().getItemStack()))return;
        Bukkit.getScheduler().runTask(plugin,()->{
            if(!player.isOnline())return;
            boolean found=false;
            for(int i=0;i<player.getInventory().getSize();i++){
                ItemStack item=player.getInventory().getItem(i);
                if(authentic(item)){found=true;player.getInventory().setItem(i,restyle(item));}
            }
            if(owner==null&&found){owner=player.getUniqueId();persist();
                Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+player.getName()+" claimed Paleheart (V / VII)!");}
        });
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void death(PlayerDeathEvent event) {
        Player dead=event.getEntity();
        if(!dead.getUniqueId().equals(owner))return;
        owner=null;persist();cooldowns.remove(dead.getUniqueId());
        if(event.getKeepInventory()){
            ItemStack drop=null;
            for(int i=0;i<dead.getInventory().getSize();i++){
                ItemStack item=dead.getInventory().getItem(i);
                if(!authentic(item))continue;
                if(drop==null)drop=item.clone();
                dead.getInventory().setItem(i,null);
            }
            event.getDrops().removeIf(this::authentic);
            if(drop!=null){ItemStack item=drop;Location at=dead.getLocation().clone();
                Bukkit.getScheduler().runTask(plugin,()->at.getWorld().dropItemNaturally(at,item));}
        }
        Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] Paleheart is unclaimed after "+dead.getName()+"'s defeat!");
    }

    private boolean ready(Player player,String key,String config,int seconds) {
        long now=System.currentTimeMillis();
        Map<String,Long> all=cooldowns.computeIfAbsent(player.getUniqueId(),k->new HashMap<>());
        long end=all.getOrDefault(key,0L);
        if(end>now){player.sendActionBar(text(key+"  "+String.format(Locale.US,"%.1fs",(end-now)/1000.0),0xF7C07A));return false;}
        all.put(key,now+1000L*Math.max(0,plugin.getConfig().getInt(config,seconds)));
        return true;
    }
    private void refund(Player player,String key){
        Map<String,Long> userCooldowns=cooldowns.get(player.getUniqueId());
        if(userCooldowns!=null)userCooldowns.remove(key);
    }
    Component hud(Player player) {
        if(!wielding(player))return null;
        Component bar=Component.empty();
        String[] names={"paleheart.roots","paleheart.step","paleheart.grove"};
        String[] labels={"GR","US","HG"};
        for(int i=0;i<names.length;i++){
            if(i>0)bar=bar.append(text("  │  ",0x8A8C75));
            long remaining=cooldowns.getOrDefault(player.getUniqueId(),Map.of()).getOrDefault(names[i],0L)-System.currentTimeMillis();
            bar=bar.append(text(labels[i]+" "+(remaining<=0?"READY":String.format(Locale.US,"%.1fs",remaining/1000.0)),remaining<=0?0xF4D4A3:0x8E9C87));
        }
        return bar;
    }
    List<String> cooldownLines(Player player) {
        List<String> lines=new ArrayList<>();
        for(String name:List.of("paleheart.roots","paleheart.step","paleheart.grove")){
            long remaining=cooldowns.getOrDefault(player.getUniqueId(),Map.of()).getOrDefault(name,0L)-System.currentTimeMillis();
            lines.add(ChatColor.GRAY+name+": "+ChatColor.GOLD+(remaining<=0?"READY":String.format(Locale.US,"%.1fs",remaining/1000.0)));
        }
        return lines;
    }

    // Watchfulness is evaluated from the TARGET'S eye direction, not the caster's.
    private boolean lookingAt(Player observer, Location where) {
        if(!observer.getWorld().equals(where.getWorld()))return false;
        Vector offset=where.clone().add(0,.9,0).toVector().subtract(observer.getEyeLocation().toVector());
        if(offset.lengthSquared()<0.01)return true;
        return observer.getEyeLocation().getDirection().normalize().dot(offset.normalize())>0.73;
    }
    private boolean lookingAtDecoy(Player observer, Location where) { return lookingAt(observer,where); }
    private void passiveTick() {
        if(owner==null)return;
        Player caster=Bukkit.getPlayer(owner);
        if(caster==null||!wielding(caster))return;
        int distance=Math.max(4,Math.min(32,plugin.getConfig().getInt("paleheart.passive.radius-blocks",12)));
        for(Player other:caster.getWorld().getPlayers()){
            if(other.equals(caster)||other.getGameMode()==GameMode.CREATIVE||other.getGameMode()==GameMode.SPECTATOR)continue;
            if(other.getLocation().distanceSquared(caster.getLocation())>distance*distance)continue;
            if(lookingAt(other,caster.getLocation()))continue;
            PotionEffect old=caster.getPotionEffect(PotionEffectType.SPEED);
            if(old==null || (old.getAmplifier()==0 && old.getDuration()<=25))
                caster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,25,0,true,false,true));
            if((System.currentTimeMillis()/1000)%2==0)caster.getWorld().spawnParticle(Particle.DUST,caster.getLocation().add(0,1,0),5,.25,.4,.25,0,pale);
            break;
        }
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void melee(EntityDamageByEntityEvent event) {
        if(!(event.getDamager() instanceof Player player)||!wielding(player)||!plugin.usableTarget(player,event.getEntity()))return;
        event.setDamage(event.getDamage()+Math.max(0,plugin.getConfig().getDouble("paleheart.passive.melee-bonus-health-points",2.0)));
        event.getEntity().getWorld().spawnParticle(Particle.DUST,event.getEntity().getLocation().add(0,1,0),14,.3,.5,.3,0,amber);
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=EquipmentSlot.HAND)return;
        if(event.getAction()!=Action.RIGHT_CLICK_AIR&&event.getAction()!=Action.RIGHT_CLICK_BLOCK)return;
        if(!authentic(event.getItem()))return;
        event.setCancelled(true); // A hoe normally tills farmland; never till while casting.
        if(!authorized(event.getPlayer()))return;
        if(event.getPlayer().isSneaking())unseenStep(event.getPlayer());
        else roots(event.getPlayer());
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void swap(PlayerSwapHandItemsEvent event) {
        if(!authentic(event.getPlayer().getInventory().getItemInMainHand()))return;
        event.setCancelled(true);
        if(authorized(event.getPlayer()))hauntedGrove(event.getPlayer());
    }
    private LivingEntity aimedTarget(Player player, double maxDistance) {
        LivingEntity best=null;double bestScore=Double.MAX_VALUE;
        Location eye=player.getEyeLocation();Vector facing=eye.getDirection().normalize();
        for(Entity possible:player.getWorld().getNearbyEntities(eye,maxDistance,maxDistance,maxDistance)){
            if(!plugin.usableTarget(player,possible))continue;
            LivingEntity living=(LivingEntity)possible;
            Vector toward=living.getEyeLocation().toVector().subtract(eye.toVector());
            double distance=toward.length();
            if(distance>maxDistance||distance<.1)continue;
            double alignment=facing.dot(toward.normalize());
            if(alignment<.96)continue;
            if(!player.hasLineOfSight(living))continue;
            double score=distance+(1-alignment)*25;
            if(score<bestScore){bestScore=score;best=living;}
        }
        return best;
    }
    private void roots(Player player) {
        LivingEntity target=aimedTarget(player,Math.max(4,Math.min(25,plugin.getConfig().getDouble("paleheart.roots.range-blocks",13))));
        if(target==null){player.sendActionBar(text("Aim at an enemy to summon Grasping Roots",0xF3D2A2));return;}
        if(!ready(player,"paleheart.roots","paleheart.roots.cooldown-seconds",22))return;
        Location base=target.getLocation().clone();World world=base.getWorld();
        world.playSound(base,Sound.BLOCK_WOOD_BREAK,1.2f,.6f);
        // Ten-tick warning: roots should be dodgeable, not guaranteed damage.
        new BukkitRunnable(){int age=0;
            @Override public void run(){
                if(age>=12){
                    if(player.isOnline()&&target.isValid()&&!target.isDead()&&target.getWorld().equals(world)
                            && target.getLocation().distanceSquared(base)<=4.0 && plugin.usableTarget(player,target)){
                        plugin.dealSovereignAbilityDamage(target,player,plugin.getConfig().getDouble("paleheart.roots.damage-health-points",3.0));
                        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                                Math.max(5,plugin.getConfig().getInt("paleheart.roots.slow-ticks",28)),4,true,true,true));
                        world.playSound(base,Sound.BLOCK_ROOTED_DIRT_BREAK,.9f,.7f);
                        world.spawnParticle(Particle.DUST,target.getLocation().add(0,1,0),42,.55,.65,.55,0,amber);
                    }
                    cancel();return;
                }
                for(int i=0;i<10;i++){
                    double angle=(i/10.0)*Math.PI*2+age*.08;
                    Location p=base.clone().add(Math.cos(angle)*(1.0-age*.035),.1+age*.035,Math.sin(angle)*(1.0-age*.035));
                    world.spawnParticle(Particle.DUST,p,1,0,0,0,0,age%4==0?amber:pale);
                }
                age+=2;
            }
        }.runTaskTimer(plugin,0L,2L);
    }
    private void unseenStep(Player player) {
        if(!ready(player,"paleheart.step","paleheart.step.cooldown-seconds",30))return;
        Location old=player.getLocation().clone();Vector forward=old.getDirection().setY(0);
        if(forward.lengthSquared()<.02)forward=new Vector(0,0,1);
        forward.normalize();
        Location landing=null;
        double range=Math.max(2,Math.min(14,plugin.getConfig().getDouble("paleheart.step.range-blocks",7)));
        // Search backward from the desired range: safe, loaded air with solid footing.
        for(double d=range;d>=1.5;d-=.5){
            Location test=old.clone().add(forward.clone().multiply(d));
            test.setX(Math.floor(test.getX())+.5);test.setZ(Math.floor(test.getZ())+.5);
            if(test.getBlock().isPassable()&&test.clone().add(0,1,0).getBlock().isPassable()
                    &&test.clone().add(0,-1,0).getBlock().getType().isSolid()){
                landing=test;break;
            }
        }
        if(landing==null){refund(player,"paleheart.step");player.sendActionBar(text("No safe Unseen Step destination",0xE6C18F));return;}
        landing.setDirection(old.getDirection());
        player.teleport(landing);
        old.getWorld().playSound(old,Sound.ENTITY_ENDERMAN_TELEPORT,.9f,1.3f);
        old.getWorld().spawnParticle(Particle.DUST,old.clone().add(0,1,0),65,.5,.7,.5,0,amber);
        player.getWorld().spawnParticle(Particle.DUST,landing.clone().add(0,1,0),65,.5,.7,.5,0,pale);
        summonDecoy(old,Math.max(20,plugin.getConfig().getInt("paleheart.step.decoy-duration-ticks",80)));
    }
    private Entity summonDecoy(Location loc,int durationTicks) {
        World world=loc.getWorld();Entity decoy;
        try {
            decoy=world.spawnEntity(loc,EntityType.CREAKING);
            if(decoy instanceof Mob mob){mob.setAI(false);mob.setSilent(true);mob.setInvulnerable(true);mob.setPersistent(false);}
        } catch(RuntimeException e) {
            // Creakings may be restricted by a server's spawn rules; a safe particle marker remains.
            world.spawnParticle(Particle.DUST,loc.clone().add(0,1,0),50,.5,1,.5,0,amber);
            return null;
        }
        decoy.addScoreboardTag(DECOY_TAG);
        activeDecoys.add(decoy.getUniqueId());
        Entity captured=decoy;
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(captured.isValid())captured.remove();
            activeDecoys.remove(captured.getUniqueId());
        },durationTicks);
        return decoy;
    }
    private void hauntedGrove(Player player) {
        if(!ready(player,"paleheart.grove","paleheart.grove.cooldown-seconds",65))return;
        Location center=player.getLocation().clone();World world=center.getWorld();
        double radius=Math.max(3,Math.min(24,plugin.getConfig().getDouble("paleheart.grove.radius-blocks",15)));
        int duration=Math.max(40,Math.min(240,plugin.getConfig().getInt("paleheart.grove.duration-ticks",140)));
        List<Location> stalkers=new ArrayList<>();
        world.playSound(center,Sound.BLOCK_CREAKING_HEART_SPAWN,1.3f,.65f);
        final int count=6;
        for(int i=0;i<count;i++){
            double a=i*Math.PI*2/count+Math.PI/6;
            Location spot=center.clone().add(Math.cos(a)*radius,0,Math.sin(a)*radius);
            // Find ground without editing terrain; skip if blocked or in the air.
            for(int dy=2;dy>=-2;dy--){
                Location check=spot.clone().add(0,dy,0);
                if(check.getBlock().isPassable()&&check.clone().add(0,1,0).getBlock().isPassable()
                        &&check.clone().add(0,-1,0).getBlock().getType().isSolid()){
                    spot=check;break;
                }
            }
            stalkers.add(spot);
            summonDecoy(spot,duration+10);
        }
        new BukkitRunnable(){int age=0;
            final Map<String,Integer> lastTouch=new HashMap<>();
            @Override public void run(){
                if(age>=duration||!player.isOnline()||!player.getWorld().equals(world)){cancel();return;}
                for(int i=0;i<36;i++){
                    double a=i*Math.PI/18.0+age*.012;
                    Location p=center.clone().add(Math.cos(a)*radius,.15,Math.sin(a)*radius);
                    world.spawnParticle(Particle.DUST,p,1,0,0,0,0,i%4==0?amber:pale);
                }
                for(Location spot:stalkers)world.spawnParticle(Particle.DUST,spot.clone().add(0,1.4,0),3,.35,.65,.35,0,amber);
                // All hostile players within the Grove are blinded; friendly players are unaffected.
                for(Player enemy:world.getPlayers()){
                    if(enemy.equals(player)||(enemy.getGameMode()!=GameMode.SURVIVAL && enemy.getGameMode()!=GameMode.ADVENTURE))continue;
                    if(enemy.getLocation().distanceSquared(center)>radius*radius || !plugin.usableTarget(player,enemy))continue;
                    enemy.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,35,0,true,false,true));
                    // Every Creaking has its own contact hit. Rate-limit per Creaking/player.
                    for(int i=0;i<stalkers.size();i++){
                        Location spot=stalkers.get(i);
                        if(Math.abs(enemy.getLocation().getY()-spot.getY())>2.4)continue;
                        Vector dx=enemy.getLocation().toVector().subtract(spot.toVector()).setY(0);
                        if(dx.lengthSquared()>1.8*1.8)continue;
                        String key=enemy.getUniqueId()+":"+i;
                        if(age-lastTouch.getOrDefault(key,-100)<20)continue;
                        lastTouch.put(key,age);
                        double amount=Math.max(2.0,plugin.getConfig().getDouble("paleheart.grove.touch-damage-health-points",2.0));
                        plugin.dealSovereignAbilityDamage(enemy,player,amount);
                        world.spawnParticle(Particle.DUST,enemy.getLocation().add(0,1,0),34,.5,.5,.5,0,amber);
                        world.playSound(spot,Sound.ENTITY_CREAKING_ATTACK,1.0f,.85f);
                    }
                }
                // The wave remains dodgeable by facing the nearest Creaking.
                if(age>0 && age%30==0){
                    for(Player enemy:world.getPlayers()){
                        if(enemy.equals(player)|| (enemy.getGameMode()!=GameMode.SURVIVAL
                                && enemy.getGameMode()!=GameMode.ADVENTURE))continue;
                        if(enemy.getLocation().distanceSquared(center)>radius*radius)continue;
                        Location closest=null;double best=Double.MAX_VALUE;
                        for(Location spot:stalkers){double d=enemy.getLocation().distanceSquared(spot);if(d<best){best=d;closest=spot;}}
                        if(closest==null || lookingAtDecoy(enemy,closest))continue; // counterplay: WATCH THEM.
                        if(!plugin.usableTarget(player,enemy))continue;
                        plugin.dealSovereignAbilityDamage(enemy,player,plugin.getConfig().getDouble("paleheart.grove.pulse-damage-health-points",2.0));
                        enemy.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,22,1,true,true,true));
                        world.spawnParticle(Particle.DUST,enemy.getLocation().add(0,1,0),40,.45,.55,.45,0,amber);
                        world.playSound(enemy.getLocation(),Sound.ENTITY_CREAKING_ATTACK,.7f,.85f);
                    }
                }
                age+=5;
            }
        }.runTaskTimer(plugin,0L,5L);
    }
}
