//
//  ViewController.swift
//  RizPad
//
//  Created by UnderLine on 2026/2/21.
//
//  Full-screen touch pad with slot-based key assignment:
//  - Each new finger gets the lowest available key slot.
//  - Moving the finger does NOT change its key.
//  - Lifting releases the key and frees the slot.
//  - Circle + key label follows the finger.
//

import UIKit

class ViewController: UIViewController {

    // MARK: - Dependencies

    private let network = NetworkManager()
    private let mapper = TouchKeyMapper()

    /// CADisplayLink drives network sends at exactly the screen refresh
    /// rate (60 Hz), regardless of touch activity.
    private var displayLink: CADisplayLink?

    // MARK: - UI state

    /// Each active touch → (circleView, keyLabel)
    private var touchVisuals: [UITouch: (circle: UIView, label: UILabel)] = [:]

    /// Connection status label (top-right corner).
    private let statusLabel: UILabel = {
        let label = UILabel()
        label.text = "未连接"
        label.textColor = .white
        label.textAlignment = .center
        label.backgroundColor = UIColor.black.withAlphaComponent(0.6)
        label.layer.cornerRadius = 8
        label.clipsToBounds = true
        label.font = .systemFont(ofSize: 14)
        return label
    }()

    /// Bottom bar showing all key slots and their status.
    private var slotIndicators: [UILabel] = []

    // MARK: - Constants

    private let circleSize: CGFloat = 90
    private let circleLabelOffset: CGFloat = -55  // label above circle

    /// Colors for each slot (cycling).
    private let slotColors: [UIColor] = [
        .systemRed, .systemOrange, .systemYellow, .systemGreen, .systemTeal,
        .systemBlue, .systemIndigo, .systemPurple, .systemPink, .systemBrown,
    ]

    // MARK: - View lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = .black
        view.isMultipleTouchEnabled = true

        // Network
        network.onConnectionChanged = { [weak self] connected in
            self?.statusLabel.text = connected ? "已连接" : "未连接"
            self?.statusLabel.textColor = connected ? .green : .white
        }
        network.start()

        // Status label
        view.addSubview(statusLabel)

        // Build bottom slot indicators
        buildSlotIndicators()

