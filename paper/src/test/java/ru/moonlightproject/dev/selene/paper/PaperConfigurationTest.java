package ru.moonlightproject.dev.selene.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class PaperConfigurationTest {
 @TempDir Path directory;
 @Test void migratesExistingValuesAndMessagesWithoutOverwritingSplitFiles() throws Exception {
  Files.writeString(directory.resolve("config.yml"), "role: STANDALONE\nstage: AFTER_JOIN\ntimeout-seconds: 99\nmessages:\n  LOGIN: 'Custom login'\n");
  Files.writeString(directory.resolve("protection.yml"), "timeout-seconds: 77\n");
  var result=PaperConfiguration.load(directory, name -> getClass().getResourceAsStream("/"+name));
  assertEquals(77,result.getLong("timeout-seconds"));
  assertEquals("STANDALONE",result.getString("role"));
  assertTrue(Files.exists(directory.resolve("config.yml.legacy.bak")));
  assertTrue(Files.readString(directory.resolve("languages/ru.json")).contains("Custom login"));
  String before=Files.readString(directory.resolve("config.yml"));
  PaperConfiguration.load(directory,name -> getClass().getResourceAsStream("/"+name));
  assertEquals(before,Files.readString(directory.resolve("config.yml")));
  assertFalse(before.contains("messages:"));
 }
 @Test void rejectsSettingsPlacedInWrongFile() throws Exception {
  PaperConfiguration.load(directory,name -> getClass().getResourceAsStream("/"+name));
  Files.writeString(directory.resolve("limbo.yml"), "role: STANDALONE\n");
  assertThrows(IllegalArgumentException.class,()->PaperConfiguration.load(directory,name -> getClass().getResourceAsStream("/"+name)));
 }
}
