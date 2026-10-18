import SwiftUI

@main enum Main {
    static func main() {
        let arguments = Array(CommandLine.arguments.dropFirst())
        guard arguments.contains(where: { ["--probe", "--run"].contains($0) }) else { return TVRemoteApp.main() }
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
}
