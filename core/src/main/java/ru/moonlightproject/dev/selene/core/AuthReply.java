package ru.moonlightproject.dev.selene.core;
import java.util.*;
public record AuthReply(String status, String session, String image, Map<String, String> green,
    List<String> premiumNames, long retryAfter) {
  public AuthReply {
    status = status == null ? "" : status;
    session = session == null ? "" : session;
    image = image == null ? "" : image;
    green = green == null ? Map.of() : Map.copyOf(green);
    premiumNames = premiumNames == null ? List.of() : List.copyOf(premiumNames);
    if (retryAfter < 0)
      throw new IllegalArgumentException("Negative retryAfter");
  }
  public static AuthReply status(String status, String session) {
    return new AuthReply(status, session, "", Map.of(), List.of(), 0);
  }
}
