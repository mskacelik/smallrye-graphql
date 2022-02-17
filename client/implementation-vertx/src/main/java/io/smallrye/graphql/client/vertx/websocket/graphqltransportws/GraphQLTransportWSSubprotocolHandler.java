package io.smallrye.graphql.client.vertx.websocket.graphqltransportws;

import java.io.StringReader;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.stream.JsonParsingException;

import org.jboss.logging.Logger;

import io.smallrye.graphql.client.GraphQLClientException;
import io.smallrye.graphql.client.GraphQLError;
import io.smallrye.graphql.client.InvalidResponseException;
import io.smallrye.graphql.client.impl.ResponseReader;
import io.smallrye.graphql.client.vertx.websocket.WebSocketSubprotocolHandler;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.MultiEmitter;
import io.smallrye.mutiny.subscription.UniEmitter;
import io.vertx.core.http.WebSocket;

/**
 * Implementation of the `graphql-transport-ws` protocol. The protocol specification is at
 * https://github.com/enisdenjo/graphql-ws/blob/master/PROTOCOL.md
 */
public class GraphQLTransportWSSubprotocolHandler implements WebSocketSubprotocolHandler {

    private static final Logger log = Logger.getLogger(GraphQLTransportWSSubprotocolHandler.class);

    private final Integer connectionInitializationTimeout;

    private JsonObject connectionInitMessage;
    private JsonObject pongMessage;

    private final WebSocket webSocket;
    private final CompletableFuture<Void> initialization;

    private final Map<String, UniEmitter<? super String>> uniOperations;
    private final Map<String, MultiEmitter<? super String>> multiOperations;

    public GraphQLTransportWSSubprotocolHandler(WebSocket webSocket, Integer subscriptionInitializationTimeout) {
        this.webSocket = webSocket;
        this.connectionInitializationTimeout = subscriptionInitializationTimeout;
        this.uniOperations = new ConcurrentHashMap<>();
        this.multiOperations = new ConcurrentHashMap<>();
        this.initialization = initialize().subscribeAsCompletionStage();
    }

    @Override
    public Uni<Void> ensureInitialized() {
        return Uni.createFrom().completionStage(initialization);
    }

    private Uni<Void> initialize() {
        return Uni.createFrom().emitter(initializationEmitter -> {
            if (log.isTraceEnabled()) {
                log.trace("Initializing websocket with graphql-transport-ws protocol");
            }
            connectionInitMessage = Json.createObjectBuilder().add("type", "connection_init").build();
            pongMessage = Json.createObjectBuilder().add("type", "pong")
                    .add("payload", Json.createObjectBuilder().add("message", "keepalive")).build();

            webSocket.closeHandler((v) -> {
                if (webSocket.closeStatusCode() != null) {
                    if (webSocket.closeStatusCode() == 1000) {
                        log.debug("WebSocket closed with status code 1000");
                        // even if the status code is OK, any unfinished single-result operation
                        // should be marked as failed
                        uniOperations.forEach((id, emitter) -> emitter.fail(
                                new InvalidResponseException("Connection closed before data was received")));
                        multiOperations.forEach((id, emitter) -> emitter.complete());
                    } else {
                        InvalidResponseException exception = new InvalidResponseException(
                                "Server closed the websocket connection with code: "
                                        + webSocket.closeStatusCode() + " and reason: " + webSocket.closeReason());
                        uniOperations.forEach((id, emitter) -> emitter.fail(exception));
                        multiOperations.forEach((id, emitter) -> emitter.fail(exception));
                    }
                } else {
                    InvalidResponseException exception = new InvalidResponseException("Connection closed");
                    uniOperations.forEach((id, emitter) -> emitter.fail(exception));
                    multiOperations.forEach((id, emitter) -> emitter.fail(exception));
                }
            });
            webSocket.exceptionHandler(this::failAllActiveOperationsWith);

            send(webSocket, connectionInitMessage);

            // set up a timeout for subscription initialization
            Cancellable timeoutWaitingForConnectionAckMessage = null;
            if (connectionInitializationTimeout != null) {
                timeoutWaitingForConnectionAckMessage = Uni.createFrom().item(1).onItem().delayIt()
                        .by(Duration.ofMillis(connectionInitializationTimeout))
                        .subscribe().with(timeout -> {
                            initializationEmitter
                                    .fail(new InvalidResponseException("Server did not send a connection_ack message"));
                            webSocket.close((short) 1002, "Timeout waiting for a connection_ack message");
                        });
            }
            // make an effectively final copy of this value to use it in a lambda expression
            Cancellable finalTimeoutWaitingForConnectionAckMessage = timeoutWaitingForConnectionAckMessage;

            webSocket.handler(text -> {
                if (log.isTraceEnabled()) {
                    log.trace("<<< " + text);
                }
                try {
                    JsonObject message = parseIncomingMessage(text.toString());
                    MessageType messageType = getMessageType(message);
                    switch (messageType) {
                        case PING:
                            send(webSocket, pongMessage);
                            break;
                        case CONNECTION_ACK:
                            // TODO: somehow protect against this being invoked multiple times?
                            if (finalTimeoutWaitingForConnectionAckMessage != null) {
                                finalTimeoutWaitingForConnectionAckMessage.cancel();
                            }
                            initializationEmitter.complete(null);
                            break;
                        case NEXT:
                            handleData(message.getString("id"), message.getJsonObject("payload"));
                            break;
                        case ERROR:
                            handleOperationError(message.getString("id"), message.getJsonArray("payload"));
                            break;
                        case COMPLETE:
                            handleComplete(message.getString("id"));
                            break;
                        case CONNECTION_INIT:
                        case PONG:
                        case SUBSCRIBE:
                            break;
                    }
                } catch (JsonParsingException | IllegalArgumentException e) {
                    log.error("Unexpected message from server: " + text);
                    // should we fail the operations here?
                }
            });
        });
    }

