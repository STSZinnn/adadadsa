package com.sovereignsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import java.util.*;

/** The last two single-owner Sovereign artifacts. No modifications to existing artifact identifiers. */
final class FinalArtifacts implements Listener {
    static final String STORM = "stormvault", HEX = "hexcaller";
    private static final String MOB_TAG = "sovereign_hexcaller_vex";
    private static final String FANG_TAG = "sovereign_hexcaller_fang";
    private final SovereignSMPPlugin plugin;
    private final Map<String, UUID> tokens = new HashMap<>(), owners = new HashMap<>();
    private final Map<String, NamespacedKey> itemKeys = new HashMap<>(), styleKeys = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> windStacks = new HashMap<>(), hexStacks = new HashMap<>();
    private final Map<UUID, Long> windUntil = new HashMap<>(), hexUntil = new HashMap<>(), fallSafe = new HashMap<>();
    private final Map<UUID, UUID> hexCaster = new HashMap<>();
    private final Set<UUID> vexes = new HashSet<>();
    private final Map<UUID,UUID> vexSummoners=new HashMap<>();
    private final Particle.DustOptions windColor = new Particle.DustOptions(Color.fromRGB(115,225,234),1.2f);
    private final Particle.DustOptions goldColor = new Particle.DustOptions(Color.fromRGB(196,149,90),1.05f);
    private final Particle.DustOptions hexColor = new Particle.DustOptions(Color.fromRGB(91,221,125),1.25f);
    private final Particle.DustOptions spellColor = new Particle.DustOptions(Color.fromRGB(164,91,212),1.15f);
    private long tickCount = 0;

