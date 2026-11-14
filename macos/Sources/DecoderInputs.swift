import Foundation
import CryptoKit

// The capsule is a cartridge and a bounded controller script, not audio PCM.
enum DecoderInputs {
    struct Prepared {
        var engine: String
        var url: URL
        var config: [Int32] = []
        var files: [DecoderFile] = []
        var temporary: URL?
    }

    private static let invalid = PlayerError.message("Повреждённый или неподдерживаемый контейнер музыки")
    private static func unpack(_ url: URL) throws -> Data {
        var length: Int32=0
        guard let bytes=ga_unpack_file(url.path,&length),length>0 else {throw invalid}
        return Data(bytesNoCopy:bytes,count:Int(length),deallocator:.free)
    }
    private static func word(_ d:Data,_ i:Int)->Int {
        Int(UInt32(d[i]) | UInt32(d[i+1])<<8 | UInt32(d[i+2])<<16 | UInt32(d[i+3])<<24)
    }
    private static func hex<S:Sequence>(_ s:S)->String where S.Element==UInt8 {
        s.map{String(format:"%02x",$0)}.joined()
    }
    private static func hash(_ d:Data)->String { hex(SHA256.hash(data:d)) }
    private static func crc(_ d:Data)->Int { d.withUnsafeBytes { Int(ga_crc32($0.bindMemory(to:UInt8.self).baseAddress,Int32(d.count))) } }
    private static func directory() throws -> URL {
        let url=FileManager.default.temporaryDirectory.appendingPathComponent("gremlin-audio-"+UUID().uuidString,isDirectory:true)
        try FileManager.default.createDirectory(at:url,withIntermediateDirectories:false,attributes:[.posixPermissions:0o700])
        return url
    }
    private static func safe(_ name:String)->Bool {
        !name.isEmpty && name.utf8.count<=1024 && !name.contains("\\") && !name.contains(":") &&
        !name.unicodeScalars.contains(where:{$0.value<32}) &&
        name.split(separator:"/",omittingEmptySubsequences:false).allSatisfy{ !$0.isEmpty && $0 != "." && $0 != ".." }
    }
    static func prepare(url:URL,track:Track,files:[DecoderFile]) throws -> Prepared {
        guard let engine=track.decoder else {throw invalid}
        var result=Prepared(engine:engine,url:url,files:files)
        if track.needsDependencies {
            if result.files.isEmpty {result.files=[DecoderFile(name:track.entryName ?? url.lastPathComponent,url:url)]}
            let names=result.files.map(\.name)
            guard names.count<=128,names.allSatisfy(safe),Set(names.map{$0.lowercased()}).count==names.count else {throw invalid}
            if engine=="ssf" {
                let dir=try directory()
                do {
                    for file in result.files {
                        let path=dir.appendingPathComponent(file.name)
                        try FileManager.default.createDirectory(at:path.deletingLastPathComponent(),withIntermediateDirectories:true)
                        try FileManager.default.copyItem(at:file.url,to:path)
                    }
                    result.url=dir.appendingPathComponent(names[0]);result.temporary=dir;result.files=[]
                } catch { try? FileManager.default.removeItem(at:dir);throw error }
            }
        } else if engine=="machine" || engine=="stm" {
            let data=try unpack(url)
            if engine=="stm",!data.starts(with:[83,84,77,26]) {result.engine="openmpt";return result}
            guard let key=Track.machine[track.decoderFormat] else {throw invalid}
            return try machine(key,data,track.subsong)
        } else if ["board:dcs","board:g0","board:k0"].contains(engine) {
            let (bytes,config)=try board(engine,unpack(url),track.subsong)
            let dir=try directory()
            do {
                let file=dir.appendingPathComponent("board.bin");try bytes.write(to:file)
                result.url=file;result.temporary=dir;result.config=config
            } catch {try? FileManager.default.removeItem(at:dir);throw error}
        }
        return result
    }

