package ru.moonlightproject.dev.selene.core;
import com.google.gson.*;
import java.nio.*;
import java.nio.charset.*;
public final class JsonCodec {
  public static final int MAX_BYTES = 262144;
  private static final Gson GSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
  private JsonCodec() {}
  public static byte[] encode(Object object) {
    byte[] bytes = GSON.toJson(object).getBytes(StandardCharsets.UTF_8);
    check(bytes);
    return bytes;
  }
  public static AuthRequest request(byte[] bytes) {
    var o = parse(bytes);
    for (String k : new String[] {"op", "session", "username", "ip", "password", "answer"})
      type(o, k, false);
    for (String k : new String[] {"premium", "captchaRequired"}) type(o, k, true);
    return GSON.fromJson(o, AuthRequest.class);
  }
  public static AuthReply reply(byte[] bytes) {
    var o = parse(bytes);
    for (String k : new String[] {"status", "session", "image"}) type(o, k, false);
    if (o.has("retryAfter")
        && (!o.get("retryAfter").isJsonPrimitive() || !o.getAsJsonPrimitive("retryAfter").isNumber()
            || !o.get("retryAfter").getAsString().matches("[0-9]+")))
      throw new IllegalArgumentException("Invalid retryAfter");
    return GSON.fromJson(o, AuthReply.class);
  }
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
  private static void check(byte[] b) {
    if (b == null || b.length == 0 || b.length > MAX_BYTES)
      throw new IllegalArgumentException("Invalid frame size");
  }
}