    FinalArtifacts(SovereignSMPPlugin plugin) {
        this.plugin=plugin;
        for (String type : List.of(STORM,HEX)) {
            itemKeys.put(type,new NamespacedKey(plugin,type+"_token"));
            styleKeys.put(type,new NamespacedKey(plugin,type));
            UUID token=plugin.getArtifactStateUuid(type+".token");
            tokens.put(type,token);
            owners.put(type,token==null?null:plugin.getArtifactStateUuid(type+".owner"));
        }
    }
    void begin() {
        for (World world:Bukkit.getWorlds()) for(Entity entity:world.getEntities())
            if(entity.getScoreboardTags().contains(MOB_TAG)||entity.getScoreboardTags().contains(FANG_TAG)) entity.remove();
        Bukkit.getScheduler().runTaskTimer(plugin,this::tickPassives,10L,10L);
    }
    void shutdown() {
        for (World world:Bukkit.getWorlds()) for(Entity entity:world.getEntities())
            if(entity.getScoreboardTags().contains(MOB_TAG)||entity.getScoreboardTags().contains(FANG_TAG))entity.remove();
        vexes.clear(); vexSummoners.clear(); cooldowns.clear(); windStacks.clear(); hexStacks.clear(); fallSafe.clear();
    }
    boolean isType(String type) {return STORM.equals(type)||HEX.equals(type);}
    boolean minted(String type) {return tokens.get(type)!=null;}
    String ownerName(String type) {
        UUID id=owners.get(type); if(id==null)return "Unclaimed";
        String name=Bukkit.getOfflinePlayer(id).getName();return name==null?id.toString():name;
    }
    private void persist(String type){
        plugin.setArtifactStateUuid(type+".token",tokens.get(type));
        plugin.setArtifactStateUuid(type+".owner",owners.get(type));
        plugin.saveArtifactState();
    }
    boolean authentic(String type,ItemStack stack){
        if(!isType(type)||tokens.get(type)==null||stack==null||!stack.hasItemMeta())return false;
        Material expected=type.equals(STORM)?Material.COPPER_SPEAR:Material.BLAZE_ROD;
        if(stack.getType()!=expected)return false;
        return tokens.get(type).toString().equals(stack.getItemMeta().getPersistentDataContainer()
                .get(itemKeys.get(type),PersistentDataType.STRING));
    }
    boolean authenticAny(ItemStack item){return authentic(STORM,item)||authentic(HEX,item);}
    private boolean wielding(String type,Player player){
        return player.isOnline() && player.getGameMode()!=GameMode.SPECTATOR
                && player.getUniqueId().equals(owners.get(type))
                && authentic(type,player.getInventory().getItemInMainHand());
    }
    private boolean authorized(String type,Player player) {
        UUID owner=owners.get(type);
        if(owner==null)player.sendMessage(ChatColor.YELLOW+"[Sovereign] "+type+" is unclaimed. Pick it up or /sovereign claim "+type+".");
        else if(!owner.equals(player.getUniqueId()))player.sendMessage(ChatColor.RED+"[Sovereign] Only the artifact holder may use "+type+".");
        return owner!=null&&owner.equals(player.getUniqueId())&&player.getGameMode()!=GameMode.SPECTATOR;
    }
    private Component text(String value,int rgb) {return Component.text(value,TextColor.color(rgb)).decoration(TextDecoration.ITALIC,false);}
    private Component gradient(String str,int start,int end,boolean bold){
        Component result=Component.empty();
        for(int i=0;i<str.length();i++){
            double a=str.length()<2?0:i/(double)(str.length()-1);
            int r=(int)(((start>>16)&255)*(1-a)+((end>>16)&255)*a);
            int g=(int)(((start>>8)&255)*(1-a)+((end>>8)&255)*a);
            int b=(int)((start&255)*(1-a)+(end&255)*a);
            result=result.append(Component.text(String.valueOf(str.charAt(i)),TextColor.color(r,g,b))
                    .decoration(TextDecoration.BOLD,bold).decoration(TextDecoration.ITALIC,false));
        } return result;
    }
    private int configI(String path,int fallback){return plugin.getConfig().getInt(path,fallback);}
    private double configD(String path,double fallback){return plugin.getConfig().getDouble(path,fallback);}
    private void loreAbility(List<Component> lore,int colorA,int colorB,String name,String key,int seconds,String control,String desc){
        lore.add(Component.empty());lore.add(gradient(name,colorA,colorB,true));
        lore.add(text(control+"  •  "+configI(key,seconds)+"s",colorA));
        lore.add(text(desc,0xD1DBDD));
    }
    private void style(String type,ItemMeta meta){
        boolean storm=type.equals(STORM);int primary=storm?0x96EBF5:0x9DF0B4;int secondary=storm?0xBA8A57:0xA87DD5;
        meta.setUnbreakable(true); meta.setEnchantmentGlintOverride(true);
        meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        if(storm){
            meta.addEnchant(Enchantment.SHARPNESS,5,true);meta.addEnchant(Enchantment.LUNGE,1,true);
            meta.addEnchant(Enchantment.LOOTING,3,true);
        } else { meta.addEnchant(Enchantment.SHARPNESS,3,true);meta.addEnchant(Enchantment.LOOTING,3,true); }
        meta.displayName(gradient(storm?"STORMVAULT  ✦  BREEZE GLAIVE":"HEXCALLER  ✦  EVOKER STAFF",primary,secondary,true));
        List<Component> lore=new ArrayList<>();
        lore.add(gradient(storm?"✧ LEGENDARY ARTIFACT VI / VII ✧":"✧ LEGENDARY ARTIFACT VII / VII ✧",primary,secondary,true));
        lore.add(text(storm?"The Trial Chambers bend before the storm.":"A forbidden ritual from the Woodland Mansion.",0xB9C4C3));
        lore.add(Component.empty());
        lore.add(gradient(storm?"PASSIVE  |  WIND MOMENTUM":"PASSIVE  |  DARK EVOCATION",primary,secondary,true));
        lore.add(text(storm?"Airborne strikes build up to 3 Wind stacks.":"Hits build up to 3 Hex Marks on enemies.",0xD1DBDD));
        if(storm){
            loreAbility(lore,primary,secondary,"I  |  GALE BURST","stormvault.gale.cooldown-seconds",18,"RIGHT-CLICK","A wind projectile knocks foes sideways.");
            loreAbility(lore,primary,secondary,"II  |  UPDRAFT","stormvault.updraft.cooldown-seconds",28,"SHIFT + RIGHT-CLICK","Leap on a gust; full charge adds a second boost.");
            loreAbility(lore,primary,secondary,"III  |  EYE OF THE TEMPEST","stormvault.tempest.cooldown-seconds",60,"F  •  SWAP HANDS","A moving tornado draws enemies into the storm.");
        } else {
            loreAbility(lore,primary,secondary,"I  |  FANGS OF JUDGMENT","hexcaller.fangs.cooldown-seconds",20,"RIGHT-CLICK","Summon evoker fangs in a deadly line.");
            loreAbility(lore,primary,secondary,"II  |  VEXBOUND","hexcaller.vexbound.cooldown-seconds",35,"SHIFT + RIGHT-CLICK","Summon two Vexes to hunt marked foes.");
            loreAbility(lore,primary,secondary,"III  |  ILLAGER'S DOMINION","hexcaller.dominion.cooldown-seconds",65,"F  •  SWAP HANDS","A three-wave ritual of fangs and Vexes.");
        }
        lore.add(Component.empty());lore.add(gradient("✦ ONLY ONE MAY WIELD THIS ARTIFACT ✦",primary,secondary,true));
        meta.lore(lore);meta.setTooltipStyle(styleKeys.get(type));
        meta.setItemModel(new NamespacedKey(plugin,type));
    }
    ItemStack restyle(ItemStack source) {
        for(String type:List.of(STORM,HEX))if(authentic(type,source)){
            ItemStack copy=source.clone();ItemMeta meta=copy.getItemMeta();style(type,meta);copy.setItemMeta(meta);return copy;
        }return source;
    }
    private ItemStack create(String type){
        ItemStack item=new ItemStack(type.equals(STORM)?Material.COPPER_SPEAR:Material.BLAZE_ROD);
        ItemMeta meta=item.getItemMeta();style(type,meta);
        meta.getPersistentDataContainer().set(itemKeys.get(type),PersistentDataType.STRING,tokens.get(type).toString());
        item.setItemMeta(meta);return item;
    }
    void mint(String type,Player recipient,boolean recovery){
        if(!isType(type))return;
        if(minted(type)&&!recovery){recipient.sendMessage(ChatColor.RED+"[Sovereign] Already minted. Recover only if truly lost.");return;}
        // Recovery revokes all previously issued copies, which fail the token authenticity check.
        tokens.put(type,UUID.randomUUID());owners.put(type,recipient.getUniqueId());persist(type);
        for(ItemStack excess:recipient.getInventory().addItem(create(type)).values())
            recipient.getWorld().dropItemNaturally(recipient.getLocation(),excess);
        Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+recipient.getName()+" now wields Artifact "+(type.equals(STORM)?"VI":"VII")+" / VII — "+type+"!");
    }
    void claim(String type,Player player){
        if(!minted(type)){player.sendMessage(ChatColor.RED+"[Sovereign] Not yet minted.");return;}
        if(owners.get(type)!=null){player.sendMessage(ChatColor.RED+"[Sovereign] Artifact is already claimed.");return;}
        for(ItemStack stack:player.getInventory().getContents())if(authentic(type,stack)){
            owners.put(type,player.getUniqueId());persist(type);
            Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+player.getName()+" claimed "+type+"!");return;
        }
        player.sendMessage(ChatColor.RED+"[Sovereign] The genuine artifact must be in your inventory to claim it.");
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true)
    public void onPickup(EntityPickupItemEvent event){
        if(!(event.getEntity() instanceof Player player))return;
        for(String type:List.of(STORM,HEX))if(authentic(type,event.getItem().getItemStack())){
            Bukkit.getScheduler().runTask(plugin,()->{
                if(!player.isOnline())return;
                boolean found=false;ItemStack[] items=player.getInventory().getContents();
                for(int i=0;i<items.length;i++)if(authentic(type,items[i])){
                    found=true;player.getInventory().setItem(i,restyle(items[i]));
                }
                if(found&&owners.get(type)==null){owners.put(type,player.getUniqueId());persist(type);
                    Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+player.getName()+" claimed "+type+"!");}
            });return;
        }
    }
    @EventHandler(priority=EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event){
        Player dead=event.getEntity();
        for(String type:List.of(STORM,HEX))if(dead.getUniqueId().equals(owners.get(type))){
            owners.put(type,null);persist(type);
            // Keep authentic artifact claimable even when keepInventory is enabled.
            if(event.getKeepInventory()){
                ItemStack lost=null;
                for(int i=0;i<dead.getInventory().getSize();i++){
                    ItemStack item=dead.getInventory().getItem(i);
                    if(authentic(type,item)){lost=item.clone();dead.getInventory().setItem(i,null);break;}
                }
                if(lost!=null){ItemStack drop=lost;Location spot=dead.getLocation().clone();
                    Bukkit.getScheduler().runTask(plugin,()->spot.getWorld().dropItemNaturally(spot,drop));}
            }
            Bukkit.broadcastMessage(ChatColor.GOLD+"[Sovereign] "+type+" is unclaimed after "+dead.getName()+"'s defeat!");
        }
    }
    private boolean ready(Player p,String key,int seconds) {
        long now=System.currentTimeMillis();Map<String,Long> map=cooldowns.computeIfAbsent(p.getUniqueId(),id->new HashMap<>());
        long end=map.getOrDefault(key,0L);if(end>now){p.sendActionBar(text(key+" "+String.format(Locale.US,"%.1fs",(end-now)/1000d),0xEECA8E));return false;}
        map.put(key,now+1000L*Math.max(0,configI(key+".cooldown-seconds",seconds)));return true;
    }
    Component hud(Player player){
        String type=wielding(STORM,player)?STORM:wielding(HEX,player)?HEX:null;if(type==null)return null;
        String[] ids=type.equals(STORM)?new String[]{"stormvault.gale","stormvault.updraft","stormvault.tempest"}:new String[]{"hexcaller.fangs","hexcaller.vexbound","hexcaller.dominion"};
        String[] labels=type.equals(STORM)?new String[]{"GB","UP","ET"}:new String[]{"FJ","VX","ID"};
        Component bar=Component.empty();for(int i=0;i<3;i++){
            if(i>0)bar=bar.append(text("  │  ",0x7E9C9D));
            long remain=cooldowns.getOrDefault(player.getUniqueId(),Map.of()).getOrDefault(ids[i],0L)-System.currentTimeMillis();
            bar=bar.append(text(labels[i]+" "+(remain<=0?"READY":String.format(Locale.US,"%.1fs",remain/1000d)),remain<=0?0xABF1D3:0x839C9B));
        }return bar;
    }
    List<String> cooldownLines(Player player){
        List<String> out=new ArrayList<>();for(String key:List.of("stormvault.gale","stormvault.updraft","stormvault.tempest","hexcaller.fangs","hexcaller.vexbound","hexcaller.dominion")){
            long remain=cooldowns.getOrDefault(player.getUniqueId(),Map.of()).getOrDefault(key,0L)-System.currentTimeMillis();
            out.add(ChatColor.GRAY+key+": "+ChatColor.AQUA+(remain<=0?"READY":String.format(Locale.US,"%.1fs",remain/1000d)));
        }return out;
    }
    private void tickPassives(){
        tickCount++;
        long now=System.currentTimeMillis();windUntil.entrySet().removeIf(e->e.getValue()<now);
        hexUntil.entrySet().removeIf(e->{if(e.getValue()<now){hexStacks.remove(e.getKey());hexCaster.remove(e.getKey());return true;}return false;});
        windStacks.keySet().removeIf(id->!windUntil.containsKey(id));
        if(tickCount%3==0)for(Player p:Bukkit.getOnlinePlayers())if(wielding(STORM,p)){
            p.getWorld().spawnParticle(Particle.DUST,p.getLocation().add(0,1,0),3,.35,.45,.35,0,windColor);
        }
    }
    private int consumeWind(Player player){
        UUID id=player.getUniqueId();int stacks=windUntil.getOrDefault(id,0L)>System.currentTimeMillis()?windStacks.getOrDefault(id,0):0;
        windStacks.remove(id);windUntil.remove(id);return stacks;
    }
    private int consumeHex(LivingEntity target,Player caster){
        UUID id=target.getUniqueId();if(!caster.getUniqueId().equals(hexCaster.get(id)))return 0;
        int stacks=hexStacks.getOrDefault(id,0);hexStacks.remove(id);hexUntil.remove(id);hexCaster.remove(id);return stacks;
    }
    private void markHex(LivingEntity target,Player caster){
        UUID id=target.getUniqueId();if(!caster.getUniqueId().equals(hexCaster.get(id))){hexStacks.remove(id);}
        hexCaster.put(id,caster.getUniqueId());hexStacks.put(id,Math.min(3,hexStacks.getOrDefault(id,0)+1));
        hexUntil.put(id,System.currentTimeMillis()+1000L*configI("hexcaller.passive.mark-duration-seconds",8));
        target.getWorld().spawnParticle(Particle.DUST,target.getLocation().add(0,1.5,0),10,.35,.45,.35,0,hexColor);
    }
    private void damage(LivingEntity enemy,Player caster,double amount,boolean hexMark){
        if(!plugin.usableTarget(caster,enemy)||enemy.getScoreboardTags().contains(MOB_TAG))return;
        plugin.dealSovereignAbilityDamage(enemy,caster,amount);
        if(hexMark && enemy.isValid() && !enemy.isDead())markHex(enemy,caster);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void passiveOnHit(EntityDamageByEntityEvent event){
        if(!(event.getDamager() instanceof Player player)||!(event.getEntity() instanceof LivingEntity enemy))return;
        ItemStack held=player.getInventory().getItemInMainHand();
        if((authentic(STORM,held)&&!wielding(STORM,player))
                ||(authentic(HEX,held)&&!wielding(HEX,player))){
            event.setCancelled(true);
            player.sendActionBar(text("Only the rightful artifact holder may wield this weapon",0xD77A7A));
            return;
        }
        if(!plugin.usableTarget(player,enemy))return;
        if(wielding(STORM,player)&&!player.isOnGround()){
            UUID id=player.getUniqueId();windStacks.put(id,Math.min(3,windStacks.getOrDefault(id,0)+1));
            windUntil.put(id,System.currentTimeMillis()+1000L*configI("stormvault.passive.stack-duration-seconds",8));
            player.getWorld().spawnParticle(Particle.CLOUD,enemy.getLocation().add(0,1,0),12,.3,.4,.3,.02);
        }
        if(wielding(HEX,player)){
            // Blaze rod Sharpness III-like melee damage, rather than a visual-only enchantment.
            event.setDamage(event.getDamage()+4.0);
            markHex(enemy,player);
        }
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND || (event.getAction()!=Action.RIGHT_CLICK_AIR && event.getAction()!=Action.RIGHT_CLICK_BLOCK))return;
        Player player=event.getPlayer();String type=authentic(STORM,event.getItem())?STORM:authentic(HEX,event.getItem())?HEX:null;
        if(type==null)return;
        event.setCancelled(true);
        if(!authorized(type,player))return;
        if(type.equals(STORM)) {if(player.isSneaking())updraft(player);else gale(player);}
        else {if(player.isSneaking())vexbound(player);else fangs(player);}
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void onSwap(PlayerSwapHandItemsEvent event){
        Player player=event.getPlayer();String type=authentic(STORM,player.getInventory().getItemInMainHand())?STORM:
                authentic(HEX,player.getInventory().getItemInMainHand())?HEX:null;
        if(type==null)return;
        event.setCancelled(true);
        if(!authorized(type,player))return;
        if(type.equals(STORM))tempest(player);else dominion(player);
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true)
    public void onFall(EntityDamageEvent event){
        if(event.getCause()==EntityDamageEvent.DamageCause.FALL && event.getEntity() instanceof Player player
                && fallSafe.getOrDefault(player.getUniqueId(),0L)>System.currentTimeMillis()){
            event.setCancelled(true);fallSafe.remove(player.getUniqueId());
        }
    }
    private void gale(Player caster){
        String key="stormvault.gale";if(!ready(caster,key,18))return;
        int stacks=consumeWind(caster);double range=configD("stormvault.gale.range-blocks",12);
        Location start=caster.getEyeLocation().clone();Vector direction=start.getDirection().normalize();World world=caster.getWorld();
        world.playSound(start,Sound.ENTITY_BREEZE_SHOOT,1.5f,.7f);
        new BukkitRunnable(){double distance=0;final Set<UUID> hit=new HashSet<>();
            @Override public void run(){
                if(!caster.isOnline()||distance>=range){cancel();return;}
                for(int j=0;j<3;j++){
                    distance+=.55;Location where=start.clone().add(direction.clone().multiply(distance));
                    if(!where.getBlock().isPassable()){cancel();return;}
                    world.spawnParticle(Particle.CLOUD,where,9,.38,.38,.38,.03);
                    world.spawnParticle(Particle.DUST,where,7,.3,.3,.3,0,windColor);
                    for(Entity entity:world.getNearbyEntities(where,1.3,1.3,1.3)){
                        if(!(entity instanceof LivingEntity living)||!plugin.usableTarget(caster,living)||!hit.add(entity.getUniqueId()))continue;
                        damage(living,caster,configD("stormvault.gale.damage-health-points",3),false);
                        Vector sideways=new Vector(-direction.getZ(),.23,direction.getX()).normalize().multiply(.7);
                        living.setVelocity(living.getVelocity().multiply(.2).add(sideways));
                        if(stacks>=3)damage(living,caster,configD("stormvault.gale.charged-bonus-health-points",1),false);
                        world.playSound(where,Sound.ENTITY_BREEZE_WIND_BURST,1.2f,.85f);
                    }
                }
            }
        }.runTaskTimer(plugin,0L,1L);
    }
    private void updraft(Player caster){
        String key="stormvault.updraft";if(!ready(caster,key,28))return;
        int stacks=consumeWind(caster);Location loc=caster.getLocation();World world=caster.getWorld();
        for(int i=0;i<48;i++){double a=Math.PI*2*i/48;Location point=loc.clone().add(Math.cos(a)*2,.1,Math.sin(a)*2);
            world.spawnParticle(Particle.DUST,point,2,.05,.03,.05,0,windColor);}
        world.spawnParticle(Particle.CLOUD,loc,60,1.2,.3,1.2,.07);
        world.playSound(loc,Sound.ENTITY_BREEZE_JUMP,1.5f,.8f);
        Vector horizontal=caster.getLocation().getDirection().setY(0);
        if(horizontal.lengthSquared()>0.01)horizontal.normalize().multiply(.4);
        caster.setVelocity(new Vector(horizontal.getX(),1.12,horizontal.getZ()));
        fallSafe.put(caster.getUniqueId(),System.currentTimeMillis()+9000L);
        if(stacks>=3)Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!caster.isOnline()||caster.isOnGround())return;
            Vector look=caster.getEyeLocation().getDirection().normalize().multiply(.75);
            caster.setVelocity(look.setY(Math.max(.4,look.getY()+.5)));
            world.spawnParticle(Particle.CLOUD,caster.getLocation(),48,.6,.5,.6,.04);
            world.playSound(caster.getLocation(),Sound.ENTITY_BREEZE_JUMP,1.0f,1.3f);
        },12L);
    }
    private void tempest(Player caster){
        if(!ready(caster,"stormvault.tempest",60))return;
        int stacks=consumeWind(caster);
        World world=caster.getWorld();Location origin=caster.getLocation().clone().add(0,.1,0);
        Vector direction=caster.getEyeLocation().getDirection().setY(0);
        if(direction.lengthSquared()<.01)direction=new Vector(0,0,1);
        final Vector travel=direction.normalize().multiply(configD("stormvault.tempest.max-travel-blocks",10)/120.0);
        double radius=configD("stormvault.tempest.pull-radius-blocks",4);
        int duration=configI("stormvault.tempest.duration-ticks",120);
        world.playSound(origin,Sound.ENTITY_BREEZE_WHIRL,1.5f,.55f);
        new BukkitRunnable(){int age=0;Location center=origin.clone();final Map<UUID,Integer> lastHit=new HashMap<>();
            @Override public void run(){
                if(!caster.isOnline()||!world.equals(caster.getWorld())||age>=duration){
                    finish();cancel();return;
                }
                Location next=center.clone().add(travel.clone().multiply(3));
                if(next.getBlock().isPassable() && next.clone().add(0,1,0).getBlock().isPassable()) center=next;
                for(int ring=0;ring<4;ring++)for(int j=0;j<20;j++){
                    double a=(j/20.0)*Math.PI*2+age*.095+ring*.55;
                    double r=.8+ring*.75;
                    Location point=center.clone().add(Math.cos(a)*r,.3+ring*1.05,Math.sin(a)*r);
                    world.spawnParticle(Particle.DUST,point,1,0,0,0,0,ring%2==0?windColor:goldColor);
                    if(j%4==0)world.spawnParticle(Particle.CLOUD,point,1,0,0,0,.015);
                }
                for(Entity entity:world.getNearbyEntities(center,radius,5,radius)){
                    if(!(entity instanceof LivingEntity target)||!plugin.usableTarget(caster,target))continue;
                    Vector toward=center.toVector().subtract(target.getLocation().toVector()).setY(0);
                    if(toward.lengthSquared()>radius*radius)continue;
                    if(toward.lengthSquared()>.1){
                        Vector pull=toward.normalize().multiply(.24);pull.setY(.18);
                        target.setVelocity(target.getVelocity().multiply(.65).add(pull));
                    }
                    if(age-lastHit.getOrDefault(entity.getUniqueId(),-100)>=20){
                        lastHit.put(entity.getUniqueId(),age);
                        damage(target,caster,configD("stormvault.tempest.pulse-damage-health-points",1),false);
                    }
                }
                age+=3;
            }
            private void finish(){
                double finalDamage= configD("stormvault.tempest.final-damage-health-points",stacks>=3?4:3);
                if(stacks>=3)finalDamage+=configD("stormvault.tempest.charged-bonus-health-points",1);
                for(Entity entity:world.getNearbyEntities(center,radius+1,5,radius+1)){
                    if(!(entity instanceof LivingEntity target)||!plugin.usableTarget(caster,target))continue;
                    Vector away=target.getLocation().toVector().subtract(center.toVector());
                    if(away.lengthSquared()>(radius+1)*(radius+1))continue;
                    damage(target,caster,finalDamage,false);
                    away.setY(.35);if(away.lengthSquared()<.04)away=new Vector(0,.6,1);
                    target.setVelocity(away.normalize().multiply(1.2));
                }
                world.spawnParticle(Particle.CLOUD,center,180,3,2,3,.14);
                world.playSound(center,Sound.ENTITY_BREEZE_WIND_BURST,1.8f,.65f);
            }
        }.runTaskTimer(plugin,20L,3L);
    }
    private void showFangs(Player caster,Location spot){
        World world=spot.getWorld();
        if(!spot.getBlock().isPassable())return;
        world.spawn(spot,EvokerFangs.class,fang->{fang.setOwner(caster);fang.addScoreboardTag(FANG_TAG);});
        world.spawnParticle(Particle.DUST,spot.clone().add(0,.35,0),14,.45,.2,.45,0,hexColor);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void onSpecialFangDamage(EntityDamageByEntityEvent event){
        if(event.getDamager().getScoreboardTags().contains(FANG_TAG))event.setCancelled(true);
    }
    private void fangLine(Player caster,Location start,Vector direction,int count,double damage,boolean finalWave){
        World world=caster.getWorld();Set<UUID> hit=new HashSet<>();
        new BukkitRunnable(){int step=0;
            @Override public void run(){
                if(!caster.isOnline()||step>=count){cancel();return;}
                Location next=start.clone().add(direction.clone().multiply(step+1));
                if(!next.getBlock().isPassable()){cancel();return;}
                world.spawnParticle(Particle.DUST,next,16,.45,.12,.45,0,spellColor);
                world.playSound(next,Sound.ENTITY_EVOKER_CAST_SPELL,.6f,.8f);
                showFangs(caster,next);
                for(Entity entity:world.getNearbyEntities(next,1.15,2,1.15)){
                    if(!(entity instanceof LivingEntity living)||!plugin.usableTarget(caster,living)||!hit.add(entity.getUniqueId()))continue;
                    int marks=finalWave?consumeHex(living,caster):consumeHex(living,caster);
                    damage(living,caster,damage+(marks>=3&&finalWave?configD("hexcaller.dominion.hexbound-bonus-health-points",1):0),!finalWave);
                    if(!finalWave && marks>=3)hexRing(caster,living.getLocation().clone());
                }
                step++;
            }
        }.runTaskTimer(plugin,7L,2L);
    }
    private void hexRing(Player caster,Location center){
        World world=center.getWorld();
        for(int i=0;i<12;i++){
            double angle=i*Math.PI/6;
            Location spot=center.clone().add(Math.cos(angle)*2,.15,Math.sin(angle)*2);
            world.spawnParticle(Particle.DUST,spot,12,.3,.08,.3,0,hexColor);
        }
        Bukkit.getScheduler().runTaskLater(plugin,()->{
            if(!caster.isOnline())return;
            Set<UUID> struck=new HashSet<>();
            for(int i=0;i<12;i++){
                double angle=i*Math.PI/6;
                Location spot=center.clone().add(Math.cos(angle)*2,0,Math.sin(angle)*2);
                showFangs(caster,spot);
                for(Entity entity:world.getNearbyEntities(spot,1,1.6,1)){
                    if(entity instanceof LivingEntity target && plugin.usableTarget(caster,target)
                            && struck.add(entity.getUniqueId()))
                        damage(target,caster,configD("hexcaller.fangs.hexbound-ring-damage-health-points",2),false);
                }
            }
        },12L);
    }
    private void fangs(Player caster){
        if(!ready(caster,"hexcaller.fangs",20))return;
        Vector direction=caster.getLocation().getDirection().setY(0);
        if(direction.lengthSquared()<.01)direction=new Vector(0,0,1);
        Location at=caster.getLocation().clone();
        caster.getWorld().playSound(at,Sound.ENTITY_EVOKER_PREPARE_ATTACK,1.3f,.8f);
        fangLine(caster,at,direction.normalize(),Math.max(1,configI("hexcaller.fangs.range-blocks",12)),configD("hexcaller.fangs.damage-health-points",3),false);
    }
    private LivingEntity findTarget(Player caster,double distance){
        LivingEntity best=null;double bestScore=Double.MAX_VALUE;
        Location eye=caster.getEyeLocation();Vector direction=eye.getDirection();
        for(Entity entity:caster.getNearbyEntities(distance,distance,distance)){
            if(!(entity instanceof LivingEntity target)||!plugin.usableTarget(caster,target))continue;
            Vector diff=target.getEyeLocation().toVector().subtract(eye.toVector());
            if(diff.lengthSquared()>.01 && diff.length()<=distance && diff.normalize().dot(direction)>.87){
                double value=caster.getLocation().distanceSquared(target.getLocation());
                if(value<bestScore){bestScore=value;best=target;}
            }
        }return best;
    }
    private void summonVex(Player caster,int durationTicks){
        World world=caster.getWorld();LivingEntity priority=findTarget(caster,18);
        for(int i=0;i<2;i++){
            Location spot=caster.getLocation().clone().add(i==0?1.1:-1.1,1,1);
            Vex vex=world.spawn(spot,Vex.class,entity->{entity.addScoreboardTag(MOB_TAG);entity.setRemoveWhenFarAway(false);});
            vexes.add(vex.getUniqueId());vexSummoners.put(vex.getUniqueId(),caster.getUniqueId());
            if(priority!=null)vex.setTarget(priority);
            world.spawnParticle(Particle.DUST,spot,42,.6,.6,.6,0,spellColor);
            world.playSound(spot,Sound.ENTITY_EVOKER_PREPARE_SUMMON,1f,1.15f);
            Bukkit.getScheduler().runTaskLater(plugin,()->{if(vex.isValid())vex.remove();vexes.remove(vex.getUniqueId());vexSummoners.remove(vex.getUniqueId());},durationTicks);
        }
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void vexDamage(EntityDamageByEntityEvent event){
        if(!event.getDamager().getScoreboardTags().contains(MOB_TAG))return;
        UUID casterId=vexSummoners.get(event.getDamager().getUniqueId());
        Player caster=casterId==null?null:Bukkit.getPlayer(casterId);
        if(caster==null||!caster.isOnline()||!plugin.usableTarget(caster,event.getEntity())){
            event.setCancelled(true);return;
        }
        event.setDamage(Math.min(event.getDamage(),configD("hexcaller.vexbound.max-damage-health-points",2.0)));
        if(event.getEntity() instanceof LivingEntity target)markHex(target,caster);
    }
    @EventHandler(priority=EventPriority.HIGH)
    public void vexTarget(EntityTargetLivingEntityEvent event){
        if(!event.getEntity().getScoreboardTags().contains(MOB_TAG))return;
        if(event.getTarget()==null)return;
        UUID summonOwner=vexSummoners.get(event.getEntity().getUniqueId());
        Player owner=summonOwner==null?null:Bukkit.getPlayer(summonOwner);
        if(owner==null||!plugin.usableTarget(owner,event.getTarget())){event.setCancelled(true);return;}
        if(!(event.getTarget() instanceof Player target))return;
        // Only target combat-eligible players; never the summoner or creative/spectator players.
        if((summonOwner!=null&&summonOwner.equals(target.getUniqueId()))
                ||target.getGameMode()==GameMode.CREATIVE||target.getGameMode()==GameMode.SPECTATOR)
            event.setCancelled(true);
    }
    private void vexbound(Player caster){if(!ready(caster,"hexcaller.vexbound",35))return;summonVex(caster,160);}
    private void dominion(Player caster){
        if(!ready(caster,"hexcaller.dominion",65))return;
        World world=caster.getWorld();Location center=caster.getLocation().clone();
        final double radius=configD("hexcaller.dominion.radius-blocks",10);
        world.playSound(center,Sound.ENTITY_EVOKER_PREPARE_ATTACK,1.5f,.55f);
        summonVex(caster,160);
        new BukkitRunnable(){int age=0;int wave=0;
            @Override public void run(){
                if(!caster.isOnline()||age>160){cancel();return;}
                for(int i=0;i<48;i++){
                    double a=i*Math.PI/24+age*.015;
                    Location rim=center.clone().add(Math.cos(a)*radius,.15,Math.sin(a)*radius);
                    world.spawnParticle(Particle.DUST,rim,1,0,0,0,0,(i%3==0)?spellColor:hexColor);
                }
                if(age==20||age==60||age==100){
                    int thisWave=wave++;
                    world.playSound(center,Sound.ENTITY_EVOKER_CAST_SPELL,1.6f,.72f);
                    // Warn where fangs will emerge; leave gaps for counterplay.
                    List<Location> marks=new ArrayList<>();
                    double rot=thisWave*.32;
                    for(int i=0;i<12;i++){
                        double a=i*Math.PI/6+rot;
                        double r=3+(i%3)*2.4;
                        Location mark=center.clone().add(Math.cos(a)*r,.1,Math.sin(a)*r);
                        marks.add(mark);
                        world.spawnParticle(Particle.DUST,mark,22,.48,.1,.48,0,spellColor);
                    }
                    Bukkit.getScheduler().runTaskLater(plugin,()->{
                        if(!caster.isOnline())return;
                        Set<UUID> hit=new HashSet<>();
                        for(Location mark:marks){
                            showFangs(caster,mark);
                            for(Entity entity:world.getNearbyEntities(mark,1.45,2.5,1.45)){
                                if(!(entity instanceof LivingEntity target)||!plugin.usableTarget(caster,target)||!hit.add(entity.getUniqueId()))continue;
                                int marksCount=thisWave==2?consumeHex(target,caster):0;
                                double damage=configD("hexcaller.dominion.wave-damage-health-points",2);
                                if(thisWave==2)damage+=configD("hexcaller.dominion.final-bonus-health-points",3)
                                        +(marksCount>=3?configD("hexcaller.dominion.hexbound-bonus-health-points",1):0);
                                damage(target,caster,damage,thisWave!=2);
                            }
                        }
                    },9L);
                }
                age+=5;
            }
        }.runTaskTimer(plugin,0L,5L);
    }
}
