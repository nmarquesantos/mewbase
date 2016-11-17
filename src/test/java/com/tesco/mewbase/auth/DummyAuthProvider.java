package com.tesco.mewbase.auth;

import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.AuthProvider;
import io.vertx.ext.auth.User;

public class DummyAuthProvider implements AuthProvider {

    private String username;
    private String password;

    public DummyAuthProvider(String username, String password) {
        this.username = username;
        this.password = password;
    }

    @Override
    public void authenticate(JsonObject authInfo, Handler<AsyncResult<User>> resultHandler) {

        if (!authInfo.getValue("username").equals(username) || !authInfo.getValue("password").equals(password)) {
            resultHandler.handle(Future.failedFuture("NOT AUTHENTICATED"));
        } else {
            resultHandler.handle(Future.succeededFuture(new DummyUser()));
        }
    }
}