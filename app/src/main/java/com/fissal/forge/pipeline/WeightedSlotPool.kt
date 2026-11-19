package com.fissal.forge.pipeline

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Cancellation-safe, FIFO weighted concurrency pool using a non-suspending JVM monitor
 * (`synchronized`) so coroutine cancellation under lock contention can never abort `finally` or
 * `catch` cleanup or leak reserved slot capacity.
 */
internal class WeightedSlotPool {

    private class PoolWaiter(
        val requestedCost: Int,
        val deferred: CompletableDeferred<Unit>,
        var wokenCost: Int = 0
    )

    private val lock = Any()
    private var used = 0
    private var reservedForWoken = 0
    private val waiters = ArrayDeque<PoolWaiter>()

    fun currentUsed(): Int = synchronized(lock) { used }

    fun notifyCapacityChanged(newCapacity: Int) {
        val cap = newCapacity.coerceAtLeast(1)
        val woke = synchronized(lock) {
            collectEligibleWaitersLocked(cap)
        }
        woke.forEach { it.complete(Unit) }
    }

    suspend fun <T> withSlots(
        cost: Int,
        capacityProvider: () -> Int,
        onPoolChange: () -> Unit,
        block: suspend () -> T
    ): T {
        val effectiveCost = cost.coerceAtLeast(1)
        var acquiredCost = 0
        var myWaiter: PoolWaiter? = null

        try {
            while (true) {
                currentCoroutineContext().ensureActive()

                var waitFor: CompletableDeferred<Unit>? = null
                var wokeDuringCheck: List<CompletableDeferred<Unit>> = emptyList()

                synchronized(lock) {
                    val prevWaiter = myWaiter
                    if (prevWaiter != null) {
                        reservedForWoken = (reservedForWoken - prevWaiter.wokenCost).coerceAtLeast(0)
                        prevWaiter.wokenCost = 0
                        myWaiter = null
                    }

                    val cap = capacityProvider().coerceAtLeast(1)
                    val clampedCost = effectiveCost.coerceAtMost(cap)
                    val canAcquire = if (prevWaiter != null) {
                        used + reservedForWoken + clampedCost <= cap
                    } else {
                        waiters.isEmpty() && used + reservedForWoken + clampedCost <= cap
                    }

                    if (canAcquire) {
                        used += clampedCost
                        acquiredCost = clampedCost
                        wokeDuringCheck = collectEligibleWaitersLocked(cap)
                    } else {
                        val newWaiter = PoolWaiter(effectiveCost, CompletableDeferred())
                        if (prevWaiter != null) {
                            waiters.addFirst(newWaiter)
                        } else {
                            waiters.addLast(newWaiter)
                        }
                        myWaiter = newWaiter
                        waitFor = newWaiter.deferred
                        wokeDuringCheck = collectEligibleWaitersLocked(cap)
                    }
                }

                wokeDuringCheck.forEach { it.complete(Unit) }

                if (waitFor == null) break
                waitFor!!.await()
            }
        } catch (t: Throwable) {
            var wokeOnCancel: List<CompletableDeferred<Unit>> = emptyList()
            var notifyPool = false
            synchronized(lock) {
                if (acquiredCost > 0) {
                    used = (used - acquiredCost).coerceAtLeast(0)
                    acquiredCost = 0
                    notifyPool = true
                }
                val w = myWaiter
                myWaiter = null
                if (w != null) {
                    val existed = waiters.remove(w)
                    if (w.wokenCost > 0) {
                        reservedForWoken = (reservedForWoken - w.wokenCost).coerceAtLeast(0)
                        w.wokenCost = 0
                    }
                    if (existed || w.deferred.isCompleted) {
                        wokeOnCancel = collectEligibleWaitersLocked(capacityProvider().coerceAtLeast(1))
                        if (wokeOnCancel.isNotEmpty()) notifyPool = true
                    }
                }
            }
            wokeOnCancel.forEach { it.complete(Unit) }
            if (notifyPool) {
                onPoolChange()
            }
            throw t
        }

        return try {
            onPoolChange()
            block()
        } finally {
            var wokeOnRelease: List<CompletableDeferred<Unit>> = emptyList()
            synchronized(lock) {
                used = (used - acquiredCost).coerceAtLeast(0)
                wokeOnRelease = collectEligibleWaitersLocked(capacityProvider().coerceAtLeast(1))
            }
            wokeOnRelease.forEach { it.complete(Unit) }
            onPoolChange()
        }
    }

    private fun collectEligibleWaitersLocked(cap: Int): List<CompletableDeferred<Unit>> {
        if (waiters.isEmpty()) return emptyList()
        var result: MutableList<CompletableDeferred<Unit>>? = null
        while (waiters.isNotEmpty()) {
            val head = waiters.first()
            val clamped = head.requestedCost.coerceAtMost(cap)
            if (used + reservedForWoken + clamped <= cap) {
                waiters.removeFirst()
                head.wokenCost = clamped
                reservedForWoken += clamped
                if (result == null) result = ArrayList(2)
                result.add(head.deferred)
            } else {
                break
            }
        }
        return result ?: emptyList()
    }
}
