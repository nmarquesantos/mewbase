package com.tesco.mewbase.server.impl;

import com.tesco.mewbase.bson.BsonAuthenticationParser;
import com.tesco.mewbase.bson.BsonObject;
import com.tesco.mewbase.common.ReadStream;
import com.tesco.mewbase.common.SubDescriptor;
import com.tesco.mewbase.doc.DocManager;
import com.tesco.mewbase.log.Log;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.NetSocket;
import io.vertx.ext.auth.AuthProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by tim on 23/09/16.
 */
public class ConnectionImpl implements ServerFrameHandler {

    private final static Logger logger = LoggerFactory.getLogger(ConnectionImpl.class);

    private final ServerImpl server;
    private final NetSocket socket;
    private final Context context;
    private final DocManager docManager;
    private final Map<Integer, SubscriptionImpl> subscriptionMap = new ConcurrentHashMap<>();
    private final PriorityQueue<WriteHolder> pq = new PriorityQueue<>();
    private AuthProvider authProvider;
    private boolean authorised;
    private int subSeq;
    private long writeSeq;
    private long expectedRespNo;

    public ConnectionImpl(ServerImpl server, NetSocket netSocket, Context context, DocManager docManager) {
        netSocket.handler(new Codec(this).recordParser());
        this.server = server;
        this.socket = netSocket;
        this.context = context;
        this.docManager = docManager;
    }

    public ConnectionImpl(ServerImpl server, NetSocket netSocket, Context context, DocManager docManager, AuthProvider authProvider) {
        netSocket.handler(new Codec(this).recordParser());
        this.server = server;
        this.socket = netSocket;
        this.context = context;
        this.docManager = docManager;
        this.authProvider = authProvider;
    }

    @Override
    public void handleConnect(BsonObject frame) {
        checkContext();
        // TODO version checking


        //this is necessary as the ClientOptions object will have
        //a JSONObject attribute. I believe this would be the best way to make it pluggable
        BsonObject bsonAuthInfo = (BsonObject) frame.getValue(Codec.AUTH_INFO);
        bsonAuthInfo.getJsonObject("map", new JsonObject());

        JsonObject authInfo = BsonAuthenticationParser.getDummyAuthInfo(bsonAuthInfo);

        authProvider.authenticate(authInfo, re -> {
            BsonObject resp = new BsonObject();
            if (re.succeeded()) {
                logger.info("authentication successful for principal {}", re.result().principal());
                authorised = true;

                resp.put(Codec.RESPONSE_OK, true);
            } else {
                resp.put(Codec.RESPONSE_OK, false);

                //TODO: where to specify an error code?
                resp.put(Codec.RESPONSE_ERRCODE, "INVALID_AUTH");
                resp.put(Codec.RESPONSE_ERRMSG, re.cause().getMessage());
            }

            writeResponse(Codec.RESPONSE_FRAME, resp, getWriteSeq());
        });
    }

    @Override
    public void handlePublish(BsonObject frame) {
        checkContext();
        checkAuthorised();
        String channel = frame.getString(Codec.PUBLISH_CHANNEL);
        BsonObject event = frame.getBsonObject(Codec.PUBLISH_EVENT);
        if (channel == null) {
            logAndClose("No channel in PUB");
            return;
        }
        if (event == null) {
            logAndClose("No event in PUB");
            return;
        }
        long order = getWriteSeq();
        Log log = server.getLog(channel);
        BsonObject record = new BsonObject();
        record.put(Codec.RECEV_TIMESTAMP, System.currentTimeMillis());
        record.put(Codec.RECEV_EVENT, event);
        CompletableFuture<Long> cf = log.append(record);

        logger.trace("Handling publish " + event.getInteger("num"));

        cf.handle((v, ex) -> {
            BsonObject resp = new BsonObject();
            if (ex == null) {
                resp.put(Codec.RESPONSE_OK, true);
            } else {
                // TODO error code
                resp.put(Codec.RESPONSE_OK, false).put(Codec.RESPONSE_ERRMSG, "Failed to persist");
            }
            writeResponse(Codec.RESPONSE_FRAME, resp, order);
            return null;
        });

    }

