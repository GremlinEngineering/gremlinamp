import AppKit
import Combine
import ServiceManagement

@MainActor final class StartupSettings: ObservableObject {
    @Published private(set) var enabled=false
    @Published private(set) var needsApproval=false
    @Published private(set) var status=""
    private var observer: NSObjectProtocol?
    private let label="ru.oopssre.gremlinamp.mac.login"
    private var legacyURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/LaunchAgents/\(label).plist")
    }
    init() {
        refresh()
        observer=NotificationCenter.default.addObserver(forName:NSApplication.didBecomeActiveNotification,
            object:nil,queue:.main) { @Sendable [weak self] _ in
                Task { @MainActor [weak self] in self?.refresh() }
            }
    }
    deinit { if let observer=observer { NotificationCenter.default.removeObserver(observer) } }
    func refresh() {
        needsApproval=false
        if #available(macOS 13.0, *) {
            let value=SMAppService.mainApp.status
            enabled=value == .enabled || value == .requiresApproval
            needsApproval=value == .requiresApproval
            status=needsApproval ? "Разрешите запуск в системных настройках входа" : (enabled ? "Запуск при входе включён" : "Запуск при входе выключен")
        } else {
            enabled=FileManager.default.fileExists(atPath:legacyURL.path)
            status=enabled ? "GremlinAmp запустится при следующем входе" : "Запуск при входе выключен"
        }
    }
    func setEnabled(_ value: Bool) {
        do {
            guard Bundle.main.bundleURL.pathExtension=="app" else {
                throw PlayerError.message("Запустите собранное приложение GremlinAmp.app")
            }
            if #available(macOS 13.0, *) {
                if value { try SMAppService.mainApp.register() }
                else { try SMAppService.mainApp.unregister() }
                // Clean up an earlier Monterey registration only after the system change succeeds.
                try? FileManager.default.removeItem(at:legacyURL)
            } else if value {
                let plist: [String:Any]=[
                    "Label":label,
                    "ProgramArguments":["/usr/bin/open","-a",Bundle.main.bundleURL.path],
                    "RunAtLoad":true,
                    "LimitLoadToSessionType":"Aqua"
                ]
                let data=try PropertyListSerialization.data(fromPropertyList:plist,format:.xml,options:0)
                try FileManager.default.createDirectory(at:legacyURL.deletingLastPathComponent(),withIntermediateDirectories:true)
                try data.write(to:legacyURL,options:.atomic)
            } else if FileManager.default.fileExists(atPath:legacyURL.path) {
                try FileManager.default.removeItem(at:legacyURL)
            }
            refresh()
        } catch { refresh();status="Автозапуск: \(error.localizedDescription)" }
    }
    func openSystemSettings() {
        if #available(macOS 13.0, *) { SMAppService.openSystemSettingsLoginItems() }
    }
}
