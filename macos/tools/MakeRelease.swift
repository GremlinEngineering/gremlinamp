import Foundation
import CryptoKit

@main struct MakeRelease {
    static func main() throws {
        let args=CommandLine.arguments
        guard args.count==5,["arm64","x86_64","universal"].contains(args[2]) else {
            throw PlayerError.message("Использование: make-release DMG ARCH Info.plist OUTPUT.json")
        }
        let dmg=URL(fileURLWithPath:args[1]), output=URL(fileURLWithPath:args[4])
        let plist=try PropertyListSerialization.propertyList(from:Data(contentsOf:URL(fileURLWithPath:args[3])),format:nil) as? [String:Any] ?? [:]
        guard let version=plist["CFBundleShortVersionString"] as? String,
              let rawBuild=plist["CFBundleVersion"] as? String,let build=Int(rawBuild),
              let bundle=plist["CFBundleIdentifier"] as? String,
              let os=plist["LSMinimumSystemVersion"] as? String else { throw PlayerError.message("Неполный Info.plist") }
        let file=try FileHandle(forReadingFrom:dmg);defer{try? file.close()}
        var hash=SHA256()
        while let bytes=try file.read(upToCount:1024*1024),!bytes.isEmpty {hash.update(data:bytes)}
        let digest=hash.finalize().map{String(format:"%02x",$0)}.joined()
        let bytes=try dmg.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0
        var release=ClientRelease(schema_version:1,bundle_id:bundle,version:version,build:build,minimum_macos:os,dmg:[:])
        if let data=try? Data(contentsOf:output),let previous=try? JSONDecoder().decode(ClientRelease.self,from:data),
           previous.build==build,previous.version==version,previous.bundle_id==bundle,previous.minimum_macos==os {
            release.dmg=previous.dmg
        }
        release.dmg[args[2]]=ClientRelease.Image(url:"/assets/arcade/music-app/macos/"+dmg.lastPathComponent,sha256:digest,bytes:bytes)
        _=try release.image(architecture:args[2],installedBuild:build-1,os:ProcessInfo.processInfo.operatingSystemVersion)
        let encoder=JSONEncoder();encoder.outputFormatting=[.prettyPrinted,.sortedKeys,.withoutEscapingSlashes]
        try encoder.encode(release).write(to:output,options:.atomic)
    }
}