    @Override
    public void handleStartTx(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    @Override
    public void handleCommitTx(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    @Override
    public void handleAbortTx(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    @Override
    public void handleSubscribe(BsonObject frame) {
        checkContext();
        checkAuthorised();
        String channel = frame.getString(Codec.SUBSCRIBE_CHANNEL);
        if (channel == null) {
            logAndClose("No channel in SUBSCRIBE");
            return;
        }
        Long startSeq = frame.getLong(Codec.SUBSCRIBE_STARTPOS);
        Long startTimestamp = frame.getLong(Codec.SUBSCRIBE_STARTTIMESTAMP);
        String durableID = frame.getString(Codec.SUBSCRIBE_DURABLEID);
        BsonObject matcher = frame.getBsonObject(Codec.SUBSCRIBE_MATCHER);
        SubDescriptor subDescriptor = new SubDescriptor().setStartPos(startSeq == null ? -1 : startSeq).setStartTimestamp(startTimestamp)
                .setMatcher(matcher).setDurableID(durableID).setChannel(channel);
        int subID = subSeq++;
        checkWrap(subSeq);
        Log log = server.getLog(channel);
        if (log == null) {
            // TODO send error back to client
            throw new IllegalStateException("No such channel " + channel);
        }
        ReadStream readStream = log.subscribe(subDescriptor);
        SubscriptionImpl subscription = new SubscriptionImpl(this, subID, readStream);
        subscriptionMap.put(subID, subscription);
        BsonObject resp = new BsonObject();
        resp.put(Codec.RESPONSE_OK, true);
        resp.put(Codec.SUBRESPONSE_SUBID, subID);
        writeResponse(Codec.SUBRESPONSE_FRAME, resp, getWriteSeq());
        logger.trace("Subscribed channel: {} startSeq {}", channel, startSeq);
    }

    @Override
    public void handleUnsubscribe(BsonObject frame) {
        checkContext();
        checkAuthorised();
        String subID = frame.getString(Codec.UNSUBSCRIBE_SUBID);
        if (subID == null) {
            logAndClose("No subID in UNSUBSCRIBE");
            return;
        }
        SubscriptionImpl subscription = subscriptionMap.remove(subID);
        if (subscription == null) {
            logAndClose("Invalid subID in UNSUBSCRIBE");
            return;
        }
        subscription.close();
        BsonObject resp = new BsonObject();
        resp.put(Codec.RESPONSE_OK, true);
        writeResponse(Codec.RESPONSE_FRAME, resp, getWriteSeq());
    }

    @Override
    public void handleAckEv(BsonObject frame) {
        checkContext();
        checkAuthorised();
        String subID = frame.getString(Codec.ACKEV_SUBID);
        if (subID == null) {
            logAndClose("No subID in ACKEV");
            return;
        }
        Integer bytes = frame.getInteger(Codec.ACKEV_BYTES);
        if (bytes == null) {
            logAndClose("No bytes in ACKEV");
            return;
        }
        SubscriptionImpl subscription = subscriptionMap.get(subID);
        if (subscription == null) {
            logAndClose("Invalid subID in ACKEV");
            return;
        }
        subscription.handleAckEv(bytes);
    }

    @Override
    public void handleQuery(BsonObject frame) {
        checkContext();
        checkAuthorised();
        int queryID = frame.getInteger(Codec.QUERY_QUERYID);
        String binder = frame.getString(Codec.QUERY_BINDER);
        BsonObject matcher = frame.getBsonObject(Codec.QUERY_MATCHER);

        // TODO currently hardcoded to assume get by ID, implement properly for other query types
        String documentId = matcher.getBsonObject("$match").getString("id");
        CompletableFuture<BsonObject> cf = docManager.findByID(binder, documentId);
        long writeSeq = getWriteSeq();

        cf.thenAccept(result -> {
            if (result == null) {
                BsonObject resp = new BsonObject();
                resp.put(Codec.QUERYRESPONSE_QUERYID, queryID);
                resp.put(Codec.QUERYRESPONSE_NUMRESULTS, 0);
                writeResponse(Codec.QUERYRESPONSE_FRAME, resp, writeSeq);
            } else {
                BsonObject resp = new BsonObject();
                resp.put(Codec.QUERYRESPONSE_QUERYID, queryID);
                resp.put(Codec.QUERYRESPONSE_NUMRESULTS, 1);
                writeResponse(Codec.QUERYRESPONSE_FRAME, resp, writeSeq);

                BsonObject res = new BsonObject();
                res.put(Codec.QUERYRESULT_QUERYID, queryID);
                res.put(Codec.QUERYRESULT_RESULT, result);
                writeNonResponse(Codec.QUERYRESULT_FRAME, res);
            }
        });
    }

    @Override
    public void handleQueryAck(BsonObject frame) {
        checkContext();
        checkAuthorised();

        Integer queryID = frame.getInteger(Codec.QUERYACK_QUERYID);

        if (queryID == null) {
            logAndClose("No queryID in QueryAck");
        }
    }

    @Override
    public void handlePing(BsonObject frame) {
        checkContext();
        checkAuthorised();
    }

    protected Buffer writeNonResponse(String frameName, BsonObject frame) {
        Buffer buff = Codec.encodeFrame(frameName, frame);
        // TODO compare performance of writing directly in all cases and via context
        Context curr = Vertx.currentContext();
        if (curr != context) {
            context.runOnContext(v -> socket.write(buff));
        } else {
            socket.write(buff);
        }
        return buff;
    }

    protected void writeResponse(String frameName, BsonObject frame, long order) {
        Buffer buff = Codec.encodeFrame(frameName, frame);
        // TODO compare performance of writing directly in all cases and via context
        Context curr = Vertx.currentContext();
        if (curr != context) {
            context.runOnContext(v -> writeResponseOrdered(buff, order));
        } else {
            writeResponseOrdered(buff, order);
        }
    }

    protected void writeResponseOrdered(Buffer buff, long order) {
        checkContext();
        // Writes can come in in the wrong order, we need to make sure they are written in the correct order
        if (order == expectedRespNo) {
            writeResponseOrdered0(buff);
        } else {
            // Out of order
            pq.add(new WriteHolder(order, buff));
        }
        while (true) {
            WriteHolder head = pq.peek();
            if (head != null && head.order == expectedRespNo) {
                pq.poll();
                writeResponseOrdered0(head.buff);
            } else {
                break;
            }
        }
    }

    protected long getWriteSeq() {
        long seq = writeSeq;
        writeSeq++;
        checkWrap(writeSeq);
        return seq;
    }

    protected void checkWrap(int i) {
        // Sanity check - wrap around - won't happen but better to close connection that give incorrect behaviour
        if (i == Integer.MIN_VALUE) {
            String msg = "int wrapped!";
            logAndClose(msg);
            throw new IllegalStateException(msg);
        }
    }

    protected void checkWrap(long l) {
        // Sanity check - wrap around - won't happen but better to close connection that give incorrect behaviour
        if (l == Long.MIN_VALUE) {
            String msg = "long wrapped!";
            logAndClose(msg);
            throw new IllegalStateException(msg);
        }
    }

    protected void writeResponseOrdered0(Buffer buff) {
        socket.write(buff);
        expectedRespNo++;
        checkWrap(expectedRespNo);
    }

    protected void checkAuthorised() {
        if (!authorised) {
            logger.error("Attempt to use unauthorised connection.");
        }
    }

    protected void logAndClose(String errMsg) {
        logger.warn(errMsg + ". connection will be closed");
        close();
    }

    protected void close() {
        authorised = false;
        socket.close();
        server.removeConnection(this);
    }

    private static final class WriteHolder implements Comparable<WriteHolder> {
        final long order;
        final Buffer buff;

        public WriteHolder(long order, Buffer buff) {
            this.order = order;
            this.buff = buff;
        }

        @Override
        public int compareTo(WriteHolder other) {
            return Long.compare(this.order, other.order);
        }
    }

    // Sanity check - this should always be executed using the correct context
    private void checkContext() {
        if (Vertx.currentContext() != context) {
            throw new IllegalStateException("Wrong context!");
        }
    }

}
