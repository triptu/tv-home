import CoreImage.CIFilterBuiltins
import ServiceManagement
import SwiftUI

struct RemoteWindow: View {
    let remote: Remote
    @FocusState private var keyboard: Bool
    @State private var phoneOpen = false

    private static let keys: [KeyEquivalent: String] = [
        .upArrow: "up", .downArrow: "down", .leftArrow: "left", .rightArrow: "right",
        .return: "ok", .escape: "back", "-": "volume_down", "=": "volume_up", .space: "play_pause",
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                Hero(remote: remote)
                pad
                transport
                apps
                actions
                ResultLine(operation: remote.operation)
                phone
            }
            .padding(20)
        }
        .scrollBounceBehavior(.basedOnSize)
        .frame(minWidth: 360, minHeight: 560)
        .containerBackground(.thickMaterial, for: .window)
        .preferredColorScheme(.dark)
        .focusable()
        .focusEffectDisabled()
        .focused($keyboard)
        .onKeyPress(keys: Set(Self.keys.keys)) { press in
            guard press.modifiers.isDisjoint(with: [.command, .control, .option]), let id = Self.keys[press.key] else { return .ignored }
            let command = Command.named(id)!
            if remote.blocker(command) == nil { Task { await remote.perform(id) } }
            return .handled
        }
        .onAppear { keyboard = true }
        .task { await remote.refresh() }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            Task { await remote.refresh() }
        }
    }

    private var pad: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(.white.opacity(0.05))
                Circle().strokeBorder(.white.opacity(0.08))
                chevron("up").offset(y: -70)
                chevron("down").offset(y: 70)
                chevron("left").offset(x: -70)
                chevron("right").offset(x: 70)
                control("ok") { command, running in
                    Busy(running: running) { Text(command.label).font(.headline) }
                        .frame(width: 84, height: 84)
                }
                .buttonStyle(Surface.Style(shape: Circle(), resting: 0.1))
            }
            .frame(width: 200, height: 200)
            HStack(spacing: 8) {
                ForEach(commands(.nav)) { command in
                    control(command.id) { command, running in
                        Label { Text(command.label) } icon: { Busy(running: running) { Image(systemName: command.symbol) } }
                            .font(.callout)
                            .padding(.horizontal, 16)
                            .frame(height: 32)
                    }
                    .buttonStyle(Surface.Style(shape: Capsule()))
                }
            }
        }
    }

    private func chevron(_ id: String) -> some View {
        control(id) { command, running in
            Busy(running: running) { Image(systemName: command.symbol).font(.title2.weight(.semibold)) }
                .frame(width: 52, height: 52)
        }
        .buttonStyle(Surface.Style(shape: Circle(), resting: 0))
    }

    private var transport: some View {
        VStack(spacing: 8) {
            ForEach([Group.volume, .media], id: \.self) { group in
                HStack(spacing: 8) {
                    ForEach(commands(group)) { command in
                        control(command.id) { command, running in
                            Busy(running: running) { Image(systemName: command.symbol).font(.title3) }
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(Surface.Style())
                    }
                }
            }
        }
    }

    private var apps: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
            ForEach(commands(.apps)) { command in
                control(command.id) { command, running in
                    VStack(spacing: 8) {
                        Busy(running: running) { Image(systemName: command.symbol).font(.title2) }
                            .frame(height: 28)
                        Text(command.label).font(.caption).lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, minHeight: 76)
                }
                .buttonStyle(Surface.Style(lifts: true))
            }
        }
    }

    private var actions: some View {
        let stream = Command.named("stream")!
        return VStack(spacing: 8) {
            control("stream") { command, running in
                Label { Text(command.label) } icon: { Busy(running: running) { Image(systemName: command.symbol) } }
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            if let hint = stream.unavailable(in: remote.snapshot.connection, settings: remote.settings) {
                Text(hint).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            control("url", perform: remote.sendClipboardURL) { _, running in
                Label { Text("Send clipboard URL") } icon: { Busy(running: running) { Image(systemName: "link") } }
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
        }
        .controlSize(.large)
    }

    private var phone: some View {
        DisclosureGroup(isExpanded: $phoneOpen) {
            if let url = remote.phone?.url {
                VStack(spacing: 12) {
                    if let code = qrCode(url.absoluteString) {
                        Image(nsImage: code)
                            .interpolation(.none)
                            .resizable()
                            .frame(width: 160, height: 160)
                            .padding(8)
                            .background(.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .accessibilityLabel("QR code for the phone remote link")
                    }
                    Text("Scan with your phone on the same Wi-Fi.").font(.callout).foregroundStyle(.secondary)
                    HStack(spacing: 8) {
                        Text(url.absoluteString)
                            .font(.caption.monospaced())
                            .lineLimit(1)
                            .truncationMode(.middle)
                            .textSelection(.enabled)
                        Button("Copy link", systemImage: "doc.on.doc") {
                            NSPasteboard.general.clearContents()
                            NSPasteboard.general.setString(url.absoluteString, forType: .string)
                        }
                        .labelStyle(.iconOnly)
                        .help("Copy link")
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 8)
            } else {
                Text("Turn it on to control the TV from a phone browser.")
                    .font(.callout).foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 8)
            }
        } label: {
            HStack {
                Text("Phone remote").font(.headline)
                Spacer()
                Toggle("Phone remote", isOn: Binding(get: { remote.phone != nil }, set: { on in
                    if on { phoneOpen = true }
                    Task { await remote.setPhone(on) }
                }))
                .toggleStyle(.switch)
                .labelsHidden()
            }
        }
    }

    private func commands(_ group: Group) -> [Command] { Command.registry.filter { $0.group == group } }

    private func control<Content: View>(
        _ id: String, perform: (() async -> Void)? = nil, @ViewBuilder label: (Command, Bool) -> Content
    ) -> some View {
        let command = Command.named(id)!
        let blocker = remote.blocker(command)
        return Button {
            Task {
                if let perform { await perform() } else { await remote.perform(id) }
            }
        } label: {
            label(command, remote.isRunning(command))
        }
        .disabled(blocker != nil)
        .help(blocker ?? command.label)
        .accessibilityLabel(command.label)
    }
}

private struct Hero: View {
    let remote: Remote
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let connection = remote.snapshot.connection
        HStack(alignment: .top, spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(remote.settings.device.name).font(.title.weight(.semibold))
                Text(connection.headline).font(.title3).foregroundStyle(.secondary)
                if let advice = connection.advice {
                    Text(advice).font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Text(connection == .unchecked ? " " : "Checked \(remote.snapshot.at.formatted(date: .omitted, time: .shortened))")
                    .font(.caption).foregroundStyle(.tertiary)
                    .padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            HStack(spacing: 8) {
                round("reconnect")
                round(connection.isAwake ? "sleep" : "wake")
            }
        }
        .padding(20)
        .background {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(LinearGradient(colors: [tint.opacity(0.45), tint.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing))
                .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(.white.opacity(0.08)))
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.4), value: connection)
    }

    private var tint: Color {
        switch remote.snapshot.connection {
        case .awake: .accentColor
        case .standby, .unchecked: .gray
        case .unreachable, .reachableUnknown: .orange
        }
    }

    private func round(_ id: String) -> some View {
        let command = Command.named(id)!
        let blocker = remote.blocker(command)
        return Button { Task { await remote.perform(id) } } label: {
            Busy(running: remote.isRunning(command)) { Image(systemName: command.symbol).font(.body.weight(.semibold)) }
                .frame(width: 40, height: 40)
        }
        .buttonStyle(Surface.Style(shape: Circle(), resting: 0.12))
        .disabled(blocker != nil)
        .help(blocker ?? command.label)
        .accessibilityLabel(command.label)
    }
}

private struct ResultLine: View {
    let operation: Operation

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            switch operation {
            case .idle:
                EmptyView()
            case .running(let id):
                Image(systemName: "ellipsis").foregroundStyle(.secondary)
                Text("\(Command.named(id)?.label ?? id)…").foregroundStyle(.secondary)
            case .finished(let receipt):
                Image(systemName: Self.symbols[receipt.kind]!.name).foregroundStyle(Self.symbols[receipt.kind]!.color)
                    .accessibilityLabel(receipt.kind.rawValue.capitalized)
                Text(receipt.message).lineLimit(2)
            }
        }
        .font(.callout)
        .frame(maxWidth: .infinity, minHeight: 32, alignment: .topLeading)
    }

    private static let symbols: [Receipt.Kind: (name: String, color: Color)] = [
        .sent: ("paperplane", .secondary),
        .observed: ("checkmark.circle", .green),
        .failed: ("exclamationmark.triangle", .red),
        .unknown: ("questionmark.circle", .yellow),
    ]
}

