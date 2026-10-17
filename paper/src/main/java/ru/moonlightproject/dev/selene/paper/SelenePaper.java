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
 private static final String MANAGEMENT_CHANNEL="selene:management";
 private final Map<UUID,Session> sessions=new ConcurrentHashMap<>();
 private final Map<UUID,Boolean> resourcePackStatuses=new ConcurrentHashMap<>();
 private final AuthFlow flow=new AuthFlow();
 private final SecureChatInput secureInput=new SecureChatInput();
 private org.bukkit.configuration.file.YamlConfiguration settings;
 private InventoryProtection inventoryProtection; private AuthGateway gateway; private AccountAdminGateway adminGateway; private AuthMessages messages; private LimboManager limbo; private PlayerPresentation presentation; private ResourcePackDelivery resourcePackDelivery; private int presentationTask=-1; private boolean standalone,captcha,dialogCapable,packetEventsEnabled;
 private static final class Session {
  final Player player; final Object inventoryConnection; final AuthSession auth; final PresentationSnapshot snapshot;
  MapView map;
  ItemStack previousMainHand;
  String captchaImage="",captchaGlyphs=""; Boolean packLoaded; int captchaDisplayGeneration;
  ManagementRequest management; boolean managementStarted;
  Session(Player p,Object connection,AuthSession auth,PresentationSnapshot snapshot){player=p;inventoryConnection=connection;this.auth=auth;this.snapshot=snapshot;}
 }
 @Override public void onEnable(){
  try {
   settings=PaperConfiguration.load(getDataFolder().toPath(),this::getResource);
   PaperConfigValidator.validate(settings.getValues(true));
   Map<String,String> builtIn;
   try(var reader=new java.io.InputStreamReader(Objects.requireNonNull(getResource("languages/ru.json")),java.nio.charset.StandardCharsets.UTF_8)){
    builtIn=new com.google.gson.Gson().fromJson(reader,new com.google.gson.reflect.TypeToken<Map<String,String>>(){}.getType());
   }
   messages=new AuthMessages(LanguageCatalog.load(getDataFolder().toPath().resolve("languages"),settings.getString("language.default","ru"),settings.getBoolean("language.auto-detect",true),builtIn));
   for(String key:List.of("unavailable","timeout","blocked","usage","CAPTCHA","CAPTCHA_INVALID","captcha-pack-fallback","captcha-pack-required","LOGIN","REGISTER","AUTHENTICATED","TOTP_REQUIRED","TOTP_INVALID","RECOVERY_INVALID","totp-format","INVALID_CREDENTIALS","ACCOUNT_EXISTS","RATE_LIMITED","BUSY","password-too-short","password-too-long","password-mismatch","captcha-format","INVALID_REQUEST","EXPIRED","UNAVAILABLE","not-ready","secure-command-only","chat-login-prompt","chat-register-prompt","chat-confirm-prompt","chat-totp-prompt","dialog-required","admin-console-only","admin-use-velocity","admin-help","admin-invalid-username","admin-list-header","admin-list-row","admin-list-more","admin-info","admin-status-ok","admin-status-not-found","admin-status-invalid-request","admin-status-busy","admin-status-unavailable"))messages.component("messages."+key);
   if(settings.getBoolean("console.banner",true))for(String line:StartupBanner.lines(getPluginMeta().getVersion(),"Paper"))getLogger().info(line);
   getLogger().info(messages.plain("console.starting"));
   limbo=new LimboManager(this,Objects.requireNonNull(settings.getConfigurationSection("limbo"),"Missing configuration section: limbo"));limbo.load();
   presentation=new PlayerPresentation(this,messages,settings);
   String role=settings.getString("role",""); if(!Set.of("STANDALONE","PROXY_AUTH").contains(role))throw new IllegalArgumentException("Invalid role"); standalone=role.equals("STANDALONE");
   String stage=settings.getString("stage","");if(!Set.of("AFTER_JOIN","BEFORE_JOIN").contains(stage))throw new IllegalArgumentException("stage must be AFTER_JOIN or BEFORE_JOIN");
   if(stage.equals("BEFORE_JOIN")&&standalone)throw new IllegalArgumentException("stage BEFORE_JOIN requires role PROXY_AUTH; standalone Paper has no separate gameplay backend");
   String captchaView=settings.getString("captcha-view","").toUpperCase(Locale.ROOT);if(!Set.of("MAP","DIALOG_FONT").contains(captchaView))throw new IllegalArgumentException("captcha-view must be MAP or DIALOG_FONT");
   String packetMode=settings.getString("packet-events-mode","AUTO").toUpperCase(Locale.ROOT);
   if(!Set.of("AUTO","ENABLED","DISABLED").contains(packetMode))throw new IllegalArgumentException("packet-events-mode must be AUTO, ENABLED or DISABLED");
   packetEventsEnabled=packetMode.equals("ENABLED")||(packetMode.equals("AUTO")&&standalone);
   if(packetEventsEnabled&&!getServer().getPluginManager().isPluginEnabled("packetevents"))throw new IllegalStateException("PacketEvents is enabled but unavailable");
   if(standalone&&!packetEventsEnabled)getLogger().warning("PacketEvents is disabled in STANDALONE mode; the client can receive the real inventory before authentication");
   String pack=settings.getString("resource-pack-provider","NONE");
   if(!Set.of("NONE","Oraxen","ItemsAdder").contains(pack) || (!pack.equals("NONE")&&!getServer().getPluginManager().isPluginEnabled(pack)))throw new IllegalArgumentException("Configured external pack manager '"+pack+"' is unavailable or failed to enable");
   if(captchaView.equals("DIALOG_FONT")&&pack.equals("NONE"))throw new IllegalArgumentException("captcha-view DIALOG_FONT requires resource-pack-provider ItemsAdder or Oraxen");
   if(settings.getBoolean("captcha.resource-pack.enabled",false))resourcePackDelivery=ResourcePackDelivery.parse(
    settings.getString("captcha.resource-pack.id",""),settings.getString("captcha.resource-pack.url",""),settings.getString("captcha.resource-pack.sha1",""),settings.getString("captcha.resource-pack.prompt",""));
   if(packetEventsEnabled){inventoryProtection=new PacketEventsInventoryProtection();getLogger().info(messages.plain("console.packets-active",AuthMessages.literal("version",com.github.retrooper.packetevents.PacketEvents.getAPI().getVersion().toString())));}
   else getLogger().info(messages.plain("console.packets-inactive",AuthMessages.literal("role",role),AuthMessages.literal("mode",packetMode)));
   String file=standalone?"standalone.properties":"relay.properties";
   if(!new File(getDataFolder(),file).exists()){saveResource(file,false);getLogger().warning("Created "+file+"; configure its TLS identity and trust before Selene can accept players");}
   Properties props=roleProperties(file,standalone);
   var tls=(!standalone || required(props,"endpoint").startsWith("tls:"))?tlsContext(file,props):null;
   if(standalone){BackendGateway backend=BackendGateway.connect(required(props,"endpoint"),tls,strictBoolean(props,"allow-loopback-plaintext"),Duration.ofSeconds(5));gateway=backend;adminGateway=backend;}else gateway=new RelayGateway(tls,required(props,"host"),positiveInt(props,"port"),Duration.ofSeconds(5),32);
   if(standalone)getLogger().info(messages.plain("console.connection",AuthMessages.literal("target","Rust backend"),AuthMessages.literal("address",required(props,"endpoint"))));
   else getLogger().info(messages.plain("console.connection",AuthMessages.literal("target","Velocity relay"),AuthMessages.literal("address",required(props,"host")+":"+positiveInt(props,"port"))));
   captcha=standalone&&strictBoolean(props,"captcha-required");
   try{Class.forName("io.papermc.paper.dialog.Dialog");dialogCapable=settings.getBoolean("dialogs.enabled",true);}catch(ClassNotFoundException ignored){}
   getServer().getPluginManager().registerEvents(this,this);
   if(!standalone){
    getServer().getMessenger().registerIncomingPluginChannel(this,SESSION_CHANNEL,this);
    getServer().getMessenger().registerOutgoingPluginChannel(this,SESSION_CHANNEL);
    getServer().getMessenger().registerIncomingPluginChannel(this,MANAGEMENT_CHANNEL,this);
    getServer().getMessenger().registerOutgoingPluginChannel(this,MANAGEMENT_CHANNEL);
   }
   for(String command:List.of("login","register")){getCommand(command).setExecutor(this);getCommand(command).setTabCompleter((s,c,a,args)->List.of());}
   getCommand("selene").setExecutor(new AccountAdminCommand(this,standalone,adminGateway,messages));getCommand("selene").setTabCompleter((s,c,a,args)->List.of());
   presentationTask=Bukkit.getScheduler().scheduleSyncRepeatingTask(this,()->{long now=System.currentTimeMillis();for(Session session:sessions.values())if(current(session)&&session.auth.view().stage()!=AuthStage.AUTHENTICATED)presentation.render(session.player,session.snapshot,session.auth.view(),now);},20,20);
   getLogger().info(messages.plain("console.ready",AuthMessages.literal("role",role),AuthMessages.literal("stage",stage),AuthMessages.literal("world",settings.getString("limbo.world")),AuthMessages.literal("dialogs",Boolean.toString(dialogCapable))));
   // Reload is unsupported: no existing connection may inherit authentication.
   for(Player p:Bukkit.getOnlinePlayers())p.kick(msg(p,"unavailable"));
  }catch(Exception | LinkageError e){String reason=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();getLogger().severe("Startup failed: "+reason+". Check plugins/Selene configuration; the server will stop.");getLogger().log(Level.FINE,"Selene startup failure details",e);Bukkit.shutdown();}
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
  for(Session s:sessions.values())s.player.kick(msg(s.player,"unavailable"));
 sessions.clear();resourcePackStatuses.clear();if(presentationTask!=-1)Bukkit.getScheduler().cancelTask(presentationTask);if(gateway!=null)gateway.close();if(inventoryProtection!=null)inventoryProtection.shutdown();
  getServer().getMessenger().unregisterIncomingPluginChannel(this,SESSION_CHANNEL,this);
  getServer().getMessenger().unregisterOutgoingPluginChannel(this,SESSION_CHANNEL);
  getServer().getMessenger().unregisterIncomingPluginChannel(this,MANAGEMENT_CHANNEL,this);
  getServer().getMessenger().unregisterOutgoingPluginChannel(this,MANAGEMENT_CHANNEL);
 }
 private net.kyori.adventure.text.Component msg(Player player,String key){return messages.forLocale(player.getLocale()).component("messages."+key);}
 private boolean locked(Player p){Session s=sessions.get(p.getUniqueId());return s==null||s.auth.view().stage()!=AuthStage.AUTHENTICATED;}
 @EventHandler(priority=EventPriority.LOWEST) public void join(PlayerJoinEvent e){
  Player p=e.getPlayer();Object connection=packetEventsEnabled?inventoryProtection.open(p):null;
  if((packetEventsEnabled&&connection==null)||gateway==null){p.kick(msg(p,"unavailable"));return;}
  long timeoutMillis=Math.max(10,settings.getLong("timeout-seconds",120))*1000;
  AuthSession auth=flow.open(new ConnectionKey(p.getUniqueId(),p),System.currentTimeMillis()+timeoutMillis);
  PresentationSnapshot snapshot=presentation.begin(p);Session s=new Session(p,connection,auth,snapshot);s.packLoaded=resourcePackStatuses.get(p.getUniqueId());sessions.put(p.getUniqueId(),s);
  applyVisibility(s);limbo.enter(p);presentation.render(p,snapshot,auth.view(),System.currentTimeMillis());
  if(resourcePackDelivery!=null)Bukkit.getScheduler().runTask(this,()->sendResourcePack(s));
  Bukkit.getScheduler().runTaskLater(this,()->{if(current(s)&&s.auth.view().stage()!=AuthStage.AUTHENTICATED)p.kick(msg(p,"timeout"));},timeoutMillis/50);
  if(standalone){flow.bindToken(s.auth,UUID.randomUUID().toString());request(s,"begin","","");}
 }
 private void sendResourcePack(Session s){
  if(!current(s)||resourcePackDelivery==null)return;
  var prompt=net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(resourcePackDelivery.prompt());
  s.player.setResourcePack(resourcePackDelivery.id(),resourcePackDelivery.url(),resourcePackDelivery.sha1(),prompt,true);
  getLogger().info("Обязательный resource pack отправлен игроку "+s.player.getName()+".");
 }
 @Override public void onPluginMessageReceived(String channel,Player p,byte[] message){
  if(standalone)return;
  Session s=sessions.get(p.getUniqueId());if(s==null||s.player!=p)return;
  if(channel.equals(MANAGEMENT_CHANNEL)){
   ManagementRequest.decode(message,s.auth.view().sessionId()).ifPresent(request->{
    if(s.management==null){s.management=request;startManagement(s);}
   });
   return;
  }
  if(!channel.equals(SESSION_CHANNEL)||message.length!=16)return;
  var bytes=java.nio.ByteBuffer.wrap(message);String token=new UUID(bytes.getLong(),bytes.getLong()).toString();
  String existing=s.auth.view().sessionId();if(!existing.isEmpty()&&!existing.equals(token))return;
  boolean first=existing.isEmpty();if(first&&!flow.bindToken(s.auth,token))return;p.sendPluginMessage(this,SESSION_CHANNEL,message);
  if(first)Bukkit.getScheduler().runTask(this,()->{if(current(s)&&!s.managementStarted){if(s.management==null)request(s,"begin","","");else startManagement(s);}});
 }
 private boolean current(Session s){return sessions.get(s.player.getUniqueId())==s&&flow.isCurrent(s.auth)&&s.player.isOnline();}
 private AuthRequest dto(Session s,String op,String password,String answer){return dto(s,op,password,answer,"","");}
 private AuthRequest dto(Session s,String op,String password,String answer,String totp,String recovery){return new AuthRequest(op,s.auth.view().sessionId(),s.player.getName(),s.player.getAddress().getAddress().getHostAddress(),password,answer,standalone&&Bukkit.getOnlineMode(),captcha,totp,recovery);}
 private void request(Session s,String op,String password,String answer){
  request(s,op,password,answer,"","");
 }
 private void request(Session s,String op,String password,String answer,String totp,String recovery){
  String requestSession=s.auth.view().sessionId();gateway.call(dto(s,op,password,answer,totp,recovery)).whenComplete((reply,error)->{
   if(!isEnabled())return;
   Bukkit.getScheduler().runTask(this,()->{
    if(!current(s))return;
    if(error!=null||reply==null){s.player.kick(msg(s.player,"unavailable"));return;}
    if(!flow.accept(s.auth,requestSession,reply,System.currentTimeMillis())){s.player.kick(msg(s.player,"unavailable"));return;}
    switch(reply.status()){
     case "AUTHENTICATED" -> {secureInput.clear(s.player.getUniqueId());if(packetEventsEnabled)inventoryProtection.authenticate(s.inventoryConnection);presentation.restore(s.player,s.snapshot);restoreVisibility(s);releaseMap(s);s.player.updateInventory();limbo.exit(s.player,s.snapshot);s.player.sendMessage(msg(s.player,"AUTHENTICATED"));}
     case "CAPTCHA" -> {s.captchaImage=reply.image();s.captchaGlyphs=reply.captchaGlyphs();showCaptcha(s);}
     case "LOGIN","REGISTER" -> {s.player.sendMessage(msg(s.player,reply.status()));openCredentialInput(s,reply.status());}
     case "TOTP_REQUIRED" -> {s.player.sendMessage(msg(s.player,"TOTP_REQUIRED"));openTotpInput(s);}
     case "TOTP_INVALID","RECOVERY_INVALID" -> {s.player.sendMessage(msg(s.player,reply.status()));openTotpInput(s);}
     case "CAPTCHA_INVALID" -> {s.player.sendMessage(msg(s.player,reply.status()));showCaptcha(s);}
     case "RATE_LIMITED","BUSY" -> s.player.sendMessage(msg(s.player,reply.status()));
     case "INVALID_CREDENTIALS","ACCOUNT_EXISTS" -> {s.player.sendMessage(msg(s.player,reply.status()));openCredentialInput(s,"LOGIN");}
     default -> {String key=AuthInputValidation.messageKeyForStatus(reply.status());if(key==null)s.player.kick(msg(s.player,"unavailable"));else if(reply.status().equals("EXPIRED"))s.player.kick(msg(s.player,key));else s.player.sendMessage(msg(s.player,key));}
    }
   });
  });
 }
 private void startManagement(Session s){
  if(!current(s)||s.management==null||s.managementStarted)return;
  s.managementStarted=true;
  if(!dialogCapable){s.player.sendMessage(msg(s.player,"dialog-required"));completeManagement(s);return;}
  String operation=s.management.initialOperation();
  if(!operation.isEmpty())requestManagement(s,operation,"","");
  else openManagementVerification(s);
 }
 private void openManagementVerification(Session s){
  if(!current(s)||s.management==null)return;
  if(!dialogCapable){s.player.sendMessage(msg(s.player,"dialog-required"));return;}
  try{AuthDialogs.totp(s.player,messages.forLocale(s.player.getLocale()),settings,code->Bukkit.getScheduler().runTask(this,()->{
   boolean recovery=!AuthInputValidation.totp(code).valid();
   if(s.management.action()==ManagementRequest.Action.RECOVERY&&recovery){s.player.sendMessage(msg(s.player,"totp-format"));openManagementVerification(s);return;}
   if(!(recovery?AuthInputValidation.recovery(code):AuthInputValidation.totp(code)).valid()){s.player.sendMessage(msg(s.player,"totp-format"));openManagementVerification(s);return;}
   String operation=s.management.action()==ManagementRequest.Action.DISABLE?"totp_disable":"totp_recovery_regenerate";
   requestManagement(s,operation,recovery?"":code,recovery?code:"");
  }));}catch(LinkageError|RuntimeException error){s.player.sendMessage(msg(s.player,"dialog-required"));}
 }
 private void requestManagement(Session s,String operation,String totp,String recovery){
  String token=s.auth.view().sessionId();
  gateway.call(dto(s,operation,"","",totp,recovery)).whenComplete((reply,error)->{
   if(!isEnabled())return;Bukkit.getScheduler().runTask(this,()->{
    if(!current(s)||s.management==null||!token.equals(s.auth.view().sessionId()))return;
    if(error!=null||reply==null||!token.equals(reply.session())){s.player.sendMessage(msg(s.player,"UNAVAILABLE"));return;}
    handleManagementReply(s,reply);
   });
  });
 }
 private void handleManagementReply(Session s,AuthReply reply){
  AuthMessages localized=messages.forLocale(s.player.getLocale());
  switch(reply.status()){
   case "TOTP_SETUP" -> {
    try{
     boolean pack=!settings.getString("resource-pack-provider","NONE").equals("NONE")
      && (settings.getString("captcha.resource-pack-loading","MANUAL").equalsIgnoreCase("MANUAL")||Boolean.TRUE.equals(s.packLoaded));
     TotpPresentation view=TotpPresentation.setup(reply.totpUri(),reply.totpSecret(),pack,
      new QrOptions(settings.getInt("dialogs.totp-setup.qr-quiet-zone",4),settings.getInt("dialogs.totp-setup.qr-max-modules",177)));
     AuthDialogs.totpSetup(s.player,localized,settings,view,code->Bukkit.getScheduler().runTask(this,()->{
      if(!AuthInputValidation.totp(code).valid()){s.player.sendMessage(msg(s.player,"totp-format"));handleManagementReply(s,reply);return;}
      requestManagement(s,"totp_setup_confirm",code,"");
     }));
    }catch(RuntimeException|LinkageError failure){getLogger().warning("Could not show TOTP setup dialog for "+s.player.getName()+": "+failure.getClass().getSimpleName());s.player.sendMessage(msg(s.player,"UNAVAILABLE"));}
   }
   case "TOTP_RECOVERY" -> AuthDialogs.recovery(s.player,localized,settings,
    TotpPresentation.recovery(reply.recoveryCodes()),()->completeManagement(s));
   case "TOTP_STATUS" -> AuthDialogs.managementResult(s.player,localized,settings,
    reply.totpEnabled()?"dialogs.management-result.enabled":"dialogs.management-result.disabled",()->completeManagement(s));
   case "TOTP_DISABLED","TOTP_NOT_ENABLED","TOTP_ALREADY_ENABLED" -> AuthDialogs.managementResult(s.player,localized,settings,
    "messages."+reply.status(),()->completeManagement(s));
   case "TOTP_INVALID","RECOVERY_INVALID" -> {s.player.sendMessage(msg(s.player,reply.status()));
    if(s.management.action()==ManagementRequest.Action.ENABLE)requestManagement(s,"totp_setup_begin","","");else openManagementVerification(s);}
   default -> s.player.sendMessage(msg(s.player,"UNAVAILABLE"));
  }
 }
 private void completeManagement(Session s){
  if(!current(s)||s.management==null)return;
  UUID token=UUID.fromString(s.auth.view().sessionId());
  s.player.sendPluginMessage(this,MANAGEMENT_CHANNEL,java.nio.ByteBuffer.allocate(16)
   .putLong(token.getMostSignificantBits()).putLong(token.getLeastSignificantBits()).array());
  s.management=null;
 }
 private void showCaptcha(Session s){
  if(!current(s)||s.auth.view().stage()!=AuthStage.WAITING_FOR_CAPTCHA)return;
  int generation=++s.captchaDisplayGeneration;
  boolean fontView="DIALOG_FONT".equalsIgnoreCase(settings.getString("captcha-view"))&&!s.captchaGlyphs.isEmpty();
  CaptchaPresentationPolicy policy=new CaptchaPresentationPolicy(settings.getString("captcha.resource-pack-loading","MANUAL"),fontView,settings.getBoolean("captcha.fallback-to-map",true));
  presentCaptcha(s,generation,0,policy);
 }
 private void presentCaptcha(Session s,int generation,int attempt,CaptchaPresentationPolicy policy){
  if(!current(s)||generation!=s.captchaDisplayGeneration||s.auth.view().stage()!=AuthStage.WAITING_FOR_CAPTCHA)return;
  CaptchaPresentationPolicy.Action action=policy.action(s.packLoaded,dialogCapable);
  if(action==CaptchaPresentationPolicy.Action.SHOW_DIALOG){
   try{AuthDialogs.captcha(s.player,messages.forLocale(s.player.getLocale()),settings,s.captchaGlyphs,answer->Bukkit.getScheduler().runTask(this,()->submitCaptcha(s,answer)));getLogger().fine("CAPTCHA dialog shown to "+s.player.getName());return;}
   catch(LinkageError|RuntimeException error){getLogger().warning("CAPTCHA dialog failed for "+s.player.getName()+": "+error.getClass().getSimpleName());action=settings.getBoolean("captcha.fallback-to-map",true)?CaptchaPresentationPolicy.Action.SHOW_MAP:CaptchaPresentationPolicy.Action.REJECT;}
  }
  int max=Math.max(1,settings.getInt("captcha.pack-load-timeout-seconds",15))*2;
  if(action==CaptchaPresentationPolicy.Action.WAIT&&attempt<max){Bukkit.getScheduler().runTaskLater(this,()->presentCaptcha(s,generation,attempt+1,policy),10);return;}
  if(action==CaptchaPresentationPolicy.Action.WAIT)action=settings.getBoolean("captcha.fallback-to-map",true)?CaptchaPresentationPolicy.Action.SHOW_MAP:CaptchaPresentationPolicy.Action.REJECT;
  if(action==CaptchaPresentationPolicy.Action.SHOW_MAP){s.player.sendMessage(msg(s.player,"captcha-pack-fallback"));showCaptchaMap(s);}
  else s.player.kick(msg(s.player,"captcha-pack-required"));
 }
 private void showCaptchaMap(Session s){try{showMap(s,s.captchaImage);s.player.sendMessage(msg(s.player,"CAPTCHA"));}catch(Exception ex){getLogger().warning("Could not render CAPTCHA for "+s.player.getName()+": "+ex.getMessage());s.player.kick(msg(s.player,"unavailable"));}}
 private void submitCaptcha(Session s,String answer){
  AuthInputValidation.ValidationResult validation=AuthInputValidation.captcha(answer);if(!validation.valid()){s.player.sendMessage(msg(s.player,validation.messageKey()));showCaptcha(s);return;}
  if(flow.submitCaptcha(s.auth,answer))request(s,"answer","",answer);else s.player.sendMessage(msg(s.player,"not-ready"));
 }
 private void showMap(Session s,String encoded)throws IOException {
  if(encoded.length()>240000)throw new IOException("Oversized image");
  var image=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
  if(image==null||image.getWidth()>512||image.getHeight()>512)throw new IOException("Invalid image");
  releaseMap(s);MapView view=Bukkit.createMap(s.player.getWorld());s.map=view;view.getRenderers().forEach(view::removeRenderer);
  view.addRenderer(new MapRenderer(true){@Override public void render(MapView v,MapCanvas c,Player p){if(p.equals(s.player))c.drawImage(0,0,MapPalette.resizeImage(image));}});
  ItemStack item=new ItemStack(Material.FILLED_MAP);MapMeta meta=(MapMeta)item.getItemMeta();meta.setMapView(view);item.setItemMeta(meta);
  if(packetEventsEnabled){
   // Virtual selected slot: the map opens in front of the player without exposing inventory.
   inventoryProtection.sendVirtualMainHand(s.inventoryConnection,item,s.player.getInventory().getHeldItemSlot());
  }else {s.previousMainHand=s.player.getInventory().getItemInMainHand().clone();s.player.getInventory().setItemInMainHand(item);}
  s.player.sendMap(view);
 }
 private void releaseMap(Session s){if(s.map!=null){
  if(!packetEventsEnabled){ItemStack held=s.player.getInventory().getItemInMainHand();if(held.getItemMeta() instanceof MapMeta meta&&meta.getMapView()==s.map)s.player.getInventory().setItemInMainHand(s.previousMainHand==null?new ItemStack(Material.AIR):s.previousMainHand);s.previousMainHand=null;}
  s.map.getRenderers().forEach(s.map::removeRenderer);s.map=null;
 }}
 @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
  if(!(sender instanceof Player p))return true;Session s=sessions.get(p.getUniqueId());if(s==null||s.auth.view().stage()==AuthStage.AUTHENTICATED)return true;
  String name=cmd.getName();
  if(args.length==0){openCredentialInput(s,name.equals("register")?"REGISTER":"LOGIN");return true;}
  if(!settings.getBoolean("secure-input.compatibility-password-arguments",false)){p.sendMessage(msg(p,"secure-command-only"));return true;}
  if((name.equals("register")&&args.length!=2)||(!name.equals("register")&&args.length!=1)){p.sendMessage(msg(p,"usage"));return true;}
  AuthInputValidation.ValidationResult validation=name.equals("register")
   ?AuthInputValidation.registration(args[0],args[1],8,256)
   :AuthInputValidation.password(args[0],8,256);
  if(!validation.valid()){p.sendMessage(msg(p,validation.messageKey()));return true;}
  boolean accepted=name.equals("register")?flow.submitRegistration(s.auth,args[0],args[1]):flow.submitLogin(s.auth,args[0]);
  if(!accepted){p.sendMessage(msg(p,"not-ready"));return true;}
  request(s,name,args[0],"");return true;
 }
 @EventHandler public void quit(PlayerQuitEvent e){secureInput.clear(e.getPlayer().getUniqueId());resourcePackStatuses.remove(e.getPlayer().getUniqueId());Session s=sessions.get(e.getPlayer().getUniqueId());if(s!=null&&s.player==e.getPlayer()&&sessions.remove(e.getPlayer().getUniqueId(),s)){if(packetEventsEnabled)inventoryProtection.close(s.inventoryConnection);presentation.restore(s.player,s.snapshot);restoreVisibility(s);releaseMap(s);gateway.call(dto(s,"close","",""));flow.close(s.auth);}}
 @EventHandler public void resourcePackStatus(PlayerResourcePackStatusEvent e){getLogger().info("Resource pack • игрок: "+e.getPlayer().getName()+" • статус: "+e.getStatus());Boolean loaded=switch(e.getStatus()){case SUCCESSFULLY_LOADED -> true;case DECLINED,FAILED_DOWNLOAD,INVALID_URL,FAILED_RELOAD,DISCARDED -> false;default -> null;};if(loaded==null)return;resourcePackStatuses.put(e.getPlayer().getUniqueId(),loaded);Session s=sessions.get(e.getPlayer().getUniqueId());if(s==null)return;s.packLoaded=loaded;if(loaded&&settings.getString("captcha.resource-pack-loading","MANUAL").equalsIgnoreCase("SERVER")&&s.auth.view().stage()==AuthStage.WAITING_FOR_CAPTCHA)showCaptcha(s);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void move(PlayerMoveEvent e){Location from=e.getFrom(),to=e.getTo();if(locked(e.getPlayer())&&to!=null&&Countdown.movementChanged(from.getX(),from.getY(),from.getZ(),from.getYaw(),from.getPitch(),to.getX(),to.getY(),to.getZ(),to.getYaw(),to.getPitch()))e.setTo(from);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void teleport(PlayerTeleportEvent e){if(locked(e.getPlayer())&&e.getCause()!=PlayerTeleportEvent.TeleportCause.PLUGIN)e.setCancelled(true);}
 @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true) public void command(PlayerCommandPreprocessEvent e){if(locked(e.getPlayer())){String c=e.getMessage().split(" ",2)[0].substring(1);if(!PacketPolicy.allowCommand(c)){e.setCancelled(true);e.getPlayer().sendMessage(msg(e.getPlayer(),"blocked"));}}}
 @EventHandler(priority=EventPriority.LOWEST,ignoreCancelled=false) public void chat(AsyncPlayerChatEvent e){if(!locked(e.getPlayer()))return;e.setCancelled(true);Player player=e.getPlayer();Session session=sessions.get(player.getUniqueId());if(session==null||!secureInput.shouldCapture(player.getUniqueId(),session.auth))return;String input=e.getMessage();Bukkit.getScheduler().runTask(this,()->consumeSecureInput(session,input));}
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
 private void openCredentialInput(Session s,String kind){
  if(!current(s))return;boolean registration=kind.equals("REGISTER");AuthStage expected=registration?AuthStage.WAITING_FOR_REGISTRATION:AuthStage.WAITING_FOR_LOGIN;if(s.auth.view().stage()!=expected){s.player.sendMessage(msg(s.player,"not-ready"));return;}
  if(dialogCapable)try{
   if(registration)AuthDialogs.registration(s.player,messages.forLocale(s.player.getLocale()),settings,(password,confirmation)->Bukkit.getScheduler().runTask(this,()->submitRegistration(s,password,confirmation)));
   else AuthDialogs.login(s.player,messages.forLocale(s.player.getLocale()),settings,password->Bukkit.getScheduler().runTask(this,()->submitLogin(s,password)));
   return;
  }catch(LinkageError|RuntimeException error){getLogger().fine("Native authentication dialog unavailable for "+s.player.getName()+": "+error.getClass().getSimpleName());}
  if(!settings.getBoolean("dialogs.fallback-to-chat",true)){s.player.sendMessage(msg(s.player,"dialog-required"));return;}
  if(registration){secureInput.beginRegistration(s.player.getUniqueId(),s.auth);s.player.sendMessage(msg(s.player,"chat-register-prompt"));}
  else{secureInput.beginLogin(s.player.getUniqueId(),s.auth);s.player.sendMessage(msg(s.player,"chat-login-prompt"));}
 }
 private void consumeSecureInput(Session s,String input){
  if(!current(s))return;SecureChatInput.Result result=secureInput.accept(s.player.getUniqueId(),s.auth,input);
  if(!result.messageKey().isEmpty()){s.player.sendMessage(msg(s.player,result.messageKey()));return;}
 switch(result.action()){
   case CAPTCHA -> submitCaptcha(s,result.secret());
   case LOGIN -> submitLogin(s,result.secret());
   case AWAIT_CONFIRMATION -> s.player.sendMessage(msg(s.player,"chat-confirm-prompt"));
   case REGISTER -> submitRegistration(s,result.secret(),result.confirmation());
   case TOTP -> submitTotp(s,result.secret(),false);
   case RECOVERY -> submitTotp(s,result.secret(),true);
   case NONE -> {}
  }
 }

 private void openTotpInput(Session s){
  if(!current(s)||s.auth.view().stage()!=AuthStage.WAITING_FOR_TOTP)return;
  if(dialogCapable)try{
   AuthDialogs.totp(s.player,messages.forLocale(s.player.getLocale()),settings,
    code->Bukkit.getScheduler().runTask(this,()->submitTotp(s,code,!AuthInputValidation.totp(code).valid())));
   return;
  }catch(LinkageError|RuntimeException error){getLogger().fine("Native TOTP dialog unavailable for "+s.player.getName()+": "+error.getClass().getSimpleName());}
  secureInput.clear(s.player.getUniqueId());s.player.sendMessage(msg(s.player,"chat-totp-prompt"));
 }

 private void submitTotp(Session s,String code,boolean recovery){
  if(!current(s))return;
  AuthInputValidation.ValidationResult validation=recovery?AuthInputValidation.recovery(code):AuthInputValidation.totp(code);
  if(!validation.valid()){s.player.sendMessage(msg(s.player,validation.messageKey()));openTotpInput(s);return;}
  boolean accepted=recovery?flow.submitRecovery(s.auth,code):flow.submitTotp(s.auth,code);
  if(!accepted){s.player.sendMessage(msg(s.player,"not-ready"));return;}
  request(s,"totp_verify","","",recovery?"":code,recovery?code:"");
 }
 private void submitLogin(Session s,String password){
  if(!current(s))return;AuthInputValidation.ValidationResult validation=AuthInputValidation.password(password,8,256);if(!validation.valid()){s.player.sendMessage(msg(s.player,validation.messageKey()));openCredentialInput(s,"LOGIN");return;}
  if(!flow.submitLogin(s.auth,password)){s.player.sendMessage(msg(s.player,"not-ready"));return;}request(s,"login",password,"");
 }
 private void submitRegistration(Session s,String password,String confirmation){
  if(!current(s))return;AuthInputValidation.ValidationResult validation=AuthInputValidation.registration(password,confirmation,8,256);if(!validation.valid()){s.player.sendMessage(msg(s.player,validation.messageKey()));openCredentialInput(s,"REGISTER");return;}
  if(!flow.submitRegistration(s.auth,password,confirmation)){s.player.sendMessage(msg(s.player,"not-ready"));return;}request(s,"register",password,"");
 }
 private void applyVisibility(Session s){
  for(Player other:Bukkit.getOnlinePlayers()){
   if(other.equals(s.player))continue;
   if(settings.getBoolean("visibility.hide-player-from-others"))other.hidePlayer(this,s.player);
   if(settings.getBoolean("visibility.hide-others-from-player"))s.player.hidePlayer(this,other);
  }
 }
 private void restoreVisibility(Session s){
  for(Player other:Bukkit.getOnlinePlayers()){other.showPlayer(this,s.player);s.player.showPlayer(this,other);}
 }
}
