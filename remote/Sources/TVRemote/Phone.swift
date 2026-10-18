import Foundation
import Network

struct HTTPRequest: Sendable {
    enum Parse { case incomplete, invalid(Int), complete(HTTPRequest) }

    let method: String
    let path: String
    let headers: [String: String]
    let body: Data

    static func parse(_ data: Data) -> Parse {
        guard let end = data.firstRange(of: Data("\r\n\r\n".utf8)) else { return data.count > 8192 ? .invalid(431) : .incomplete }
        let lines = String(decoding: data[..<end.lowerBound], as: UTF8.self).components(separatedBy: "\r\n")
        let start = lines[0].split(separator: " ")
        guard start.count == 3 else { return .invalid(400) }
        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { return .invalid(400) }
            headers[line[..<colon].lowercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        guard let length = Int(headers["content-length"] ?? "0"), (0...4096).contains(length) else { return .invalid(413) }
        let body = data[end.upperBound...]
        guard body.count >= length else { return .incomplete }
        return .complete(HTTPRequest(method: String(start[0]), path: String(start[1]), headers: headers, body: Data(body.prefix(length))))
    }
}

@MainActor final class Phone {
    private struct Response {
        var status: Int
        var type = "application/json"
        var body = Data()
    }

    private struct State: Encodable {
        struct Row: Encodable { let id, label, group: String; let reason: String? }
        let device, status: String
        let receipt: Receipt?
        let commands: [Row]
    }

    let url: URL
    private let host: String
    private let token: String
    private let page: Data
    private let listener: NWListener
    private weak var remote: Remote?
    private let onStop: @MainActor (String) -> Void
    private var starting: CheckedContinuation<Void, Error>?
    private var stopped = false
    private var connections: [ObjectIdentifier: NWConnection] = [:]
    private var receiving: Set<ObjectIdentifier> = []
    private var responding: Set<ObjectIdentifier> = []

