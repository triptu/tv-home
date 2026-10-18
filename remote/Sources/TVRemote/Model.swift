import Foundation

struct Device: Codable, Sendable, Equatable {
    var name = "Living room"
    var host = "192.168.1.30"
    var port: UInt16 = 5555
    var mac = "2c:1b:3a:f3:66:cf"
    var serial: String { "\(host):\(port)" }
}

struct StreamTarget: Codable, Sendable, Equatable {
    var hostUUID = ""
    var appID = ""
    var parsed: (uuid: UUID, appID: String)? {
        guard let uuid = UUID(uuidString: hostUUID), let app = UInt(appID), app > 0 else { return nil }
        return (uuid, String(app))
    }
}

struct Settings: Codable, Sendable, Equatable {
    var device = Device()
    var adbPath = "~/Library/Android/sdk/platform-tools/adb"
    var stream = StreamTarget()
    var adb: URL { URL(fileURLWithPath: NSString(string: adbPath).expandingTildeInPath) }
}

enum Connection: Sendable, Equatable {
    case unchecked
    case unreachable(String)
    case reachableUnknown(String)
    case standby
    case awake(foreground: String?)

    static func parse(_ probe: String) -> Connection {
        guard let power = probe.firstMatch(of: /mWakefulness=(\w+)/)?.1 else {
            return .reachableUnknown("TV gave no power state")
        }
        switch power {
        case "Awake", "Dreaming":
            let package = probe.firstMatch(of: /topResumedActivity=\S+ \S+ ([\w.]+)\//)?.1
            return .awake(foreground: package.map(String.init))
        case "Asleep", "Dozing":
            return .standby
        default:
            return .reachableUnknown("TV reported power state \(power)")
        }
    }

    var summary: String {
        switch self {
        case .unchecked: "Not checked yet"
        case .unreachable(let reason): "Cannot reach TV. \(reason)"
        case .reachableUnknown(let reason): "State unclear. \(reason)"
        case .standby: "Standby"
        case .awake(let package?): "Awake · \(Command.appName(package))"
        case .awake(nil): "Awake"
        }
    }
}

struct Snapshot: Sendable, Equatable {
    var connection: Connection
    var at = Date()
    init(_ connection: Connection) { self.connection = connection }
}

struct Receipt: Codable, Sendable, Equatable {
    enum Kind: String, Codable, Sendable { case sent, observed, failed, unknown }
    let kind: Kind
    let message: String
    init(_ kind: Kind, _ message: String) { (self.kind, self.message) = (kind, message) }
}

enum Operation: Equatable {
    case idle
    case running(String)
    case finished(Receipt)
}

struct Refusal: Error {
    let reason: String
    init(_ reason: String) { self.reason = reason }
}

enum Group: String, Sendable { case power, volume, media, pad, nav, apps, stream, url }

enum Requirement: Sendable { case any, notAwake, awake, awakeOrStandby }

struct Command: Sendable, Identifiable {
    enum Action: Sendable, Equatable {
        case reconnect, wake, sleep, stream, url
        case key(String)
        case launch(package: String, fallback: String)
    }

    let id: String
    let label: String
    let symbol: String
    let group: Group
    let action: Action

    static let pairHint = "Pair Moonlight, then set host UUID and app id in Settings"

