/*
 * Copyright 2013 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.cache;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.BroadcasterCache;
import org.atmosphere.cpr.BroadcasterConfig;
import org.atmosphere.util.ExecutorsFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * An improved {@link BroadcasterCache} implementation that is based on the unique identifier (UUID) that all
 * {@link AtmosphereResource}s have.
 *
 * @author Paul Khodchenkov
 * @author Jeanfrancois Arcand
 */
public class UUIDBroadcasterCache implements BroadcasterCache {

    private final static Logger logger = LoggerFactory.getLogger(UUIDBroadcasterCache.class);

    private final Map<String, ClientQueue> messages = new ConcurrentHashMap<String, ClientQueue>();
    private final Map<String, Long> activeClients = new ConcurrentHashMap<String, Long>();
    protected final List<BroadcasterCacheInspector> inspectors = new LinkedList<BroadcasterCacheInspector>();
    private ScheduledFuture scheduledFuture;
    protected ScheduledExecutorService taskScheduler;
    private long clientIdleTime = TimeUnit.SECONDS.toMillis(60); // 1 minutes
    private long invalidateCacheInterval = TimeUnit.SECONDS.toMillis(30); // 30 seconds
    private boolean shared = true;
    protected final List<Object> emptyList = Collections.<Object>emptyList();

    public final static class ClientQueue {

        private final LinkedList<CacheMessage> queue = new LinkedList<CacheMessage>();
        private final Set<String> ids = new HashSet<String>();

        public LinkedList<CacheMessage> getQueue() {
            return queue;
        }

        public Set<String> getIds() {
            return ids;
        }

        @Override
        public String toString() {
            return queue.toString();
        }
    }

    @Override
    public void configure(BroadcasterConfig config) {
        Object o = config.getAtmosphereConfig().properties().get("shared");
        if (o != null) {
            shared = Boolean.parseBoolean(o.toString());
        }

        if (shared) {
            taskScheduler = ExecutorsFactory.getScheduler(config.getAtmosphereConfig());
        } else {
            taskScheduler = Executors.newSingleThreadScheduledExecutor();
        }
    }

    @Override
    public void start() {
        scheduledFuture = taskScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                invalidateExpiredEntries();
            }
        }, 0, invalidateCacheInterval, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        cleanup();

        if (taskScheduler != null) {
            taskScheduler.shutdown();
        }
    }

    @Override
    public void cleanup() {
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
            scheduledFuture = null;
        }
    }

    @Override
    public CacheMessage addToCache(String broadcasterId, AtmosphereResource r, BroadcastMessage message) {

        Object e = message.message;
        if (logger.isTraceEnabled()) {
            logger.trace("Adding for AtmosphereResource {} cached messages {}", r != null ? r.uuid() : "null", e);
            logger.trace("Active clients {}", activeClients());
        }

        long now = System.currentTimeMillis();
        String messageId = UUID.randomUUID().toString();
        CacheMessage cacheMessage = new CacheMessage(messageId, e);
        if (r == null) {
            //no clients are connected right now, caching message for all active clients
            for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
                addMessageIfNotExists(entry.getKey(), cacheMessage);
            }
        } else {
            String clientId = uuid(r);

            activeClients.put(clientId, now);
            addMessageIfNotExists(clientId, cacheMessage);
        }
        return cacheMessage;
    }

    @Override
    public List<Object> retrieveFromCache(String broadcasterId, AtmosphereResource r) {
        String clientId = uuid(r);
        long now = System.currentTimeMillis();

        List<Object> result = new ArrayList<Object>();

        ClientQueue clientQueue;
        activeClients.put(clientId, now);
        clientQueue = messages.remove(clientId);
        List<CacheMessage> clientMessages;
        if (clientQueue == null) {
            clientMessages = Collections.emptyList();
        } else {
            clientMessages = clientQueue.getQueue();
        }

        for (CacheMessage cacheMessage : clientMessages) {
            result.add(cacheMessage.getMessage());
        }

        if (logger.isTraceEnabled()) {
            logger.trace("Retrieved for AtmosphereResource {} cached messages {}", r.uuid(), result);
            logger.trace("Available cached message {}", messages);
        }

        return result;
    }

    @Override
    public void clearCache(String broadcasterId, AtmosphereResource r, CacheMessage message) {
        if (message == null) {
            AtmosphereResourceEvent e = r.getAtmosphereResourceEvent();
            logger.trace("Cached message is null for {}, but event was {}", r.uuid(), e == null ? "null" : e.getMessage());
            return;
        }

        String clientId = uuid(r);
        ClientQueue clientQueue;
        clientQueue = messages.get(clientId);
        if (clientQueue != null) {
            logger.trace("Removing for AtmosphereResource {} cached message {}", r.uuid(), message.getMessage());
            clientQueue.getQueue().remove(message);
        }
    }

    @Override
    public BroadcasterCache inspector(BroadcasterCacheInspector b) {
        inspectors.add(b);
        return this;
    }

    protected String uuid(AtmosphereResource r) {
        return r.uuid();
    }

    private void addMessageIfNotExists(String clientId, CacheMessage message) {
        if (!hasMessage(clientId, message.getId())) {
            addMessage(clientId, message);
        } else {
            logger.debug("Duplicate message {} for client {}", clientId, message);
        }
    }

    private void addMessage(String clientId, CacheMessage message) {
        ClientQueue clientQueue = messages.get(clientId);
        if (clientQueue == null) {
            clientQueue = new ClientQueue();
            messages.put(clientId, clientQueue);
        }
        clientQueue.getQueue().addLast(message);
        clientQueue.getIds().add(message.getId());
    }

    private boolean hasMessage(String clientId, String messageId) {
        ClientQueue clientQueue = messages.get(clientId);
        return clientQueue != null && clientQueue.getIds().contains(messageId);
    }

    public Map<String, ClientQueue> messages() {
        return messages;
    }

    public Map<String, Long> activeClients() {
        return activeClients;
    }

    protected boolean inspect(BroadcastMessage m) {
        for (BroadcasterCacheInspector b : inspectors) {
            if (!b.inspect(m)) return false;
        }
        return true;
    }

    public void setInvalidateCacheInterval(long invalidateCacheInterval) {
        this.invalidateCacheInterval = invalidateCacheInterval;
        scheduledFuture.cancel(true);
        start();
    }

    public void setClientIdleTime(long clientIdleTime) {
        this.clientIdleTime = clientIdleTime;
    }

    protected void invalidateExpiredEntries() {
        long now = System.currentTimeMillis();

        Set<String> inactiveClients = new HashSet<String>();
        for (Map.Entry<String, Long> entry : activeClients.entrySet()) {
            if (now - entry.getValue() > clientIdleTime) {
                logger.trace("Invalidate client {}", entry.getKey());
                inactiveClients.add(entry.getKey());
            }
        }

        for (String clientId : inactiveClients) {
            activeClients.remove(clientId);
            messages.remove(clientId);
        }

    }

    @Override
    public void excludeFromCache(String broadcasterId, AtmosphereResource r) {
        activeClients.remove(r.uuid());
    }

    @Override
    public String toString() {
        return this.getClass().getName();
    }
}
