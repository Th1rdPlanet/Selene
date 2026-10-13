package ru.moonlightproject.dev.selene.core;
import java.io.*;
final class Frames {
  static byte[] read(InputStream input) throws IOException {
    var in = new DataInputStream(input);
    int n = in.readInt();
    if (n < 1 || n > JsonCodec.MAX_BYTES)
      throw new IOException("Invalid frame length");
    byte[] b = new byte[n];
    in.readFully(b);
    return b;
  }
  static void write(OutputStream output, byte[] b) throws IOException {
    if (b.length < 1 || b.length > JsonCodec.MAX_BYTES)
      throw new IOException("Invalid frame length");
    var out = new DataOutputStream(output);
    out.writeInt(b.length);
    out.write(b);
    out.flush();
  }
}
