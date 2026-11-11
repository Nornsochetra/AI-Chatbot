package com.kbprasac.chatbot.handover.service;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongFunction;

import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open Server-Sent Events connections, grouped by topic ("session:…" for the widget, "handover:…" for the
 * agent console). Each connection remembers the last message id it received, so a push only sends what is new.
 * R&D: in memory, one backend instance. Several instances need a shared channel (e.g. Redis pub/sub).
 */
@Component
public class LiveEventHub {

    /** the browser reconnects by itself after this, resuming from the last event id */
    static final long TIMEOUT_MS = 30 * 60 * 1000L;

    /** An open connection and the last message id sent on it. */
    public static final class Subscriber {
        private final SseEmitter emitter;
        private long cursor;

        Subscriber(SseEmitter emitter, long cursor) {
            this.emitter = emitter;
            this.cursor = cursor;
        }

        public SseEmitter emitter() {
            return emitter;
        }
    }

    /** One event to send; {@code cursor} becomes the connection's new position and the SSE event id. */
    public record Push(String name, Object data, long cursor) {
    }

    private final Map<String, Set<Subscriber>> topics = new ConcurrentHashMap<>();

    public Subscriber subscribe(String topic, long cursor) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Subscriber subscriber = new Subscriber(emitter, cursor);
        topics.computeIfAbsent(topic, key -> ConcurrentHashMap.newKeySet()).add(subscriber);
        emitter.onCompletion(() -> remove(topic, subscriber));
        emitter.onTimeout(() -> {
            remove(topic, subscriber);
            emitter.complete();
        });
        emitter.onError(error -> remove(topic, subscriber));
        return subscriber;
    }

    /** Sends {@code next.apply(cursor)} to every connection of the topic; a null result sends nothing. */
    public void publish(String topic, LongFunction<Push> next) {
        for (Subscriber subscriber : topics.getOrDefault(topic, Set.of())) {
            push(topic, subscriber, next);
        }
    }

    public void push(String topic, Subscriber subscriber, LongFunction<Push> next) {
        // one push at a time per connection, so two quick changes cannot send the same message twice
        synchronized (subscriber) {
            Push push = next.apply(subscriber.cursor);
            if (push == null) {
                return;
            }
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .name(push.name())
                    .id(String.valueOf(push.cursor()))
                    .data(push.data(), MediaType.APPLICATION_JSON);
            if (send(topic, subscriber, event)) {
                subscriber.cursor = push.cursor();
            }
        }
    }

    /** An event without a position (e.g. "typing"); not replayed after a reconnect. */
    public void broadcast(String topic, String name, Object data) {
        for (Subscriber subscriber : topics.getOrDefault(topic, Set.of())) {
            synchronized (subscriber) {
                send(topic, subscriber, SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            }
        }
    }

    /** Keeps idle connections open through proxies and notices closed browsers. */
    @Scheduled(fixedDelay = 20_000, initialDelay = 20_000)
    void heartbeat() {
        topics.forEach((topic, subscribers) -> subscribers.forEach(subscriber -> {
            synchronized (subscriber) {
                send(topic, subscriber, SseEmitter.event().comment("ping"));
            }
        }));
    }

    int connectionCount(String topic) {
        return topics.getOrDefault(topic, Set.of()).size();
    }

    private boolean send(String topic, Subscriber subscriber, SseEmitter.SseEventBuilder event) {
        try {
            subscriber.emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            // the browser went away
            remove(topic, subscriber);
            return false;
        }
    }

    private void remove(String topic, Subscriber subscriber) {
        topics.computeIfPresent(topic, (key, subscribers) -> {
            subscribers.remove(subscriber);
            return subscribers.isEmpty() ? null : subscribers;
        });
    }
}
