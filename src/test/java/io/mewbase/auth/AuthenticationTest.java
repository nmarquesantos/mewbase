package io.mewbase.auth;

import io.mewbase.bson.BsonObject;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;


/*
 TODO

 More tests needed:

 * we need to test authentication on all protocol frames, not just the ones used in a simple pubsubtest
 * test that authentication applies to only one connection and is cancelled when connection is closed
 * etc
 *

 */
@RunWith(VertxUnitRunner.class)
public class AuthenticationTest extends AuthenticationTestBase {

    @Test
    public void testSuccessfulAuthentication(TestContext context) throws Exception {
        authInfo = new BsonObject().put("success", true);
        execSimplePubSub(true, context);
    }

    @Test
    public void testFailedAuthentication(TestContext context) throws Exception {
        authInfo = new BsonObject().put("success", false);
        execSimplePubSub(false, context);
    }

}