/// Swaps a control's symbol for a spinner without changing its size.
private struct Busy<Content: View>: View {
    let running: Bool
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            content.opacity(running ? 0 : 1)
            if running { ProgressView().controlSize(.small) }
        }
    }
}

private struct Surface: View {
    struct Style: ButtonStyle {
        var shape = AnyShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        var resting = 0.07
        var lifts = false

        init<S: Shape>(shape: S, resting: Double = 0.07) {
            self.shape = AnyShape(shape)
            self.resting = resting
        }

        init(lifts: Bool = false) { self.lifts = lifts }

        func makeBody(configuration: Configuration) -> some View {
            Surface(configuration: configuration, shape: shape, resting: resting, lifts: lifts)
        }
    }

    let configuration: ButtonStyleConfiguration
    let shape: AnyShape
    let resting: Double
    let lifts: Bool
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var hovering = false

    var body: some View {
        let lifted = lifts && hovering && enabled && !reduceMotion
        configuration.label
            .foregroundStyle(enabled ? .primary : .tertiary)
            .background(shape.fill(.white.opacity(fill)))
            .contentShape(shape)
            .offset(y: lifted ? -2 : 0)
            .shadow(color: .black.opacity(lifted ? 0.3 : 0), radius: 8, y: 4)
            .onHover { hovering = $0 }
            .animation(reduceMotion ? nil : .snappy(duration: 0.18), value: hovering)
            .animation(reduceMotion ? nil : .snappy(duration: 0.1), value: configuration.isPressed)
    }

