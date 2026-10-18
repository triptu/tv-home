import AppKit
import Observation

@MainActor @Observable final class Remote {
    private(set) var settings: Settings
    private(set) var snapshot = Snapshot(.unchecked)
    private(set) var operation = Operation.idle
    private(set) var phone: Phone?
    private var adb: ADB
    private var inFlight = 0
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
            let executor = adb
            inFlight += 1
            let result = await executor.perform(request)
            inFlight -= 1
            receipt = result.0
            if executor === adb { snapshot = result.1 }
        } catch {
            receipt = Receipt(.failed, error.reason)
        }
        operation = .finished(receipt)
        return receipt
    }

    func sendClipboardURL() async {
        await perform("url", url: NSPasteboard.general.string(forType: .string) ?? "")
    }

    func setPhone(_ on: Bool) async {
        phone?.stop()
        phone = nil
        guard on else { return }
        do {
            phone = try await Phone(remote: self) { [weak self] reason in
                self?.phone = nil
                self?.operation = .finished(Receipt(.failed, "Phone remote stopped: \(reason)"))
            }
        } catch {
            operation = .finished(Receipt(.failed, (error as? Refusal)?.reason ?? error.localizedDescription))
        }
    }

    func save(_ new: Settings) throws {
        guard inFlight == 0 else { throw Refusal("Wait for the current command to finish") }
        try FileManager.default.createDirectory(at: settingsURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(new).write(to: settingsURL, options: .atomic)
        settings = new
        adb = ADB(settings: new)
        snapshot = Snapshot(.unchecked)
    }
}
