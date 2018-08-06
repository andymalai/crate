/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.execution.engine.collect;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.google.common.annotations.VisibleForTesting;
import io.crate.breaker.RamAccountingContext;
import io.crate.data.BatchIterator;
import io.crate.data.ListenableRowConsumer;
import io.crate.data.Row;
import io.crate.data.RowConsumer;
import io.crate.exceptions.JobKilledException;
import io.crate.execution.dsl.phases.CollectPhase;
import io.crate.execution.dsl.phases.RoutedCollectPhase;
import io.crate.execution.jobs.CompletionState;
import io.crate.execution.jobs.SharedShardContexts;
import io.crate.execution.jobs.Task;
import io.crate.metadata.RowGranularity;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.threadpool.ThreadPool;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public class CollectTask implements Task {

    private final CollectPhase collectPhase;
    private final MapSideDataCollectOperation collectOperation;
    private final RamAccountingContext queryPhaseRamAccountingContext;
    private final ListenableRowConsumer consumer;
    private final SharedShardContexts sharedShardContexts;

    private final IntObjectHashMap<Engine.Searcher> searchers = new IntObjectHashMap<>();
    private final Object subContextLock = new Object();
    private final String threadPoolName;

    private final AtomicReference<State> currentState = new AtomicReference<>(State.CREATED);
    private final CompletableFuture<CompletionState> completionFuture;

    private BatchIterator<Row> batchIterator = null;

    public CollectTask(final CollectPhase collectPhase,
                       MapSideDataCollectOperation collectOperation,
                       RamAccountingContext queryPhaseRamAccountingContext,
                       RowConsumer consumer,
                       SharedShardContexts sharedShardContexts) {
        this.collectPhase = collectPhase;
        this.collectOperation = collectOperation;
        this.queryPhaseRamAccountingContext = queryPhaseRamAccountingContext;
        this.sharedShardContexts = sharedShardContexts;
        this.consumer = new ListenableRowConsumer(consumer);
        this.completionFuture = this.consumer.completionFuture().handle((result, ex) -> {
            closeSearchContexts();
            CompletionState completionState = new CompletionState();
            completionState.bytesUsed(queryPhaseRamAccountingContext.totalBytes());
            queryPhaseRamAccountingContext.close();
            return completionState;
        });
        this.threadPoolName = threadPoolName(collectPhase);
    }

    void addSearcher(int searcherId, Engine.Searcher searcher) {
        synchronized (subContextLock) {
            Engine.Searcher replacedSearcher = searchers.put(searcherId, searcher);
            if (replacedSearcher != null) {
                replacedSearcher.close();
                searcher.close();
                throw new IllegalArgumentException(String.format(Locale.ENGLISH,
                    "ShardCollectContext for %d already added", searcherId));
            }
        }
    }

    private void closeSearchContexts() {
        synchronized (subContextLock) {
            for (ObjectCursor<Engine.Searcher> cursor : searchers.values()) {
                cursor.value.close();
            }
            searchers.clear();
        }
    }

    @Override
    public void prepare() throws Exception {
        if (currentState.compareAndSet(State.CREATED, State.PREPARED)) {
            batchIterator = collectOperation.createIterator(collectPhase, consumer.requiresScroll(), this);
        }
    }

    @Override
    public void start() {
        if (currentState.compareAndSet(State.PREPARED, State.RUNNING)) {
            collectOperation.launch(() -> consumer.accept(batchIterator, null), threadPoolName);
        } else {
            State state = currentState.get();
            switch (state) {
                case CREATED:
                    throw new IllegalStateException("Must call \"prepare\" before calling start");

                case PREPARED:
                    throw new IllegalStateException("CollectTask is already prepared");

                case RUNNING:
                    throw new IllegalStateException("CollectTask is already started");

                case STOPPED:
                    // nothing to do
                    break;

                default:
                    throw new AssertionError("Invalid state: " + state);
            }
        }
    }

    @Override
    public void kill(@Nullable Throwable throwable) {
        State prevState = currentState.getAndSet(State.STOPPED);
        if (throwable == null) {
            throwable = new JobKilledException();
        }
        switch (prevState) {
            case CREATED:
            case PREPARED:
                consumer.accept(null, throwable);
                return;

            case RUNNING:
                batchIterator.kill(throwable);
                return;

            case STOPPED:
                // nothing to do
                break;

            default:
                throw new AssertionError("Invalid state: " + prevState);
        }
    }

    @Override
    public String name() {
        return collectPhase.name();
    }

    @Override
    public int id() {
        return collectPhase.phaseId();
    }


    @Override
    public String toString() {
        return "CollectTask{" +
               "id=" + collectPhase.phaseId() +
               ", sharedContexts=" + sharedShardContexts +
               ", consumer=" + consumer +
               ", searchContexts=" + searchers.keys() +
               '}';
    }

    public RamAccountingContext queryPhaseRamAccountingContext() {
        return queryPhaseRamAccountingContext;
    }

    public SharedShardContexts sharedShardContexts() {
        return sharedShardContexts;
    }

    @VisibleForTesting
    static String threadPoolName(CollectPhase phase) {
        if (phase instanceof RoutedCollectPhase) {
            RoutedCollectPhase collectPhase = (RoutedCollectPhase) phase;
            if (collectPhase.maxRowGranularity() == RowGranularity.NODE
                       || collectPhase.maxRowGranularity() == RowGranularity.SHARD) {
                // Node or Shard system table collector
                return ThreadPool.Names.GET;
            }
        }

        // Anything else like doc tables, INFORMATION_SCHEMA tables or sys.cluster table collector, partition collector
        return ThreadPool.Names.SEARCH;
    }

    @Override
    public CompletableFuture<CompletionState> completionFuture() {
        return completionFuture;
    }
}
