/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

package io.crate.execution.jobs;

import io.crate.Streamer;
import io.crate.data.BatchIterator;
import io.crate.data.Bucket;
import io.crate.data.InMemoryBatchIterator;
import io.crate.data.ListenableBatchIterator;
import io.crate.data.Row;
import io.crate.data.RowConsumer;
import io.crate.data.SentinelRow;
import io.crate.execution.engine.distribution.merge.BatchPagingIterator;
import io.crate.execution.engine.distribution.merge.KeyIterable;
import io.crate.execution.engine.distribution.merge.PagingIterator;
import io.netty.util.collection.IntObjectHashMap;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.GuardedBy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link PageBucketReceiver} which receives buckets from upstreams, wait to receive the page from all upstreams
 * and forwards the merged bucket results to the consumers for further processing. It then continues to receive
 * the buckets from the next page from all upstreams.
 */
public class CumulativePageBucketReceiver implements PageBucketReceiver {

    private final Object lock = new Object();
    private final String nodeName;
    private final boolean traceEnabled;
    private final int phaseId;
    private final Executor executor;
    private final Streamer<?>[] streamers;
    private final int numBuckets;
    @GuardedBy("buckets")
    private final Set<Integer> buckets;
    @GuardedBy("lock")
    private final Set<Integer> exhausted;
    @GuardedBy("buckets")
    private final Map<Integer, PageResultListener> listenersByBucketIdx;
    @GuardedBy("lock")
    private final Map<Integer, Bucket> bucketsByIdx;
    private final BatchIterator<Row> batchIterator;
    private final Logger logger;
    private final CompletableFuture<Void> processingFuture = new CompletableFuture<>();
    private final AtomicReference<CompletableFuture<Iterable<KeyIterable<Integer, Row>>>> currentLoadingPage =
        new AtomicReference<>(new CompletableFuture<>());
    private final AtomicBoolean firstFetchMore = new AtomicBoolean(true);

    private Throwable lastThrowable = null;

    public CumulativePageBucketReceiver(Logger logger,
                                        String nodeName,
                                        int phaseId,
                                        Executor executor,
                                        Streamer<?>[] streamers,
                                        RowConsumer rowConsumer,
                                        PagingIterator<Integer, Row> pagingIterator,
                                        int numBuckets) {
        this.logger = logger;
        this.nodeName = nodeName;
        this.phaseId = phaseId;
        this.executor = executor;
        this.streamers = streamers;

        this.numBuckets = numBuckets;
        this.buckets = Collections.newSetFromMap(new IntObjectHashMap<>(numBuckets));
        this.exhausted = Collections.newSetFromMap(new IntObjectHashMap<>(numBuckets));
        this.bucketsByIdx = new IntObjectHashMap<>(numBuckets);
        this.listenersByBucketIdx = new IntObjectHashMap<>(numBuckets);
        processingFuture.whenComplete((result, ex) -> {
            synchronized (buckets) {
                for (PageResultListener resultListener : listenersByBucketIdx.values()) {
                    resultListener.needMore(false);
                }
                listenersByBucketIdx.clear();
            }
        });
        if (numBuckets == 0) {
            batchIterator = new ListenableBatchIterator<>(
                InMemoryBatchIterator.empty(SentinelRow.SENTINEL),
                processingFuture
            );
        } else {
            batchIterator = new BatchPagingIterator<>(
                pagingIterator,
                this::fetchMore,
                this::allUpstreamsExhausted,
                throwable -> {
                    if (throwable == null) {
                        processingFuture.complete(null);
                    } else {
                        processingFuture.completeExceptionally(throwable);
                    }
                }
            );
        }
        rowConsumer.accept(batchIterator, null);
        traceEnabled = logger.isTraceEnabled();
    }

