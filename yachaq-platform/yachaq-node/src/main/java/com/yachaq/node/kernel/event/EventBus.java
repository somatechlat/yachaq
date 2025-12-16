package com.yachaq.node.kernel.event;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Internal event bus for module communication.
 * Requirement 302.3: Route all module communication through event bus.
 */
public class EventBus {

    private final Map<KernelEventType, CopyOnWriteArrayList<Subscription>> subscriptions;
    private final Map<String, Subscription> subscriptionById;
    private final ExecutorService executor;

    public EventBus() {
        this.subscriptions = new ConcurrentHashMap<>();
        this.subscriptionById = new ConcurrentHashMap<>();
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * Emits an event to all subscribers.
     * 
     * @param event Event to emit
     */
    public void emit(KernelEvent event) {
        if (event == null) {
            return;
        }
        
        CopyOnWriteArrayList<Subscription> subs = subscriptions.get(event.eventType());
        if (subs != null) {
            for (Subscription sub : subs) {
                executor.submit(() -> {
                    try {
                        sub.handler().accept(event);
                    } catch (Exception e) {
                        // Log but don't propagate handler exceptions
                        System.err.println("Event handler error: " + e.getMessage());
                    }
                });
            }
        }
        
        // Also emit to wildcard subscribers
        CopyOnWriteArrayList<Subscription> wildcardSubs = subscriptions.get(KernelEventType.ALL);
        if (wildcardSubs != null) {
            for (Subscription sub : wildcardSubs) {
                executor.submit(() -> {
                    try {
                        sub.handler().accept(event);
                    } catch (Exception e) {
                        System.err.println("Wildcard handler error: " + e.getMessage());
                    }
                });
            }
        }
    }

    /**
     * Subscribes to events of a specific type.
     * 
     * @param eventType Type of events to subscribe to
     * @param handler Handler to invoke
     * @return Subscription ID
     */
    public String subscribe(KernelEventType eventType, Consumer<KernelEvent> handler) {
        String subscriptionId = UUID.randomUUID().toString();
        Subscription subscription = new Subscription(subscriptionId, eventType, handler);
        
        subscriptions.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(subscription);
        subscriptionById.put(subscriptionId, subscription);
        
        return subscriptionId;
    }

    /**
     * Unsubscribes from events.
     * 
     * @param subscriptionId Subscription ID
     */
    public void unsubscribe(String subscriptionId) {
        Subscription subscription = subscriptionById.remove(subscriptionId);
        if (subscription != null) {
            CopyOnWriteArrayList<Subscription> subs = subscriptions.get(subscription.eventType());
            if (subs != null) {
                subs.remove(subscription);
            }
        }
    }

    /**
     * Gets the count of subscribers for an event type.
     */
    public int getSubscriberCount(KernelEventType eventType) {
        CopyOnWriteArrayList<Subscription> subs = subscriptions.get(eventType);
        return subs != null ? subs.size() : 0;
    }

    /**
     * Shuts down the event bus executor.
     */
    public void shutdown() {
        executor.shutdown();
    }

    private record Subscription(
            String id,
            KernelEventType eventType,
            Consumer<KernelEvent> handler
    ) {}
}
