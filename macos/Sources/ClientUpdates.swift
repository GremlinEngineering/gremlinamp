import Foundation
import AppKit
import Combine

private struct ReadyClient: Codable {
    var release: ClientRelease
    var image: ClientRelease.Image
    var filename: String
}

private actor ClientUpdateStore {
    private let root: URL
    init() {
        root=FileManager.default.urls(for:.applicationSupportDirectory,in:.userDomainMask)[0]
            .appendingPathComponent("GremlinAmp/updates",isDirectory:true)
        try? FileManager.default.createDirectory(at:root,withIntermediateDirectories:true)
    }
    func ready(installedBuild: Int) -> (URL,String)? {
        guard let data=try? Data(contentsOf:root.appendingPathComponent("ready.json")),
              let item=try? JSONDecoder().decode(ReadyClient.self,from:data),
              item.release.build>installedBuild,
              item.filename==URL(fileURLWithPath:item.filename).lastPathComponent else { return nil }
        let file=root.appendingPathComponent(item.filename)
        guard (try? file.resourceValues(forKeys:[.fileSizeKey]).fileSize)==item.image.bytes,
              (try? LibraryStore.fileHash(file))==item.image.sha256.lowercased() else { return nil }
        return (file,item.release.version)
    }
    func download(_ release: ClientRelease, image: ClientRelease.Image) async throws -> URL {
        let name="GremlinAmp-\(release.version)-\(image.sha256.prefix(12)).dmg"
        let file=root.appendingPathComponent(name)
        let part=file.appendingPathExtension("part")
        if (try? file.resourceValues(forKeys:[.fileSizeKey]).fileSize) != image.bytes ||
           (try? LibraryStore.fileHash(file)) != image.sha256.lowercased() {
            if (try? part.resourceValues(forKeys:[.fileSizeKey]).fileSize)==image.bytes,
               (try? LibraryStore.fileHash(part)) != image.sha256.lowercased() {
                try FileManager.default.removeItem(at:part)
            }
            if (try? part.resourceValues(forKeys:[.fileSizeKey]).fileSize) != image.bytes {
                try await AssetTransfer(destination:part,expected:image.bytes).run(SiteURLRules.resolve(image.url))
            }
            guard try LibraryStore.fileHash(part)==image.sha256.lowercased() else {
                try? FileManager.default.removeItem(at:part)
                throw PlayerError.message("SHA-256 обновления не совпадает")
            }
            try? FileManager.default.removeItem(at:file)
            try FileManager.default.moveItem(at:part,to:file)
        }
        let ready=ReadyClient(release:release,image:image,filename:name)
        try JSONEncoder().encode(ready).write(to:root.appendingPathComponent("ready.json"),options:.atomic)
        // Keep only the verified current image; incomplete transfers remain resumable.
        for old in (try? FileManager.default.contentsOfDirectory(at:root,includingPropertiesForKeys:nil)) ?? []
            where old.pathExtension=="dmg" && old != file { try? FileManager.default.removeItem(at:old) }
        return file
    }
}

@MainActor final class ClientUpdates: ObservableObject {
    @Published var automatic=UserDefaults.standard.bool(forKey:"autoClientUpdates") {
        didSet { UserDefaults.standard.set(automatic,forKey:"autoClientUpdates");if automatic { checkIfDue() } }
    }
    @Published var interval=SyncInterval(rawValue:UserDefaults.standard.string(forKey:"clientUpdateInterval") ?? "morning") ?? .morning {
        didSet { UserDefaults.standard.set(interval.rawValue,forKey:"clientUpdateInterval");checkIfDue() }
    }
    @Published private(set) var busy=false
    @Published private(set) var status="Обновления клиента ещё не проверялись"
    @Published private(set) var readyURL: URL?
    @Published private(set) var readyVersion=""
    @Published private(set) var lastCheck=UserDefaults.standard.object(forKey:"clientUpdateLastCheck") as? Date
    private let store=ClientUpdateStore()
    private let installedBuild=Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "0") ?? 0
    static let manifest="assets/arcade/music-app/macos-release.json"
    private var timer: Timer?
    init() {
        Task { await restore() }
        timer=Timer.scheduledTimer(withTimeInterval:60,repeats:true) { [weak self] _ in
            Task { @MainActor in self?.checkIfDue() }
        }
    }
    deinit { timer?.invalidate() }
    private func restore() async {
        if let (url,version)=await store.ready(installedBuild:installedBuild) {
            readyURL=url;readyVersion=version;status="Версия \(version) скачана · откройте DMG для установки"
        }
        checkIfDue()
    }
    func checkIfDue() {
        guard automatic,SyncSchedule.due(now:Date(),last:lastCheck,interval:interval) else { return }
        check()
    }
    func check() {
        guard !busy else { return };busy=true;status="Проверка новой версии…"
        Task {
            defer {
                busy=false;lastCheck=Date();UserDefaults.standard.set(lastCheck,forKey:"clientUpdateLastCheck")
            }
            do {
                let (data,response)=try await SiteHTTP.data(SiteURLRules.resolve(Self.manifest),limit:128*1024)
                if response.statusCode==404 { status="Файл релиза macOS пока недоступен на сайте";return }
                guard response.statusCode==200 else { throw PlayerError.message("Релиз: HTTP \(response.statusCode)") }
                let release=try JSONDecoder().decode(ClientRelease.self,from:data)
                #if arch(arm64)
                let architecture="arm64"
                #else
                let architecture="x86_64"
                #endif
                guard let image=try release.image(architecture:architecture,installedBuild:installedBuild,
                                                  os:ProcessInfo.processInfo.operatingSystemVersion) else {
                    readyURL=nil;readyVersion="";status="Установлена актуальная версия";return
                }
                status="Скачивается GremlinAmp \(release.version) · \(ByteCountFormatter.string(fromByteCount:Int64(image.bytes),countStyle:.file))"
                let file=try await store.download(release,image:image)
                readyURL=file;readyVersion=release.version
                status="Версия \(release.version) скачана и проверена · откройте DMG для установки"
            } catch { status="Обновление клиента: \(error.localizedDescription)" }
        }
    }
    func openDownloaded() {
        guard !busy else { return }
        Task {
            guard let (url,version)=await store.ready(installedBuild:installedBuild) else {
                readyURL=nil;status="Файл обновления недоступен или повреждён · скачайте повторно";return
            }
            readyURL=url;readyVersion=version
            if !NSWorkspace.shared.open(url) { status="Не удалось открыть DMG" }
        }
    }
    func revealDownloaded() {
        if let url=readyURL { NSWorkspace.shared.activateFileViewerSelecting([url]) }
    }
}
