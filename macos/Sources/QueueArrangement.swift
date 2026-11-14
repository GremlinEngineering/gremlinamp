import Accelerate
import AVFoundation
import Foundation

enum QueueStyle: String, CaseIterable, Identifiable {
    case auto, drone, dub, downtempo
    var id: String { rawValue }
    var title: String {
        switch self {case .auto:return "Авто";case .drone:return "Дрон-эмбиент";case .dub:return "Даб-эмбиент";case .downtempo:return "Даунтемпо"}
    }
    var bpm: Double {switch self {case .drone:return 52;case .dub:return 68;case .downtempo,.auto:return 82}}
}
enum QueueLength: String, CaseIterable, Identifiable {
    case short, normal, long
    var id: String { rawValue }
    var title: String {switch self {case .short:return "Короткая";case .normal:return "Обычная";case .long:return "Длинная"}}
    var bars: Int {switch self {case .short:return 8;case .normal:return 12;case .long:return 20}}
}
struct QueueOptions: Equatable, Sendable {
    var style: QueueStyle
    var length: QueueLength
    var variant: UInt64
}
struct ArrangedTrack: Sendable {
    let url: URL
    let style: QueueStyle
    let originalDuration: Int?
}
enum QueueArrangement {
    private static let analysisRate=11025, rate=44100, size=4096, hop=441
    private struct Note {var start:Double;var end:Double;var pitch:Int;var level:Double}
    private struct Theme {var notes:[Note];var bass:[Note];var bpm:Double;var density:Double}
    private enum Voice: Int {case pad,bass,bell,stab,keys,kick,snare,hat}
    private struct Event {var start:Int;var wave:[Float];var left:Float;var right:Float}
    private struct Tone: Hashable {var voice:Voice;var pitch:Int;var duration:Int}
    private struct RNG {
        var state:UInt64
        mutating func next()->Double {
            state &+= 0x9e3779b97f4a7c15
            var n=state
            n=(n^(n>>30)) &* 0xbf58476d1ce4e5b9
            n=(n^(n>>27)) &* 0x94d049bb133111eb
            return Double((n^(n>>31))>>11)/9007199254740992
        }
    }
    private static func frequency(_ pitch:Int)->Double {440 * pow(2,Double(pitch-69)/12)}
    private static func octave(_ pitch:Int,_ low:Int,_ high:Int)->Int {
        var p=pitch;while p<low {p+=12};while p>high {p-=12};return p
    }
    private static func decoded(url:URL,track:Track,files:[DecoderFile]) throws -> ([Float],Int?) {
        let native=track.isNative ? try NativeStream(url:url,track:track,files:files):nil
        let audio=native==nil ? try AVAudioFile(forReading:url):nil
        let sourceRate=Int(native?.format.sampleRate ?? audio?.processingFormat.sampleRate ?? 0)
        guard (8000...384000).contains(sourceRate) else {throw PlayerError.message("Неверная частота для аранжировки")}
        var mono=[Float]();mono.reserveCapacity(42*analysisRate)
        var phase=0,bucket=0,read=0,sum:Float=0,first:Int?
        var block=[Float](repeating:0,count:4096)
        let media=audio.flatMap{AVAudioPCMBuffer(pcmFormat:$0.processingFormat,frameCapacity:2048)}
        while read<42*sourceRate {
            try Task.checkCancellation()
            let n:Int
            if let native {
                if ga_ended(native.handle) != 0 {break}
                ga_render(native.handle,&block,2048)
                if ga_failed(native.handle) != 0 {throw PlayerError.message("Ошибка анализа звука")}
                n=2048
            } else if let audio,let media {
                let remaining=audio.length-audio.framePosition
                if remaining<=0 {break}
                try audio.read(into:media,frameCount:AVAudioFrameCount(min(2048,remaining)))
                n=Int(media.frameLength)
                guard let channels=media.floatChannelData else {throw PlayerError.message("Неверный формат для анализа")}
                for i in 0..<n {
                    block[2*i]=channels[0][i]
                    block[2*i+1]=media.format.channelCount>1 ? channels[1][i]:channels[0][i]
                }
            } else {break}
            if n==0 {break}
            for i in 0..<n {
                let l=block[2*i],r=block[2*i+1]
                let mid=(l+r)*0.5
                sum += abs(mid)<0.2*max(abs(l),abs(r)) ? l:mid
                bucket+=1;phase+=analysisRate
                while phase>=sourceRate {
                    let v=bucket>0 ? sum/Float(bucket):mono.last ?? 0
                    mono.append(v)
                    if first==nil,abs(v)>0.004 {first=mono.count-1}
                    phase-=sourceRate;bucket=0;sum=0
                }
            }
            read+=n
            if let first,mono.count-first>=28*analysisRate {break}
        }
        guard let first else {throw PlayerError.message("В треке нет слышимого сигнала")}
        let start=max(0,first-analysisRate/5)
        return (Array(mono[start..<min(mono.count,start+28*analysisRate)]),native?.duration ?? audio.map{Int(Double($0.length)*1000/Double(sourceRate))})
    }

