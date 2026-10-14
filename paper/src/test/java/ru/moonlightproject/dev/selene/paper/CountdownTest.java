package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CountdownTest {
 @Test void reportsCeilingSecondsAndClampedFraction(){
  assertEquals(10,Countdown.secondsRemaining(1_000,10_001));
  assertEquals(1,Countdown.secondsRemaining(9_500,10_001));
  assertEquals(0,Countdown.secondsRemaining(10_001,10_001));
  assertEquals(1.0f,Countdown.fraction(1_000,1_000,11_000));
  assertEquals(0.5f,Countdown.fraction(6_000,1_000,11_000));
  assertEquals(0.0f,Countdown.fraction(12_000,1_000,11_000));
 }

 @Test void detectsPositionAndCameraChanges(){
  assertFalse(Countdown.movementChanged(1,2,3,90,0,1,2,3,90,0));
  assertTrue(Countdown.movementChanged(1,2,3,90,0,1,2,3,91,0));
  assertTrue(Countdown.movementChanged(1,2,3,90,0,1,2,3,90,1));
  assertTrue(Countdown.movementChanged(1,2,3,90,0,1.1,2,3,90,0));
 }
}
