import AVFoundation
import Foundation

@main struct QueuePlaybackTests {
    @MainActor static func main() async throws {
        let fm=FileManager.default
        let root=fm.temporaryDirectory.appendingPathComponent("gremlin-queue-test-"+UUID().uuidString)
        defer {try? fm.removeItem(at:root)}
        let store=LibraryStore(root:root)
        try fm.createDirectory(at:root.appendingPathComponent("files"),withIntermediateDirectories:true)
        var tracks=[Track]()
        for (index,name) in ["tone.nsf","tone.vgz","tone.wav"].enumerated() {
            let source=URL(fileURLWithPath:"tests/"+name)
            let bytes=try Data(contentsOf:source),hash=LibraryStore.digest(bytes),format=source.pathExtension
            let target=root.appendingPathComponent("files/\(hash).\(format)")
            try bytes.write(to:target)
            tracks.append(Track(["id":"test-\(index)","title":name,"format":format,
                "backend":index==2 ? "media":"native","duration_known":true,"play_ms":1000,"fade_ms":0,
                "asset":["format":format,"sha256":hash,"bytes":bytes.count,"url":name]],
                game:Game([:]),manifestURL:"https://games.oopssre.ru/fixture.json",index:index))
        }
        let player=Playback(library:store)
        defer {player.stop()}
        player.volume=0
        player.shuffle=false;player.loop=false;player.repeatOne=false
        player.setArrangement(style:.drone,length:.short)
        player.play(tracks)
        try await wait("start tracker") { player.index==0 && player.arrangementActive == .drone && player.canSeek }
        guard let length=player.duration,length>36000 && length<41000 else {throw fail("Wrong arranged duration")}
        player.seek(12_000)
        try await wait("forward seek") {player.elapsed>=11900 && player.elapsed<13500}
        player.seek(3000)
        try await wait("backward seek") {player.elapsed>=2950 && player.elapsed<4300}
        player.seek(length-350)
        try await wait("natural advance") {player.index==1 && player.arrangementActive == .drone && player.canSeek}
        player.pause()
        player.setArrangement(style:.dub)
        try await wait("paused style change") {player.index==1 && player.paused && player.arrangementActive == .dub && player.canSeek}
        player.seek(10000)
        player.setArrangement(enabled:false)
        try await wait("return to original") {player.index==1 && player.paused && player.canSeek && player.arrangementActive==nil}
        guard let original=player.duration,original<length else {throw fail("Original duration did not return")}
        guard player.elapsed>0 && player.elapsed<original else {throw fail("Original position was not restored")}
        player.setArrangement(style:.downtempo)
        try await wait("downtempo") {player.index==1 && player.arrangementActive == .downtempo && player.paused}
        player.next()
        try await wait("media track in queue") {player.index==2 && player.arrangementActive == .downtempo && player.canSeek}
        let arrangementURL=try QueueArrangement.make(url:URL(fileURLWithPath:"tests/tone.nsf"),track:tracks[0],files:[],
            options:QueueOptions(style:.auto,length:.short,variant:0))
        defer {try? fm.removeItem(at:arrangementURL.url)}
        let file=try AVAudioFile(forReading:arrangementURL.url)
        let pcm=AVAudioPCMBuffer(pcmFormat:file.processingFormat,frameCapacity:4096)!
        file.framePosition=44100;try file.read(into:pcm)
        let peak=UnsafeBufferPointer(start:pcm.floatChannelData![0],count:Int(pcm.frameLength)).map(abs).max() ?? 0
        guard peak>0.005 else {throw fail("Generated file is silent")}
        print("Queue: native NSF and VGM, system WAV, automatic and manual styles, audible synthesis, seek both directions, natural transition, pause and proportional return: passed")
    }
    static func fail(_ message:String)->PlayerError { .message(message) }
    @MainActor static func wait(_ label:String,_ condition:()->Bool) async throws {
        for _ in 0..<150 {
            if condition() {return}
            try await Task.sleep(nanoseconds:100_000_000)
        }
        throw fail("Timeout: "+label)
    }
}
