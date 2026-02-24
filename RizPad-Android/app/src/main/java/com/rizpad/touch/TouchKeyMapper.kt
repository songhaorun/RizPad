package com.rizpad.touch

import android.os.SystemClock

/**
 * Slot-based key assignment with cooldown lock (Android port of iOS TouchKeyMapper).
 *
 * - Each new finger (pointer ID) gets the **lowest available slot** (key index).
 * - Moving the finger anywhere on screen does NOT change its key.
 * - Lifting the finger releases the key and frees the slot.
 * - Supports up to 10 simultaneous fingers.
 *
 * Cooldown lock:
 * - When a slot is released, it enters a cooldown period (default 500 ms).
 * - During cooldown, the slot is LOCKED and will be skipped.
 * - After cooldown expires, the slot becomes available again.
 */
class TouchKeyMapper(
    val keys: List<Byte> = DEFAULT_KEYS,
    var cooldownDuration: Long = 500L // milliseconds
) {

    companion object {
        val DEFAULT_KEYS: List<Byte> = listOf(
            // Primary (rhythm-game row)
            'a'.code.toByte(), 's'.code.toByte(), 'd'.code.toByte(), 'f'.code.toByte(), 'g'.code.toByte(),
            'h'.code.toByte(), 'j'.code.toByte(), 'k'.code.toByte(), 'l'.code.toByte(), ';'.code.toByte(),
            // Overflow (bottom row → top row)
            'z'.code.toByte(), 'x'.code.toByte(), 'c'.code.toByte(), 'v'.code.toByte(), 'b'.code.toByte(),
            'n'.code.toByte(), 'm'.code.toByte(), 'q'.code.toByte(), 'w'.code.toByte(), 'e'.code.toByte(),
            'r'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'u'.code.toByte(), 'i'.code.toByte(),
            'o'.code.toByte(), 'p'.code.toByte(),
        )

        const val PRIMARY_SLOT_COUNT = 10
    }

    /** Pointer ID → assigned slot index */
    val pointerSlots: MutableMap<Int, Int> = mutableMapOf()

    private val occupiedSlots: MutableSet<Int> = mutableSetOf()
    private val slotReleaseTime: MutableMap<Int, Long> = mutableMapOf()

    /**
     * Assign the lowest available slot to the given pointer ID.
     * Returns the slot index, or null if all slots are occupied.
     */
    fun assignSlot(pointerId: Int): Int? {
        pointerSlots[pointerId]?.let { return it }
        val now = SystemClock.elapsedRealtime()

        // Find lowest available slot not in cooldown
        for (i in keys.indices) {
            if (i !in occupiedSlots && !isInCooldown(i, now)) {
                pointerSlots[pointerId] = i
                occupiedSlots.add(i)
                return i
            }
        }

        // Fallback: pick slot with closest-to-expiring cooldown
        var bestSlot: Int? = null
        var earliestRelease = Long.MAX_VALUE
        for (i in keys.indices) {
            if (i !in occupiedSlots) {
                val rt = slotReleaseTime[i] ?: 0L
                if (rt < earliestRelease) {
                    earliestRelease = rt
                    bestSlot = i
                }
            }
        }

        if (bestSlot != null) {
            pointerSlots[pointerId] = bestSlot
            occupiedSlots.add(bestSlot)
            return bestSlot
        }

        return null
    }

    /** Release the slot occupied by the given pointer ID. */
    fun releaseSlot(pointerId: Int) {
        val slot = pointerSlots.remove(pointerId) ?: return
        occupiedSlots.remove(slot)
        slotReleaseTime[slot] = SystemClock.elapsedRealtime()
    }

    /** Get the key byte for a given pointer ID. */
    fun key(pointerId: Int): Byte? {
        val slot = pointerSlots[pointerId] ?: return null
        return if (slot < keys.size) keys[slot] else null
    }

    /** Get the slot index for a given pointer ID. */
    fun slot(pointerId: Int): Int? = pointerSlots[pointerId]

    /** Get all currently pressed key bytes, sorted by slot. */
    fun pressedKeys(): List<Byte> {
        return occupiedSlots.sorted().mapNotNull { slot ->
            if (slot < keys.size) keys[slot] else null
        }
    }

    /** Get the display label for a slot. */
    fun keyLabel(slot: Int): String {
        if (slot >= keys.size) return "?"
        return String(charArrayOf(keys[slot].toInt().toChar()))
    }

    /** Get the display label for a primary slot. */
    fun primaryKeyLabel(slot: Int): String = keyLabel(slot)

    private fun isInCooldown(slot: Int, now: Long): Boolean {
        val releaseTime = slotReleaseTime[slot] ?: return false
        return (now - releaseTime) < cooldownDuration
    }
}