    private static func analyze(_ samples:[Float]) throws -> Theme {
        guard samples.count>analysisRate/5,
              let fft=vDSP_DFT_zop_CreateSetup(nil,vDSP_Length(size),.FORWARD) else {throw PlayerError.message("Слишком мало звука для аранжировки")}
        defer {vDSP_DFT_DestroySetup(fft)}
        let n=2048
        let window=(0..<n).map{Float(0.5-0.5*cos(2*Double.pi*Double($0)/Double(n-1)))}
        let frequencies=(29...93).map{frequency($0)*Double(size)/Double(analysisRate)}
        var input=[Float](repeating:0,count:size),zeros=input,re=input,im=input
        var prior=0.0,prevFlux=0.0
        var onsets=[Double](),notes=[Note](),bass=[Note]()
        let dt=Double(hop)/Double(analysisRate)
        func record(_ out:inout [Note],_ time:Double,_ pitch:Int,_ level:Double) {
            guard pitch>0 else {return}
            if let last=out.last,last.pitch==pitch,time-last.end<dt*1.5 {
                out[out.count-1].end=time+dt
            } else {out.append(Note(start:time,end:time+dt,pitch:pitch,level:level))}
        }
        for (row,from) in stride(from:0,to:samples.count,by:hop).enumerated() {
            if row%32==0 {try Task.checkCancellation()}
            var energy:Double=0
            for j in 0..<n {
                let k=from+j-n/2
                let x=k>=0 && k<samples.count ? samples[k]:0
                energy+=Double(x*x);input[j]=x*window[j]
            }
            let rms=sqrt(energy/Double(n)),time=Double(from)/Double(analysisRate)
            let flux=max(0,rms-prior)
            if flux>max(0.003,0.12*rms),prevFlux<=flux,(onsets.last.map{time-$0>0.12} ?? true) {onsets.append(time)}
            prior=rms;prevFlux=flux
            if rms<0.003 {continue}
            vDSP_DFT_Execute(fft,&input,&zeros,&re,&im)
            func magnitude(_ bin:Double)->Double {
                let i=Int(bin),a=bin-Double(i)
                if i<1 || i+1>=size/2 {return 0}
                return (1-a)*Double(hypotf(re[i],im[i]))+a*Double(hypotf(re[i+1],im[i+1]))
            }
            var lead=(-1,0.0),root=(-1,0.0)
            for (i,bin) in frequencies.enumerated() {
                let direct=magnitude(bin)
                let score=direct+0.34*magnitude(bin*2)+0.15*magnitude(bin*3)-0.45*magnitude(bin*0.5)
                let pitch=i+29
                if pitch>=45,score>lead.1 {lead=(pitch,score)}
                if pitch<=63,direct>root.1 {root=(pitch,direct)}
            }
            let level=min(1.0,max(0.45,rms*10))
            record(&notes,time,lead.0,level)
            record(&bass,time,root.0,level)
        }
        var filtered=notes.filter{$0.end-$0.start>=dt*1.5}
        if filtered.isEmpty {filtered=notes}
        guard !filtered.isEmpty else {throw PlayerError.message("Не удалось выделить мотив")}
        var best=(bpm:96.0,score:0.0)
        for bpm in 64...166 {
            let beat=60/Double(bpm)
            var score=0.0
            for i in onsets.indices where i>0 {
                for j in max(0,i-4)..<i {
                    let v=(onsets[i]-onsets[j])/beat
                    if v>0.22 && v<4 {score += max(0,1-abs(v*2-(v*2).rounded())*3)/sqrt(v)}
                }
            }
            score *= (1-abs(Double(bpm)-100)*0.0025)
            if score>best.score {best=(Double(bpm),score)}
        }
        return Theme(notes:filtered,bass:bass,bpm:best.bpm,density:Double(onsets.count)/(Double(samples.count)/Double(analysisRate)))
    }