    init(remote: Remote, port: UInt16 = 8765, onStop: @escaping @MainActor (String) -> Void) async throws {
        guard let address = lanAddress(near: remote.settings.device.host) else { throw Refusal("Not on the TV's network") }
        guard let pageURL = Bundle.module.url(forResource: "phone", withExtension: "html") else { throw Refusal("phone.html is missing") }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else { throw Refusal("No random bytes") }
        token = bytes.map { String(format: "%02x", $0) }.joined()
        host = "\(address):\(port)"
        url = URL(string: "http://\(host)/#\(token)")!
        page = try Data(contentsOf: pageURL)
        self.remote = remote
        self.onStop = onStop
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: NWEndpoint.Host(address), port: NWEndpoint.Port(rawValue: port)!)
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in MainActor.assumeIsolated { self?.accept(connection) } }
        listener.stateUpdateHandler = { [weak self] state in MainActor.assumeIsolated { self?.changed(state, port: port) } }
        try await withCheckedThrowingContinuation { continuation in
            starting = continuation
            listener.start(queue: .main)
        }
    }

    func stop() {
        stopped = true
        listener.cancel()
        connections.values.forEach { $0.cancel() }
        (connections, receiving, responding) = ([:], [], [])
    }

    private func changed(_ state: NWListener.State, port: UInt16) {
        switch state {
        case .ready:
            starting?.resume()
        case .failed(let error), .waiting(let error):
            guard let starting else {
                if !stopped { stop(); onStop(error.localizedDescription) }
                return
            }
            listener.cancel()
            starting.resume(throwing: Refusal("Port \(port) is unavailable: \(error.localizedDescription)"))
        default:
            return
        }
        starting = nil
    }

    private func accept(_ connection: NWConnection) {
        guard !stopped else { return connection.cancel() }
        let id = ObjectIdentifier(connection)
        connections[id] = connection
        receiving.insert(id)
        connection.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 5) { [weak self] in
            MainActor.assumeIsolated { if self?.receiving.remove(id) != nil { self?.close(connection) } }
        }
        receive(connection, Data())
    }

    private func close(_ connection: NWConnection) {
        let id = ObjectIdentifier(connection)
        connections[id] = nil
        receiving.remove(id)
        responding.remove(id)
        connection.cancel()
    }

    private func receive(_ connection: NWConnection, _ buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 8192) { [weak self] data, _, done, error in
            MainActor.assumeIsolated {
                guard let self else { return connection.cancel() }
                let id = ObjectIdentifier(connection)
                let buffer = buffer + (data ?? Data())
                switch HTTPRequest.parse(buffer) {
                case .incomplete where done || error != nil: self.close(connection)
                case .incomplete: self.receive(connection, buffer)
                case .invalid(let status):
                    guard self.receiving.remove(id) != nil else { return }
                    self.send(connection, Response(status: status))
                case .complete(let request):
                    guard self.receiving.remove(id) != nil else { return }
                    self.responding.insert(id)
                    // Every step has its own deadline, so this only guards a respond that never returns.
                    DispatchQueue.main.asyncAfter(deadline: .now() + 120) { [weak self] in
                        MainActor.assumeIsolated { if self?.responding.contains(id) == true { self?.close(connection) } }
                    }
                    Task {
                        let response = await self.respond(request)
                        guard self.responding.remove(id) != nil else { return }
                        self.send(connection, response)
                    }
                }
            }
        }
    }

    private func respond(_ request: HTTPRequest) async -> Response {
        guard !stopped else { return Response(status: 401) }
        guard request.headers["host"] == host else { return Response(status: 421) }
        if let origin = request.headers["origin"], origin != "http://\(host)" { return Response(status: 403) }
        if request.path == "/" {
            return request.method == "GET" ? Response(status: 200, type: "text/html; charset=utf-8", body: page) : Response(status: 405)
        }
        guard ["/api/state", "/api/refresh", "/api/command"].contains(request.path) else { return Response(status: 404) }
        guard request.headers["authorization"] == "Bearer \(token)", let remote else { return Response(status: 401) }
        switch (request.method, request.path) {
        case ("GET", "/api/state"):
            return state(remote, nil)
        case ("POST", "/api/refresh"):
            return state(remote, await remote.perform("reconnect"))
        case ("POST", "/api/command"):
            guard let fields = try? JSONSerialization.jsonObject(with: request.body) as? [String: String],
                  let id = fields["id"], Set(fields.keys).isSubset(of: ["id", "url"]) else { return Response(status: 400) }
            return state(remote, await remote.perform(id, url: fields["url"]))
        default:
            return Response(status: 405)
        }
    }

    private func state(_ remote: Remote, _ receipt: Receipt?) -> Response {
        let rows = Command.registry.map {
            State.Row(id: $0.id, label: $0.label, group: $0.group.rawValue,
                      reason: $0.unavailable(in: remote.snapshot.connection, settings: remote.settings))
        }
        let state = State(device: remote.settings.device.name, status: remote.snapshot.connection.summary, receipt: receipt, commands: rows)
        return Response(status: 200, body: (try? JSONEncoder().encode(state)) ?? Data())
    }

    private func send(_ connection: NWConnection, _ response: Response) {
        let reasons = [200: "OK", 400: "Bad Request", 401: "Unauthorized", 403: "Forbidden", 404: "Not Found",
                       405: "Method Not Allowed", 413: "Content Too Large", 421: "Misdirected Request", 431: "Header Too Large"]
        var head = "HTTP/1.1 \(response.status) \(reasons[response.status] ?? "")\r\nContent-Type: \(response.type)\r\n"
        head += "Content-Length: \(response.body.count)\r\nConnection: close\r\nCache-Control: no-store\r\n"
        head += "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n"
        head += "Content-Security-Policy: default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'\r\n\r\n"
        connection.send(content: Data(head.utf8) + response.body, completion: .contentProcessed { [weak self] _ in
            MainActor.assumeIsolated { if let self { self.close(connection) } else { connection.cancel() } }
        })
    }
}

func lanAddress(near tv: String) -> String? {
    var list: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&list) == 0, let first = list else { return nil }
    defer { freeifaddrs(list) }
    let addresses = sequence(first: first) { $0.pointee.ifa_next }.compactMap { entry -> String? in
        guard let address = entry.pointee.ifa_addr, address.pointee.sa_family == AF_INET,
              String(cString: entry.pointee.ifa_name).hasPrefix("en") else { return nil }
        return address.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { String(cString: inet_ntoa($0.pointee.sin_addr)) }
    }
    let subnet = tv.split(separator: ".").dropLast().joined(separator: ".") + "."
    return addresses.first { $0.hasPrefix(subnet) }
}
