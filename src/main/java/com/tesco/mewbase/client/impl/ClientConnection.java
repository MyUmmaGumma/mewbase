package com.tesco.mewbase.client.impl;

import com.tesco.mewbase.bson.BsonObject;
import com.tesco.mewbase.client.*;
import com.tesco.mewbase.common.SubDescriptor;
import com.tesco.mewbase.server.impl.Codec;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Created by tim on 22/09/16.
 */
public class ClientConnection implements Connection, ClientFrameHandler {

    private final static Logger log = LoggerFactory.getLogger(ClientConnection.class);


    private final AtomicInteger sessionSeq = new AtomicInteger();
    private final AtomicInteger nextQueryId = new AtomicInteger();
    private final NetSocket netSocket;
    private final Codec codec;
    private final Map<Integer, ProducerImpl> producerMap = new ConcurrentHashMap<>();
    private final Map<Integer, SubscriptionImpl> subscriptionMap = new ConcurrentHashMap<>();
    private final Queue<Consumer<BsonObject>> respQueue = new ConcurrentLinkedQueue<>();
    private final Map<Integer, CompletableFuture<BsonObject>> expectedQueryResults = new ConcurrentHashMap<>();
    private final Context context;

    public ClientConnection(NetSocket netSocket) {
        this.netSocket = netSocket;
        this.codec = new Codec(netSocket, this);
        this.context = Vertx.currentContext();
    }

    @Override
    public Producer createProducer(String channel) {
        int id = sessionSeq.getAndIncrement();
        ProducerImpl prod = new ProducerImpl(this, channel, id);
        producerMap.put(id, prod);
        return prod;
    }

    @Override
    public CompletableFuture<Subscription> subscribe(SubDescriptor descriptor) {
        CompletableFuture<Subscription> cf = new CompletableFuture<>();
        BsonObject frame = new BsonObject();
        if (descriptor.getChannel() == null) {
            throw new IllegalArgumentException("No channel in SubDescriptor");
        }
        frame.put(Codec.SUBSCRIBE_CHANNEL, descriptor.getChannel());
        frame.put(Codec.SUBSCRIBE_STARTPOS, descriptor.getStartPos());
        frame.put(Codec.SUBSCRIBE_STARTTIMESTAMP, descriptor.getStartTimestamp());
        frame.put(Codec.SUBSCRIBE_DURABLEID, descriptor.getDurableID());
        frame.put(Codec.SUBSCRIBE_MATCHER, descriptor.getMatcher());
        Buffer buffer = Codec.encodeFrame(Codec.SUBSCRIBE_FRAME, frame);
        write(buffer, resp -> {
            boolean ok = resp.getBoolean(Codec.RESPONSE_OK);
            if (ok) {
                int subID = resp.getInteger(Codec.SUBRESPONSE_SUBID);
                SubscriptionImpl sub = new SubscriptionImpl(subID, descriptor.getChannel(), this);
                subscriptionMap.put(subID, sub);
                cf.complete(sub);
            } else {
                cf.completeExceptionally(new MewException(resp.getString(Codec.RESPONSE_ERRMSG), resp.getString(Codec.RESPONSE_ERRCODE)));
            }
        });
        return cf;
    }

    @Override
    public CompletableFuture<Void> emit(String channel, BsonObject event) {
        return doEmit(channel, -1, event);
    }

    @Override
    public CompletableFuture<Void> emit(String channel, BsonObject event, Function<BsonObject, String> partitionFunc) {
        // TODO partitions
        return doEmit(channel, -1, event);
    }

    @Override
    public CompletableFuture<BsonObject> getByID(String binderName, String id) {
        CompletableFuture<BsonObject> cf = new CompletableFuture<>();
        BsonObject matcher = new BsonObject().put("$match", new BsonObject().put("id", id));
        int queryID = nextQueryId.getAndIncrement();

        BsonObject frame = new BsonObject();
        frame.put(Codec.QUERY_QUERYID, queryID);
        frame.put(Codec.QUERY_BINDER, binderName);
        frame.put(Codec.QUERY_MATCHER, matcher);

        Buffer buffer = Codec.encodeFrame(Codec.QUERY_FRAME, frame);

        write(buffer, resp -> {
            if (resp.getInteger(Codec.QUERYRESPONSE_QUERYID) != queryID) {
                cf.completeExceptionally(new IllegalStateException("Result query ID does not match handler expectation"));
                return;
            }

            Integer numResults = resp.getInteger(Codec.QUERYRESPONSE_NUMRESULTS);
            if (numResults == 1) {
                expectedQueryResults.put(queryID, cf);
            } else if (numResults == 0) {
                cf.complete(null);
            } else {
                cf.completeExceptionally(new IllegalStateException("Invalid result count for get by ID"));
            }
        });

        return cf;
    }