    static func make(url:URL,track:Track,files:[DecoderFile],options:QueueOptions) throws -> ArrangedTrack {
        let (samples,originalDuration)=try decoded(url:url,track:track,files:files)
        let theme=try analyze(samples)
        let style=options.style == .auto ? (theme.density<1.5 ? .drone:theme.density<2.85 ? .dub:.downtempo):options.style
        var rng=RNG(state:options.variant ^ track.id.utf8.reduce(UInt64(14695981039346656037)) { ($0 ^ UInt64($1)) &* 1099511628211 })
        let beat=60/style.bpm, sourceBeat=60/theme.bpm
        let first=theme.notes[0].start
        let total=Int((Double(options.length.bars*4)*beat+2.4)*Double(rate))
        let grid=style == .drone ? 0.5:0.25
        var rows=[Int:Note]()
        for note in theme.notes where note.start>=first && note.start<first+sourceBeat*16 {
            let key=Int(((note.start-first)/(sourceBeat*grid)).rounded())
            if Double(key)*grid<16,rows[key].map({$0.end-$0.start < note.end-note.start}) ?? true {rows[key]=note}
        }
        func chord(_ bar:Int)->(Int,Int) {
            let a=first+Double(bar*4)*sourceBeat,b=a+4*sourceBeat
            var weights=[Double](repeating:0,count:12),lower=weights
            for note in theme.notes where note.start<b && note.end>a {weights[note.pitch%12]+=min(note.end,b)-max(note.start,a)}
            for note in theme.bass where note.start<b && note.end>a {lower[note.pitch%12]+=min(note.end,b)-max(note.start,a)}
            guard weights.contains(where:{$0>0}) || lower.contains(where:{$0>0}) else {
                return (theme.bass.first?.pitch ?? theme.notes[0].pitch,7)
            }
            let root=(0..<12).max{lower[$0]+0.35*weights[$0]<lower[$1]+0.35*weights[$1]} ?? theme.notes[0].pitch%12
            let minor=weights[(root+3)%12],major=weights[(root+4)%12]
            return (root,max(minor,major)<0.07 ? 7 : minor>major ? 3:4)
        }
        let progression=(0..<4).map(chord)
        var events=[Event](),waves=[Tone:[Float]]()
        func add(_ when:Double,_ voice:Voice,_ pitch:Int,_ duration:Double,_ gain:Double,_ pan:Double=0,_ send:Double=0) {
            let frames=max(1,Int(duration*Double(rate)))
            let key=Tone(voice:voice,pitch:pitch,duration:frames)
            let wave:[Float]
            if let cached=waves[key] {wave=cached} else {
                wave=synth(voice,pitch:pitch,frames:frames,rng:&rng);waves[key]=wave
            }
            func place(_ time:Double,_ g:Double,_ p:Double) {
                events.append(Event(start:Int(time*Double(rate)),wave:wave,left:Float(g*sqrt((1-p)*0.5)),right:Float(g*sqrt((1+p)*0.5))))
            }
            place(when*beat,gain,pan)
            if send>0 {
                place((when+0.75)*beat,gain*send*0.38,-pan-0.22)
                place((when+1.5)*beat,gain*send*0.20,pan+0.22)
            }
        }
        for bar in 0..<options.length.bars {
            try Task.checkCancellation()
            let base=Double(bar*4), (root,third)=progression[bar%4]
            for interval in Set([0,third,7]).sorted() {
                add(base,.pad,octave(root+interval,45,59),beat*4.4,style == .drone ? 0.095:0.060,interval==0 ? -0.32:0.32,0.24)
            }
            let low=octave(root,31,43)
            switch style {
            case .drone:
                if bar%2==0 {add(base,.bass,low,beat*5,0.09)}
                if bar%4==3 {add(base+3.5,.hat,0,0.36,0.025,0.25,0.4)}
            case .dub:
                for t in [0.0,bar%2==0 ? 2.0:2.5] {add(base+t,.bass,low,1.05,0.23)}
                for t in [0.5,2.5] {for interval in [0,third,7] {add(base+t,.stab,octave(root+interval,57,72),0.49,0.046,-0.22,0.95)}}
                add(base,.kick,0,0.32,0.20);add(base+2.5,.hat,0,0.17,0.058,-0.3,0.4)
                if bar%2==1 {add(base+3,.snare,0,0.25,0.13,0.18,0.52)}
            case .downtempo,.auto:
                let accent=bar%2==0 ? 1.75:3.25
                for t in [0,accent,2.5] {add(base+t,.bass,low,0.52,0.17);add(base+t,.kick,0,0.29,0.14)}
                for t in [1.0,3.0] {add(base+t,.snare,0,0.22,0.13,0.13,0.22)}
                for t in [0.5,1.5,2.25,3.5] {add(base+t,.hat,0,0.13,0.039+0.025*rng.next(),t<2 ? -0.3:0.3)}
            }
        }
        let sorted=rows.keys.sorted()
        for section in stride(from:0,to:options.length.bars*4,by:16) {
            for (index,key) in sorted.enumerated() {
                let at=Double(section)+Double(key)*grid
                guard at<Double(options.length.bars*4),let n=rows[key] else {continue}
                if section>0 && style == .drone && rng.next()<0.28 {continue}
                let shift=section>0 && index%6==Int(options.variant%6) ? -12:0
                let pitch=octave(n.pitch+shift,style == .drone ? 53:57,82)
                let voice:Voice=style == .drone ? .bell:style == .dub ? .stab:.keys
                let stretch=style == .drone ? 1.55:style == .dub ? 0.78:1.0
                let duration=beat*(0.34+min(3.5,max(grid*0.7,(n.end-n.start)/sourceBeat))*stretch)
                add(at,voice,pitch,duration,0.16*n.level,index%2==0 ? -0.2:0.2,style == .downtempo ? 0.46:0.82)
            }
        }
        let result=FileManager.default.temporaryDirectory.appendingPathComponent("gremlin-arrangement-"+UUID().uuidString+".caf")
        do {try write(events:events,frames:total,url:result)}
        catch {try? FileManager.default.removeItem(at:result);throw error}
        return ArrangedTrack(url:result,style:style,originalDuration:originalDuration)
    }

