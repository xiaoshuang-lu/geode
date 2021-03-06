/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.cache.wan.parallel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.apache.logging.log4j.Logger;

import org.apache.geode.GemFireException;
import org.apache.geode.InternalGemFireException;
import org.apache.geode.cache.CacheException;
import org.apache.geode.cache.EntryEvent;
import org.apache.geode.cache.Region;
import org.apache.geode.cache.wan.GatewayQueueEvent;
import org.apache.geode.internal.cache.EntryEventImpl;
import org.apache.geode.internal.cache.EnumListenerEvent;
import org.apache.geode.internal.cache.InternalRegion;
import org.apache.geode.internal.cache.LocalRegion;
import org.apache.geode.internal.cache.RegionQueue;
import org.apache.geode.internal.cache.wan.AbstractGatewaySender;
import org.apache.geode.internal.cache.wan.AbstractGatewaySenderEventProcessor;
import org.apache.geode.internal.cache.wan.GatewaySenderEventDispatcher;
import org.apache.geode.internal.cache.wan.GatewaySenderEventImpl;
import org.apache.geode.internal.cache.wan.GatewaySenderException;
import org.apache.geode.internal.i18n.LocalizedStrings;
import org.apache.geode.internal.logging.LogService;
import org.apache.geode.internal.logging.LoggingThreadGroup;
import org.apache.geode.internal.logging.log4j.LocalizedMessage;

/**
 * Parallel processor which constitutes of multiple {@link ParallelGatewaySenderEventProcessor}.
 * Each of the {@link ParallelGatewaySenderEventProcessor} is responsible of dispatching events from
 * a set of shadowPR or buckets. Once the buckets/shadowPRs are assigned to a processor it should
 * not change to avoid any event ordering issue.
 *
 * The {@link ParallelGatewaySenderQueue} should be shared among all the
 * {@link ParallelGatewaySenderEventProcessor}s.
 */