        // Start display-link driven send loop (60 Hz)
        startDisplayLink()
    }

    deinit {
        displayLink?.invalidate()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        let sw = view.bounds.width
        let safeTop = view.safeAreaInsets.top
        statusLabel.frame = CGRect(x: sw - 120, y: safeTop + 8, width: 110, height: 30)

        layoutSlotIndicators()
    }

    // MARK: - Status bar & home indicator

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }

    // MARK: - Touch handling

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            if let slot = mapper.assignSlot(for: touch) {
                addVisuals(for: touch, slot: slot)
            }
        }
        refreshAllVisuals()
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            moveVisuals(for: touch)
        }
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            removeVisuals(for: touch)
            mapper.releaseSlot(for: touch)
        }
        refreshAllVisuals()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for touch in touches {
            removeVisuals(for: touch)
            mapper.releaseSlot(for: touch)
        }
        refreshAllVisuals()
    }

    // MARK: - Visual circle + key label

    private func addVisuals(for touch: UITouch, slot: Int) {
        let pos = touch.location(in: view)

        // Circle (color will be set by refreshAllVisuals)
        let circle = UIView(frame: CGRect(x: 0, y: 0, width: circleSize, height: circleSize))
        circle.center = pos
        circle.layer.cornerRadius = circleSize / 2
        circle.layer.borderWidth = 2
        circle.isUserInteractionEnabled = false
        view.addSubview(circle)

        // Floating key label above circle (text set by refreshAllVisuals)
        let label = UILabel(frame: CGRect(x: 0, y: 0, width: 50, height: 30))
        label.center = CGPoint(x: pos.x, y: pos.y + circleLabelOffset)
        label.textAlignment = .center
        label.textColor = .white
        label.font = .monospacedSystemFont(ofSize: 22, weight: .bold)
        label.isUserInteractionEnabled = false
        view.addSubview(label)

        touchVisuals[touch] = (circle, label)
    }

    private func moveVisuals(for touch: UITouch) {
        guard let vis = touchVisuals[touch] else { return }
        let pos = touch.location(in: view)
        vis.circle.center = pos
        vis.label.center = CGPoint(x: pos.x, y: pos.y + circleLabelOffset)
    }

    private func removeVisuals(for touch: UITouch) {
        guard let vis = touchVisuals.removeValue(forKey: touch) else { return }
        vis.circle.removeFromSuperview()
        vis.label.removeFromSuperview()
    }

    // MARK: - Refresh all visuals from current state

    /// Update circle colors, key labels, and all bottom indicators to
    /// match the current slot assignments.
    ///
    /// Called after every batch of slot assigns/releases so the UI
    /// always reflects reality (slot = key index, 1:1 mapping).
    private func refreshAllVisuals() {
        // 1) Update circle color + label for every active touch
        for (touch, vis) in touchVisuals {
            guard let slot = mapper.slot(for: touch) else { continue }
            let color = slotColors[slot % slotColors.count]
            vis.circle.backgroundColor = color.withAlphaComponent(0.45)
            vis.circle.layer.borderColor = color.cgColor
            vis.label.text = mapper.keyLabel(for: slot).uppercased()
        }

        // 2) Reset ALL bottom indicators, then light up occupied slots
        for i in 0..<slotIndicators.count {
            setSlotIndicatorInactive(i)
        }
        for slot in mapper.touchSlots.values {
            setSlotIndicatorActive(slot)
        }
    }

    // MARK: - Network send (driven by CADisplayLink)

    private func startDisplayLink() {
        let link = CADisplayLink(target: self, selector: #selector(displayLinkFired))
        // Default preferredFramesPerSecond = 0 → matches display refresh (60 Hz)
        link.add(to: .main, forMode: .common)
        displayLink = link
    }

    @objc private func displayLinkFired() {
        let pressed = mapper.pressedKeys()
        network.sendKeyState(pressedKeys: pressed)
    }

    /// Number of primary slots shown in the bottom indicator bar.
    /// Overflow slots (index >= this) still work but have no indicator.
    private let primarySlotCount = 10

    // MARK: - Bottom slot indicators

    private func buildSlotIndicators() {
        for i in 0..<primarySlotCount {
            let label = UILabel()
            label.text = mapper.primaryKeyLabel(for: i).uppercased()
            label.textAlignment = .center
            label.textColor = UIColor.white.withAlphaComponent(0.3)
            label.backgroundColor = UIColor.white.withAlphaComponent(0.05)
            label.font = .monospacedSystemFont(ofSize: 16, weight: .medium)
            label.layer.cornerRadius = 6
            label.clipsToBounds = true
            label.isUserInteractionEnabled = false
            view.addSubview(label)
            slotIndicators.append(label)
        }
    }

    private func layoutSlotIndicators() {
        let count = slotIndicators.count
        guard count > 0 else { return }
        let sw = view.bounds.width
        let sh = view.bounds.height
        let padding: CGFloat = 8
        let totalPad = padding * CGFloat(count + 1)
        let itemW = (sw - totalPad) / CGFloat(count)
        let itemH: CGFloat = 36
        let y = sh - itemH - max(view.safeAreaInsets.bottom, 12)

        for (i, label) in slotIndicators.enumerated() {
            label.frame = CGRect(x: padding + CGFloat(i) * (itemW + padding), y: y, width: itemW, height: itemH)
        }
    }

    private func setSlotIndicatorActive(_ slot: Int) {
        guard slot < slotIndicators.count else { return }
        let color = slotColors[slot % slotColors.count]
        let label = slotIndicators[slot]
        label.backgroundColor = color.withAlphaComponent(0.5)
        label.textColor = .white
    }

    private func setSlotIndicatorInactive(_ slot: Int) {
        guard slot < slotIndicators.count else { return }
        let label = slotIndicators[slot]
        label.backgroundColor = UIColor.white.withAlphaComponent(0.05)
        label.textColor = UIColor.white.withAlphaComponent(0.3)
    }
}

