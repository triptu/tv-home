import Foundation

enum RunError: Error {
    case timedOut
    case failed(String)
    case unrunnable(String)
}

private final class Capture: @unchecked Sendable {
    private let lock = NSLock()
    private var output = [Data(), Data()]
    private var open: Int
    private var status: Int32?
    private var finished = false
    private var timedOut = false
    var continuation: CheckedContinuation<String, Error>?

    init(open: Int) { self.open = open }

    func append(_ index: Int, _ data: Data, _ handle: FileHandle) {
        lock.withLock {
            if data.isEmpty { handle.readabilityHandler = nil; open -= 1 }
            else if output[index].count < 1 << 20 { output[index].append(data) }
        }
        finishIfDone()
    }

    func exited(_ code: Int32) {
        lock.withLock { status = code }
        finishIfDone()
        // A forked adb server can inherit our pipes and hold them open after adb itself exits.
        DispatchQueue.global().asyncAfter(deadline: .now() + 0.3) { self.finish() }
    }

    func fail(_ error: Error) {
        lock.withLock { finished = true }
        continuation?.resume(throwing: RunError.unrunnable(error.localizedDescription))
    }

    func markTimedOut() -> Bool {
        lock.withLock {
            guard status == nil else { return true }
            timedOut = true
            return false
        }
    }

    private func finishIfDone() { if lock.withLock({ status != nil && open == 0 }) { finish() } }

    private func finish() {
        let result: Result<String, Error>? = lock.withLock {
            guard !finished, let status else { return nil }
            finished = true
            let (out, err) = (String(decoding: output[0], as: UTF8.self), String(decoding: output[1], as: UTF8.self))
            if timedOut { return .failure(RunError.timedOut) }
            if status != 0 { return .failure(RunError.failed((err.isEmpty ? out : err).trimmingCharacters(in: .whitespacesAndNewlines))) }
            return .success(out)
        }
        if let result { continuation?.resume(with: result) }
    }
}

func run(_ executable: URL, _ arguments: [String], deadline: Duration, quiet: Bool = false) async throws -> String {
    let process = Process()
    process.executableURL = executable
    process.arguments = arguments
    process.standardInput = FileHandle.nullDevice
    let capture = Capture(open: quiet ? 0 : 2)
    if quiet {
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
    } else {
        let pipes = [Pipe(), Pipe()]
        (process.standardOutput, process.standardError) = (pipes[0], pipes[1])
        for (index, pipe) in pipes.enumerated() {
            pipe.fileHandleForReading.readabilityHandler = { capture.append(index, $0.availableData, $0) }
        }
    }
    process.terminationHandler = { capture.exited($0.terminationStatus) }
    return try await withCheckedThrowingContinuation { continuation in
        capture.continuation = continuation
        do { try process.run() } catch { return capture.fail(error) }
        let seconds = Double(deadline.components.seconds) + Double(deadline.components.attoseconds) * 1e-18
        DispatchQueue.global().asyncAfter(deadline: .now() + seconds) {
            if capture.markTimedOut() { return }
            process.terminate()
            DispatchQueue.global().asyncAfter(deadline: .now() + 1) {
                if process.isRunning { kill(process.processIdentifier, SIGKILL) }
            }
        }
    }
}

