package ru.moonlightproject.dev.selene.core;

import java.util.List;

public record AdminReply(String status,List<Account> accounts,Account account,int page,boolean hasMore){
 public AdminReply{status=status==null?"":status;accounts=accounts==null?List.of():List.copyOf(accounts);if(page<0)throw new IllegalArgumentException("Invalid admin page");}
 public record Account(String name,boolean premium,String trustedIp,long trustedUntil,boolean passwordReset,boolean totpEnabled){
  public Account{name=name==null?"":name;trustedIp=trustedIp==null?"":trustedIp;if(trustedUntil<0)throw new IllegalArgumentException("Invalid trust expiry");}
  public Account(String name,boolean premium,String trustedIp,long trustedUntil,boolean passwordReset){this(name,premium,trustedIp,trustedUntil,passwordReset,false);}
 }
}