    @Override
    public CompletableFuture<BsonObject> getByMatch(String binderName, String id) {
        return null;
    }

    @Override
    public CompletableFuture<QueryResult> getAllMatching(String binderName, BsonObject matcher) {
        return null;
    }

    @Override
    public CompletableFuture<Void> close() {
        return null;
    }

    // FrameHandler


    @Override
    public void handleRecev(BsonObject frame) {
        int subID = frame.getInteger(Codec.RECEV_SUBID);
        SubscriptionImpl sub = subscriptionMap.get(subID);
        if (sub == null) {
            // No subscription for this - maybe closed - ignore
        } else {
            sub.handleRecevFrame(frame);
        }
    }

    @Override
    public void handleQueryResponse(BsonObject frame) {
        handleResponse(frame);
    }

    @Override
    public void handleQueryResult(BsonObject frame) {
        int queryID = frame.getInteger(Codec.QUERYRESULT_QUERYID);
        CompletableFuture<BsonObject> cf = expectedQueryResults.get(queryID);
        if (cf != null) {
            cf.complete(frame.getBsonObject(Codec.QUERYRESULT_RESULT));
            doQueryAck(queryID);
            expectedQueryResults.remove(queryID);
        } else {
            throw new IllegalStateException("Received unexpected query result");
        }
    }

    @Override
    public void handlePing(BsonObject frame) {

    }

    @Override
    public void handleSubResponse(BsonObject frame) {
        handleResponse(frame);
    }

    @Override
    public void handleResponse(BsonObject frame) {
        Consumer<BsonObject> respHandler = respQueue.poll();
        if (respHandler == null) {
            throw new IllegalStateException("Unexpected response");
        }
        respHandler.accept(frame);
    }

    // Must be synchronized to prevent interleaving
    protected synchronized void write(Buffer buff, Consumer<BsonObject> respHandler) {
        respQueue.add(respHandler);
        netSocket.write(buff);
    }


    protected void doUnsubscribe(int subID) {
        subscriptionMap.remove(subID);
        BsonObject frame = new BsonObject();
        frame.put("SubID", subID);
        Buffer buffer = Codec.encodeFrame(Codec.UNSUBSCRIBE_FRAME, frame);
        netSocket.write(buffer);
    }

    protected synchronized void doConnect(CompletableFuture<Connection> cf) {
        BsonObject frame = new BsonObject();
        frame.put(Codec.CONNECT_VERSION, "0.1");
        Buffer buffer = Codec.encodeFrame(Codec.CONNECT_FRAME, frame);
        write(buffer, resp -> {
            boolean ok = resp.getBoolean(Codec.RESPONSE_OK);
            if (ok) {
                cf.complete(ClientConnection.this);
            } else {
                cf.completeExceptionally(new MewException(resp.getString(Codec.RESPONSE_ERRMSG), resp.getString(Codec.RESPONSE_ERRCODE)));
            }
        });
    }

    protected void doAckEv(int subID, int sizeBytes) {
        BsonObject frame = new BsonObject();
        frame.put(Codec.ACKEV_SUBID, subID);
        frame.put(Codec.ACKEV_BYTES, sizeBytes);
        Buffer buffer = Codec.encodeFrame(Codec.ACKEV_FRAME, frame);
        netSocket.write(buffer);
    }

    protected void doQueryAck(int queryID) {
        BsonObject frame = new BsonObject();
        frame.put(Codec.QUERYACK_QUERYID, queryID);
        Buffer buffer = Codec.encodeFrame(Codec.QUERYACK_FRAME, frame);
        netSocket.write(buffer);
    }

    protected CompletableFuture<Void> doEmit(String channel, int producerID, BsonObject event) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        BsonObject frame = new BsonObject();
        frame.put(Codec.EMIT_CHANNEL, channel);
        frame.put(Codec.EMIT_SESSID, producerID);
        frame.put(Codec.EMIT_EVENT, event);
        Buffer buffer = Codec.encodeFrame(Codec.EMIT_FRAME, frame);
        write(buffer, resp -> {
            boolean ok = resp.getBoolean(Codec.RESPONSE_OK);
            if (ok) {
                cf.complete(null);
            } else {
                cf.completeExceptionally(new MewException(resp.getString(Codec.RESPONSE_ERRMSG), resp.getString(Codec.RESPONSE_ERRCODE)));
            }
        });
        return cf;
    }

}
