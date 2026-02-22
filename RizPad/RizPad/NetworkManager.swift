//
//  NetworkManager.swift
//  RizPad
//
//  TCP Server using Network.framework (iOS 12+).
//  Listens on a fixed port; accepts one PC client at a time.
//  Sends binary key-state packets on every touch event call.
//

import Foundation
import Network

/// Manages a TCP listener and the current client connection.
///
/// Marked `nonisolated` so NWListener / NWConnection callbacks on a custom
/// DispatchQueue don't conflict with the implicit @MainActor isolation.
nonisolated final class NetworkManager: @unchecked Sendable {

    // MARK: - Public

    /// The port the server listens on.
    static let port: UInt16 = 24864

    /// Callback fired on the **main queue** when connection state changes.
    var onConnectionChanged: ((_ connected: Bool) -> Void)?

    /// Whether a PC client is currently connected.
    private(set) var isConnected: Bool = false

    // MARK: - Private

    private var listener: NWListener?
    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "com.rizpad.network", qos: .userInteractive)

    /// Kept so we can reset on disconnect.
    private var lastPayload: Data?

    // MARK: - Lifecycle

    /// Start listening.
    func start() {
        do {
            // Build TCP parameters with Nagle disabled for lowest latency
            let tcpOptions = NWProtocolTCP.Options()
            tcpOptions.noDelay = true
            let params = NWParameters(tls: nil, tcp: tcpOptions)

            listener = try NWListener(using: params, on: NWEndpoint.Port(rawValue: Self.port)!)
        } catch {
            print("[Net] Failed to create listener: \(error)")
            return
        }

        listener?.newConnectionHandler = { [weak self] newConn in
            self?.handleNewConnection(newConn)
        }

        listener?.stateUpdateHandler = { state in
            switch state {
            case .ready:
                print("[Net] Listening on port \(Self.port)")
            case .failed(let err):
                print("[Net] Listener failed: \(err)")
            default:
                break
            }
        }

        listener?.start(queue: queue)
    }

    /// Stop listening and disconnect.
    func stop() {
        listener?.cancel()
        listener = nil
        connection?.cancel()
        connection = nil
        setConnected(false)
    }

    // MARK: - Sending

    /// Send a key-state packet immediately.
    ///
    /// Called on every touch event so the send rate matches the iOS
    /// touch-event rate (~60 Hz, or ~120 Hz on ProMotion devices).
    ///
    /// Packet format:
    /// ```
    /// [1 byte length N] [1 byte command 0x01] [N-1 bytes: ASCII key codes]
    /// ```
    func sendKeyState(pressedKeys: [UInt8]) {
        let payload = Data([0x01] + pressedKeys)
        lastPayload = payload

        let length = UInt8(clamping: payload.count)
        let packet = Data([length]) + payload
        sendRaw(packet)
    }

    // MARK: - Private helpers

    private func sendRaw(_ data: Data) {
        guard let conn = connection else { return }
        conn.send(content: data, completion: .contentProcessed { error in
            if let error = error {
                print("[Net] Send error: \(error)")
            }
        })
    }

    private func handleNewConnection(_ newConn: NWConnection) {
        // Only allow one client; drop old connection if any
        connection?.cancel()

        connection = newConn

        newConn.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                print("[Net] Client connected")
                self?.setConnected(true)
            case .failed(let err):
                print("[Net] Connection failed: \(err)")
                self?.setConnected(false)
            case .cancelled:
                print("[Net] Connection cancelled")
                self?.setConnected(false)
            default:
                break
            }
        }

        // Start receiving (even if we don't expect much from PC → iPad in v1)
        startReceive(on: newConn)
        newConn.start(queue: queue)
    }

    private func startReceive(on conn: NWConnection) {
        conn.receive(minimumIncompleteLength: 1, maximumLength: 256) { [weak self] data, _, isComplete, error in
            if let data = data, !data.isEmpty {
                // For now just log; future: handle LED / config from PC
                print("[Net] Received \(data.count) bytes from PC")
            }
            if isComplete || error != nil {
                self?.setConnected(false)
                conn.cancel()
                return
            }
            // Keep reading
            self?.startReceive(on: conn)
        }
    }

    private func setConnected(_ value: Bool) {
        let changed = (isConnected != value)
        isConnected = value
        if changed {
            if !value { lastPayload = nil }
            DispatchQueue.main.async { [weak self] in
                self?.onConnectionChanged?(value)
            }
        }
    }
}
