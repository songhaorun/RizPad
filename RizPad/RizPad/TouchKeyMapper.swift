//
//  TouchKeyMapper.swift
//  RizPad
//
//  Slot-based key assignment with cooldown lock:
//  - Each new finger gets the **lowest available slot** (key index).
//  - Moving the finger anywhere on screen does NOT change its key.
//  - Lifting the finger releases the key and frees the slot.
//  - Supports up to 10 simultaneous fingers.
//
//  Cooldown lock:
//  - When a slot is released, it enters a cooldown period (default 500 ms).
//  - During cooldown, the slot is LOCKED and will be skipped when
//    assigning new touches.
//  - After cooldown expires, the slot becomes available again.
//  - ALL recently-released slots can be in cooldown simultaneously.
//
//  This naturally achieves rapid-tap alternation:
//    tap → slot 0 (A), release → slot 0 locked
//    tap again (<500ms) → slot 0 locked, → slot 1 (S), release → slot 1 locked
//    tap again (<500ms) → slot 1 locked, slot 0 unlocked → slot 0 (A)
//    ...
//

import UIKit

/// Manages slot-based touch → key assignment.
final class TouchKeyMapper {

    // MARK: - Configuration

    /// Ordered key list. Index 0 = first slot, index 1 = second slot, etc.
    let keys: [UInt8]

    /// After a slot is released, it stays locked for this duration (seconds).
    /// Set to 0 to disable cooldown.
    var cooldownDuration: TimeInterval = 0.5

    // MARK: - Default layout

    /// Extended key pool — enough slots so that cooldown locks never
    /// run out of available keys even under rapid multi-finger tapping.
    /// Primary 10 keys + 16 overflow keys = 26 total.
    static let defaultKeys: [UInt8] = [
        // Primary (rhythm-game row)
        UInt8(ascii: "a"), UInt8(ascii: "s"), UInt8(ascii: "d"), UInt8(ascii: "f"), UInt8(ascii: "g"),
        UInt8(ascii: "h"), UInt8(ascii: "j"), UInt8(ascii: "k"), UInt8(ascii: "l"), UInt8(ascii: ";"),
        // Overflow (bottom row → top row)
        UInt8(ascii: "z"), UInt8(ascii: "x"), UInt8(ascii: "c"), UInt8(ascii: "v"), UInt8(ascii: "b"),
        UInt8(ascii: "n"), UInt8(ascii: "m"), UInt8(ascii: "q"), UInt8(ascii: "w"), UInt8(ascii: "e"),
        UInt8(ascii: "r"), UInt8(ascii: "t"), UInt8(ascii: "y"), UInt8(ascii: "u"), UInt8(ascii: "i"),
        UInt8(ascii: "o"), UInt8(ascii: "p"),
    ]

    init(keys: [UInt8] = TouchKeyMapper.defaultKeys) {
        self.keys = keys
    }

    // MARK: - Slot tracking

    /// Maps an active UITouch → assigned slot index.
    private(set) var touchSlots: [UITouch: Int] = [:]

    /// Which slot indices are currently occupied by a finger.
    private var occupiedSlots: Set<Int> = []

    /// Timestamp when each slot was last released (for cooldown).
    private var slotReleaseTime: [Int: TimeInterval] = [:]

    // MARK: - Public API

    /// Assign the lowest available (not occupied AND not in cooldown) slot
    /// to a new touch.  Returns the slot index, or `nil` if all slots are
    /// full or locked.
    @discardableResult
    func assignSlot(for touch: UITouch) -> Int? {
        // If already assigned (shouldn't happen), return existing
        if let existing = touchSlots[touch] { return existing }

        let now = ProcessInfo.processInfo.systemUptime

        // Find lowest free slot that is NOT in cooldown
        for i in 0..<keys.count {
            if !occupiedSlots.contains(i) && !isInCooldown(slot: i, now: now) {
                touchSlots[touch] = i
                occupiedSlots.insert(i)
                return i
            }
        }

        // Fallback: if all free slots are in cooldown, pick the one whose
        // cooldown is closest to expiring (least recently released).
        var bestSlot: Int? = nil
        var earliestRelease: TimeInterval = .greatestFiniteMagnitude
        for i in 0..<keys.count {
            if !occupiedSlots.contains(i) {
                let rt = slotReleaseTime[i] ?? 0
                if rt < earliestRelease {
                    earliestRelease = rt
                    bestSlot = i
                }
            }
        }
        if let slot = bestSlot {
            touchSlots[touch] = slot
            occupiedSlots.insert(slot)
            return slot
        }

        return nil // truly all slots occupied
    }

    /// Release the slot for a touch that ended/cancelled.
    func releaseSlot(for touch: UITouch) {
        if let slot = touchSlots.removeValue(forKey: touch) {
            occupiedSlots.remove(slot)
            slotReleaseTime[slot] = ProcessInfo.processInfo.systemUptime
        }
    }

    /// The key (ASCII byte) for a given touch, or `nil`.
    func key(for touch: UITouch) -> UInt8? {
        guard let slot = touchSlots[touch], slot < keys.count else { return nil }
        return keys[slot]
    }

    /// The slot index assigned to a given touch, or `nil`.
    func slot(for touch: UITouch) -> Int? {
        return touchSlots[touch]
    }

    /// Returns the sorted list of ASCII key codes currently pressed.
    func pressedKeys() -> [UInt8] {
        return occupiedSlots.sorted().compactMap { slot in
            slot < keys.count ? keys[slot] : nil
        }
    }

    /// Key label string for a slot index.
    func keyLabel(for slot: Int) -> String {
        guard slot < keys.count else { return "?" }
        return String(UnicodeScalar(keys[slot]))
    }

    /// Primary key label (same as keyLabel now — no alternation).
    func primaryKeyLabel(for slot: Int) -> String {
        return keyLabel(for: slot)
    }

    // MARK: - Private

    /// Whether a slot is currently in its post-release cooldown.
    private func isInCooldown(slot: Int, now: TimeInterval) -> Bool {
        guard let releaseTime = slotReleaseTime[slot] else { return false }
        return (now - releaseTime) < cooldownDuration
    }
}
