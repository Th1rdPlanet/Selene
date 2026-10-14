package ru.moonlightproject.dev.selene.velocity;

final class AuthenticationRoute {
 private AuthenticationRoute(){}
 static String initial(boolean authenticated,String authServer,String gameplayServer){return authenticated?gameplayServer:authServer;}
 static boolean mayConnect(boolean authenticated,String target,String authServer){return authenticated||target.equals(authServer);}
}
