package ru.moonlightproject.dev.selene.core;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

/** Wraps each backend call, for example in a tracing span. The call must be started inside {@link #around}. */
public interface CallScope {
  CallScope DIRECT = new CallScope() {
    public <T> CompletableFuture<T> around(String kind, String operation, String username,
        Supplier<CompletableFuture<T>> call, Function<T, String> status) { return call.get(); }
  };
  <T> CompletableFuture<T> around(String kind, String operation, String username,
      Supplier<CompletableFuture<T>> call, Function<T, String> status);
}
