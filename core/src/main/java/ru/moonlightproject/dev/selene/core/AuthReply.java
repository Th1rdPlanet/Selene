package ru.moonlightproject.dev.selene.core;
import java.util.*;
public record AuthReply(String status, String session, String image, String captchaGlyphs, Map<String, String> green,
    List<String> premiumNames, long retryAfter, String totpUri, String totpSecret, List<String> recoveryCodes,
    boolean totpEnabled, Map<String,PremiumTrust> premiumTrust) {
  public AuthReply {
    status = status == null ? "" : status;
    session = session == null ? "" : session;
    image = image == null ? "" : image;
    captchaGlyphs = captchaGlyphs == null ? "" : captchaGlyphs;
    green = green == null ? Map.of() : Map.copyOf(green);
    premiumNames = premiumNames == null ? List.of() : List.copyOf(premiumNames);
    totpUri=bounded(totpUri,2048,"totpUri");
    totpSecret=bounded(totpSecret,128,"totpSecret");
    recoveryCodes=recoveryCodes==null?List.of():List.copyOf(recoveryCodes);
    if(recoveryCodes.size()>32||recoveryCodes.stream().anyMatch(code->code==null||code.length()>128))throw new IllegalArgumentException("Invalid recoveryCodes");
    premiumTrust=premiumTrust==null?Map.of():Map.copyOf(premiumTrust);
    if(premiumTrust.size()>4096||premiumTrust.entrySet().stream().anyMatch(entry->entry.getKey()==null||entry.getKey().isBlank()||entry.getKey().length()>16||entry.getValue()==null))throw new IllegalArgumentException("Invalid premiumTrust");
    if (retryAfter < 0)
      throw new IllegalArgumentException("Negative retryAfter");
  }
  public AuthReply(String status,String session,String image,String captchaGlyphs,Map<String,String> green,
      List<String> premiumNames,long retryAfter){this(status,session,image,captchaGlyphs,green,premiumNames,retryAfter,"","",List.of(),false,Map.of());}
  public static AuthReply status(String status, String session) {
    return new AuthReply(status, session, "", "", Map.of(), List.of(), 0,"","",List.of(),false,Map.of());
  }
  @Override public String toString(){return "AuthReply[status="+status+", session="+session+", security=<redacted>]";}
  private static String bounded(String value,int maximum,String field){String normalized=value==null?"":value;if(normalized.length()>maximum)throw new IllegalArgumentException(field+" is too long");return normalized;}
}
