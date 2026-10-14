package ru.moonlightproject.dev.selene.core;

import java.util.concurrent.CompletableFuture;

public interface AccountAdminGateway {
 CompletableFuture<AdminReply> admin(AdminRequest request);
}