    private void handleData(String operationId, JsonObject data) {
        // If this is a uni operation, we remove it right away from the active operation map,
        // even though we still should receive a 'complete' message later - we don't wait for it.
        // This is to prevent a potential memory leak in case that the server doesn't actually send it.
        UniEmitter<? super String> uniEmitter = uniOperations.remove(operationId);
        if (uniEmitter != null) {
            if (log.isTraceEnabled()) {
                log.trace("Received data for single-result operation " + operationId);
            }
            uniEmitter.complete(data.toString());
        } else {
            MultiEmitter<? super String> multiEmitter = multiOperations.get(operationId);
            if (multiEmitter != null) {
                if (multiEmitter.isCancelled()) {
                    log.warn("Received data for already cancelled operation " + operationId);
                } else {
                    multiEmitter.emit(data.toString());
                }
            } else {
                log.warn("Received event for an unknown subscription ID: " + operationId);
            }
        }
    }

    private void handleOperationError(String operationId, JsonArray errors) {
        List<GraphQLError> parsedErrors = errors.stream().map(ResponseReader::readError).collect(Collectors.toList());
        GraphQLClientException exception = new GraphQLClientException("Received an error", parsedErrors);
        UniEmitter<? super String> emitter = uniOperations.remove(operationId);
        if (emitter != null) {
            emitter.fail(exception);
        } else {
            MultiEmitter<? super String> multiEmitter = multiOperations.remove(operationId);
            if (multiEmitter != null) {
                multiEmitter.fail(exception);
            }
        }
    }

    private void handleComplete(String operationId) {
        UniEmitter<? super String> emitter = uniOperations.remove(operationId);
        if (emitter != null) {
            // For a uni operation, we should have received a 'next' message before the 'complete' message.
            // If that happened, the emitter was already completed and operation removed from the map.
            // If that didn't happen, then this is an issue with the server, let's fail the operation then.
            emitter.fail(new InvalidResponseException("Protocol error: received a 'complete' message for" +
                    " this operation before the actual data"));
        } else {
            MultiEmitter<? super String> multiEmitter = multiOperations.remove(operationId);
            if (multiEmitter != null) {
                log.debug("Completed operation " + operationId);
                multiEmitter.complete();
            }
        }
    }

    private void cancelUni(String id) {
        send(webSocket, createCompleteMessage(id));
        uniOperations.remove(id);
    }

    private void cancelMulti(String id) {
        send(webSocket, createCompleteMessage(id));
        multiOperations.remove(id);
    }

    private void failAllActiveOperationsWith(Throwable throwable) {
        log.debug("Failing all active operations");
        for (String s : uniOperations.keySet()) {
            UniEmitter<? super String> emitter = uniOperations.remove(s);
            if (emitter != null) {
                emitter.fail(throwable);
            }
        }
        for (String s : multiOperations.keySet()) {
            MultiEmitter<? super String> emitter = multiOperations.remove(s);
            if (emitter != null) {
                emitter.fail(throwable);
            }
        }
    }

    @Override
    public void executeUni(JsonObject request, UniEmitter<? super String> emitter) {
        String id = UUID.randomUUID().toString();
        ensureInitialized().subscribe().with(ready -> {
            uniOperations.put(id, emitter);
            JsonObject subscribe = createSubscribeMessage(request, id);
            send(webSocket, subscribe);
        }, emitter::fail);
        emitter.onTermination(() -> {
            cancelUni(id);
        });
    }

    @Override
    public void executeMulti(JsonObject request, MultiEmitter<? super String> emitter) {
        String id = UUID.randomUUID().toString();
        ensureInitialized().subscribe().with(ready -> {
            multiOperations.put(id, emitter);
            JsonObject subscribe = createSubscribeMessage(request, id);
            send(webSocket, subscribe);
        }, emitter::fail);
        emitter.onTermination(() -> {
            cancelMulti(id);
        });
    }

    @Override
    public void close() {
        if (webSocket != null && !webSocket.isClosed()) {
            webSocket.close((short) 1000);
        }
    }

    private MessageType getMessageType(JsonObject message) {
        return MessageType.fromString(message.getString("type"));
    }

    private JsonObject parseIncomingMessage(String message) {
        return Json.createReader(new StringReader(message)).readObject();
    }

    private JsonObject createSubscribeMessage(JsonObject request, String id) {
        JsonObjectBuilder payload = Json.createObjectBuilder();

        payload.add("query", request.getString("query"));
        JsonValue operationName = request.get("operationName");
        if (operationName instanceof JsonString) {
            payload.add("operationName", operationName);
        }
        JsonObject variables = request.getJsonObject("variables");
        if (variables != null) {
            payload.add("variables", variables);
        }
        return Json.createObjectBuilder()
                .add("type", "subscribe")
                .add("id", id)
                .add("payload", payload)
                .build();
    }

    private JsonObject createCompleteMessage(String id) {
        return Json.createObjectBuilder()
                .add("type", "complete")
                .add("id", id)
                .build();
    }

    private void send(WebSocket webSocket, JsonObject message) {
        String string = message.toString();
        if (log.isTraceEnabled()) {
            log.trace(">>> " + string);
        }
        webSocket.writeTextMessage(string);
    }

}
