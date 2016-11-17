package com.tesco.mewbase.auth;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.AbstractUser;
import io.vertx.ext.auth.AuthProvider;

public class DummyUser extends AbstractUser {

    @Override
    protected void doIsPermitted(String permission, Handler<AsyncResult<Boolean>> resultHandler) {
        resultHandler.handle(Future.succeededFuture(true));
    }

    @Override
    public JsonObject principal() {
        return new JsonObject().put("username", "dummy");
    }

    @Override
    public void setAuthProvider(AuthProvider authProvider) {

    }
}