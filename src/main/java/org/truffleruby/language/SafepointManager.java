/*
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

import com.oracle.truffle.api.CompilerAsserts;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.InterruptMode;
import org.truffleruby.core.fiber.FiberManager;
import org.truffleruby.core.thread.RubyThread;
import org.truffleruby.core.thread.ThreadManager;
import org.truffleruby.platform.Signals;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

public final class SafepointManager {

    private final RubyLanguage language;
    private final RubyContext context;

    private final Set<Thread> runningThreads = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ReentrantLock lock = new ReentrantLock();

    private final Phaser phaser = createPhaser();

    private static Phaser createPhaser() {
        return new Phaser() {
            @Override
            protected boolean onAdvance(int phase, int registeredParties) {
                // This Phaser should not be automatically terminated,
                // even when registeredParties drops to 0.
                // This notably happens when pre-initializing the context.
                return false;
            }
        };
    }

    /** The Assumption needs to be per language to be PE constant. This flag tracks whether there is an active safepoint
     * per context. */
    private volatile boolean active = false;

    private volatile SafepointPredicate filter;
    private volatile SafepointAction action;

    public SafepointManager(RubyContext context, RubyLanguage language) {
        this.context = context;
        this.language = language;
    }

    @TruffleBoundary
    public void enterThread() {
        final Thread thread = Thread.currentThread();

        lock.lock();
        try {
            int phase = phaser.register();
            assert phase >= 0 : "Phaser terminated";
            if (!runningThreads.add(thread)) {
                throw new UnsupportedOperationException(thread + " was already registered");
            }
        } finally {
            lock.unlock();
        }
    }

    @TruffleBoundary
    public void leaveThread() {
        final Thread thread = Thread.currentThread();
        assert runningThreads.contains(thread);

        phaser.arriveAndDeregister();
        if (!runningThreads.remove(thread)) {
            throw new UnsupportedOperationException(thread + " was not registered");
        }
    }

    public static void poll(RubyLanguage language, Node currentNode) {
        poll(language, currentNode, false);
    }

    public static void pollFromBlockingCall(RubyLanguage language, Node currentNode) {
        poll(language, currentNode, true);
    }

    private static void poll(RubyLanguage language, Node currentNode, boolean fromBlockingCall) {
        final Assumption safepointAssumption = language.getSafepointAssumption();
        CompilerAsserts.partialEvaluationConstant(safepointAssumption);
        if (!safepointAssumption.isValid()) {
            // For the TruffleCheckNeverPartOfCompilation check, isValid() deopts anyway
            CompilerDirectives.transferToInterpreterAndInvalidate();
            final SafepointManager safepointManager = RubyLanguage.getCurrentContext().getSafepointManager();
            if (safepointManager.active) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                safepointManager.assumptionInvalidated(currentNode, fromBlockingCall);
            }
        }
    }

    @TruffleBoundary
    private void assumptionInvalidated(Node currentNode, boolean fromBlockingCall) {
        if (lock.isHeldByCurrentThread()) {
            throw CompilerDirectives.shouldNotReachHere("poll() should not be called by the driving thread");
        }

        final RubyThread thread = context.getThreadManager().getCurrentThread();
        final InterruptMode interruptMode = thread.interruptMode;

        final boolean interruptible = (interruptMode == InterruptMode.IMMEDIATE) ||
                (fromBlockingCall && interruptMode == InterruptMode.ON_BLOCKING);

        final SafepointAction deferredAction = step(currentNode, false, null);

        // We're now running again normally and can run deferred actions
        if (deferredAction != null) {
            if (interruptible) {
                deferredAction.accept(thread, currentNode);
            } else {
                thread.pendingSafepointActions.add(deferredAction);
            }
        }
    }

    @TruffleBoundary
    private SafepointAction step(Node currentNode, boolean isDrivingThread, String reason) {
        assert isDrivingThread == lock.isHeldByCurrentThread();

        // Wait for other threads to reach their safepoint
        if (isDrivingThread) {
            driveArrivalAtPhaser(reason);
            language.resetSafepointAssumption();
            this.active = false;
        } else {
            phaser.arriveAndAwaitAdvance();
        }

        // Wait for the assumption to be renewed
        phaser.arriveAndAwaitAdvance();

        // Run the filter and read the action field while in the safepoint
        SafepointAction deferredAction = null;

        try {
            final RubyThread thread = context.getThreadManager().getCurrentThreadOrNull();

            if (thread != null && filter.test(thread)) {
                if (SafepointAction.isDeferred(action)) {
                    deferredAction = action;
                } else {
                    action.accept(thread, currentNode);
                }
            }
        } finally {
            // Wait for other threads to finish their action
            phaser.arriveAndAwaitAdvance();
        }

        return deferredAction;
    }

    private static final int WAIT_TIME_IN_SECONDS = 5;
    private static final int MAX_WAIT_TIME_IN_SECONDS = 60;
    // Currently 6 on JVM and 9 on SVM, adding some margin to be robust to changes
    private static final int STEP_BACKTRACE_MAX_OFFSET = 12;

    private void driveArrivalAtPhaser(String reason) {
        int phase = phaser.arrive();
        long t0 = System.nanoTime();
        long max = t0 + TimeUnit.SECONDS.toNanos(WAIT_TIME_IN_SECONDS);
        long exitTime = t0 + TimeUnit.SECONDS.toNanos(MAX_WAIT_TIME_IN_SECONDS);
        int waits = 1;
        while (true) {
            try {
                phaser.awaitAdvanceInterruptibly(phase, 100, TimeUnit.MILLISECONDS);
                break;
            } catch (InterruptedException e) {
                // retry
            } catch (TimeoutException e) {
                if (System.nanoTime() >= max) {
                    // Possibly not in a context, so we cannot use TruffleLogger
                    context.getLogger().severe(String.format(
                            "waited %d seconds in the SafepointManager but %d of %d threads did not arrive - a thread is likely making a blocking call - reason for the safepoint: %s",
                            waits * WAIT_TIME_IN_SECONDS,
                            phaser.getUnarrivedParties(),
                            phaser.getRegisteredParties(),
                            reason));
                    if (waits == 1) {
                        printStacktracesOfBlockedThreads();
                        restoreDefaultInterruptHandler();
                    }
                    if (max >= exitTime) {
                        context.getLogger().severe(
                                "waited " + MAX_WAIT_TIME_IN_SECONDS +
                                        " seconds in the SafepointManager, terminating the process as it is unlikely to get unstuck");
                        System.exit(1);
                    }
                    max += TimeUnit.SECONDS.toNanos(WAIT_TIME_IN_SECONDS);
                    waits++;
                } else {
                    // Retry interrupting other threads, as they might not have been yet
                    // in the blocking call when the signal was sent.
                    interruptOtherThreads();
                }
            }
        }
    }

    private void printStacktracesOfBlockedThreads() {
        final Thread drivingThread = Thread.currentThread();

        System.err.println("Dumping stacktraces of all threads:");
        for (Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            final Thread thread = entry.getKey();
            if (runningThreads.contains(thread)) {
                final StackTraceElement[] stackTrace = entry.getValue();
                boolean blocked = true;

                for (int i = 0; i < stackTrace.length && i <= STEP_BACKTRACE_MAX_OFFSET; i++) {
                    if (stackTrace[i].getClassName().equals(SafepointManager.class.getName()) &&
                            stackTrace[i].getMethodName().equals("step")) {
                        // In SafepointManager#step, ignore
                        blocked = false;
                        break;
                    }
                }

                String kind = thread == drivingThread ? "DRIVER" : (blocked ? "BLOCKED" : "IN SAFEPOINT");
                System.err.println(kind + ": " + thread);
                for (StackTraceElement stackTraceElement : stackTrace) {
                    System.err.println(stackTraceElement);
                }
                System.err.println();
            }
        }
    }

    private void restoreDefaultInterruptHandler() {
        // Possibly not in a context, so we cannot use TruffleLogger
        context.getLogger().warning("restoring default interrupt handler");
        try {
            Signals.restoreDefaultHandler("INT");
        } catch (Throwable t) {
            context.getLogger().warning("failed to restore default interrupt handler\n" + t);
        }
    }

    @TruffleBoundary
    public void pauseAllThreadsAndExecute(String reason, Node currentNode, SafepointPredicate filter,
            SafepointAction action) {
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Re-entered SafepointManager");
        }

        // Need to lock interruptibly since we are in the registered threads.
        while (!lock.tryLock()) {
            poll(language, currentNode);
        }

        final SafepointAction deferredAction;
        try {
            deferredAction = pauseAllThreadsAndExecuteInternal(reason, currentNode, filter, action);
        } finally {
            lock.unlock();
        }

        // Run deferred actions after leaving the SafepointManager lock.
        if (deferredAction != null) {
            action.accept(context.getThreadManager().getCurrentThread(), currentNode);
        }
    }

    @TruffleBoundary
    public void pauseAllThreadsAndExecuteFromNonRubyThread(String reason, SafepointPredicate filter,
            SafepointAction action) {
        if (lock.isHeldByCurrentThread()) {
            throw new IllegalStateException("Re-entered SafepointManager");
        }

        assert !runningThreads.contains(Thread.currentThread());

        // Just wait to grab the lock, since we are not in the registered threads.

        lock.lock();
        try {
            enterThread();
            try {
                pauseAllThreadsAndExecuteInternal(reason, null, filter, action);
            } finally {
                leaveThread();
            }
        } finally {
            lock.unlock();
        }
    }

    // Variant for a single thread

    @TruffleBoundary
    public void pauseRubyThreadAndExecute(String reason, RubyThread rubyThread, Node currentNode,
            SafepointAction action) {
        final ThreadManager threadManager = context.getThreadManager();
        final RubyThread currentThread = threadManager.getCurrentThread();
        final FiberManager fiberManager = rubyThread.fiberManager;

        if (currentThread == rubyThread) {
            if (threadManager.getRubyFiberFromCurrentJavaThread() != fiberManager.getCurrentFiber()) {
                throw new IllegalStateException(
                        "The currently executing Java thread does not correspond to the currently active fiber for the current Ruby thread");
            }
            // fast path if we are already the right thread
            action.accept(rubyThread, currentNode);
        } else {
            pauseAllThreadsAndExecute(
                    reason,
                    currentNode,
                    SafepointPredicate.currentFiberOfThread(context, rubyThread),
                    action);
        }
    }

    private SafepointAction pauseAllThreadsAndExecuteInternal(String reason, Node currentNode,
            SafepointPredicate filter,
            SafepointAction action) {
        assert lock.isHeldByCurrentThread();

        this.filter = filter;
        this.action = action;

        /* We need to invalidate first so the interrupted threads see the invalidation in poll() in their
         * catch(InterruptedException) clause and wait on the Phaser instead of retrying their blocking action. */
        this.active = true;
        language.invalidateSafepointAssumption(reason);
        interruptOtherThreads();

        return step(currentNode, true, reason);
    }

    private void interruptOtherThreads() {
        Thread current = Thread.currentThread();
        for (Thread thread : runningThreads) {
            if (thread != current) {
                context.getThreadManager().interrupt(thread);
            }
        }
    }

    public void checkNoRunningThreads() {
        if (!runningThreads.isEmpty()) {
            RubyLanguage.LOGGER.warning(
                    "threads are still registered with safepoint manager at shutdown:\n" +
                            context.getThreadManager().getThreadDebugInfo() + getSafepointDebugInfo());
        }
    }

    public String getSafepointDebugInfo() {
        final Thread[] threads = new Thread[Thread.activeCount() + 1024];
        final int threadsCount = Thread.enumerate(threads);
        final long appearRunning = Arrays.stream(threads).limit(threadsCount).filter(
                t -> t.getName().startsWith(FiberManager.NAME_PREFIX)).count();
        return String.format(
                "safepoints: %d known threads, %d registered with phaser, %d arrived, %d appear to be running",
                runningThreads.size(),
                phaser.getRegisteredParties(),
                phaser.getArrivedParties(),
                appearRunning);
    }

}
