import Foundation
import AppKit
import AVFoundation
import MediaPlayer
import Combine

private final class PlaybackArtwork: @unchecked Sendable {
    let image:NSImage
    init(_ image:NSImage) {self.image=image}
    func mediaItem() -> MPMediaItemArtwork {
        let snapshot=self
        return MPMediaItemArtwork(boundsSize:image.size) { @Sendable _ in snapshot.image }
    }
}

@MainActor final class Playback: NSObject,ObservableObject,AVAudioPlayerDelegate {
    @Published var queue:[Track]=[]
    @Published var index=0
    @Published var current:Track?
    @Published private(set) var trackerMode: TrackerMode?
    @Published private(set) var station: RadioStation?
    @Published private(set) var signalSummary = ""
    @Published private(set) var trackerBPM = 0
    @Published private(set) var sleepRemaining: Int?
    @Published private(set) var spectrum = [Float](repeating: 0, count: 18)
    @Published private(set) var arrangementEnabled = UserDefaults.standard.bool(forKey:"queueArrangement.enabled")
    @Published private(set) var arrangementStyle = QueueStyle(rawValue:UserDefaults.standard.string(forKey:"queueArrangement.style") ?? "") ?? .auto
    @Published private(set) var arrangementLength = QueueLength(rawValue:UserDefaults.standard.string(forKey:"queueArrangement.length") ?? "") ?? .normal
    @Published private(set) var arrangementActive: QueueStyle?
    @Published private(set) var arrangementNotice: String?
    private var arrangementVariant: UInt64 = 0
    private var arrangementFile: URL?
    private struct Prepared: Sendable {
        var native: NativeStream?
        var arranged: ArrangedTrack?
        var notice: String?
        var originalDuration: Int?
    }
    private actor Preparation {
        func open(url:URL,track:Track,files:[DecoderFile],options:QueueOptions?) throws -> Prepared {
            var result=Prepared()
            if let options {
                do {
                    let arrangement=try QueueArrangement.make(url:url,track:track,files:files,options:options)
                    result.arranged=arrangement
                    result.originalDuration=arrangement.originalDuration
                    return result
                } catch is CancellationError {throw CancellationError()}
                catch {result.notice="Оригинал · аранжировка недоступна для этого трека"}
            }
            try Task.checkCancellation()
            if track.isNative {result.native=try NativeStream(url:url,track:track,files:files)}
            else if ["psf2","minipsf2"].contains(track.decoderFormat),track.wavRender==nil {
                throw PlayerError.message("В каталоге нет WAV для этого PSF2")
            }
            return result
        }
    }
    private let preparation=Preparation()
    var playable: Bool { current != nil || trackerMode != nil || station != nil }
    var title: String? { trackerMode?.title ?? station?.title ?? current?.title }
    var detail: String? { trackerMode?.detail ?? (station == nil ? nil : "Интернет-радио") ?? current.map { $0.artist.isEmpty ? $0.album : "\($0.artist) · \($0.album)" } }
    var canSeek: Bool { current != nil && duration != nil && !loadingNow && !seeking && !completed }
    var liveLabel: String { trackerMode != nil ? "\(stamp(elapsed)) · \(trackerBPM) BPM" : station != nil ? "ПРЯМОЙ ЭФИР" : stamp(elapsed) + " / " + (duration.map(stamp) ?? "--:--") }
    @Published var paused=true
    @Published var state="Выберите композицию"
    @Published var elapsed=0
    @Published var duration:Int?
    @Published var artwork:NSImage? {
        didSet {mediaArtwork=artwork.map{PlaybackArtwork($0).mediaItem()}}
    }
    @Published var shuffle=UserDefaults.standard.bool(forKey:"shuffle") {didSet{UserDefaults.standard.set(shuffle,forKey:"shuffle")}}
    @Published var repeatOne=UserDefaults.standard.bool(forKey:"repeatOne") {
        didSet { UserDefaults.standard.set(repeatOne,forKey:"repeatOne");if repeatOne && loop {loop=false} }
    }
    @Published var loop=UserDefaults.standard.bool(forKey:"loop") && !UserDefaults.standard.bool(forKey:"repeatOne") {
        didSet { UserDefaults.standard.set(loop,forKey:"loop");if loop && repeatOne {repeatOne=false} }
    }
    @Published var volume:Float=UserDefaults.standard.object(forKey:"volume").map{($0 as? NSNumber)?.floatValue ?? 0.8} ?? 0.8 {didSet{media?.volume=volume;radio?.volume=volume;engine?.mainMixerNode.outputVolume=volume;UserDefaults.standard.set(volume,forKey:"volume")}}
    private let library:LibraryStore
    private var generation=0
    private var loading:Task<Void,Never>?
    private var media:AVAudioPlayer?
    private var engine:AVAudioEngine?
    private var source:AVAudioSourceNode?
    private var native:NativeStream?
    private var tracker: TrackerSession?
    private var radio: AVPlayer?
    private var radioObservation: NSKeyValueObservation?
    private let levels = TerminalLevels()
    private var hasMeter = false
    private var sleepDeadline: TimeInterval?
    private var ticker:Timer?
    private var skipped=0
    private var loadingNow=false
    private var completed=false
    private var seeking=false
    private var lastAnnouncement=0
    private var nativeEndDeadline:TimeInterval?
    private var configurationObserver:NSObjectProtocol?
    private var mediaArtwork:MPMediaItemArtwork?
    private(set) var userRevision=0
    private(set) var playedGames=Set<String>()
    private(set) var playedKeys=Set<String>()
    private var visited=Set<Int>()
    var queueFinished: ((Track)->Void)?
    var trackStarted: (()->Void)?
    init(library:LibraryStore) {
        self.library=library;super.init()
        ticker=Timer.scheduledTimer(withTimeInterval:0.2,repeats:true){[weak self] _ in Task{@MainActor in self?.tick()}}
        installRemote()
        configurationObserver=NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: nil, queue: .main
        ) { @Sendable [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self=self, let audio=self.engine, !audio.isRunning,
                      !self.paused, !self.loadingNow, !self.seeking, !self.completed else {return}
                self.pause()
                self.state="Изменился звуковой выход · нажмите Играть"
            }
        }
    }
    func play(_ tracks:[Track],at position:Int=0) {
        guard tracks.indices.contains(position) else {return}
        userRevision+=1;queue=tracks;skipped=0;visited=[];playedGames=[];playedKeys=[];start(position)
    }
    func append(_ tracks:[Track]) { userRevision+=1;if queue.isEmpty {play(tracks)} else {queue+=tracks} }
    func jump(_ position:Int) {guard queue.indices.contains(position) else {return};userRevision+=1;skipped=0;start(position)}
    func remove(_ position:Int) {
        guard queue.indices.contains(position) else {return}
        userRevision+=1
        visited=Set(visited.filter{$0 != position}.map{$0>position ? $0-1 : $0})
        if position==index {queue.remove(at:position);if queue.isEmpty{stop()}else{start(min(position,queue.count-1))}}
        else {queue.remove(at:position);if position<index {index-=1}}
    }
    func clear() {userRevision+=1;queue=[];stop()}
    private func releaseAudio() {
        media?.stop();media?.delegate=nil;media=nil
        if let file=arrangementFile {try? FileManager.default.removeItem(at:file)}
        arrangementFile=nil;arrangementActive=nil;arrangementNotice=nil
        radioObservation = nil
        radio?.pause()
        radio = nil
        tracker?.setPlaying(false)
        tracker = nil
        if hasMeter { engine?.mainMixerNode.removeTap(onBus: 0); hasMeter = false }
        levels.reset()
        spectrum = [Float](repeating: 0, count: 18)
        engine?.stop();if let s=source {engine?.detach(s)};source=nil;engine=nil;native=nil
    }
    func stop() {
        userRevision+=1;generation+=1;nativeEndDeadline=nil;loading?.cancel();loading=nil;releaseAudio();clearLive();current=nil;artwork=nil;elapsed=0;duration=nil;paused=true;loadingNow=false;completed=false;seeking=false;state="Выберите композицию";announce()
    }
    private func clearLive() {
        trackerMode = nil
        station = nil
        signalSummary = ""
        trackerBPM = 0
    }

    private func startLive() {
        stop()
        queue = []
        paused = false
        state = "Подключение…"
    }

    func playTracker(_ mode: TrackerMode) {
        if trackerMode == mode, tracker != nil, !completed {
            if paused { resume() }
            return
        }
        startLive()
        trackerMode = mode
        do {
            let session = try TrackerSession(mode: mode)
            let (audio, node) = try session.stream.makeEngine(volume: volume)
            engine = audio
            source = node
            tracker = session
            installMeter()
            try audio.start()
            session.setPlaying(true)
            trackerBPM = session.stream.bpm
            signalSummary = session.summary
            state = "Воспроизведение"
        } catch {
            releaseAudio()
            paused = true
            completed = true
            state = error.localizedDescription
        }
        announce()
    }

    func trackerOptionsChanged(_ mode: TrackerMode) {
        if trackerMode == mode { tracker?.updateOptions() }
    }

    func playRadio(_ station: RadioStation) {
        if self.station == station, radio != nil, !completed {
            if paused { resume() }
            return
        }
        startLive()
        self.station = station
        let item = AVPlayerItem(url: station.url)
        let player = AVPlayer(playerItem: item)
        player.volume = volume
        radio = player
        let token = generation
        radioObservation = player.observe(\.timeControlStatus, options: [.new]) { [weak self] _, _ in
            Task { @MainActor in
                guard let self, self.generation == token, !self.paused, let radio = self.radio else { return }
                self.state = radio.timeControlStatus == .playing ? "В прямом эфире" : "Подключение…"
            }
        }
        player.play()
        announce()
    }

    func setSleep(minutes: Int) {
        sleepDeadline = minutes > 0 ? ProcessInfo.processInfo.systemUptime + Double(minutes) * 60 : nil
        sleepRemaining = minutes > 0 ? minutes * 60 : nil
    }

    func setArrangement(enabled:Bool?=nil, style:QueueStyle?=nil, length:QueueLength?=nil, variation:Bool=false) {
        let old=(arrangementEnabled,arrangementStyle,arrangementLength)
        if let enabled {arrangementEnabled=enabled}
        if let style {arrangementStyle=style;arrangementEnabled=true}
        if let length {arrangementLength=length;arrangementEnabled=true}
        if variation {arrangementVariant &+= 1;arrangementEnabled=true}
        guard variation || old != (arrangementEnabled,arrangementStyle,arrangementLength) else {return}
        UserDefaults.standard.set(arrangementEnabled,forKey:"queueArrangement.enabled")
        UserDefaults.standard.set(arrangementStyle.rawValue,forKey:"queueArrangement.style")
        UserDefaults.standard.set(arrangementLength.rawValue,forKey:"queueArrangement.length")
        guard current != nil,!completed,queue.indices.contains(index) else {return}
        userRevision+=1
        let progress=duration.flatMap{$0>0 ? min(0.999,max(0,Double(elapsed)/Double($0))):nil} ?? 0
        start(index,at:progress,initialPause:paused,changingArrangement:true)
    }

    private func start(_ position:Int,at progress:Double=0,initialPause:Bool=false,changingArrangement:Bool=false) {
        guard queue.indices.contains(position) else {return}
        generation+=1;nativeEndDeadline=nil;let token=generation;loading?.cancel();releaseAudio();clearLive()
        let retainedArtwork=changingArrangement ? artwork:nil
        index=position;let track=queue[position];current=track;artwork=retainedArtwork;elapsed=0;duration=nil
        paused=initialPause;loadingNow=true;completed=false;seeking=false
        state=arrangementEnabled ? "Подготовка аранжировки…":"Загрузка композиции…";announce()
        let options=arrangementEnabled ? QueueOptions(style:arrangementStyle,length:arrangementLength,variant:arrangementVariant):nil
        visited.insert(position)
        loading=Task { [weak self] in
            guard let self=self else {return}
            do {
                let url=try await library.fetch(track)
                try Task.checkCancellation()
                let ready=await library.measured(track,url:url)
                let files=try await library.decoderFiles(ready,main:url)
                try Task.checkCancellation();guard token==generation else {return}
                guard queue.indices.contains(index),queue[index].id==track.id else {return}
                queue[index]=ready
                let threshold=UserDefaults.standard.integer(forKey:"shortSeconds"),unknown=UserDefaults.standard.string(forKey:"unknownDuration") ?? "show"
                // If the manifest has no duration, first let the native decoder read the file.
                let prepared=try await preparation.open(url:url,track:ready,files:files,options:options)
                var adopted=false
                defer {if !adopted,let file=prepared.arranged?.url {try? FileManager.default.removeItem(at:file)}}
                try Task.checkCancellation();guard token==generation else {return}
                let known=ready.knownMS ?? prepared.native?.duration ?? prepared.originalDuration
                if !changingArrangement && !PlaybackRules.include(known,threshold:threshold,unknown:unknown) {
                    skipped+=1
                    if skipped>=queue.count {paused=true;loadingNow=false;completed=true;state="Все композиции скрыты фильтром";announce()}
                    else if let next=ContinuationRules.next(index:index,count:queue.count,visited:visited,shuffle:shuffle,loop:loop) {start(next)}
                    else {paused=true;loadingNow=false;completed=true;state="Очередь завершена";announce()}
                    return
                }
                if let stream=prepared.native,progress>0,let length=stream.duration {
                    let target=Int64(Double(length)*progress)
                    _=await Task.detached(priority:.userInitiated){ga_seek(stream.handle,target)}.value
                    try Task.checkCancellation();guard token==generation else {return}
                }
                skipped=0;current=ready
                try begin(url:url,prepared:prepared,progress:progress)
                adopted=true;loadingNow=false;state=paused ? "Пауза" : "Воспроизведение"
                playedGames.insert(ready.gameID);if !ready.analysisKey.isEmpty {playedKeys.insert(ready.analysisKey)}
                if !changingArrangement {trackStarted?()};announce()
                if artwork==nil,!ready.gameID.isEmpty,let art=try? await library.artwork(id:ready.gameID,series:false,preview:false),token==generation,!Task.isCancelled {artwork=NSImage(contentsOf:art);announce()}
            } catch is CancellationError {} catch {
                guard token==generation else {return};releaseAudio();loadingNow=false;paused=true;completed=true
                state="Ошибка: \(error.localizedDescription)";announce()
            }
        }
    }
    private func begin(url:URL,prepared:Prepared,progress:Double) throws {
        if let stream=prepared.native {
            let (audio,node)=try stream.makeEngine(volume:volume)
            native=stream;engine=audio;source=node
            duration=stream.duration;elapsed=stream.position
            installMeter()
            if !paused {try audio.start()}
        } else {
            let p=try SystemAudio.makePlayer(url:prepared.arranged?.url ?? url,volume:volume);p.delegate=self
            media=p;duration=Int(p.duration*1000)
            p.currentTime=p.duration*progress;elapsed=Int(p.currentTime*1000)
            if !paused && !p.play() {throw PlayerError.message("Не удалось начать воспроизведение")}
        }
        arrangementFile=prepared.arranged?.url;arrangementActive=prepared.arranged?.style
        arrangementNotice=prepared.notice
    }
    func pause() {
        guard playable else { return }
        userRevision += 1
        nativeEndDeadline = nil
        paused = true
        media?.pause()
        engine?.pause()
        radio?.pause()
        tracker?.setPlaying(false)
        levels.reset()
        spectrum = [Float](repeating: 0, count: 18)
        if !completed { state = "Пауза" }
        announce()
    }
    func resume() {
        guard playable, paused else {return}
        if let mode = trackerMode {
            guard let tracker, let engine, !completed else { playTracker(mode); return }
            do {
                try engine.start()
                tracker.setPlaying(true)
                paused = false
                state = "Воспроизведение"
            } catch { completed = true; state = error.localizedDescription }
            announce()
            return
        }
        if let station {
            guard let radio, !completed else { playRadio(station); return }
            paused = false
            radio.play()
            state = "Подключение…"
            announce()
            return
        }
        userRevision+=1
        if completed {start(index);return}
        paused=false
        if loadingNow {state="Загрузка композиции…";announce();return}
        do {if let p=media,!p.play(){throw PlayerError.message("Не удалось продолжить")};try resumeNative();state="Воспроизведение"}
        catch {paused=true;completed=true;state="Ошибка: \(error.localizedDescription)"};announce()
    }
    func toggle() {if paused {resume()}else{pause()}}
    func next(natural:Bool=false) {
        guard !queue.isEmpty else {return}
        if !natural {userRevision+=1}
        if natural && repeatOne {start(index);return}
        if let n=ContinuationRules.next(index:index,count:queue.count,visited:visited,shuffle:shuffle,loop:loop) {
            if shuffle && visited.count>=queue.count { visited=[index] }
            start(n)
        } else {
            paused=true;completed=true;state="Очередь завершена";media?.stop();engine?.pause();announce()
            if !repeatOne && !loop,let seed=current {queueFinished?(seed)}
        }
    }
    func continueWith(_ tracks: [Track]) {
        guard !tracks.isEmpty else {return}
        let next=queue.count;queue+=tracks;skipped=0;start(next)
    }
    func previous() {guard !queue.isEmpty else {return};userRevision+=1;skipped=0;start(max(0,index-1))}
    func seek(_ value:Int) {
        guard canSeek else {return}
        userRevision+=1
        nativeEndDeadline=nil
        let target=max(0,min(value,duration ?? max(value,0)))
        if let p=media {p.currentTime=Double(target)/1000;elapsed=target;announce()}
        else if let stream=native {
            let token=generation;seeking=true;engine?.pause()
            Task {
                let ok=await Task.detached{ga_seek(stream.handle,Int64(target)) != 0}.value
                guard generation==token else {return};seeking=false
                if ok {elapsed=stream.position}else{state="Не удалось перемотать"}
                if !paused {do{try resumeNative()}catch{paused=true;state=error.localizedDescription}};announce()
            }
        }
    }
    private func resumeNative() throws {
        guard let stream=native else {return}
        if hasMeter { engine?.mainMixerNode.removeTap(onBus: 0); hasMeter = false }
        engine?.stop()
        if let node=source {engine?.detach(node)}
        source=nil;engine=nil
        let (audio,node)=try stream.makeEngine(volume:volume)
        source=node;engine=audio
        installMeter()
        try audio.start()
    }
    private func installMeter() {
        let capture = levels
        engine?.mainMixerNode.installTap(onBus: 0, bufferSize: 1024, format: nil) { buffer, _ in
            capture.accept(buffer)
        }
        hasMeter = engine != nil
    }

    private func tick() {
        if let deadline = sleepDeadline {
            let remaining = max(0, Int(ceil(deadline - ProcessInfo.processInfo.systemUptime)))
            if remaining == 0 {
                setSleep(minutes: 0)
                if !paused { pause(); state = "Остановлено по таймеру" }
            } else if sleepRemaining != remaining { sleepRemaining = remaining }
        }
        guard !paused,!loadingNow,!seeking,!completed else {return}
        if engine != nil { spectrum = levels.read() }
        if let tracker {
            guard engine?.isRunning == true else {
                pause(); state = "Звуковой выход остановлен · нажмите Играть"; return
            }
            tracker.tick()
            let summary = tracker.summary
            if signalSummary != summary { signalSummary = summary }
            let bpm = tracker.stream.bpm
            if trackerBPM != bpm { trackerBPM = bpm }
            if let source, let time = source.lastRenderTime, time.isSampleTimeValid {
                elapsed = max(0, Int(Double(time.sampleTime) * 1000 / time.sampleRate))
            }
        }
        if let radio, radio.currentItem?.status == .failed {
            completed = true
            pause()
            state = "Радиостанция недоступна"
            return
        }
        if let p=media {
            let seconds=p.currentTime
            guard seconds.isFinite, seconds>=0, seconds<Double(Int.max/1000) else {
                pause();completed=true;state="Некорректная позиция аудиофайла";return
            }
            elapsed=Int(seconds*1000)
        }
        if let n=native {
            guard engine?.isRunning == true else {
                pause();state="Звуковой выход остановлен · нажмите Играть";return
            }
            elapsed=Int(ga_position(n.handle))
            if ga_failed(n.handle) != 0 {paused=true;completed=true;engine?.pause();state="Ошибка декодирования";announce();return}
            if ga_ended(n.handle) != 0 {
                if duration==nil,elapsed>0 {duration=elapsed}
                let now=ProcessInfo.processInfo.systemUptime
                if let deadline=nativeEndDeadline {
                    if now>=deadline {next(natural:true);return}
                } else {
                    nativeEndDeadline=now+max(0.05,source?.outputPresentationLatency ?? 0)+0.05
                }
            }
        }
        if elapsed/1000 != lastAnnouncement {lastAnnouncement=elapsed/1000;announce()}
    }
    nonisolated func audioPlayerDidFinishPlaying(_ player:AVAudioPlayer,successfully flag:Bool) {
        Task{@MainActor [weak self] in guard let self=self,self.media===player else {return};if flag {self.next(natural:true)} else {self.pause();self.completed=true;self.state="Ошибка декодирования"}}
    }
    private func announce() {
        let center=MPNowPlayingInfoCenter.default()
        let commands = MPRemoteCommandCenter.shared()
        commands.changePlaybackPositionCommand.isEnabled = canSeek
        commands.nextTrackCommand.isEnabled = current != nil && !queue.isEmpty
        commands.previousTrackCommand.isEnabled = commands.nextTrackCommand.isEnabled
        guard let title else {center.nowPlayingInfo=nil;center.playbackState = .stopped;return}
        var info:[String:Any]=[MPMediaItemPropertyTitle:title,MPMediaItemPropertyAlbumTitle:current?.album ?? detail ?? "",
                              MPMediaItemPropertyArtist:current?.artist ?? "GremlinAmp",MPNowPlayingInfoPropertyElapsedPlaybackTime:Double(elapsed)/1000,MPNowPlayingInfoPropertyPlaybackRate:paused ? 0.0 : 1.0]
        if let d=duration {info[MPMediaItemPropertyPlaybackDuration]=Double(d)/1000}
        if let image=mediaArtwork {info[MPMediaItemPropertyArtwork]=image}
        center.nowPlayingInfo=info;center.playbackState=paused ? .paused : .playing
    }
    private func installRemote() {
        let r=MPRemoteCommandCenter.shared()
        r.playCommand.addTarget{ @Sendable [weak self] _ in Task{@MainActor in self?.resume()};return .success}
        r.pauseCommand.addTarget{ @Sendable [weak self] _ in Task{@MainActor in self?.pause()};return .success}
        r.togglePlayPauseCommand.addTarget{ @Sendable [weak self] _ in Task{@MainActor in self?.toggle()};return .success}
        r.nextTrackCommand.addTarget{ @Sendable [weak self] _ in Task{@MainActor in self?.next()};return .success}
        r.previousTrackCommand.addTarget{ @Sendable [weak self] _ in Task{@MainActor in self?.previous()};return .success}
        r.changePlaybackPositionCommand.addTarget{ @Sendable [weak self] e in guard let p=e as? MPChangePlaybackPositionCommandEvent else {return .commandFailed};let target=Int(p.positionTime*1000);Task{@MainActor in self?.seek(target)};return .success}
    }
}
