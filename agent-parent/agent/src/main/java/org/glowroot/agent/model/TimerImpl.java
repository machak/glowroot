/*
 * Copyright 2011-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.model;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.google.common.base.Ticker;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.plugin.api.Timer;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.util.Tickers;
import org.glowroot.common.util.Styles;
import org.glowroot.wire.api.model.TraceOuterClass.Trace;

// instances are updated by a single thread, but can be read by other threads
// memory visibility is therefore an issue for the reading threads
//
// memory visibility could be guaranteed by making selfNestingLevel volatile
//
// selfNestingLevel is written after other fields are written and it is read before
// other fields are read, so it could be used to create a memory barrier and make the latest values
// of the other fields visible to the reading thread
//
// but benchmarking shows making selfNestingLevel non-volatile reduces timer capture overhead
// from 88 nanoseconds down to 41 nanoseconds, which is very good since System.nanoTime() takes 17
// nanoseconds and each timer capture has to call it twice
//
// the down side is that the latest updates to timers for transactions that are captured
// in-flight (e.g. partial traces and active traces displayed in the UI) may not be visible
//
// all timing data is in nanoseconds
@Styles.Private
public class TimerImpl implements Timer, CommonTimerImpl {

    private static final Logger logger = LoggerFactory.getLogger(TimerImpl.class);

    private static final Ticker ticker = Tickers.getTicker();

    private final ThreadContextImpl threadContext;
    private final @Nullable TimerImpl parent;
    private final TimerNameImpl timerName;

    // nanosecond rollover (292 years) isn't a concern for total time on a single transaction
    private long totalNanos;
    private long count;

    private long startTick;
    private int selfNestingLevel;

    // nestedTimers is only accessed by the transaction thread so no need for volatile or
    // synchronized access during timer capture which is important
    //
    // lazy initialize to save memory in common case where this is a leaf timer
    private @MonotonicNonNull NestedTimerMap nestedTimers;

    // separate linked list for safe iterating by other threads (e.g. partial trace capture and
    // active trace viewer)
    private @MonotonicNonNull TimerImpl headChild;
    private final @Nullable TimerImpl nextSibling;

    public static TimerImpl createRootTimer(ThreadContextImpl threadContext,
            TimerNameImpl timerName) {
        return new TimerImpl(threadContext, null, null, timerName);
    }

    private TimerImpl(ThreadContextImpl threadContext, @Nullable TimerImpl parent,
            @Nullable TimerImpl nextSibling, TimerNameImpl timerName) {
        this.timerName = timerName;
        this.parent = parent;
        this.nextSibling = nextSibling;
        this.threadContext = threadContext;
    }

    // safe to be called from another thread when transaction is still active transaction
    @JsonIgnore
    Trace.Timer toProto() {
        Trace.Timer.Builder builder = Trace.Timer.newBuilder();
        builder.setName(timerName.name());
        builder.setExtended(timerName.extended());

        TimerImplSnapshot snapshot = getSnapshot();
        builder.setTotalNanos(snapshot.totalNanos());
        builder.setCount(snapshot.count());
        builder.setActive(snapshot.active());

        if (headChild != null) {
            List<Trace.Timer> nestedTimers = Lists.newArrayList();
            TimerImpl curr = headChild;
            while (curr != null) {
                nestedTimers.add(curr.toProto());
                curr = curr.nextSibling;
            }
            builder.addAllChildTimer(nestedTimers);
        }
        return builder.build();
    }

    @Override
    public TimerImplSnapshot getSnapshot() {
        if (selfNestingLevel > 0) {
            // try to grab a quick, consistent view, but no guarantee on consistency since the
            // transaction is active
            //
            // grab total before curr, to avoid case where total is updated in between
            // these two lines and then "total + curr" would overstate the correct value
            // (it seems better to understate the correct value if there is an update to the
            // timer values in between these two lines)
            long theTotalNanos = totalNanos;
            // capture startTick before ticker.read() so curr is never < 0
            long theStartTick = startTick;
            long curr = ticker.read() - theStartTick;
            if (theTotalNanos == 0) {
                return ImmutableTimerImplSnapshot.of(curr, 1, true);
            } else {
                return ImmutableTimerImplSnapshot.of(theTotalNanos + curr, count + 1, true);
            }
        } else {
            return ImmutableTimerImplSnapshot.of(totalNanos, count, false);
        }
    }

    @Override
    public void stop() {
        if (--selfNestingLevel == 0) {
            endInternal(ticker.read());
        }
    }

    @Override
    public Timer extend() {
        return extend(ticker.read());
    }

    public void end(long endTick) {
        if (--selfNestingLevel == 0) {
            endInternal(endTick);
        }
    }

    @Override
    public String getName() {
        return timerName.name();
    }

    @Override
    public boolean isExtended() {
        return timerName.extended();
    }

    // only called after transaction completion
    @Override
    public long getTotalNanos() {
        return totalNanos;
    }

    // only called after transaction completion
    @Override
    public long getCount() {
        return count;
    }

    // only called after transaction completion
    @Override
    @JsonIgnore
    public Iterator<TimerImpl> getChildTimers() {
        if (headChild == null) {
            return ImmutableList.<TimerImpl>of().iterator();
        } else {
            return new Iterator<TimerImpl>() {
                private @Nullable TimerImpl next = headChild;
                @Override
                public boolean hasNext() {
                    return next != null;
                }
                @Override
                public TimerImpl next() {
                    TimerImpl curr = next;
                    if (curr == null) {
                        throw new NoSuchElementException();
                    }
                    next = curr.nextSibling;
                    return curr;
                }
                @Override
                public void remove() {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    // only called by transaction thread
    public TimerImpl startNestedTimer(TimerName timerName) {
        // timer names are guaranteed one instance per name so pointer equality can be used
        if (this.timerName == timerName) {
            selfNestingLevel++;
            return this;
        }
        long nestedTimerStartTick = ticker.read();
        return startNestedTimerInternal(timerName, nestedTimerStartTick);
    }

    // only called by transaction thread
    public TimerImpl startNestedTimer(TimerName timerName, long startTick) {
        // timer names are guaranteed one instance per name so pointer equality can be used
        if (this.timerName == timerName) {
            selfNestingLevel++;
            return this;
        }
        return startNestedTimerInternal(timerName, startTick);
    }

    public TimerImpl extend(long startTick) {
        TimerImpl currentTimer = threadContext.getCurrentTimer();
        if (currentTimer == null) {
            logger.warn("extend() transaction currentTimer is null");
            return this;
        }
        if (currentTimer == parent) {
            // restarting a previously stopped execution, so need to decrement count
            count--;
            start(startTick);
            return this;
        }
        if (currentTimer == this) {
            selfNestingLevel++;
            return this;
        }
        // otherwise can't just restart timer, so need to start an "extended" timer under the
        // current timer
        TimerNameImpl extendedTimer = timerName.extendedTimer();
        if (extendedTimer == null) {
            logger.warn("extend() should only be accessible to non-extended timers");
            return this;
        }
        return currentTimer.startNestedTimer(extendedTimer);
    }

    void start(long startTick) {
        this.startTick = startTick;
        selfNestingLevel++;
        threadContext.setCurrentTimer(this);
    }

    ThreadContextImpl getThreadContext() {
        return threadContext;
    }

    private void endInternal(long endTick) {
        totalNanos += endTick - startTick;
        count++;
        threadContext.setCurrentTimer(parent);
    }

    private TimerImpl startNestedTimerInternal(TimerName timerName, long nestedTimerStartTick) {
        if (nestedTimers == null) {
            nestedTimers = new NestedTimerMap();
        }
        TimerNameImpl timerNameImpl = (TimerNameImpl) timerName;
        TimerImpl nestedTimer = nestedTimers.get(timerNameImpl);
        if (nestedTimer != null) {
            nestedTimer.start(nestedTimerStartTick);
            return nestedTimer;
        }
        nestedTimer = new TimerImpl(threadContext, this, headChild, timerNameImpl);
        nestedTimer.start(nestedTimerStartTick);
        nestedTimers.put(timerNameImpl, nestedTimer);
        headChild = nestedTimer;
        return nestedTimer;
    }

    @Value.Immutable
    @Styles.AllParameters
    interface TimerImplSnapshot {
        long totalNanos();
        long count();
        boolean active();
    }
}
