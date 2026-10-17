package ru.moonlightproject.dev.selene.core;
public record AuthRequest(String op, String session, String username, String ip, String password,
    String answer, boolean premium, boolean captchaRequired, String totpCode, String recoveryCode) {
  public AuthRequest {
    op = s(op);
    session = s(session);
    username = s(username);
    ip = s(ip);
    password = s(password);
    answer = s(answer);
    totpCode = bounded(totpCode, 64, "totpCode");
    recoveryCode = bounded(recoveryCode, 128, "recoveryCode");
  }
  public AuthRequest(String op,String session,String username,String ip,String password,String answer,
      boolean premium,boolean captchaRequired){this(op,session,username,ip,password,answer,premium,captchaRequired,"","");}
  private static String s(String v) {
    return v == null ? "" : v;
  }
  private static String bounded(String value,int maximum,String field){
    String normalized=s(value);if(normalized.length()>maximum)throw new IllegalArgumentException(field+" is too long");return normalized;
  }
  @Override
  public String toString() {
    return "AuthRequest[op=" + op + ", session=" + session + ", credentials=<redacted>]";
  }
}