    static let registry: [Command] = [
        Command(id: "reconnect", label: "Reconnect", symbol: "arrow.clockwise", group: .power, action: .reconnect),
        Command(id: "wake", label: "Wake TV", symbol: "power", group: .power, action: .wake),
        Command(id: "sleep", label: "Sleep TV", symbol: "moon", group: .power, action: .sleep),
        Command(id: "volume_down", label: "Volume down", symbol: "speaker.minus", group: .volume, action: .key("VOLUME_DOWN")),
        Command(id: "mute", label: "Mute", symbol: "speaker.slash", group: .volume, action: .key("VOLUME_MUTE")),
        Command(id: "volume_up", label: "Volume up", symbol: "speaker.plus", group: .volume, action: .key("VOLUME_UP")),
        Command(id: "previous", label: "Previous", symbol: "backward.end", group: .media, action: .key("MEDIA_PREVIOUS")),
        Command(id: "play_pause", label: "Play or pause", symbol: "playpause", group: .media, action: .key("MEDIA_PLAY_PAUSE")),
        Command(id: "next", label: "Next", symbol: "forward.end", group: .media, action: .key("MEDIA_NEXT")),
        Command(id: "up", label: "Up", symbol: "chevron.up", group: .pad, action: .key("DPAD_UP")),
        Command(id: "left", label: "Left", symbol: "chevron.left", group: .pad, action: .key("DPAD_LEFT")),
        Command(id: "ok", label: "OK", symbol: "circle", group: .pad, action: .key("DPAD_CENTER")),
        Command(id: "right", label: "Right", symbol: "chevron.right", group: .pad, action: .key("DPAD_RIGHT")),
        Command(id: "down", label: "Down", symbol: "chevron.down", group: .pad, action: .key("DPAD_DOWN")),
        Command(id: "back", label: "Back", symbol: "chevron.backward", group: .nav, action: .key("BACK")),
        Command(id: "home", label: "Home", symbol: "house", group: .nav, action: .key("HOME")),
        Command(id: "inputs", label: "Inputs", symbol: "cable.connector", group: .nav, action: .key("TV_INPUT")),
        Command(id: "youtube", label: "YouTube", symbol: "play.rectangle", group: .apps, action: .launch(
            package: "com.google.android.youtube.tv",
            fallback: "com.google.android.youtube.tv/com.google.android.apps.youtube.tv.activity.ShellActivity")),
        Command(id: "netflix", label: "Netflix", symbol: "film", group: .apps, action: .launch(
            package: "com.netflix.ninja", fallback: "com.netflix.ninja/.MainActivity")),
        Command(id: "prime", label: "Prime Video", symbol: "play.tv", group: .apps, action: .launch(
            package: "com.amazon.amazonvideo.livingroom",
            fallback: "com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity")),
        Command(id: "hotstar", label: "Hotstar", symbol: "star", group: .apps, action: .launch(
            package: "in.startv.hotstar", fallback: "in.startv.hotstar/com.hotstar.MainActivity")),
        Command(id: "youtube_music", label: "YouTube Music", symbol: "music.note", group: .apps, action: .launch(
            package: "com.google.android.youtube.tvmusic",
            fallback: "com.google.android.youtube.tvmusic/com.google.android.apps.youtube.tvmusic.activity.MainActivity")),
        Command(id: "moonlight", label: "Moonlight", symbol: "gamecontroller", group: .apps, action: .launch(
            package: "com.limelight", fallback: "com.limelight/.PcView")),
        Command(id: "stream", label: "Mac on TV", symbol: "desktopcomputer", group: .stream, action: .stream),
        Command(id: "url", label: "Send URL", symbol: "link", group: .url, action: .url),
    ]

    static func named(_ id: String) -> Command? { registry.first { $0.id == id } }

    static func appName(_ package: String) -> String {
        if package == "com.gathin.tvhome" { return "Home" }
        return registry.first { if case .launch(package, _) = $0.action { true } else { false } }?.label ?? package
    }

    var requirement: Requirement {
        switch action {
        case .reconnect: .any
        case .wake: .notAwake
        case .launch: .awakeOrStandby
        case .sleep, .stream, .url, .key: .awake
        }
    }

    func unavailable(in connection: Connection, settings: Settings) -> String? {
        if action == .stream, settings.stream.parsed == nil { return Self.pairHint }
        switch (requirement, connection) {
        case (.any, _), (.awake, .awake), (.awakeOrStandby, .awake), (.awakeOrStandby, .standby),
             (.notAwake, .standby), (.notAwake, .unreachable), (.notAwake, .reachableUnknown):
            return nil
        case (.notAwake, .awake): return "TV is already awake"
        case (_, .unchecked): return "Checking the TV"
        case (_, .unreachable): return "Cannot reach the TV. Reconnect."
        case (_, .standby): return "TV is in standby. Wake it first."
        case (_, .reachableUnknown): return "TV state unclear. Reconnect."
        }
    }
}

enum Argument: Sendable, Equatable {
    case none
    case url(URL)
    case stream(uuid: UUID, appID: String)
}

struct CommandRequest: Sendable {
    let command: Command
    let argument: Argument