    private static let cores:[String:(name:String,rate:Int,revision:String)] = [
        "AGM":("gba",32768,"1c61b54208ca6266129d0f2394c04bd8c44f98c5"),
        "GBM":("gb",32768,"77f2567d718b8973d02caa2daa8dce4953faf811"),
        "IVM":("iv",44100,"ef3e0fe322bec62a7f916c0bb0834c08c348d0b4"),
        "JGM":("jag",48000,"34696082dbc0448caf23aa4ecb5ed40817cb8da0"),
        "MDM":("md",44100,"1890c2932234c9d30f4cd3851d02228baae8f09e"),
        "STM":("st",31400,"7d1361e407e63f29e52892655069e5fb4096e691"),
        "SVM":("sv",44100,"227c5f6f3ce74d32e9002ce24c1420288559a860"),
        "VXM":("vx",187500,"dccff5402a16d1ea7c112095a8e42632bf149506"),
        "WSM":("ws",44100,"4b01295838ea89e3f1355bbe4cb5cf98aa6108cd"),
        "NFM":("nf",48000,"e0f3086a8104f8fbbe49a2615b10401504a9533f"),
        "A5M":("a5",44100,"40c6f2f1ad4a3145b328d5baaf010fae6c7e752b")
    ]
    private static func machine(_ key:String,_ d:Data,_ song:Int) throws -> Prepared {
        guard let core=cores[key],d.count>=16,d.starts(with:Array(key.utf8)+[26]) else {throw invalid}
        let rom:Data,bios:Data,frames:Int,profile:Int,fds:Bool,pre:[Int32],play:[Int32]
        let mask=key=="JGM" ? 0x3ffffff : key=="IVM" ? 0xfffff : 0xffff
        func verified(_ pairs:[(Int,Int)],_ expected:Int?=nil) throws -> [Int32] {
            guard (1...1024).contains(pairs.count),pairs.allSatisfy({(1...60000).contains($0.0) && $0.1>=0 && $0.1 & ~mask == 0}) else {throw invalid}
            let total=pairs.reduce(0){$0+$1.0}
            guard total<=60000,expected==nil || total==expected else {throw invalid}
            return pairs.flatMap{[Int32($0.0),Int32($0.1)]}
        }
        if key=="NFM" || key=="A5M" {
            let header=word(d,4),size=word(d,8),biosSize=word(d,12)
            guard (1...65536).contains(header),(1...16777216).contains(size),biosSize<=8192,
                  16+header+size+biosSize==d.count,
                  let meta=try JSONSerialization.jsonObject(with:d.subdata(in:16..<(16+header))) as? JSON else {throw invalid}
            fds=meta.bool("fds");profile=meta.int("profile")
            let tracks=meta.rows("tracks")
            guard meta.int("version")==1,meta.string("core_commit")==core.revision,meta.int("source_rate")==core.rate,
                  biosSize==(key=="A5M" ? 2048 : fds ? 8192 : 0),tracks.count<=64,tracks.indices.contains(song) else {throw invalid}
            rom=d.subdata(in:(16+header)..<(16+header+size));bios=d.suffix(biosSize)
            guard hash(rom)==meta.string("rom_sha256"),hash(bios)==meta.string("bios_sha256") else {throw invalid}
            let row=tracks[song];frames=row.int("audio_frames")
            pre=try verified(row.rows("prefix").map{($0.int("frames"),$0.int("mask",-1))})
            play=try verified(row.rows("play").map{($0.int("frames"),$0.int("mask",-1))})
        } else {
            guard d.count>=128 else {throw invalid}
            profile=word(d,8);fds=false;bios=Data()
            let tracks=word(d,24),scripts=word(d,36),scriptSize=word(d,40),offset=word(d,44),romSize=word(d,20)
            guard word(d,4)==1,profile>=1,word(d,12)==core.rate,word(d,28)==128,word(d,32)==64,
                  word(d,48)==d.count,(1...64).contains(tracks),song>=0,song<tracks,
                  scripts==128+tracks*64,(8...262144).contains(scriptSize),scriptSize%8==0,
                  offset==scripts+scriptSize,(1...16777216).contains(romSize),offset+romSize==d.count,
                  core.revision==hex(d[96..<116]),crc(d.subdata(in:128..<scripts))==word(d,52),
                  crc(d.subdata(in:scripts..<offset))==word(d,56) else {throw invalid}
            rom=d.suffix(romSize)
            guard crc(rom)==word(d,60),hash(rom)==hex(d[64..<96]) else {throw invalid}
            let row=128+64*song
            guard word(d,row)==song else {throw invalid}
            frames=word(d,row+28)
            func events(_ i:Int) throws -> [Int32] {
                let at=word(d,i),count=word(d,i+4),total=word(d,i+8)
                guard at%8==0,(1...1024).contains(count),at+count*8<=scriptSize else {throw invalid}
                return try verified((0..<count).map { n in let p=scripts+at+n*8;return (word(d,p),word(d,p+4)) },total)
            }
            pre=try events(row+4);play=try events(row+16)
        }
        guard frames>0,frames<=core.rate*1200 else {throw invalid}
        let dir=try directory()
        do {
            let path=dir.appendingPathComponent("capsule.rom");try rom.write(to:path)
            if key=="IVM" {
                let end=rom.count-10240
                guard end>0 else {throw invalid}
                try rom.prefix(end).write(to:dir.appendingPathComponent("original.bin"))
                try rom.suffix(10240).prefix(8192).write(to:dir.appendingPathComponent("exec.bin"))
                try rom.suffix(2048).write(to:dir.appendingPathComponent("grom.bin"))
            } else if !bios.isEmpty {
                try bios.write(to:dir.appendingPathComponent(key=="NFM" ? "disksys.rom":"5200.rom"))
            }
            let name=key=="WSM" && profile==4 ? "ws-headroom" : core.name
            return Prepared(engine:"machine:"+name,url:path,config:[Int32(core.rate),Int32(frames),fds ? 1:0,Int32(pre.count)]+pre+play,
                            files:key=="IVM" || !bios.isEmpty ? [DecoderFile(name:"",url:dir)]:[],temporary:dir)
        } catch {try? FileManager.default.removeItem(at:dir);throw error}
    }

