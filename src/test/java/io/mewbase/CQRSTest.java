package io.mewbase;

import io.mewbase.bson.BsonArray;
import io.mewbase.bson.BsonObject;
import io.mewbase.client.*;
import io.mewbase.common.SubDescriptor;
import io.mewbase.server.CommandHandler;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static junit.framework.TestCase.assertFalse;
import static org.junit.Assert.*;

/**
 * Created by tim on 26/09/16.
 */
@RunWith(VertxUnitRunner.class)
public class CQRSTest extends ServerTestBase {

    private final static Logger logger = LoggerFactory.getLogger(CQRSTest.class);

    @Override
    protected void setupChannelsAndBinders() throws Exception {
        server.createChannel(TEST_CHANNEL_1).get();
    }

    @Test
    public void testSimpleCommand(TestContext testContext) throws Exception {

        String commandName = "testcommand";

        CommandHandler handler = server.buildCommandHandler(commandName)
               .emittingTo(TEST_CHANNEL_1)
               .as((command, context) -> {
                    context.publishEvent(new BsonObject().put("eventField", command.getString("commandField")));
                    context.complete();
               })
               .create();

        assertNotNull(handler);
        assertEquals(commandName, handler.getName());


        Async async = testContext.async();

        Consumer<ClientDelivery> subHandler = del -> {
            BsonObject event = del.event();
            testContext.assertEquals("foobar", event.getString("eventField"));
            async.complete();
        };

        Subscription sub = client.subscribe(new SubDescriptor().setChannel(TEST_CHANNEL_1), subHandler).get();

        BsonObject sentCommand = new BsonObject().put("commandField", "foobar");

        client.sendCommand(commandName, sentCommand);

    }

}
