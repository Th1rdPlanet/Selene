package ru.moonlightproject.dev.selene.paper;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import ru.moonlightproject.dev.selene.core.*;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.*;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import javax.imageio.ImageIO;
import java.io.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class SelenePaper extends JavaPlugin implements Listener {
 private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
 private InventoryFirewall firewall; private AuthGateway gateway; private boolean standalone,captcha,dialogCapable;
 private static final class Session {
  final Player player; final User user; final Location origin; String id=UUID.randomUUID().toString();
  boolean authenticated,busy; MapView map; final Map<PotionEffectType,PotionEffect> previousEffects=new HashMap<>();
  Session(Player p,User u){player=p;user=u;origin=p.getLocation().clone();}
 }
 @Override public void onEnable(){
  try {
   saveDefaultConfig();
   if(!getServer().getPluginManager().isPluginEnabled("packetevents"))throw new IllegalStateException("PacketEvents is required");
   if(!"AFTER_JOIN".equals(getConfig().getString("stage")) || !"MAP".equals(getConfig().getString("captcha-view")))throw new IllegalArgumentException("Only AFTER_JOIN with MAP captcha is implemented");
   String role=getConfig().getString("role",""); if(!Set.of("STANDALONE","PROXY_AUTH").contains(role))throw new IllegalArgumentException("Invalid role"); standalone=role.equals("STANDALONE");
   String pack=getConfig().getString("resource-pack-provider","NONE");
   if(!Set.of("NONE","Oraxen","ItemsAdder").contains(pack) || (!pack.equals("NONE")&&getServer().getPluginManager().getPlugin(pack)==null))throw new IllegalArgumentException("Configured external pack manager is unavailable");
   firewall=new InventoryFirewall(); PacketEvents.getAPI().getEventManager().registerListener(firewall);
   String file=standalone?"standalone.properties":"relay.properties"; if(!new File(getDataFolder(),file).exists())saveResource(file,false);
   Properties props=new Properties();try(var in=new FileInputStream(new File(getDataFolder(),file))){props.load(in);}
   var tls=(!standalone || props.getProperty("endpoint","").startsWith("tls:"))?TlsContexts.load(new File(getDataFolder(),props.getProperty("key-store")).toPath(),props.getProperty("key-password-env"),new File(getDataFolder(),props.getProperty("trust-store")).toPath(),props.getProperty("trust-password-env")):null;
   gateway=standalone?BackendGateway.connect(props.getProperty("endpoint"),tls,Boolean.parseBoolean(props.getProperty("allow-loopback-plaintext","false")),Duration.ofSeconds(5)):new RelayGateway(tls,props.getProperty("host"),Integer.parseInt(props.getProperty("port")),Duration.ofSeconds(5),32);
   captcha=standalone&&Boolean.parseBoolean(props.getProperty("captcha-required","true"));
   try{Class.forName("io.papermc.paper.dialog.Dialog");dialogCapable=true;}catch(ClassNotFoundException ignored){}
   getServer().getPluginManager().registerEvents(this,this);
   for(String command:List.of("login","register","captcha")){getCommand(command).setExecutor(this);getCommand(command).setTabCompleter((s,c,a,args)->List.of());}
   // Reload is unsupported: no existing connection may inherit authentication.
   for(Player p:Bukkit.getOnlinePlayers())p.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));
  }catch(Exception | LinkageError e){getLogger().severe("Selene could not initialize securely ("+e.getClass().getSimpleName()+"). Stopping server."); Bukkit.shutdown();}
 }
 @Override public void onDisable(){
  for(Session s:sessions.values())s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));
  sessions.clear();if(gateway!=null)gateway.close();
  // Do not unregister firewall while connections may still be flushing during shutdown.
 }
 private String msg(String key){return getConfig().getString("messages."+key,getConfig().getString("messages.unavailable",""));}
 private boolean locked(Player p){Session s=sessions.get(p.getUniqueId());return s==null||!s.authenticated;}
 @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){
  Player p=e.getPlayer(); User u=PacketEvents.getAPI().getPlayerManager().getUser(p);
  if(u==null||gateway==null){p.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
  Session s=new Session(p,u);sessions.put(p.getUniqueId(),s);firewall.gate.open(u);
  applyPresentation(s);
  if(getConfig().getBoolean("auth-location.enabled")){
   World w=Bukkit.getWorld(getConfig().getString("auth-location.world",""));
   if(w==null){p.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
   p.teleport(new Location(w,getConfig().getDouble("auth-location.x"),getConfig().getDouble("auth-location.y"),getConfig().getDouble("auth-location.z")));
  }
  Bukkit.getScheduler().runTaskLater(this,()->{if(current(s)&&!s.authenticated)p.kick(net.kyori.adventure.text.Component.text(msg("timeout")));},Math.max(10,getConfig().getLong("timeout-seconds",120))*20);
  request(s,"begin","","");
 }
 private boolean current(Session s){return sessions.get(s.player.getUniqueId())==s&&s.player.isOnline();}
 private AuthRequest dto(Session s,String op,String password,String answer){return new AuthRequest(op,s.id,s.player.getName(),s.player.getAddress().getAddress().getHostAddress(),password,answer,standalone&&Bukkit.getOnlineMode(),captcha);}
 private void request(Session s,String op,String password,String answer){
  if(s.busy||s.authenticated)return;s.busy=true;
  gateway.call(dto(s,op,password,answer)).whenComplete((reply,error)->{
   if(!isEnabled())return;
   Bukkit.getScheduler().runTask(this,()->{
    if(!current(s))return;s.busy=false;
    if(error!=null||reply==null){s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
    if(!standalone&&op.equals("begin")&&!reply.session().isEmpty())s.id=reply.session();
    if(!reply.session().equals(s.id)){s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
    switch(reply.status()){
     case "AUTHENTICATED" -> {s.authenticated=true;firewall.gate.authenticate(s.user);restorePresentation(s);s.player.updateInventory();s.player.teleport(s.origin);s.player.sendMessage(msg("AUTHENTICATED"));releaseMap(s);}
     case "CAPTCHA" -> {try{showMap(s,reply.image());s.player.sendMessage(msg("CAPTCHA"));}catch(Exception ex){s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));}}
     case "LOGIN","REGISTER" -> {String text=msg(reply.status());s.player.sendMessage(text);if(dialogCapable&&getConfig().getBoolean("dialogs")&&s.user.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_6))try{AuthNotice.show(s.player,msg("dialog-title"),text,msg("dialog-close"));}catch(LinkageError ignored){}}
     case "CAPTCHA_INVALID","INVALID_CREDENTIALS","ACCOUNT_EXISTS","RATE_LIMITED","BUSY" -> s.player.sendMessage(msg(reply.status()));
     default -> s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));
    }
   });
  });
 }
 private void showMap(Session s,String encoded)throws IOException {
  if(encoded.length()>240000)throw new IOException("Oversized image");
  var image=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
  if(image==null||image.getWidth()>512||image.getHeight()>512)throw new IOException("Invalid image");
  releaseMap(s);MapView view=Bukkit.createMap(s.player.getWorld());s.map=view;view.getRenderers().forEach(view::removeRenderer);
  view.addRenderer(new MapRenderer(true){@Override public void render(MapView v,MapCanvas c,Player p){if(p.equals(s.player))c.drawImage(0,0,MapPalette.resizeImage(image));}});
  ItemStack item=new ItemStack(Material.FILLED_MAP);MapMeta meta=(MapMeta)item.getItemMeta();meta.setMapView(view);item.setItemMeta(meta);
  // Virtual off-hand: never mutate or clear the persistent inventory.
  s.user.sendPacketSilently(new WrapperPlayServerSetSlot(0,0,45,SpigotConversionUtil.fromBukkitItemStack(item)));
  s.player.sendMap(view);
 }
 private void releaseMap(Session s){if(s.map!=null){s.map.getRenderers().forEach(s.map::removeRenderer);s.map=null;}}
 @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
  if(!(sender instanceof Player p))return true;Session s=sessions.get(p.getUniqueId());if(s==null||s.authenticated)return true;
  String name=cmd.getName();
  if((name.equals("register")&&args.length!=2)||(!name.equals("register")&&args.length!=1)||Arrays.stream(args).anyMatch(a->a.length()>256)){p.sendMessage(msg("usage"));return true;}
  if(name.equals("register")&&!args[0].equals(args[1])){p.sendMessage(msg("mismatch"));return true;}
  if(name.equals("captcha")&&!args[0].matches("[0-9]{1,12}")){p.sendMessage(msg("usage"));return true;}
  request(s,name.equals("captcha")?"answer":name,name.equals("captcha")?"":args[0],name.equals("captcha")?args[0]:"");return true;
 }
 @EventHandler public void quit(PlayerQuitEvent e){Session s=sessions.remove(e.getPlayer().getUniqueId());if(s!=null){firewall.gate.close(s.user);restorePresentation(s);releaseMap(s);gateway.call(dto(s,"close","",""));}}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){if(locked(e.getPlayer())&&e.hasChangedPosition())e.setTo(e.getFrom());}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(locked(e.getPlayer())&&e.getCause()!=PlayerTeleportEvent.TeleportCause.PLUGIN)e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){if(locked(e.getPlayer())){String c=e.getMessage().split(" ",2)[0].toLowerCase(Locale.ROOT);if(!Set.of("/login","/l","/register","/reg","/captcha","/selene:login","/selene:register","/selene:captcha").contains(c)){e.setCancelled(true);e.getPlayer().sendMessage(msg("blocked"));}}}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void chat(AsyncPlayerChatEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interact(PlayerInteractEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void interactEntity(PlayerInteractEntityEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void damage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&locked(p))e.setCancelled(true);if(e instanceof EntityDamageByEntityEvent hit&&hit.getDamager() instanceof Player p&&locked(p))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drop(PlayerDropItemEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void pickup(EntityPickupItemEvent e){if(e.getEntity() instanceof Player p&&locked(p))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void click(InventoryClickEvent e){if(e.getWhoClicked() instanceof Player p&&locked(p))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void drag(InventoryDragEvent e){if(e.getWhoClicked() instanceof Player p&&locked(p))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void open(InventoryOpenEvent e){if(e.getPlayer() instanceof Player p&&locked(p))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void swap(PlayerSwapHandItemsEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void consume(PlayerItemConsumeEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void held(PlayerItemHeldEvent e){if(locked(e.getPlayer()))e.setCancelled(true);}
 private void applyPresentation(Session s){
  addOwnedEffect(s,PotionEffectType.BLINDNESS,"effects.blindness");
  addOwnedEffect(s,PotionEffectType.DARKNESS,"effects.darkness");
  for(Player other:Bukkit.getOnlinePlayers()){
   if(other.equals(s.player))continue;
   if(getConfig().getBoolean("visibility.hide-player-from-others"))other.hidePlayer(this,s.player);
   if(getConfig().getBoolean("visibility.hide-others-from-player"))s.player.hidePlayer(this,other);
  }
 }
 private void addOwnedEffect(Session s,PotionEffectType type,String path){
  if(!getConfig().getBoolean(path))return;
  PotionEffect previous=s.player.getPotionEffect(type);if(previous!=null)s.previousEffects.put(type,previous);
  s.player.addPotionEffect(new PotionEffect(type,Integer.MAX_VALUE,0,false,false,false));
 }
 private void restorePresentation(Session s){
  for(PotionEffectType type:List.of(PotionEffectType.BLINDNESS,PotionEffectType.DARKNESS)){
   if((type==PotionEffectType.BLINDNESS&&getConfig().getBoolean("effects.blindness"))||(type==PotionEffectType.DARKNESS&&getConfig().getBoolean("effects.darkness"))){
    s.player.removePotionEffect(type);PotionEffect previous=s.previousEffects.get(type);if(previous!=null)s.player.addPotionEffect(previous);
   }
  }
  for(Player other:Bukkit.getOnlinePlayers()){other.showPlayer(this,s.player);s.player.showPlayer(this,other);}
 }
}
