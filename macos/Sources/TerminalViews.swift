import SwiftUI

enum TerminalColors {
    static let accent = dynamic(0x8DE9AD, 0x17613B)
    static let background = dynamic(0x0D1611, 0xF0F5EB)
    static let panel = dynamic(0x15251C, 0xE1EDDF)
    static let line = dynamic(0x2E4A3A, 0xA7BFA9)
    static let primary = dynamic(0xE7F3E9, 0x173125)
    static let muted = dynamic(0x9EB9A7, 0x456451)
    static let onAccent = dynamic(0x0D1611, 0xF0F5EB)

    private static func dynamic(_ dark: Int, _ light: Int) -> Color {
        Color(nsColor: NSColor(name: nil) { appearance in
            let value = appearance.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light
            return NSColor(srgbRed: CGFloat((value >> 16) & 255) / 255,
                           green: CGFloat((value >> 8) & 255) / 255,
                           blue: CGFloat(value & 255) / 255, alpha: 1)
        })
    }
}

struct TerminalSpectrum: View {
    let levels: [Float]

    var body: some View {
        HStack(alignment: .bottom, spacing: 3) {
            ForEach(levels.indices, id: \.self) { band in
                let height = Int((levels[band] * 8).rounded(.up))
                VStack(spacing: 3) {
                    ForEach((0..<8).reversed(), id: \.self) { row in
                        RoundedRectangle(cornerRadius: 1)
                            .fill(row < height ? TerminalColors.accent.opacity(0.54 + 0.06 * Double(row)) : TerminalColors.line)
                            .frame(width: 6, height: 6)
                    }
                }
            }
        }
        .padding(9)
        .background(TerminalColors.background, in: RoundedRectangle(cornerRadius: 6))
        .accessibilityHidden(true)
    }
}

@MainActor struct TrackerBrowser: View {
    @ObservedObject var player: Playback
    @State private var settings: TrackerMode?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Трекер").font(.system(.title2, design: .monospaced).weight(.semibold))
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    group("Атмосферы", [.tea, .workshop, .weather])
                    group("Город", [.city, .moscow])
                    group("Космос", [.voyager, .stars, .solar, .orbits])
                }.padding(.trailing, 5)
            }
        }.sheet(item: $settings) { TrackerSettingsView(mode: $0, player: player) }
    }

    private func group(_ title: String, _ modes: [TrackerMode]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.system(.subheadline, design: .monospaced).weight(.semibold))
                .foregroundColor(TerminalColors.accent)
            ForEach(modes) { mode in
                HStack(spacing: 8) {
                    Button { player.playTracker(mode) } label: {
                        HStack(spacing: 11) {
                            Image(systemName: player.trackerMode == mode && !player.paused ? "speaker.wave.2.fill" : "waveform")
                                .foregroundColor(TerminalColors.accent).frame(width: 22)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(mode.title).font(.system(.body, design: .monospaced).weight(.semibold))
                                Text(mode.detail).font(.caption).foregroundColor(TerminalColors.muted)
                            }
                            Spacer()
                            Image(systemName: "play.fill").font(.caption).foregroundColor(TerminalColors.accent)
                        }.padding(9).contentShape(Rectangle())
                    }.buttonStyle(.plain).accessibilityLabel("Играть \(mode.title)")
                    Button { settings = mode } label: {
                        Image(systemName: "gearshape").frame(width: 27, height: 27)
                    }.buttonStyle(.plain).padding(.trailing, 9)
                        .accessibilityLabel("Настройки · \(mode.title)").help("Настройки · \(mode.title)")
                }.background(TerminalColors.panel, in: RoundedRectangle(cornerRadius: 7))
                    .overlay(RoundedRectangle(cornerRadius: 7)
                        .stroke(player.trackerMode == mode ? TerminalColors.accent : TerminalColors.line)
                        .allowsHitTesting(false))
            }
        }
    }
}