actor ADB {
    private let settings: Settings
    private var snapshot = Snapshot(.unchecked)
    private var queue: [(CommandRequest, CheckedContinuation<(Receipt, Snapshot), Never>)] = []
    private var draining = false
    private var components: [String: String] = [:]
    private var serverStarted = false

    init(settings: Settings) { self.settings = settings }

    func perform(_ request: CommandRequest) async -> (Receipt, Snapshot) {
        guard queue.count < 4 else { return (Receipt(.failed, "Busy. Try again in a moment."), snapshot) }
        return await withCheckedContinuation { continuation in
            queue.append((request, continuation))
            if !draining {
                draining = true
                Task { await drain() }
            }
        }
    }

    private func drain() async {
        while !queue.isEmpty {
            let (request, continuation) = queue.removeFirst()
            continuation.resume(returning: await execute(request))
        }
        draining = false
    }

    private func execute(_ request: CommandRequest) async -> (Receipt, Snapshot) {
        let steps: [Step]
        do { steps = try plan(request, snapshot, settings: settings) } catch { return (Receipt(.failed, error.reason), snapshot) }
        var receipt = Receipt(.sent, "\(request.command.label) sent")
        var note: String?
        walk: for step in steps {
            do {
                switch step {
                case .connect(let deadline): try await connect(within: deadline, fresh: request.command.action == .reconnect && snapshot.connection.looksStale)
                case .wakeOnLAN:
                    do { try sendMagicPacket() } catch { note = error.reason }
                case .startSunshine: try await startSunshine()
                case .shell(let command): try await shell(command)
                case .launch(let package, let fallback): try await shell("am start -n \(quote(await component(package, fallback: fallback)))")
                case .probe(let expect): receipt = await probe(until: expect) ?? receipt
                }
            } catch RunError.timedOut {
                receipt = Receipt(.unknown, "Result unknown. Check the TV before trying again.")
                break walk
            } catch {
                switch step {
                case .connect:
                    snapshot = Snapshot(.unreachable(describe(error)))
                    receipt = Receipt(.failed, "TV did not answer. Reconnect.")
                case .shell, .launch:
                    receipt = Receipt(.failed, describe(error))
                    if case .probe = steps.last { _ = await probe(until: nil) }
                default:
                    receipt = Receipt(.failed, describe(error))
                }
                break walk
            }
        }
        if request.command.action == .reconnect, receipt.kind == .sent { receipt = Receipt(.observed, snapshot.connection.summary) }
        if let note { receipt = Receipt(receipt.kind, receipt.message + (receipt.message.hasSuffix(".") ? " " : ". ") + note) }
        return (receipt, snapshot)
    }

    private func describe(_ error: Error) -> String {
        switch error {
        case let refusal as Refusal: refusal.reason
        case RunError.failed(let message), RunError.unrunnable(let message): message
        default: error.localizedDescription
        }
    }

    @discardableResult
    private func shell(_ command: String, deadline: Duration = .seconds(4)) async throws -> String {
        let output = try await run(settings.adb, ["-s", settings.device.serial, "shell", command], deadline: deadline)
        if command.hasPrefix("am "), let error = output.split(whereSeparator: \.isNewline).first(where: { $0.hasPrefix("Error") }) {
            throw Refusal("TV refused: \(error)")
        }
        return output
    }

    private func connect(within deadline: Duration, fresh: Bool) async throws {
        guard FileManager.default.isExecutableFile(atPath: settings.adb.path) else {
            throw RunError.failed("adb not found at \(settings.adbPath). Set its path in Settings.")
        }
        if !serverStarted {
            _ = try? await run(settings.adb, ["start-server"], deadline: .seconds(8), quiet: true)
            serverStarted = true
        }
        // adb keeps a dropped TV as "offline" and answers "already connected" until the transport is cleared.
        if fresh { _ = try? await run(settings.adb, ["disconnect", settings.device.serial], deadline: .seconds(4)) }
        let end = ContinuousClock.now + deadline
        var reason = "TV did not answer"
        repeat {
            do {
                let output = try await run(settings.adb, ["connect", settings.device.serial], deadline: .seconds(8))
                if output.contains("connected to") {
                    components = [:]
                    return
                }
                reason = output.trimmingCharacters(in: .whitespacesAndNewlines)
            } catch RunError.failed(let message) {
                reason = message
            } catch {
                reason = "TV did not answer"
            }
            if ContinuousClock.now < end { try? await Task.sleep(for: .seconds(2)) }
        } while ContinuousClock.now < end
        throw RunError.failed(reason)
    }

    private func probe(until expect: Expect?) async -> Receipt? {
        let end = ContinuousClock.now + .seconds(8)
        while true {
            do {
                let output = try await shell("dumpsys power | grep mWakefulness=; dumpsys activity activities | grep topResumedActivity; true")
                snapshot = Snapshot(Connection.parse(output))
            } catch RunError.unrunnable(let reason) {
                snapshot = Snapshot(.unreachable(reason))
            } catch {
                snapshot = Snapshot(.reachableUnknown("Status check failed"))
            }
            guard let expect else { return nil }
            if expect.met(by: snapshot.connection) { return Receipt(.observed, expect.observed) }
            if ContinuousClock.now >= end { return Receipt(.unknown, expect.unconfirmed) }
            try? await Task.sleep(for: .seconds(1))
        }
    }

    private func component(_ package: String, fallback: String) async throws -> String {
        if let cached = components[package] { return cached }
        let output = try await shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER -p \(quote(package))")
        let component = resolvedComponent(output, package: package) ?? fallback
        components[package] = component
        return component
    }

    private func startSunshine() async throws(Refusal) {
        do { _ = try await run(URL(fileURLWithPath: "/usr/bin/open"), ["-g", "-a", "Sunshine"], deadline: .seconds(4)) }
        catch { throw Refusal("Sunshine could not be started") }
        let end = ContinuousClock.now + .seconds(10)
        // open returns once Launch Services accepts the request, before Sunshine listens for Moonlight.
        while !listening(onLocalPort: 47989) {
            guard ContinuousClock.now < end else { throw Refusal("Sunshine did not start") }
            try? await Task.sleep(for: .milliseconds(500))
        }
    }

    private func sendMagicPacket() throws(Refusal) {
        let parts = settings.device.mac.split(separator: ":")
        let mac = parts.compactMap { UInt8($0, radix: 16) }
        guard parts.count == 6, mac.count == 6 else { throw Refusal("Wake-on-LAN not sent: the MAC address in Settings is not valid") }
        let packet = Data(repeating: 0xFF, count: 6) + Data((0..<16).flatMap { _ in mac })
        // Home LANs are /24; a subnet broadcast reaches a TV in deep standby without needing its ARP entry.
        let broadcast = settings.device.host.split(separator: ".").dropLast().joined(separator: ".") + ".255"
        // Network.framework cannot set SO_BROADCAST, so NWConnection gets EACCES sending to a broadcast address.
        guard var address = ipv4(broadcast, port: 9) else { throw Refusal("Wake-on-LAN not sent: \(broadcast) is not an IPv4 address") }
        let socket = Darwin.socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard socket >= 0 else { throw Refusal("Wake-on-LAN not sent: \(String(cString: strerror(errno)))") }
        defer { close(socket) }
        var on: Int32 = 1
        guard setsockopt(socket, SOL_SOCKET, SO_BROADCAST, &on, socklen_t(MemoryLayout<Int32>.size)) == 0 else {
            throw Refusal("Wake-on-LAN not sent: \(String(cString: strerror(errno)))")
        }
        let sent = packet.withUnsafeBytes { bytes in
            withUnsafePointer(to: &address) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(socket, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent == packet.count else { throw Refusal("Wake-on-LAN not sent: \(String(cString: strerror(errno)))") }
    }
}

func ipv4(_ text: String, port: UInt16) -> sockaddr_in? {
    var address = sockaddr_in()
    address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
    address.sin_family = sa_family_t(AF_INET)
    address.sin_port = port.bigEndian
    return inet_pton(AF_INET, text, &address.sin_addr) == 1 ? address : nil
}

func listening(onLocalPort port: UInt16) -> Bool {
    guard var address = ipv4("127.0.0.1", port: port) else { return false }
    let socket = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    guard socket >= 0 else { return false }
    defer { close(socket) }
    return withUnsafePointer(to: &address) {
        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0 }
    }
}