    private static func board(_ key:String,_ d:Data,_ song:Int) throws -> (Data,[Int32]) {
        let konami=key=="board:k0", dcs=key=="board:dcs", header=konami ? 24:16
        let magic=konami ? "K0M" : dcs ? "DCM" : "G0M"
        guard d.count>=header,d.starts(with:Array(magic.utf8)+[26]) else {throw invalid}
        let json=word(d,4),rom=word(d,8),vgm=konami ? word(d,12):0,prom=konami ? word(d,16):0,events=word(d,konami ? 20:12)
        guard (1...65536).contains(json),(4096...8388608).contains(rom),vgm<=1000000,
              !konami || (vgm>=64 && prom==512),events<=50000,
              dcs ? events==0 : events>0 && song==0,
              header+json+rom+vgm+prom+8*events==d.count,
              let m=try JSONSerialization.jsonObject(with:d.subdata(in:header..<(header+json))) as? JSON,
              m.int("version")==1,m.int("source_rate")==((dcs) ? 31250:48000) else {throw invalid}
        let start=header+json,end=start+rom,firstEvent=end+vgm+prom
        guard hash(d.subdata(in:start..<end))==m.string(dcs ? "banks_sha256":"rom_sha256") else {throw invalid}
        if dcs {
            let rows=m.rows("tracks"),chips=m.int("chips"),bank=m.int("chip_bytes"),volume=m.int("volume",-1)
            guard rows.indices.contains(song),(1...8).contains(chips),[524288,1048576].contains(bank),chips*bank==rom,(0...255).contains(volume) else {throw invalid}
            let cmd=rows[song].int("command",-1),frames=rows[song].int("audio_frames")
            guard (0...0x54ff).contains(cmd),(1...37500000).contains(frames) else {throw invalid}
            return (d.suffix(from:start),[chips,bank,cmd,volume,frames].map(Int32.init))
        }
        let from=m.int("start_sample",-1),frames=m.int("audio_frames"),circuit=konami ? 0:m.int("circuit",-1)
        guard (0...4800000).contains(from),(1...57600000).contains(frames),(0...1).contains(circuit),
              hash(d.suffix(from:firstEvent))==m.string("events_sha256") else {throw invalid}
        if konami {
            guard (m["K_gain"] as? NSNumber)?.doubleValue==0.35,
                  hash(d.subdata(in:end..<(end+vgm)))==m.string("vgm_sha256"),
                  hash(d.subdata(in:(end+vgm)..<firstEvent))==m.string("prom_sha256") else {throw invalid}
        }
        var previous=0
        for i in 0..<events {
            let p=firstEvent+8*i,time=word(d,p),port=Int(d[p+4]),word16=Int(d[p+6])+256*Int(d[p+7])
            guard time>=previous,time<=from+frames,
                  konami ? (port<=5 && word16 <= (port<2 ? 4095:port<4 ? 0:255) && d[p+5]==0)
                         : (port<=2 && (port==0 || d[p+5]<=1) && word16==0) else {throw invalid}
            previous=time
        }
        return (d.suffix(from:end),[circuit,from,frames,vgm].map(Int32.init))
    }
}
