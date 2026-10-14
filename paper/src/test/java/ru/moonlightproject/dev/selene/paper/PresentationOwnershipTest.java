package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PresentationOwnershipTest {
 @Test void restoresScoreboardOnlyWhileSeleneStillOwnsIt(){
  Object previous=new Object(),owned=new Object(),foreign=new Object();
  assertSame(previous,PresentationSnapshot.scoreboardToRestore(owned,owned,previous));
  assertNull(PresentationSnapshot.scoreboardToRestore(foreign,owned,previous));
 }

 @Test void restoresExperienceOnlyIfValuesStillMatchSelenePresentation(){
  assertTrue(PresentationSnapshot.mayRestoreExperience(0.4f,42,0.4f,42));
  assertFalse(PresentationSnapshot.mayRestoreExperience(0.5f,42,0.4f,42));
  assertFalse(PresentationSnapshot.mayRestoreExperience(0.4f,43,0.4f,42));
 }

 @Test void ownedEffectFingerprintMustStillMatch(){
  assertTrue(PresentationSnapshot.mayRestoreEffect(0,false,false,false,0,false,false,false));
  assertFalse(PresentationSnapshot.mayRestoreEffect(1,false,false,false,0,false,false,false));
 }
}
