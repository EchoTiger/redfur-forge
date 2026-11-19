package com.fissal.forge.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class WeightedSlotPoolTest {

    @Test
    fun enforcesWeightedSlotBudgetAcrossConcurrentTasks() = runBlocking {
        val pool = WeightedSlotPool()
        val activeWeight = AtomicInteger(0)
        val peakWeight = AtomicInteger(0)
        val completed = AtomicInteger(0)

        withTimeout(10_000L) {
            val jobs = (1..12).map { idx ->
                val weight = if (idx % 3 == 0) 2 else 1
                launch(Dispatchers.Default) {
                    pool.withSlots(
                        cost = weight,
                        capacityProvider = { 3 },
                        onPoolChange = {}
                    ) {
                        val now = activeWeight.addAndGet(weight)
                        peakWeight.updateAndGet { prev -> maxOf(prev, now) }
                        delay(15L)
                        activeWeight.addAndGet(-weight)
                        completed.incrementAndGet()
                    }
                }
            }
            jobs.joinAll()
        }

        assertEquals(12, completed.get())
        assertTrue("Expected peak weight <= 3, was ${peakWeight.get()}", peakWeight.get() <= 3)
        assertEquals(0, pool.currentUsed())
    }

    @Test
    fun massiveConcurrentCancellationDoesNotLeakSlotsOrDeadlockWaiters() = runBlocking {
        val pool = WeightedSlotPool()
        val capacity = 3
        val gate = CompletableDeferred<Unit>()
        val startedCount = AtomicInteger(0)

        withTimeout(15_000L) {
            // Launch 60 heavy & light waiters that contend under cancellation storms.
            val launched = ArrayList<Job>(60)
            repeat(60) { i ->
                val cost = if (i % 2 == 0) 2 else 1
                val job = launch(Dispatchers.Default) {
                    try {
                        pool.withSlots(
                            cost = cost,
                            capacityProvider = { capacity },
                            onPoolChange = {}
                        ) {
                            startedCount.incrementAndGet()
                            if (i < 40) {
                                gate.await()
                            } else {
                                delay(5L)
                            }
                        }
                    } catch (_: CancellationException) {
                        // Expected on cancelled coroutines
                    }
                }
                launched += job
            }

            // Let initial slots fill and remaining 50+ queue up, then cancel 45 mid-wait & mid-flight!
            delay(40L)
            for (i in 0 until 45) {
                launched[i].cancel()
            }
            gate.complete(Unit)
            launched.joinAll()

            // Verify every single slot is returned and subsequent full-capacity work proceeds immediately.
            assertEquals(0, pool.currentUsed())
            val followUpRan = AtomicInteger(0)
            val followUps = (1..8).map {
                launch(Dispatchers.Default) {
                    pool.withSlots(
                        cost = 3,
                        capacityProvider = { capacity },
                        onPoolChange = {}
                    ) {
                        followUpRan.incrementAndGet()
                        delay(2L)
                    }
                }
            }
            followUps.joinAll()
            assertEquals(8, followUpRan.get())
            assertEquals(0, pool.currentUsed())
        }
    }

    @Test
    fun notifyCapacityChangedWakesQueuedWaitersImmediately() = runBlocking {
        val pool = WeightedSlotPool()
        val capacity = AtomicInteger(1)
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        withTimeout(5_000L) {
            val first = launch(Dispatchers.Default) {
                pool.withSlots(cost = 1, capacityProvider = { capacity.get() }, onPoolChange = {}) {
                    firstEntered.complete(Unit)
                    releaseFirst.await()
                }
            }
            firstEntered.await()

            val second = launch(Dispatchers.Default) {
                pool.withSlots(cost = 1, capacityProvider = { capacity.get() }, onPoolChange = {}) {
                    secondEntered.complete(Unit)
                }
            }

            delay(30L)
            assertEquals(1, pool.currentUsed())
            // Raise capacity while first task is still running; notifyCapacityChanged must wake waiter immediately
            capacity.set(2)
            pool.notifyCapacityChanged(2)
            secondEntered.await()

            releaseFirst.complete(Unit)
            first.join()
            second.join()
            assertEquals(0, pool.currentUsed())
        }
    }
}
