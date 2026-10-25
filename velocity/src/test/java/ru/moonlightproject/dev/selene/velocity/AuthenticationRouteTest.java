package ru.moonlightproject.dev.selene.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationRouteTest {
 @Test void unauthenticatedPlayerCanOnlyJoinAuthenticationLimbo(){
  assertEquals("auth",AuthenticationRoute.initial(false,"auth","lobby"));
  assertTrue(AuthenticationRoute.mayConnect(false,false,"auth","auth"));
  assertFalse(AuthenticationRoute.mayConnect(false,false,"lobby","auth"));
 }
 @Test void successfulAuthenticationReleasesGameplayRouting(){
  assertEquals("lobby",AuthenticationRoute.initial(true,"auth","lobby"));
  assertTrue(AuthenticationRoute.mayConnect(true,false,"survival","auth"));
  // A logged-in player switching back by hand would wait forever for a new login session.
  assertFalse(AuthenticationRoute.mayConnect(true,false,"auth","auth"));
  assertTrue(AuthenticationRoute.mayConnect(true,true,"auth","auth"));
 }
}