    @Override
    public void setBucket(int bucketIdx, Bucket rows, boolean isLast, PageResultListener pageResultListener) {
        synchronized (buckets) {
            buckets.add(bucketIdx);
            if (!isLast && lastThrowable == null) {
                listenersByBucketIdx.put(bucketIdx, pageResultListener);
            } else {
                pageResultListener.needMore(false);
            }
        }
        final boolean allBucketsOfPageReceived;
        synchronized (lock) {
            traceLog("method=setBucket", bucketIdx);

            if (bucketsByIdx.putIfAbsent(bucketIdx, rows) != null) {
                processingFuture.completeExceptionally(new IllegalStateException(String.format(Locale.ENGLISH,
                    "Same bucket of a page set more than once. node=%s method=setBucket phaseId=%d bucket=%d",
                    nodeName, phaseId, bucketIdx)));
            }
            if (isLast) {
                exhausted.add(bucketIdx);
            }
            allBucketsOfPageReceived = bucketsByIdx.size() == numBuckets;
        }
        if (allBucketsOfPageReceived) {
            final List<KeyIterable<Integer, Row>> buckets = getBuckets();
            final CompletableFuture<Iterable<KeyIterable<Integer, Row>>> future = currentLoadingPage.get();
            try {
                executor.execute(() -> future.complete(buckets));
            } catch (EsRejectedExecutionException | RejectedExecutionException e) {
                future.complete(buckets);
            }
        }
    }

    private List<KeyIterable<Integer, Row>> getBuckets() {
        List<KeyIterable<Integer, Row>> buckets = new ArrayList<>(bucketsByIdx.size());
        synchronized (lock) {
            Iterator<Map.Entry<Integer, Bucket>> entryIt = bucketsByIdx.entrySet().iterator();
            while (entryIt.hasNext()) {
                Map.Entry<Integer, Bucket> entry = entryIt.next();
                Integer bucketIdx = entry.getKey();
                buckets.add(new KeyIterable<>(bucketIdx, entry.getValue()));
                if (exhausted.contains(bucketIdx)) {
                    entry.setValue(Bucket.EMPTY);
                } else {
                    entryIt.remove();
                }
            }
        }
        return buckets;
    }

    private boolean allUpstreamsExhausted() {
        return exhausted.size() == numBuckets && !firstFetchMore.get();
    }

    private CompletableFuture<Iterable<KeyIterable<Integer, Row>>> fetchMore(Integer exhaustedBucket) {
        // The first page is being pushed without the merge explicitly requesting any data
        // We switch to "pull" after the first page has arrived
        if (firstFetchMore.compareAndSet(true, false)) {
            return currentLoadingPage.get();
        }
        CompletableFuture<Iterable<KeyIterable<Integer, Row>>> nextPage = new CompletableFuture<>();
        currentLoadingPage.set(nextPage);
        if (exhaustedBucket == null || exhausted.contains(exhaustedBucket)) {
            fetchFromUnExhausted();
        } else {
            fetchExhausted(exhaustedBucket);
        }
        return nextPage;
    }

    private void fetchExhausted(int exhaustedBucket) {
        synchronized (buckets) {
            // We're only requesting data for 1 specific bucket,
            // so we need to fill in other buckets to meet the
            // "receivedAllBucketsOfPage" condition once we get the data for this bucket
            for (Integer bucketIdx : buckets) {
                if (!bucketIdx.equals(exhaustedBucket)) {
                    bucketsByIdx.putIfAbsent(bucketIdx, Bucket.EMPTY);
                }
            }
            PageResultListener pageResultListener = listenersByBucketIdx.remove(exhaustedBucket);
            pageResultListener.needMore(true);
        }
    }

    private void fetchFromUnExhausted() {
        synchronized (buckets) {
            for (PageResultListener listener : listenersByBucketIdx.values()) {
                listener.needMore(true);
            }
            listenersByBucketIdx.clear();
        }
    }

    private void traceLog(String msg, int bucketIdx) {
        if (traceEnabled) {
            logger.trace("{} phaseId={} bucket={}", msg, phaseId, bucketIdx);
        }
    }

    @Override
    public Streamer<?>[] streamers() {
        return streamers;
    }

    @Override
    public CompletableFuture<?> completionFuture() {
        return processingFuture;
    }

    @Override
    public void consumeRows() {
    }

    @Override
    public void kill(@Nonnull Throwable t) {
        lastThrowable = t;
        batchIterator.kill(t);
        batchIterator.close();
        currentLoadingPage.get().completeExceptionally(t);
    }
}