public class ConcurrentParallelGatewaySenderEventProcessor
    extends AbstractGatewaySenderEventProcessor {

  protected static final Logger logger = LogService.getLogger();

  protected ParallelGatewaySenderEventProcessor processors[];

  private GemFireException ex = null;

  final int nDispatcher;

  public ConcurrentParallelGatewaySenderEventProcessor(AbstractGatewaySender sender) {
    super(
        LoggingThreadGroup.createThreadGroup("Event Processor for GatewaySender_" + sender.getId()),
        "Event Processor for GatewaySender_" + sender.getId(), sender);
    // initializeMessageQueue(sender.getId());
    logger.info("ConcurrentParallelGatewaySenderEventProcessor: dispatcher threads {}",
        sender.getDispatcherThreads());

    nDispatcher = sender.getDispatcherThreads();
    /**
     * We have to divide the buckets/shadowPRs here. So that the individual processors can start
     * with a set of events to deal with In case of shadowPR getting created it will have to attach
     * itself to one of the processors when they are created.
     */
    // We have to do static partitioning of buckets and region attached.
    // We should remember that this partitioning may change in future as new shadowPRs
    // get created.
    // Static partitioning is as follows
    // for each of the shadowPR:
    // each of the processor gets : 0 .. totalNumBuckets/totalDispatcherThreads and last processor
    // gets the remaining
    // bucket
    Set<Region> targetRs = new HashSet<Region>();
    for (InternalRegion pr : sender.getCache().getApplicationRegions()) {
      if (((LocalRegion) pr).getAllGatewaySenderIds().contains(sender.getId())) {
        targetRs.add(pr);
      }
    }
    if (logger.isDebugEnabled()) {
      logger.debug("The target PRs are {} Dispatchers: {}", targetRs, nDispatcher);
    }

    createProcessors(sender.getDispatcherThreads(), targetRs);

    // this.queue = parallelQueue;
    this.queue = new ConcurrentParallelGatewaySenderQueue(sender, this.processors);
    setDaemon(true);
  }

  protected void createProcessors(int dispatcherThreads, Set<Region> targetRs) {
    processors = new ParallelGatewaySenderEventProcessor[sender.getDispatcherThreads()];
    if (logger.isDebugEnabled()) {
      logger.debug("Creating AsyncEventProcessor");
    }
    for (int i = 0; i < sender.getDispatcherThreads(); i++) {
      processors[i] = new ParallelGatewaySenderEventProcessor(sender, targetRs, i,
          sender.getDispatcherThreads());
    }
  }

  @Override
  protected void initializeMessageQueue(String id) {
    // nothing
  }

  @Override
  public void enqueueEvent(EnumListenerEvent operation, EntryEvent event, Object substituteValue)
      throws IOException, CacheException {
    Region region = event.getRegion();
    // int bucketId = PartitionedRegionHelper.getHashKey((EntryOperation)event);
    int bucketId = ((EntryEventImpl) event).getEventId().getBucketID();
    if (bucketId < 0) {
      return;
    }
    int pId = bucketId % this.nDispatcher;
    this.processors[pId].enqueueEvent(operation, event, substituteValue);
  }

  @Override
  public void run() {
    final boolean isDebugEnabled = logger.isDebugEnabled();

    for (int i = 0; i < this.processors.length; i++) {
      if (isDebugEnabled) {
        logger.debug("Starting the ParallelProcessors {}", i);
      }
      this.processors[i].start();
    }
    try {
      waitForRunningStatus();
    } catch (GatewaySenderException e) {
      this.ex = e;
    }

    synchronized (this.runningStateLock) {
      if (ex != null) {
        this.setException(ex);
        setIsStopped(true);
      } else {
        setIsStopped(false);
      }
      this.runningStateLock.notifyAll();
    }

    for (ParallelGatewaySenderEventProcessor parallelProcessor : this.processors) {
      try {
        parallelProcessor.join();
      } catch (InterruptedException e) {
        if (isDebugEnabled) {
          logger.debug("Got InterruptedException while waiting for child threads to finish.");
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private void waitForRunningStatus() {
    for (ParallelGatewaySenderEventProcessor parallelProcessor : this.processors) {
      synchronized (parallelProcessor.runningStateLock) {
        while (parallelProcessor.getException() == null && parallelProcessor.isStopped()) {
          try {
            parallelProcessor.runningStateLock.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        Exception ex = parallelProcessor.getException();
        if (ex != null) {
          throw new GatewaySenderException(
              LocalizedStrings.Sender_COULD_NOT_START_GATEWAYSENDER_0_BECAUSE_OF_EXCEPTION_1
                  .toLocalizedString(new Object[] {this.sender.getId(), ex.getMessage()}),
              ex.getCause());
        }
      }
    }
  }

  @Override
  public void stopProcessing() {
    if (!this.isAlive()) {
      return;
    }

    setIsStopped(true);

    final LoggingThreadGroup loggingThreadGroup = LoggingThreadGroup
        .createThreadGroup("ConcurrentParallelGatewaySenderEventProcessor Logger Group", logger);

    ThreadFactory threadFactory = new ThreadFactory() {
      public Thread newThread(final Runnable task) {
        final Thread thread = new Thread(loggingThreadGroup, task,
            "ConcurrentParallelGatewaySenderEventProcessor Stopper Thread");
        thread.setDaemon(true);
        return thread;
      }
    };

    List<SenderStopperCallable> stopperCallables = new ArrayList<SenderStopperCallable>();
    for (ParallelGatewaySenderEventProcessor parallelProcessor : this.processors) {
      stopperCallables.add(new SenderStopperCallable(parallelProcessor));
    }

    ExecutorService stopperService = Executors.newFixedThreadPool(processors.length, threadFactory);
    try {
      List<Future<Boolean>> futures = stopperService.invokeAll(stopperCallables);
      for (Future<Boolean> f : futures) {
        try {
          Boolean b = f.get();
          if (logger.isDebugEnabled()) {
            logger.debug(
                "ConcurrentParallelGatewaySenderEventProcessor: {} stopped dispatching: {}",
                (b ? "Successfully" : "Unsuccesfully"), this);
          }
        } catch (ExecutionException e) {
          // we don't expect any exception but if caught then eat it and log warning
          logger.warn(
              LocalizedMessage.create(
                  LocalizedStrings.GatewaySender_0_CAUGHT_EXCEPTION_WHILE_STOPPING_1, sender),
              e.getCause());
        }
      }
    } catch (InterruptedException e) {
      throw new InternalGemFireException(e);
    }

    stopperService.shutdown();
    closeProcessor();
    if (logger.isDebugEnabled()) {
      logger.debug("ConcurrentParallelGatewaySenderEventProcessor: Stopped dispatching: {}", this);
    }
  }

  @Override
  public void closeProcessor() {
    for (ParallelGatewaySenderEventProcessor parallelProcessor : this.processors) {
      parallelProcessor.closeProcessor();
    }
  }

  @Override
  public void pauseDispatching() {
    for (ParallelGatewaySenderEventProcessor parallelProcessor : this.processors) {
      parallelProcessor.pauseDispatching();
    }
    super.pauseDispatching();
    if (logger.isDebugEnabled()) {
      logger.debug("ConcurrentParallelGatewaySenderEventProcessor: Paused dispatching: {}", this);
    }
  }

  @Override
  public void waitForDispatcherToPause() {
    for (ParallelGatewaySenderEventProcessor parallelProcessor : this.processors) {
      parallelProcessor.waitForDispatcherToPause();
    }
  }

  @Override
  public void resumeDispatching() {
    for (ParallelGatewaySenderEventProcessor parallelProcessor : this.processors) {
      parallelProcessor.resumeDispatching();
    }
    super.resumeDispatching();
    if (logger.isDebugEnabled()) {
      logger.debug("ConcurrentParallelGatewaySenderEventProcessor: Resumed dispatching: {}", this);
    }
  }

  @Override
  protected void waitForResumption() throws InterruptedException {
    // TODO Auto-generated method stub
    super.waitForResumption();
  }

  /**
   * Test only methods for verification purpose.
   */
  public List<ParallelGatewaySenderEventProcessor> getProcessors() {
    List<ParallelGatewaySenderEventProcessor> l =
        new LinkedList<ParallelGatewaySenderEventProcessor>();
    for (int i = 0; i < processors.length; i++) {
      l.add(processors[i]);
    }
    return l;
  }

  @Override
  public RegionQueue getQueue() {
    return this.queue;
  }

  @Override
  public GatewaySenderEventDispatcher getDispatcher() {
    return this.processors[0].getDispatcher();// Suranjan is that fine??
  }

  @Override
  protected void rebalance() {
    // no op for AsyncEventProcessor

  }

  @Override
  public void initializeEventDispatcher() {
    // no op for AsyncEventProcessor
  }

  @Override
  protected void enqueueEvent(GatewayQueueEvent event) {
    int pId = ((GatewaySenderEventImpl) event).getBucketId() % this.nDispatcher;
    this.processors[pId].enqueueEvent(event);
  }
}
