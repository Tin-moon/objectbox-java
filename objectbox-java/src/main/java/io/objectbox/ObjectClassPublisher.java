/*
 * Copyright 2017 ObjectBox Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.objectbox;

import org.greenrobot.essentials.collections.MultimapSet;
import org.greenrobot.essentials.collections.MultimapSet.SetType;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Set;

import javax.annotation.Nullable;

import io.objectbox.annotation.apihint.Internal;
import io.objectbox.reactive.DataObserver;
import io.objectbox.reactive.DataPublisher;
import io.objectbox.reactive.DataPublisherUtils;
import io.objectbox.reactive.SubscriptionBuilder;

/**
 * A {@link DataPublisher} that notifies {@link DataObserver}s about changes in an entity box.
 * Publishing is requested when a subscription is {@link SubscriptionBuilder#observer(DataObserver) observed} and
 * then by {@link BoxStore} for each {@link BoxStore#txCommitted(Transaction, int[]) txCommitted}.
 * Publish requests are processed on a single thread, one at a time, in the order publishing was requested.
 */
@SuppressWarnings("rawtypes")
@Internal
class ObjectClassPublisher implements DataPublisher<Class>, Runnable {
    final BoxStore boxStore;
    final MultimapSet<Integer, DataObserver<Class>> observersByEntityTypeId = MultimapSet.create(SetType.THREAD_SAFE);
    private final Deque<PublishRequest> changesQueue = new ArrayDeque<>();
    private static class PublishRequest {
        @Nullable private final DataObserver<Class> observer;
        private final int[] entityTypeIds;
        PublishRequest(@Nullable DataObserver<Class> observer, int[] entityTypeIds) {
            this.observer = observer;
            this.entityTypeIds = entityTypeIds;
        }
    }
    volatile boolean changePublisherRunning;

    ObjectClassPublisher(BoxStore boxStore) {
        this.boxStore = boxStore;
    }

    @Override
    public void subscribe(DataObserver<Class> observer, @Nullable Object forClass) {
        if (forClass == null) {
            for (int entityTypeId : boxStore.getAllEntityTypeIds()) {
                observersByEntityTypeId.putElement(entityTypeId, (DataObserver) observer);
            }
        } else {
            int entityTypeId = boxStore.getEntityTypeIdOrThrow((Class) forClass);
            observersByEntityTypeId.putElement(entityTypeId, (DataObserver) observer);
        }
    }

    /**
     * Removes the given observer from all object classes it added itself to earlier (forClass == null).
     * This also considers weakly added observers.
     */
    public void unsubscribe(DataObserver<Class> observer, @Nullable Object forClass) {
        if (forClass != null) {
            int entityTypeId = boxStore.getEntityTypeIdOrThrow((Class) forClass);
            unsubscribe(observer, entityTypeId);
        } else {
            for (int entityTypeId : boxStore.getAllEntityTypeIds()) {
                unsubscribe(observer, entityTypeId);
            }
        }
    }

    private void unsubscribe(DataObserver<Class> observer, int entityTypeId) {
        Set<DataObserver<Class>> observers = observersByEntityTypeId.get(entityTypeId);
        DataPublisherUtils.removeObserverFromCopyOnWriteSet(observers, observer);
    }

    @Override
    public void publishSingle(DataObserver<Class> observer, @Nullable Object forClass) {
        int[] entityTypeIds = forClass != null
                ? new int[]{boxStore.getEntityTypeIdOrThrow((Class) forClass)}
                : boxStore.getAllEntityTypeIds();

        synchronized (changesQueue) {
            changesQueue.add(new PublishRequest(observer, entityTypeIds));
            // Only one thread at a time.
            if (!changePublisherRunning) {
                changePublisherRunning = true;
                boxStore.internalScheduleThread(this);
            }
        }
    }

    private void handleObserverException(Class objectClass) {
        RuntimeException newEx = new RuntimeException(
                "Observer failed while processing data for " + objectClass +
                        ". Consider using an ErrorObserver");
        // So it won't be swallowed by thread pool
        newEx.printStackTrace();
        throw newEx;
    }

    /**
     * Non-blocking: will just enqueue the changes for a separate thread.
     */
    void publish(int[] entityTypeIdsAffected) {
        synchronized (changesQueue) {
            changesQueue.add(new PublishRequest(null, entityTypeIdsAffected));
            // Only one thread at a time.
            if (!changePublisherRunning) {
                changePublisherRunning = true;
                boxStore.internalScheduleThread(this);
            }
        }
    }

    /**
     * Processes publish requests using a single thread to prevent any data generated by observers to get stale.
     * This publisher on its own can NOT deliver stale data (the entity class types do not change).
     * However, a {@link DataObserver} of this publisher might apply a {@link io.objectbox.reactive.DataTransformer}
     * which queries for data which CAN get stale if delivered out of order.
     */
    @Override
    public void run() {
        try {
            while (true) {
                PublishRequest request;
                synchronized (changesQueue) {
                    request = changesQueue.pollFirst();
                    if (request == null) {
                        changePublisherRunning = false;
                        break;
                    }
                }

                for (int entityTypeId : request.entityTypeIds) {
                    // If no specific observer specified, notify all current observers.
                    Collection<DataObserver<Class>> observers = request.observer != null
                            ? Collections.singletonList(request.observer)
                            : observersByEntityTypeId.get(entityTypeId);
                    if (observers == null || observers.isEmpty()) {
                        continue; // No observers for this entity type.
                    }

                    Class entityClass = boxStore.getEntityClassOrThrow(entityTypeId);
                    try {
                        for (DataObserver<Class> observer : observers) {
                            observer.onData(entityClass);
                        }
                    } catch (RuntimeException e) {
                        handleObserverException(entityClass);
                    }
                }
            }
        } finally {
            // Just in Case of exceptions; it's better done within synchronized for regular cases
            changePublisherRunning = false;
        }
    }
}