    private var fill: Double {
        guard enabled else { return resting * 0.5 }
        if configuration.isPressed { return resting + 0.12 }
        return hovering ? resting + 0.06 : resting
    }
}

struct SettingsView: View {
    let remote: Remote
    @State private var draft = Settings()
    @State private var error: String?
    @State private var openAtLogin = SMAppService.mainApp.status == .enabled
    @State private var loginNote: String?

    var body: some View {
        Form {
            Section {
                Toggle("Open at login", isOn: $openAtLogin)
                if let loginNote { Text(loginNote).font(.callout).foregroundStyle(.secondary) }
            }
            Section("TV") {
                TextField("Name", text: $draft.device.name)
                TextField("Host", text: $draft.device.host)
                TextField("Port", value: $draft.device.port, format: .number.grouping(.never))
                TextField("MAC address", text: $draft.device.mac)
                TextField("adb path", text: $draft.adbPath)
            }
            Section("Mac on TV") {
                TextField("Sunshine app", text: $draft.stream.app, prompt: Text("Desktop"))
                TextField("Host UUID", text: $draft.stream.hostUUID, prompt: Text("Read from Sunshine on this Mac"))
            }
            if let error { Text(error).foregroundStyle(.red) }
            Button("Save") {
                do { try remote.save(draft); error = nil } catch { self.error = error.localizedDescription }
            }
            .disabled(draft == remote.settings)
        }
        .formStyle(.grouped)
        .frame(width: 420)
        .onAppear { draft = remote.settings }
        .onChange(of: openAtLogin) { _, on in
            do {
                try on ? SMAppService.mainApp.register() : SMAppService.mainApp.unregister()
                loginNote = SMAppService.mainApp.status == .requiresApproval
                    ? "Allow TV Remote in System Settings, General, Login Items." : nil
            } catch {
                loginNote = error.localizedDescription
            }
        }
    }
}

private func qrCode(_ text: String) -> NSImage? {
    let filter = CIFilter.qrCodeGenerator()
    filter.message = Data(text.utf8)
    guard let image = filter.outputImage, let cgImage = CIContext().createCGImage(image, from: image.extent) else { return nil }
    return NSImage(cgImage: cgImage, size: image.extent.size)
}
