package ru.moonlightproject.dev.selene.core;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import org.junit.jupiter.api.Test;
class FrameTest {
  @Test
  void rejectsOversizedFrameBeforeAllocation() {
    var in = new ByteArrayInputStream(new byte[] {0, 4, 0, 1});
    assertThrows(IOException.class, () -> Frames.read(in));
  }
  @Test
  void rejectsTruncatedFrame() {
    var in = new ByteArrayInputStream(new byte[] {0, 0, 0, 2, 1});
    assertThrows(IOException.class, () -> Frames.read(in));
  }
  @Test
  void roundTrips() {
    var out = new ByteArrayOutputStream();
    assertDoesNotThrow(() -> Frames.write(out, new byte[] {1, 2}));
    assertArrayEquals(new byte[] {1, 2},
        assertDoesNotThrow(() -> Frames.read(new ByteArrayInputStream(out.toByteArray()))));
  }
}
