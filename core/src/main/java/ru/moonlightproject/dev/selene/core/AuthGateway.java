package ru.moonlightproject.dev.selene.core;
import java.util.concurrent.CompletableFuture;
public interface AuthGateway extends AutoCloseable {
  CompletableFuture<AuthReply> call(AuthRequest request);
  default void close() {}
}
