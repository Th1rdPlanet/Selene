package ru.moonlightproject.dev.selene.velocity;

final class AuthenticationRoute {
 private AuthenticationRoute(){}
 static String initial(boolean authenticated,String authServer,String gameplayServer){return authenticated?gameplayServer:authServer;}
 /**
  * Unauthenticated players may only reach the auth server. Authenticated players may go anywhere
  * except back to it, unless a 2FA management flow sends them there: the auth server would
  * otherwise wait for a login session that never comes.
  */
 static boolean mayConnect(boolean authenticated,boolean managing,String target,String authServer){
  boolean auth=target.equals(authServer);
  return authenticated?!auth||managing:auth;
 }
}