@MainActor struct TrackerSettingsView: View {
    let mode: TrackerMode
    @ObservedObject var player: Playback
    @Environment(\.dismiss) private var dismiss
    @State private var values = [TrackerOption: Bool]()

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack {
                Text(mode.title).font(.system(.title2, design: .monospaced).weight(.semibold))
                Spacer()
                Button("Готово") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            Text(mode.detail).foregroundColor(TerminalColors.muted)
            Divider()
            ForEach(mode.options) { option in
                Toggle(option == .clock && mode == .city ? "Менять темп по времени суток" : option.label,
                       isOn: Binding(get: { values[option] ?? option.enabled(mode) }, set: { enabled in
                           values[option] = enabled
                           option.set(mode, enabled)
                           player.trackerOptionsChanged(mode)
                       }))
            }
            if mode.options.contains(.refresh) {
                Text("При отключении обновлений используется последний сохранённый срез.")
                    .font(.caption).foregroundColor(TerminalColors.muted)
            }
            if mode == .moscow {
                Text("Микрофон включается только во время воспроизведения «Москвы».")
                    .font(.caption).foregroundColor(TerminalColors.muted)
            }
        }.padding(24).frame(width: 480)
            .background(TerminalColors.background).tint(TerminalColors.accent)
    }
}

@MainActor struct RadioBrowser: View {
    @ObservedObject var player: Playback
    @AppStorage("personal_radio") private var personal = false
    @State private var askingCode = false
    @State private var code = ""
    @State private var invalid = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Радио").font(.system(.title2, design: .monospaced).weight(.semibold))
                Spacer()
                if !RadioStation.personal.isEmpty {
                    Button(personal ? "Закрыть личные станции" : "Личный режим") {
                        if personal {
                            if let station = player.station, RadioStation.personal.contains(station) { player.stop() }
                            personal = false
                        } else {
                            code = ""
                            invalid = false
                            askingCode = true
                        }
                    }
                }
            }
            ScrollView {
                VStack(spacing: 6) {
                    ForEach(RadioStation.publicStations + (personal ? RadioStation.personal : [])) { station in
                        Button { player.playRadio(station) } label: {
                            HStack {
                                Image(systemName: player.station == station && !player.paused ? "speaker.wave.2.fill" : "radio")
                                    .foregroundColor(TerminalColors.accent)
                                Text(station.title).font(.system(.body, design: .monospaced))
                                Spacer()
                                Image(systemName: "play.fill").font(.caption)
                            }.padding(11).contentShape(Rectangle())
                        }.buttonStyle(.plain).background(TerminalColors.panel, in: RoundedRectangle(cornerRadius: 7))
                            .overlay(RoundedRectangle(cornerRadius: 7)
                                .stroke(player.station == station ? TerminalColors.accent : TerminalColors.line)
                                .allowsHitTesting(false))
                    }
                }
            }
        }.sheet(isPresented: $askingCode) {
            VStack(alignment: .leading, spacing: 14) {
                Text("Личный режим").font(.headline)
                SecureField("Код", text: $code)
                if invalid { Text("Неверный код").foregroundColor(.red) }
                HStack {
                    Button("Отмена") { askingCode = false }.keyboardShortcut(.cancelAction)
                    Spacer()
                    Button("Включить") {
                        guard RadioStation.accepts(code) else { invalid = true; return }
                        personal = true
                        askingCode = false
                    }.keyboardShortcut(.defaultAction).disabled(code.isEmpty)
                }
            }.padding(22).frame(width: 340)
        }
    }
}

@MainActor struct SleepTimerView: View {
    @ObservedObject var player: Playback
    @Environment(\.dismiss) private var dismiss
    @State private var minutes = 15

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Таймер сна").font(.system(.title2, design: .monospaced).weight(.semibold))
            Text("Остановить воспроизведение через").foregroundColor(TerminalColors.muted)
            HStack {
                Button("−5 мин") { minutes -= 5 }.disabled(minutes <= 5)
                Spacer()
                Text("\(minutes) мин").font(.system(.title3, design: .monospaced))
                Spacer()
                Button("+5 мин") { minutes += 5 }.disabled(minutes >= 720)
            }
            HStack {
                if player.sleepRemaining != nil { Button("Выключить") { player.setSleep(minutes: 0); dismiss() } }
                Spacer()
                Button("Отмена") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Запустить") { player.setSleep(minutes: minutes); dismiss() }.keyboardShortcut(.defaultAction)
            }
        }.padding(24).frame(width: 420)
            .background(TerminalColors.background).tint(TerminalColors.accent)
            .onAppear { if let remaining = player.sleepRemaining { minutes = max(5, ((remaining + 299) / 300) * 5) } }
    }
}