    private static func synth(_ voice:Voice,pitch:Int,frames:Int,rng:inout RNG)->[Float] {
        let f=frequency(pitch),tau=2*Double.pi
        let duration=Double(frames)/Double(rate)
        let phase=tau*rng.next()
        var low=0.0
        var pcm=[Float](repeating:0,count:frames)
        for i in pcm.indices {
            let t=Double(i)/Double(rate), p=tau*f*t,fade=min(1,(duration-t)/0.05)
            let y:Double
            switch voice {
            case .pad:
                y=(0.63*sin(p)+0.18*sin(p*1.004+phase)+0.12*sin(p*2.001)+0.07*sin(p*3+phase))
                    * min(1,t/0.7) * min(1,(duration-t)/1.0)
            case .bass:y=(0.82*sin(p)+0.18*sin(p*2))*min(1,t/0.018)*exp(-t*1.6)
            case .bell:y=(0.78*sin(p+0.6*exp(-t*3)*sin(p*2.01))+0.22*sin(p*3.003)*exp(-t*5))*min(1,t/0.035)*exp(-t*1.85)
            case .stab:y=(0.65*sin(p)+0.20*sin(p*2)+0.10*sin(p*3)+0.05*sin(p*5))*min(1,t/0.012)*exp(-t*5.6)
            case .keys:y=(0.70*sin(p+0.7*sin(p*2)*exp(-t*3))+0.2*sin(p*2.003))*min(1,t/0.018)*exp(-t*2.7)
            case .kick:y=sin(tau*(44*t+7.6*(1-exp(-t*18))))*exp(-t*18)*min(1,t/0.003)
            case .snare,.hat:
                let noise=rng.next()*2-1
                low+=0.23*(noise-low)
                y=(voice == .hat ? noise-low : 0.64*(noise-low)+0.36*sin(tau*168*t)*exp(-t*45))*exp(-t*(voice == .hat ? 34:19))
            }
            pcm[i]=Float(y*max(0,fade))
        }
        return pcm
    }

    private static func write(events:[Event],frames:Int,url:URL) throws {
        guard let format=AVAudioFormat(standardFormatWithSampleRate:Double(rate),channels:2),
              let pcm=AVAudioPCMBuffer(pcmFormat:format,frameCapacity:4096),let channels=pcm.floatChannelData else {
            throw PlayerError.message("Не удалось создать аранжировку")
        }
        var settings=format.settings
        settings[AVLinearPCMIsNonInterleaved]=false
        let file=try AVAudioFile(forWriting:url,settings:settings,commonFormat:.pcmFormatFloat32,interleaved:false)
        let sorted=events.sorted{$0.start<$1.start}
        var first=0,upper=0
        for start in stride(from:0,to:frames,by:4096) {
            try Task.checkCancellation()
            let count=min(4096,frames-start)
            pcm.frameLength=AVAudioFrameCount(count)
            let l=channels[0],r=channels[1]
            l.update(repeating:0,count:count);r.update(repeating:0,count:count)
            while first<sorted.count && sorted[first].start+sorted[first].wave.count<=start {first+=1}
            while upper<sorted.count && sorted[upper].start<start+count {upper+=1}
            for e in sorted[first..<upper] {
                let from=max(start,e.start),end=min(start+count,e.start+e.wave.count)
                if from>=end {continue}
                for p in from..<end {let v=e.wave[p-e.start];l[p-start]+=v*e.left;r[p-start]+=v*e.right}
            }
            for i in 0..<count {
                let tail=Float(min(1,Double(frames-start-i)/Double(rate)))
                l[i]=tanh(l[i]*1.4)*tail;r[i]=tanh(r[i]*1.4)*tail
            }
            try file.write(from:pcm)
        }
    }
}
