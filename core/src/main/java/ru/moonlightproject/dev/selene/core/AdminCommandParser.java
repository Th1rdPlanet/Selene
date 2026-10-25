package ru.moonlightproject.dev.selene.core;

import java.util.Locale;

public final class AdminCommandParser {
 public enum Access{PLAYER_DENIED,PROXY_GUIDANCE,ALLOWED}
 public record Parsed(AdminRequest request,String error){public boolean valid(){return request!=null;}}
 private AdminCommandParser(){}
 /** Console, or a player who has the permission and has already finished the Selene login. */
 public static Access access(boolean console,boolean permitted,boolean backendLocal){return !console&&!permitted?Access.PLAYER_DENIED:backendLocal?Access.ALLOWED:Access.PROXY_GUIDANCE;}
 /** Permission needed for a backend operation: reading accounts or changing them. */
 public static String permission(String operation){return operation!=null&&(operation.equals("list")||operation.equals("info"))?"selene.command.account.view":"selene.command.account.manage";}
 /** Backend operation for a command action such as {@code reset-password}; null when unknown. */
 public static String operation(String action){return switch(action.toLowerCase(Locale.ROOT)){case "list"->"list";case "info"->"info";case "delete"->"delete";case "reset-password"->"resetPassword";case "reset-totp"->"resetTotp";default->null;};}
 public static Parsed parse(String[] arguments){
  if(arguments==null||arguments.length<2||!arguments[0].equalsIgnoreCase("account"))return error("syntax");
  String action=arguments[1].toLowerCase(Locale.ROOT);
  if(action.equals("list")&&(arguments.length==2||arguments.length==3)){
   int page=1;try{if(arguments.length==3)page=Integer.parseInt(arguments[2]);}catch(NumberFormatException ignored){return error("syntax");}
   if(page<1||page>1_000_000)return error("syntax");return new Parsed(new AdminRequest("list","",page,20),"");
  }
  if(arguments.length!=3)return error("syntax");String username=arguments[2];if(!username.matches("[A-Za-z0-9_]{3,16}"))return error("username");
  String operation=operation(action);
  return operation==null||operation.equals("list")?error("syntax"):new Parsed(new AdminRequest(operation,username,0,0),"");
 }
 public static String messageKey(String status){return switch(status){case "OK","NOT_FOUND","INVALID_REQUEST","BUSY","UNAVAILABLE"->"admin-status-"+status.toLowerCase(Locale.ROOT).replace('_','-');default->"admin-status-unavailable";};}
 public static String audit(String operation,String account,String status,long durationMillis){return "operation="+safe(operation)+" account="+safe(account)+" status="+safe(status)+" durationMs="+Math.max(0,durationMillis);}
 private static Parsed error(String value){return new Parsed(null,value);}
 private static String safe(String value){return value==null?"":value.replaceAll("[^A-Za-z0-9_-]","?");}
}