    init(id: String, urlText: String?, settings: Settings) throws(Refusal) {
        guard let command = Command.named(id) else { throw Refusal("Unknown command \(id)") }
        self.command = command
        switch command.action {
        case .url:
            guard let url = urlText.flatMap(webURL) else { throw Refusal("Copy a web link first.") }
            argument = .url(url)
        case .stream:
            guard let target = settings.stream.parsed else { throw Refusal(Command.pairHint) }
            argument = .stream(uuid: target.uuid, appID: target.appID)
        default:
            argument = .none
        }
    }
}

enum Expect: Sendable, Equatable {
    case awake, standby
    case foreground(String)

    func met(by connection: Connection) -> Bool {
        switch (self, connection) {
        case (.awake, .awake), (.standby, .standby): true
        case (.foreground(let package), .awake(let front)): package == front
        default: false
        }
    }

    var observed: String {
        switch self {
        case .awake: "TV is awake"
        case .standby: "TV is in standby"
        case .foreground(let package): "\(Command.appName(package)) is in front"
        }
    }

    var unconfirmed: String {
        switch self {
        case .awake: "Wake sent. The TV has not confirmed it is awake."
        case .standby: "Sleep sent. The TV has not confirmed standby."
        case .foreground(let package): "Launch sent. \(Command.appName(package)) is not in front yet."
        }
    }
}

enum Step: Sendable, Equatable {
    case connect(Duration)
    case wakeOnLAN
    case startSunshine
    case shell(String)
    case launch(package: String, fallback: String)
    case probe(until: Expect?)
}

func plan(_ request: CommandRequest, _ snapshot: Snapshot, settings: Settings) throws(Refusal) -> [Step] {
    if let reason = request.command.unavailable(in: snapshot.connection, settings: settings) { throw Refusal(reason) }
    switch (request.command.action, request.argument) {
    case (.reconnect, _):
        return [.connect(.seconds(8)), .probe(until: nil)]
    case (.wake, _):
        let wake: [Step] = [.shell(keyevent("WAKEUP")), .probe(until: .awake)]
        if case .unreachable = snapshot.connection { return [.wakeOnLAN, .connect(.seconds(20))] + wake }
        return wake
    case (.sleep, _):
        return [.shell(keyevent("SLEEP")), .probe(until: .standby)]
    case (.key(let code), _):
        return [.shell(keyevent(code)), .probe(until: nil)]
    // WAKEUP is a no-op when awake, but it ends a screensaver, which otherwise covers the launched activity.
    case (.launch(let package, let fallback), _):
        return [.shell(keyevent("WAKEUP")), .launch(package: package, fallback: fallback), .probe(until: .foreground(package))]
    case (.stream, .stream(let uuid, let appID)):
        return [
            .startSunshine,
            .shell(keyevent("WAKEUP")),
            .shell("am start -n com.limelight/.ShortcutTrampoline --es UUID \(quote(uuid.uuidString)) --es AppId \(quote(appID))"),
            .probe(until: .foreground("com.limelight")),
        ]
    case (.url, .url(let url)):
        return [.shell(keyevent("WAKEUP")), .shell("am start -a android.intent.action.VIEW -d \(quote(url.absoluteString))"), .probe(until: nil)]
    case (.stream, _), (.url, _):
        throw Refusal("\(request.command.label) is missing its argument")
    }
}

func keyevent(_ code: String) -> String { "input keyevent KEYCODE_\(code)" }

func quote(_ text: String) -> String { "'" + text.replacingOccurrences(of: "'", with: #"'\''"#) + "'" }

func webURL(_ text: String) -> URL? {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.count <= 2048, !trimmed.contains(where: \.isWhitespace),
          let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
          scheme == "http" || scheme == "https", url.host?.isEmpty == false else { return nil }
    return url
}

func resolvedComponent(_ output: String, package: String) -> String? {
    let line = output.split(whereSeparator: \.isNewline).last?.trimmingCharacters(in: .whitespaces) ?? ""
    return line.hasPrefix(package + "/") ? line : nil
}
