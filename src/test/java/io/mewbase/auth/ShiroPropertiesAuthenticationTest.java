package io.mewbase.auth;

import io.mewbase.server.impl.auth.MewbaseVertxAuthProvider;
import io.mewbase.bson.BsonObject;
import io.mewbase.server.ServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.shiro.PropertiesProviderConstants;
import io.vertx.ext.auth.shiro.ShiroAuth;
import io.vertx.ext.auth.shiro.ShiroAuthOptions;
import io.vertx.ext.auth.shiro.ShiroAuthRealmType;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class ShiroPropertiesAuthenticationTest extends AuthenticationTestBase {
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Override
    protected ServerOptions createServerOptions() {
        return super.createServerOptions()
                .setAuthProvider(new MewbaseVertxAuthProvider(createShiroAuthProvider()));
    }

    private ShiroAuth createShiroAuthProvider() {
        JsonObject config = new JsonObject();
        config.put(PropertiesProviderConstants.PROPERTIES_PROPS_PATH_FIELD, "classpath:test-shiro-auth.properties");

        ShiroAuthOptions shiroAuthOptions = new ShiroAuthOptions().setType(ShiroAuthRealmType.PROPERTIES).setConfig(config);

        return ShiroAuth.create(vertx, shiroAuthOptions);
    }

    @Test
    public void testSuccessfulAuthentication(TestContext context) throws Exception {
        authInfo = new BsonObject().put("username", "mew").put("password", "base");
        execSimplePubSub(true, context);
    }

    @Test
    public void testFailedAuthentication(TestContext context) throws Exception {
        authInfo = new BsonObject().put("username", "error").put("password", "error");
        execSimplePubSub(false, context);
    }

}
