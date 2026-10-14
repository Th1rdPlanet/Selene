package ru.moonlightproject.dev.selene.velocity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticationRouteTest {
 @Test void unauthenticatedPlayerCanOnlyJoinAuthenticationLimbo(){
  assertEquals("auth",AuthenticationRoute.initial(false,"auth","lobby"));
  assertTrue(AuthenticationRoute.mayConnect(false,"auth","auth"));
  assertFalse(AuthenticationRoute.mayConnect(false,"lobby","auth"));
 }
 @Test void successfulAuthenticationReleasesGameplayRouting(){
  assertEquals("lobby",AuthenticationRoute.initial(true,"auth","lobby"));
  assertTrue(AuthenticationRoute.mayConnect(true,"survival","auth"));
 }
}
