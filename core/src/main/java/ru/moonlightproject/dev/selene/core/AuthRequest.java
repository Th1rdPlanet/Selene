package ru.moonlightproject.dev.selene.core;
public record AuthRequest(String op, String session, String username, String ip, String password,
    String answer, boolean premium, boolean captchaRequired) {
  public AuthRequest {
    op = s(op);
    session = s(session);
    username = s(username);
    ip = s(ip);
    password = s(password);
    answer = s(answer);
  }
  private static String s(String v) {
    return v == null ? "" : v;
  }
  @Override
  public String toString() {
    return "AuthRequest[op=" + op + ", session=" + session + ", credentials=<redacted>]";
  }
}
