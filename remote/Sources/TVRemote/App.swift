import Foundation

@main enum Main {
    static func main() {
        let arguments = Array(CommandLine.arguments.dropFirst())
        Task { @MainActor in
            await commandLine(arguments)
            exit(0)
        }
        dispatchMain()
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
