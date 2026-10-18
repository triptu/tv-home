import ServiceManagement
import SwiftUI

struct RemoteView: View {
    let remote: Remote
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            status
            row(.volume)
            row(.media)
            Grid(horizontalSpacing: 6, verticalSpacing: 6) {
                GridRow { Color.clear.gridCellUnsizedAxes([.horizontal, .vertical]); button("up", icon: true); Color.clear.gridCellUnsizedAxes([.horizontal, .vertical]) }
                GridRow { button("left", icon: true); button("ok", icon: true); button("right", icon: true) }
                GridRow { Color.clear.gridCellUnsizedAxes([.horizontal, .vertical]); button("down", icon: true); Color.clear.gridCellUnsizedAxes([.horizontal, .vertical]) }
            }
            .frame(width: 180)
            .frame(maxWidth: .infinity)
            row(.nav)
            LazyVGrid(columns: [GridItem(), GridItem()], spacing: 6) {
                ForEach(commands(.apps)) { button($0) }
            }
            button("stream").buttonStyle(.borderedProminent)
            let url = Command.named("url")!
            Button { Task { await remote.sendClipboardURL() } } label: {
                Label("Send clipboard URL", systemImage: url.symbol).frame(maxWidth: .infinity)
            }
            .disabled(reason(url) != nil)
            .help(reason(url) ?? "Open the copied web link on the TV")
            result
            Divider()
            footer
        }
        .controlSize(.large)
        .padding(12)
        .frame(width: 320)
        .task { await remote.perform("reconnect") }
    }

    private var status: some View {
        HStack {
            VStack(alignment: .leading) {
                Text(remote.settings.device.name).font(.headline)
                Text(remote.snapshot.connection.summary).font(.subheadline).lineLimit(2)
                Text("Checked \(remote.snapshot.at.formatted(date: .omitted, time: .shortened))")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Spacer()
            SwiftUI.Group {
                button("reconnect", icon: true)
                if case .awake = remote.snapshot.connection { button("sleep", icon: true) } else { button("wake", icon: true) }
            }
            .fixedSize()
        }
    }

    @ViewBuilder private var result: some View {
        switch remote.operation {
        case .idle: EmptyView()
        case .running: ProgressView().controlSize(.small)
        case .finished(let receipt):
            Text(receipt.message)
                .font(.callout)
                .foregroundStyle(receipt.kind == .failed || receipt.kind == .unknown ? .red : .secondary)
        }
    }

    private var footer: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle("Open at login", isOn: Binding(
                get: { SMAppService.mainApp.status == .enabled },
                set: { try? $0 ? SMAppService.mainApp.register() : SMAppService.mainApp.unregister() }))
            HStack {
                Button("Settings…") {
                    NSApp.activate()
                    openSettings()
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
        }
        .controlSize(.regular)
    }

    private func commands(_ group: Group) -> [Command] { Command.registry.filter { $0.group == group } }

    private func reason(_ command: Command) -> String? {
        command.unavailable(in: remote.snapshot.connection, settings: remote.settings)
    }

    private func row(_ group: Group) -> some View {
        HStack(spacing: 6) { ForEach(commands(group)) { button($0, icon: true) } }
    }

    private func button(_ id: String, icon: Bool = false) -> some View { button(Command.named(id)!, icon: icon) }

    private func button(_ command: Command, icon: Bool = false) -> some View {
        Button { Task { await remote.perform(command.id) } } label: {
            SwiftUI.Group {
                if icon { Image(systemName: command.symbol) } else { Label(command.label, systemImage: command.symbol) }
            }
            .frame(maxWidth: .infinity)
        }
        .disabled(reason(command) != nil)
        .help(reason(command) ?? command.label)
        .accessibilityLabel(command.label)
    }
}

struct SettingsView: View {
    let remote: Remote
    @State private var draft = Settings()
    @State private var error: String?

    var body: some View {
        Form {
            Section("TV") {
                TextField("Name", text: $draft.device.name)
                TextField("Host", text: $draft.device.host)
                TextField("Port", value: $draft.device.port, format: .number.grouping(.never))
                TextField("MAC address", text: $draft.device.mac)
                TextField("adb path", text: $draft.adbPath)
            }
            Section("Mac on TV") {
                TextField("Moonlight host UUID", text: $draft.stream.hostUUID)
                TextField("Sunshine app id", text: $draft.stream.appID)
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
    }
}
