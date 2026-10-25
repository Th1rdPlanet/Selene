package ru.moonlightproject.dev.selene.core;
public record AuthRequest(String op, String session, String username, String ip, String password,
    String answer, boolean premium, boolean captchaRequired, String totpCode, String recoveryCode,
    boolean passwordTrusted, boolean totpTrusted, String captchaKind, String challengeNonce,
    long maintenanceRevision, boolean maintenanceEnableCaptcha, boolean maintenanceVerifyCaptcha,
    boolean maintenanceEnablePassword, boolean maintenanceVerifyPassword,
    boolean maintenanceEnableTotp, boolean maintenanceVerifyTotp) {
  public AuthRequest {
    op = s(op);
    session = s(session);
    username = s(username);
    ip = s(ip);
    password = s(password);
    answer = s(answer);
    totpCode = bounded(totpCode, 64, "totpCode");
    recoveryCode = bounded(recoveryCode, 128, "recoveryCode");
    captchaKind = bounded(captchaKind, 32, "captchaKind");
    if (!captchaKind.isEmpty() && !captchaKind.equals("DIALOG_FONT") && !captchaKind.equals("MAP")
        && !captchaKind.equals("INTERACTIVE")) throw new IllegalArgumentException("Unknown captchaKind");
    challengeNonce = bounded(challengeNonce, 128, "challengeNonce");
    if (maintenanceRevision < 0) throw new IllegalArgumentException("Negative maintenanceRevision");
    if (maintenanceRevision > 0 && (maintenanceVerifyCaptcha && !maintenanceEnableCaptcha
        || maintenanceVerifyPassword && !maintenanceEnablePassword
        || maintenanceVerifyTotp && !maintenanceEnableTotp))
      throw new IllegalArgumentException("Cannot verify a disabled maintenance factor");
  }
  public AuthRequest(String op,String session,String username,String ip,String password,String answer,
      boolean premium,boolean captchaRequired){this(op,session,username,ip,password,answer,premium,captchaRequired,"","",false,false,"","",0,false,false,false,false,false,false);}
  public AuthRequest(String op,String session,String username,String ip,String password,String answer,
      boolean premium,boolean captchaRequired,String totpCode,String recoveryCode){this(op,session,username,ip,password,answer,premium,captchaRequired,totpCode,recoveryCode,false,false,"","",0,false,false,false,false,false,false);}
  public AuthRequest(String op,String session,String username,String ip,String password,String answer,
      boolean premium,boolean captchaRequired,String totpCode,String recoveryCode,boolean passwordTrusted,
      boolean totpTrusted){this(op,session,username,ip,password,answer,premium,captchaRequired,totpCode,recoveryCode,passwordTrusted,totpTrusted,"","",0,false,false,false,false,false,false);}
  public AuthRequest(String op,String session,String username,String ip,String password,String answer,
      boolean premium,boolean captchaRequired,String totpCode,String recoveryCode,boolean passwordTrusted,
      boolean totpTrusted,String captchaKind,String challengeNonce){this(op,session,username,ip,password,answer,
      premium,captchaRequired,totpCode,recoveryCode,passwordTrusted,totpTrusted,captchaKind,challengeNonce,
      0,false,false,false,false,false,false);}
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
