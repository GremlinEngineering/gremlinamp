import AVFoundation
import Foundation

@main struct DecoderParityTests {
    static func main() throws {
        let fm=FileManager.default
        let parent=URL(fileURLWithPath:CommandLine.arguments[1])
        let args=Array(CommandLine.arguments.dropFirst(2))
        let dirs=try fm.contentsOfDirectory(at:parent,includingPropertiesForKeys:[.isDirectoryKey]).sorted{$0.path<$1.path}
        var failures=0,passed=0
        for dir in dirs where args.isEmpty || args.contains(dir.lastPathComponent) {
            let name=dir.lastPathComponent
            guard let data=try? Data(contentsOf:dir.appendingPathComponent("track.json")) else {continue}
            do {
                let json=try JSONSerialization.jsonObject(with:data) as! JSON
                let track=Track(json,game:Game(["id":name]),manifestURL:"https://games.oopssre.ru/test.json",index:0)
                let names=try String(contentsOf:dir.appendingPathComponent("paths.txt"),encoding:.utf8).split(whereSeparator:\.isNewline).map(String.init)
                let files=names.map{DecoderFile(name:$0,url:dir.appendingPathComponent($0))}
                guard let first=files.first else {throw PlayerError.message("Missing input")}
                if !track.isNative {
                    let url=track.wavRender.map { asset in
                        let named=dir.appendingPathComponent(URL(string:asset.url)?.lastPathComponent ?? "")
                        return fm.fileExists(atPath:named.path) ? named:dir.appendingPathComponent("fallback.wav")
                    } ?? first.url
                    guard fm.fileExists(atPath:url.path) else {print("SKIP \(name): fixture lacks published WAV");continue}
                    let player=try SystemAudio.makePlayer(url:url,volume:0)
                    if let expected=track.wavRender,try Data(contentsOf:url).count != expected.bytes {
                        throw PlayerError.message("Wrong WAV bytes")
                    }
                    guard player.duration>0 else {throw PlayerError.message("No duration")}
                    print("PASS \(name): PCM \(player.duration) s");passed+=1;continue
                }
                let stream=try NativeStream(url:first.url,track:track,files:files)
                var pcm=[Float](repeating:0,count:4096)
                var level:Float=0
                let count=max(20,min(450,Int(stream.format.sampleRate)*4/2048))
                for _ in 0..<count {
                    ga_render(stream.handle,&pcm,2048)
                    if ga_failed(stream.handle) != 0 {throw PlayerError.message("Decode failed")}
                    level=max(level,pcm.reduce(Float(0)){max($0,abs($1))})
                    if level>0.001 || ga_ended(stream.handle) != 0 {break}
                }
                guard level>0 else {throw PlayerError.message("Silent fixture (\(stream.position) ms)")}
                let target=min(1000,(stream.duration ?? 2000)/2)
                for t in [target,target/5,target] {
                    guard ga_seek(stream.handle,Int64(t)) != 0,abs(stream.position-t)<=3 else {
                        throw PlayerError.message("Seek wanted \(t) ms, got \(stream.position) ms")
                    }
                    ga_render(stream.handle,&pcm,2048)
                    guard ga_failed(stream.handle)==0 else {throw PlayerError.message("Decode after seek failed")}
                }
                if let length=stream.duration,length<55000 {
                    let end=max(0,length-30)
                    guard ga_seek(stream.handle,Int64(end)) != 0 else {throw PlayerError.message("End seek failed")}
                    for _ in 0..<10 where ga_ended(stream.handle)==0 {ga_render(stream.handle,&pcm,2048)}
                    guard ga_ended(stream.handle) != 0,ga_failed(stream.handle)==0 else {throw PlayerError.message("No natural end")}
                }
                print("PASS \(name): \(Int(stream.format.sampleRate)) Hz, \(stream.duration.map(String.init) ?? "unknown") ms")
                passed+=1
            } catch {
                print("FAIL \(name): \(error.localizedDescription)")
                failures+=1
            }
        }
        print("Decoder fixtures: \(passed) passed, \(failures) failed")
        if failures>0 || passed==0 {exit(1)}
    }
}
