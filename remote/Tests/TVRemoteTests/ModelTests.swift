import Foundation
import Testing
@testable import TVRemote

private let awake = Snapshot(.awake(foreground: "com.gathin.tvhome"))
private let paired = Settings(stream: StreamTarget(hostUUID: "6F0B7C2E-1D3A-4C5B-9E8F-0A1B2C3D4E5F", appID: "881448767"))

private func steps(_ id: String, _ snapshot: Snapshot, url: String? = nil, settings: Settings = Settings()) throws -> [Step] {
    try plan(CommandRequest(id: id, urlText: url, settings: settings), snapshot, settings: settings)
}

@Test func registryIDsAreUnique() {
    let ids = Command.registry.map(\.id)
    #expect(Set(ids).count == ids.count)
}

@Test func availabilityFollowsConnection() {
    let settings = Settings()
    func available(_ id: String, _ connection: Connection) -> Bool {
        Command.named(id)!.unavailable(in: connection, settings: settings) == nil
    }
    #expect(available("reconnect", .unreachable("x")))
    #expect(available("wake", .standby) && available("wake", .unreachable("x")) && !available("wake", awake.connection))
    #expect(available("sleep", awake.connection) && !available("sleep", .standby))
    #expect(available("volume_up", awake.connection) && !available("volume_up", .standby))
    #expect(available("netflix", .standby) && !available("netflix", .unreachable("x")))
    #expect(!available("stream", awake.connection))
    #expect(Command.named("stream")!.unavailable(in: awake.connection, settings: paired) == nil)
    #expect(Command.named("stream")!.unavailable(in: .standby, settings: paired) == nil)
}

@Test func parsesProbeOutput() {
    let awakeOutput = """
      mWakefulness=Dreaming
          topResumedActivity=ActivityRecord{13652b1 u0 com.netflix.ninja/.MainActivity t6451}
    """
    #expect(Connection.parse(awakeOutput) == .awake(foreground: "com.netflix.ninja"))
    #expect(Connection.parse("  mWakefulness=Asleep\n") == .standby)
    #expect(Connection.parse("  mWakefulness=Awake\n") == .awake(foreground: nil))
    #expect(Connection.parse("garbage") == .reachableUnknown("TV gave no power state"))
}

@Test func plansFollowState() throws {
    #expect(try steps("volume_up", awake) == [.shell("input keyevent KEYCODE_VOLUME_UP")])
    #expect(try steps("netflix", Snapshot(.standby)) == [
        .shell("input keyevent KEYCODE_WAKEUP"),
        .launch(package: "com.netflix.ninja", fallback: "com.netflix.ninja/.MainActivity"),
        .probe(until: .foreground("com.netflix.ninja")),
    ])
    #expect(try steps("wake", Snapshot(.unreachable("x"))) == [
        .wakeOnLAN, .connect(.seconds(20)), .shell("input keyevent KEYCODE_WAKEUP"), .probe(until: .awake),
    ])
    #expect(try steps("sleep", awake) == [.shell("input keyevent KEYCODE_SLEEP"), .probe(until: .standby)])
    #expect(try steps("stream", awake, settings: paired) == [
        .startSunshine,
        .shell("input keyevent KEYCODE_WAKEUP"),
        .shell("am start -n com.limelight/.ShortcutTrampoline --es UUID '6F0B7C2E-1D3A-4C5B-9E8F-0A1B2C3D4E5F' --es AppId '881448767'"),
        .probe(until: .foreground("com.limelight")),
    ])
    #expect(throws: Refusal.self) { try steps("volume_up", Snapshot(.standby)) }
}

@Test func urlsAreValidatedAndQuoted() throws {
    #expect(try steps("url", awake, url: " https://youtu.be/x?a=1&b='2' ") == [
        .shell("input keyevent KEYCODE_WAKEUP"),
        .shell(#"am start -a android.intent.action.VIEW -d 'https://youtu.be/x?a=1&b='\''2'\'''"#), .probe(until: nil),
    ])
    for bad in ["javascript:alert(1)", "file:///etc/passwd", "https://a.com https://b.com", "not a url", ""] {
        #expect(webURL(bad) == nil, "\(bad)")
    }
}

@Test func shellQuotingSurvivesQuotes() {
    #expect(quote("it's") == #"'it'\''s'"#)
    #expect(quote("$(reboot)") == "'$(reboot)'")
}

@Test func resolvesOnlyComponentsInPackage() {
    let output = "priority=0 preferredOrder=0 match=0x108000\ncom.netflix.ninja/.MainActivity\n"
    #expect(resolvedComponent(output, package: "com.netflix.ninja") == "com.netflix.ninja/.MainActivity")
    #expect(resolvedComponent(output, package: "com.limelight") == nil)
    #expect(resolvedComponent("No activity found", package: "com.netflix.ninja") == nil)
}

@Test func settingsWithMissingKeysKeepDefaults() throws {
    let decoded = try JSONDecoder().decode(Settings.self, from: Data(#"{"device":{"name":"Den"},"stream":{"appID":"7"}}"#.utf8))
    #expect(decoded.device == Device(name: "Den"))
    #expect(decoded.stream == StreamTarget(appID: "7"))
    #expect(decoded.adbPath == Settings().adbPath)
}

private enum Parsed: Equatable {
    case incomplete, invalid(Int), complete(method: String, path: String, host: String?, body: String)
}

private func parsed(_ chunks: [String]) -> Parsed {
    var buffer = Data()
    var result = Parsed.incomplete
    for chunk in chunks {
        buffer += Data(chunk.utf8)
        result = switch HTTPRequest.parse(buffer) {
        case .incomplete: .incomplete
        case .invalid(let status): .invalid(status)
        case .complete(let request):
            .complete(method: request.method, path: request.path, host: request.headers["host"], body: String(decoding: request.body, as: UTF8.self))
        }
    }
    return result
}

@Test func parsesHTTPRequests() {
    let post = "POST /api/command HTTP/1.1\r\nHost: m:8765\r\nContent-Length: 11\r\n\r\n"
    let cases: [([String], Parsed)] = [
        (["GET / HTTP/1.1\r\nHost: m:8765\r\n\r\n"], .complete(method: "GET", path: "/", host: "m:8765", body: "")),
        ([post + #"{"id":"#], .incomplete),
        ([post + #"{"id":"#, #""ok"}"#], .complete(method: "POST", path: "/api/command", host: "m:8765", body: #"{"id":"ok"}"#)),
        (["GET / HTTP/1.1\r\nAccept: */*\r\n\r\n"], .complete(method: "GET", path: "/", host: nil, body: "")),
        (["GET / HTTP/1.1\r\nX: " + String(repeating: "a", count: 9000)], .invalid(431)),
        (["POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"], .invalid(413)),
        (["POST / HTTP/1.1\r\nContent-Length: 5000\r\n\r\n"], .invalid(413)),
    ]
    for (chunks, expected) in cases {
        #expect(parsed(chunks) == expected, "\(chunks)")
    }
}
