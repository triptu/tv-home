import AppKit
import Observation

@MainActor @Observable final class Remote {
    private(set) var settings: Settings
    private(set) var snapshot = Snapshot(.unchecked)
    private(set) var operation = Operation.idle
    private var adb: ADB
    private let settingsURL = URL.applicationSupportDirectory.appending(path: "TVRemote/settings.json")

    init() {
        let saved = try? JSONDecoder().decode(Settings.self, from: Data(contentsOf: settingsURL))
        settings = saved ?? Settings()
        adb = ADB(settings: saved ?? Settings())
    }

    @discardableResult
    func perform(_ id: String, url: String? = nil) async -> Receipt {
        let receipt: Receipt
        do {
            let request = try CommandRequest(id: id, urlText: url, settings: settings)
            operation = .running(id)
            (receipt, snapshot) = await adb.perform(request)
        } catch {
            receipt = Receipt(.failed, error.reason)
        }
        operation = .finished(receipt)
        return receipt
    }

    func sendClipboardURL() async {
        await perform("url", url: NSPasteboard.general.string(forType: .string) ?? "")
    }

    func save(_ new: Settings) throws {
        try FileManager.default.createDirectory(at: settingsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(new).write(to: settingsURL, options: .atomic)
        settings = new
        adb = ADB(settings: new)
        snapshot = Snapshot(.unchecked)
    }
}
