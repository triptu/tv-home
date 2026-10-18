import SwiftUI

@main enum Main {
    static func main() {
        let arguments = Array(CommandLine.arguments.dropFirst())
        guard arguments.contains(where: { ["--probe", "--run", "--phone"].contains($0) }) else { return TVRemoteApp.main() }
        setvbuf(stdout, nil, _IOLBF, 0)
        Task { @MainActor in
            await commandLine(arguments)
            exit(0)
        }
        dispatchMain()
    }
}

struct TVRemoteApp: App {
    @NSApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var remote = Remote()

    var body: some Scene {
        Window("TV Remote", id: "main") {
            RemoteWindow(remote: remote)
        }
        .defaultSize(width: 400, height: 760)
        .windowResizability(.contentMinSize)
        MenuBarExtra {
            QuickMenu(remote: remote)
        } label: {
            Image(systemName: remote.snapshot.connection.symbol)
        }
        .menuBarExtraStyle(.menu)
        SwiftUI.Settings { SettingsView(remote: remote) }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }
}

struct QuickMenu: View {
    let remote: Remote
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        Text(remote.snapshot.connection.headline)
        item(remote.snapshot.connection.isAwake ? "sleep" : "wake")
        item("volume_up")
        item("volume_down")
        item("mute")
        item("play_pause")
        item("stream")
        Divider()
        Button("Open TV Remote…") {
            openWindow(id: "main")
            NSApp.activate()
        }
        Button("Settings…") {
            NSApp.activate()
            openSettings()
        }
        .keyboardShortcut(",")
        Button("Quit TV Remote") { NSApp.terminate(nil) }
            .keyboardShortcut("q")
    }

    private func item(_ id: String) -> some View {
        let command = Command.named(id)!
        return Button(command.label, systemImage: command.symbol) { Task { await remote.perform(id) } }
            .disabled(remote.blocker(command) != nil)
    }
}

extension Connection {
    var symbol: String {
        switch self {
        case .awake: "tv.fill"
        case .standby: "tv"
        case .unchecked, .unreachable, .reachableUnknown: "tv.slash"
        }
    }

    var isAwake: Bool { if case .awake = self { true } else { false } }

    var headline: String {
        switch self {
        case .unchecked: "Checking the TV"
        case .unreachable: "Cannot reach TV"
        case .reachableUnknown: "State unclear"
        case .standby, .awake: summary
        }
    }

    var advice: String? {
        switch self {
        case .unreachable(let reason): "\(clause(reason)), so check the TV is on and on this Wi-Fi, then reconnect."
        case .reachableUnknown(let reason): "\(clause(reason)), so reconnect to check again."
        case .unchecked, .standby, .awake: nil
        }
    }

    private func clause(_ reason: String) -> String {
        reason.trimmingCharacters(in: .whitespacesAndNewlines.union(.init(charactersIn: ".")))
    }
}

extension Command {
    var isKey: Bool { if case .key = action { true } else { false } }
}

extension Remote {
    /// Key presses may overlap each other, which keeps volume taps responsive. Anything else waits for the running command.
    func blocker(_ command: Command) -> String? {
        if case .running(let id) = operation, let active = Command.named(id), !(active.isKey && command.isKey) {
            return id == command.id ? "\(command.label) in progress" : "Waiting for \(active.label.lowercased())"
        }
        return command.unavailable(in: snapshot.connection, settings: settings)
    }

    func isRunning(_ command: Command) -> Bool { operation == .running(command.id) }

    func refresh() async {
        if case .running = operation { return }
        guard snapshot.connection == .unchecked || snapshot.connection.looksStale || snapshot.at.timeIntervalSinceNow < -30 else { return }
        await perform("reconnect")
    }
}

@MainActor func commandLine(_ arguments: [String]) async {
    let remote = Remote()
    func report(_ id: String, _ receipt: Receipt) {
        print("\(id): \(receipt.kind.rawValue) | \(receipt.message) | \(remote.snapshot.connection)")
    }
    report("reconnect", await remote.perform("reconnect"))
    for (flag, id) in zip(arguments, arguments.dropFirst()) where flag == "--run" {
        report(id, await remote.perform(id))
    }
    guard arguments.contains("--phone") else { return }
    await remote.setPhone(true)
    guard let url = remote.phone?.url else { return print("phone: \(remote.operation)") }
    print("phone: \(url.absoluteString)")
    signal(SIGUSR1, SIG_IGN)
    let stop = DispatchSource.makeSignalSource(signal: SIGUSR1, queue: .main)
    stop.setEventHandler { Task { @MainActor in await remote.setPhone(false); print("phone: stopped") } }
    stop.resume()
    while true { try? await Task.sleep(for: .seconds(3600)) }
}
