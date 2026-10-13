package ru.moonlightproject.dev.selene.paper;

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
import org.bukkit.plugin.messaging.PluginMessageListener;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class SelenePaper extends JavaPlugin implements Listener, PluginMessageListener {
 private static final String SESSION_CHANNEL="selene:session";
 private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
 private final AuthFlow flow=new AuthFlow();
 private InventoryProtection inventoryProtection; private AuthGateway gateway; private boolean standalone,captcha,dialogCapable,packetEventsEnabled;
 private static final class Session {
  final Player player; final Object inventoryConnection; final Location origin; final AuthSession auth;
  MapView map; final Map<PotionEffectType,PotionEffect> previousEffects=new HashMap<>();
  Session(Player p,Object connection,AuthSession auth){player=p;inventoryConnection=connection;origin=p.getLocation().clone();this.auth=auth;}
 }
 @Override public void onEnable(){
  try {
   saveDefaultConfig();
   if(!"AFTER_JOIN".equals(getConfig().getString("stage")) || !"MAP".equals(getConfig().getString("captcha-view")))throw new IllegalArgumentException("Only AFTER_JOIN with MAP captcha is implemented");
   String role=getConfig().getString("role",""); if(!Set.of("STANDALONE","PROXY_AUTH").contains(role))throw new IllegalArgumentException("Invalid role"); standalone=role.equals("STANDALONE");
   String packetMode=getConfig().getString("packet-events-mode","AUTO").toUpperCase(Locale.ROOT);
   if(!Set.of("AUTO","ENABLED","DISABLED").contains(packetMode))throw new IllegalArgumentException("packet-events-mode must be AUTO, ENABLED or DISABLED");
   packetEventsEnabled=packetMode.equals("ENABLED")||(packetMode.equals("AUTO")&&standalone);
   if(packetEventsEnabled&&!getServer().getPluginManager().isPluginEnabled("packetevents"))throw new IllegalStateException("PacketEvents is enabled but unavailable");
   if(standalone&&!packetEventsEnabled)getLogger().warning("PacketEvents is disabled in STANDALONE mode; the client can receive the real inventory before authentication");
   String pack=getConfig().getString("resource-pack-provider","NONE");
   if(!Set.of("NONE","Oraxen","ItemsAdder").contains(pack) || (!pack.equals("NONE")&&getServer().getPluginManager().getPlugin(pack)==null))throw new IllegalArgumentException("Configured external pack manager is unavailable");
   if(packetEventsEnabled)inventoryProtection=new PacketEventsInventoryProtection();
   String file=standalone?"standalone.properties":"relay.properties";
   if(!new File(getDataFolder(),file).exists()){saveResource(file,false);getLogger().warning("Created "+file+"; configure its TLS identity and trust before Selene can accept players");}
   Properties props=roleProperties(file,standalone);
   var tls=(!standalone || required(props,"endpoint").startsWith("tls:"))?tlsContext(file,props):null;
   gateway=standalone?BackendGateway.connect(required(props,"endpoint"),tls,strictBoolean(props,"allow-loopback-plaintext"),Duration.ofSeconds(5)):new RelayGateway(tls,required(props,"host"),positiveInt(props,"port"),Duration.ofSeconds(5),32);
   if(standalone)getLogger().info("Selene STANDALONE backend endpoint: "+required(props,"endpoint"));
   else getLogger().info("Selene PROXY_AUTH relay endpoint: "+required(props,"host")+":"+positiveInt(props,"port")+" (must match Velocity relay-bind)");
   captcha=standalone&&strictBoolean(props,"captcha-required");
   try{Class.forName("io.papermc.paper.dialog.Dialog");dialogCapable=true;}catch(ClassNotFoundException ignored){}
   getServer().getPluginManager().registerEvents(this,this);
   if(!standalone){getServer().getMessenger().registerIncomingPluginChannel(this,SESSION_CHANNEL,this);getServer().getMessenger().registerOutgoingPluginChannel(this,SESSION_CHANNEL);}
   for(String command:List.of("login","register")){getCommand(command).setExecutor(this);getCommand(command).setTabCompleter((s,c,a,args)->List.of());}
   // Reload is unsupported: no existing connection may inherit authentication.
   for(Player p:Bukkit.getOnlinePlayers())p.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));
  }catch(Exception | LinkageError e){String reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();getLogger().log(Level.SEVERE,"Selene could not initialize securely: "+reason+". Stopping server.",e);Bukkit.shutdown();}
 }
 private javax.net.ssl.SSLContext tlsContext(String file,Properties props)throws Exception{
  Path key=new File(getDataFolder(),required(props,"key-store")).toPath();Path trust=new File(getDataFolder(),required(props,"trust-store")).toPath();
  String keyEnv=required(props,"key-password-env"),trustEnv=required(props,"trust-password-env");List<String> missing=new ArrayList<>();
  if(!Files.isRegularFile(key))missing.add("file "+key.toAbsolutePath());if(!Files.isRegularFile(trust))missing.add("file "+trust.toAbsolutePath());
  if(System.getenv(keyEnv)==null||System.getenv(keyEnv).isBlank())missing.add("environment variable "+keyEnv);
  if(System.getenv(trustEnv)==null||System.getenv(trustEnv).isBlank())missing.add("environment variable "+trustEnv);
  if(!missing.isEmpty())throw new IllegalStateException("TLS setup in plugins/Selene/"+file+" is incomplete; missing "+String.join(", ",missing));
  return TlsContexts.load(key,keyEnv,trust,trustEnv);
 }
 private Properties roleProperties(String file,boolean standalone)throws IOException{
  Properties props=new Properties();try(var in=new FileInputStream(new File(getDataFolder(),file))){props.load(in);}
  Set<String> allowed=standalone
   ?Set.of("endpoint","allow-loopback-plaintext","key-store","key-password-env","trust-store","trust-password-env","captcha-required")
   :Set.of("host","port","key-store","key-password-env","trust-store","trust-password-env");
  for(String key:props.stringPropertyNames())if(!allowed.contains(key))throw new IllegalArgumentException("Unknown "+file+" setting: "+key);
  for(String key:allowed)required(props,key);
  return props;
 }
 private static String required(Properties props,String key){String value=props.getProperty(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing setting: "+key);return value.trim();}
 private static boolean strictBoolean(Properties props,String key){String value=required(props,key);if(!value.equalsIgnoreCase("true")&&!value.equalsIgnoreCase("false"))throw new IllegalArgumentException("Setting must be true or false: "+key);return Boolean.parseBoolean(value);}
 private static int positiveInt(Properties props,String key){int value=Integer.parseInt(required(props,key));if(value<1||value>65535)throw new IllegalArgumentException("Setting must be a valid port: "+key);return value;}
 @Override public void onDisable(){
  for(Session s:sessions.values())s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));
 sessions.clear();if(gateway!=null)gateway.close();
  getServer().getMessenger().unregisterIncomingPluginChannel(this,SESSION_CHANNEL,this);
  getServer().getMessenger().unregisterOutgoingPluginChannel(this,SESSION_CHANNEL);
  // A standalone firewall stays registered while connections may still be flushing during shutdown.
 }
 private String msg(String key){return getConfig().getString("messages."+key,getConfig().getString("messages.unavailable",""));}
 private boolean locked(Player p){Session s=sessions.get(p.getUniqueId());return s==null||s.auth.view().stage()!=AuthStage.AUTHENTICATED;}
 @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){
  Player p=e.getPlayer();Object connection=packetEventsEnabled?inventoryProtection.open(p):null;
  if((packetEventsEnabled&&connection==null)||gateway==null){p.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
  long timeoutMillis=Math.max(10,getConfig().getLong("timeout-seconds",120))*1000;
  AuthSession auth=flow.open(new ConnectionKey(p.getUniqueId(),p),System.currentTimeMillis()+timeoutMillis);
  Session s=new Session(p,connection,auth);sessions.put(p.getUniqueId(),s);
  applyPresentation(s);
  if(getConfig().getBoolean("auth-location.enabled")){
   World w=Bukkit.getWorld(getConfig().getString("auth-location.world",""));
   if(w==null){p.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
   p.teleport(new Location(w,getConfig().getDouble("auth-location.x"),getConfig().getDouble("auth-location.y"),getConfig().getDouble("auth-location.z")));
  }
  Bukkit.getScheduler().runTaskLater(this,()->{if(current(s)&&s.auth.view().stage()!=AuthStage.AUTHENTICATED)p.kick(net.kyori.adventure.text.Component.text(msg("timeout")));},timeoutMillis/50);
  if(standalone){flow.bindToken(s.auth,UUID.randomUUID().toString());request(s,"begin","","");}
 }
 @Override public void onPluginMessageReceived(String channel,Player p,byte[] message){
  if(standalone||!channel.equals(SESSION_CHANNEL)||message.length!=16)return;
  Session s=sessions.get(p.getUniqueId());if(s==null||s.player!=p)return;
  var bytes=java.nio.ByteBuffer.wrap(message);String token=new UUID(bytes.getLong(),bytes.getLong()).toString();
  String existing=s.auth.view().sessionId();if(!existing.isEmpty()&&!existing.equals(token))return;
  boolean first=existing.isEmpty();if(first&&!flow.bindToken(s.auth,token))return;p.sendPluginMessage(this,SESSION_CHANNEL,message);if(first)request(s,"begin","","");
 }
 private boolean current(Session s){return sessions.get(s.player.getUniqueId())==s&&flow.isCurrent(s.auth)&&s.player.isOnline();}
 private AuthRequest dto(Session s,String op,String password,String answer){return new AuthRequest(op,s.auth.view().sessionId(),s.player.getName(),s.player.getAddress().getAddress().getHostAddress(),password,answer,standalone&&Bukkit.getOnlineMode(),captcha);}
 private void request(Session s,String op,String password,String answer){
  String requestSession=s.auth.view().sessionId();gateway.call(dto(s,op,password,answer)).whenComplete((reply,error)->{
   if(!isEnabled())return;
   Bukkit.getScheduler().runTask(this,()->{
    if(!current(s))return;
    if(error!=null||reply==null){s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
    if(!flow.accept(s.auth,requestSession,reply,System.currentTimeMillis())){s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));return;}
    switch(reply.status()){
     case "AUTHENTICATED" -> {if(packetEventsEnabled)inventoryProtection.authenticate(s.inventoryConnection);restorePresentation(s);releaseMap(s);s.player.updateInventory();s.player.teleport(s.origin);s.player.sendMessage(msg("AUTHENTICATED"));}
     case "CAPTCHA" -> {try{showMap(s,reply.image());s.player.sendMessage(msg("CAPTCHA"));}catch(Exception ex){s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));}}
     case "LOGIN","REGISTER" -> {String text=msg(reply.status());s.player.sendMessage(text);if(dialogCapable&&getConfig().getBoolean("dialogs"))try{AuthNotice.show(s.player,msg("dialog-title"),text,msg("dialog-close"));}catch(LinkageError ignored){}}
     case "CAPTCHA_INVALID","INVALID_CREDENTIALS","ACCOUNT_EXISTS","RATE_LIMITED","BUSY" -> s.player.sendMessage(msg(reply.status()));
     default -> {String key=AuthInputValidation.messageKeyForStatus(reply.status());if(key==null)s.player.kick(net.kyori.adventure.text.Component.text(msg("unavailable")));else if(reply.status().equals("EXPIRED"))s.player.kick(net.kyori.adventure.text.Component.text(msg(key)));else s.player.sendMessage(msg(key));}
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
  if(packetEventsEnabled){
   // Virtual off-hand: never mutate or clear the persistent inventory.
   inventoryProtection.sendVirtualOffhand(s.inventoryConnection,item);
  }else s.player.getInventory().setItemInOffHand(item);
  s.player.sendMap(view);
 }
 private void releaseMap(Session s){if(s.map!=null){
  if(!packetEventsEnabled){ItemStack held=s.player.getInventory().getItemInOffHand();if(held.getItemMeta() instanceof MapMeta meta&&meta.getMapView()==s.map)s.player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));}
  s.map.getRenderers().forEach(s.map::removeRenderer);s.map=null;
 }}
 @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
  if(!(sender instanceof Player p))return true;Session s=sessions.get(p.getUniqueId());if(s==null||s.auth.view().stage()==AuthStage.AUTHENTICATED)return true;
  String name=cmd.getName();
  if((name.equals("register")&&args.length!=2)||(!name.equals("register")&&args.length!=1)){p.sendMessage(msg("usage"));return true;}
  AuthInputValidation.ValidationResult validation=name.equals("register")
   ?AuthInputValidation.registration(args[0],args[1],8,256)
   :AuthInputValidation.password(args[0],8,256);
  if(!validation.valid()){p.sendMessage(msg(validation.messageKey()));return true;}
  boolean accepted=name.equals("register")?flow.submitRegistration(s.auth,args[0],args[1]):flow.submitLogin(s.auth,args[0]);
  if(!accepted){p.sendMessage(msg("not-ready"));return true;}
  request(s,name,args[0],"");return true;
 }
 @EventHandler public void quit(PlayerQuitEvent e){Session s=sessions.get(e.getPlayer().getUniqueId());if(s!=null&&s.player==e.getPlayer()&&sessions.remove(e.getPlayer().getUniqueId(),s)){if(packetEventsEnabled)inventoryProtection.close(s.inventoryConnection);restorePresentation(s);releaseMap(s);gateway.call(dto(s,"close","",""));flow.close(s.auth);}}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){if(locked(e.getPlayer())&&e.hasChangedPosition())e.setTo(e.getFrom());}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(locked(e.getPlayer())&&e.getCause()!=PlayerTeleportEvent.TeleportCause.PLUGIN)e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){if(locked(e.getPlayer())){String c=e.getMessage().split(" ",2)[0].substring(1);if(!PacketPolicy.allowCommand(c)){e.setCancelled(true);e.getPlayer().sendMessage(msg("blocked"));}}}
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
