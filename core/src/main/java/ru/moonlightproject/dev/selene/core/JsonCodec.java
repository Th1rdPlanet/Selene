package ru.moonlightproject.dev.selene.core;
import com.google.gson.*;
import java.nio.*;
import java.nio.charset.*;
public final class JsonCodec {
  public static final int MAX_BYTES = 262144;
  private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
  /** Compiled once: the field checks below run on every reply. */
  private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("[0-9]+");
  private static final TypeAdapter<AuthRequest> AUTH_REQUEST = GSON.getAdapter(AuthRequest.class);
  private static final TypeAdapter<AuthReply> AUTH_REPLY = GSON.getAdapter(AuthReply.class);
  private static final TypeAdapter<AdminRequest> ADMIN_REQUEST = GSON.getAdapter(AdminRequest.class);
  private static final TypeAdapter<AdminReply> ADMIN_REPLY = GSON.getAdapter(AdminReply.class);
  private JsonCodec() {}
  public static byte[] encode(Object object) {
    byte[] bytes = GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    check(bytes);
    return bytes;
  }
  public static AuthRequest request(byte[] bytes) {
    var o = parse(bytes);
    for (String k : new String[] {"op", "session", "username", "ip", "password", "answer", "totpCode", "recoveryCode"})
      type(o, k, false);
    for (String k : new String[] {"premium", "captchaRequired"}) type(o, k, true);
    return decode(o,AUTH_REQUEST,"AuthRequest");
  }
  public static AuthReply reply(byte[] bytes) {
    var o = parse(bytes);
    for (String k : new String[] {"status", "session", "image", "captchaGlyphs", "totpUri", "totpSecret"}) type(o, k, false);
    type(o,"totpEnabled",true);
    if(o.has("recoveryCodes")&&!o.get("recoveryCodes").isJsonArray())throw new IllegalArgumentException("Invalid recoveryCodes");
    if(o.has("premiumTrust")&&!o.get("premiumTrust").isJsonObject())throw new IllegalArgumentException("Invalid premiumTrust");
    if (o.has("retryAfter")
        && (!o.get("retryAfter").isJsonPrimitive() || !o.getAsJsonPrimitive("retryAfter").isNumber()
            || !DIGITS.matcher(o.get("retryAfter").getAsString()).matches()))
      throw new IllegalArgumentException("Invalid retryAfter");
    return decode(o,AUTH_REPLY,"AuthReply");
  }
  public static AdminRequest adminRequest(byte[] bytes){
    var o=parse(bytes);for(String k:new String[]{"op","username"})type(o,k,false);number(o,"page");number(o,"pageSize");return decode(o,ADMIN_REQUEST,"AdminRequest");
  }
  public static AdminReply adminReply(byte[] bytes){
    var o=parse(bytes);type(o,"status",false);number(o,"page");type(o,"hasMore",true);
    if(o.has("accounts")&&!o.get("accounts").isJsonArray())throw new IllegalArgumentException("Invalid field accounts");
    if(o.has("account")&&!o.get("account").isJsonNull()&&!o.get("account").isJsonObject())throw new IllegalArgumentException("Invalid field account");
    return decode(o,ADMIN_REPLY,"AdminReply");
  }
  private static void number(JsonObject o,String key){if(o.has(key)&&(!o.get(key).isJsonPrimitive()||!o.getAsJsonPrimitive(key).isNumber()||!DIGITS.matcher(o.get(key).getAsString()).matches()))throw new IllegalArgumentException("Invalid field "+key);}
  private static void type(JsonObject o, String k, boolean bool) {
    if (!o.has(k) || o.get(k).isJsonNull())
      return;
    var v = o.get(k);
    if (!v.isJsonPrimitive()
        || (bool ? !v.getAsJsonPrimitive().isBoolean() : !v.getAsJsonPrimitive().isString()))
      throw new IllegalArgumentException("Invalid field " + k);
  }
  private static JsonObject parse(byte[] bytes) {
    check(bytes);
    try {
      String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
      var e = GSON.fromJson(text, JsonElement.class);
      if (e == null || !e.isJsonObject())
        throw new IllegalArgumentException("Expected object");
      return e.getAsJsonObject();
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid JSON", e);
    }
  }
  private static <T>T decode(JsonObject object,TypeAdapter<T> adapter,String type){
    try{
      T value=adapter.fromJsonTree(object);
      if(value==null)throw new JsonParseException("null "+type);
      return value;
    }catch(RuntimeException error){throw new IllegalArgumentException("Invalid "+type,error);}
  }
  /**
   * Runs the request and reply paths until the JIT has compiled them. A proxy with few logins
   * otherwise keeps interpreting the codec, which costs a few hundred microseconds per call.
   */
  public static void warmUp(){
    AuthRequest request=new AuthRequest("begin","00000000-0000-4000-8000-000000000000","WarmUp","127.0.0.1","","",false,false);
    byte[] reply=encode(new AuthReply("LOGIN","00000000-0000-4000-8000-000000000000","","",null,null,0,"","",null,false,null,""));
    for(int i=0;i<20_000;i++){encode(request);reply(reply);}
  }
  private static void check(byte[] b) {
    if (b == null || b.length == 0 || b.length > MAX_BYTES)
      throw new IllegalArgumentException("Invalid frame size");
  }
}
