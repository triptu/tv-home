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
    @State private var remote = Remote()

    var body: some Scene {
        MenuBarExtra {
            RemoteView(remote: remote)
        } label: {
            Image(systemName: remote.snapshot.connection.symbol)
        }
        .menuBarExtraStyle(.window)
        SwiftUI.Settings { SettingsView(remote: remote) }
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
