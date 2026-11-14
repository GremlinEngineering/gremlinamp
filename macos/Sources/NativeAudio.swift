import Foundation
import AVFoundation

enum SystemAudio {
    static func makePlayer(url: URL, volume: Float) throws -> AVAudioPlayer {
        let player = try AVAudioPlayer(contentsOf: url)
        player.volume = volume.isFinite ? max(0, min(volume, 1)) : 0.8
        guard player.prepareToPlay(), player.duration.isFinite,
              player.duration > 0, player.duration < Double(Int.max / 1000) else {
            throw PlayerError.message("Не удалось подготовить аудиофайл")
        }
        return player
    }
}

// Deliberately outside MainActor: AVAudioSourceNode invokes its block on an
// audio thread. That block owns this stream for its entire rendering lifetime.
final class NativeStream: @unchecked Sendable {
    let handle: OpaquePointer
    let format: AVAudioFormat

    private let temporary: URL?
    var duration: Int? { let value=ga_duration(handle);return value>0 ? Int(value):nil }
    var position: Int { Int(ga_position(handle)) }

    init(url: URL, track: Track, files: [DecoderFile] = []) throws {
        let p=try DecoderInputs.prepare(url:url,track:track,files:files)
        var error = [CChar](repeating: 0, count: 512)
        let paths=p.files.map{strdup($0.url.path)},names=p.files.map{strdup($0.name)}
        defer { paths.forEach {free($0)};names.forEach {free($0)} }
        let pathPointers=paths.map{$0.map{UnsafePointer($0)}},namePointers=names.map{$0.map{UnsafePointer($0)}}
        let opened=ga_open_decoder(p.engine,p.url.path,Int32(clamping:track.subsong),
            Int64(track.knownMS ?? -1),Int64(max(0,track.fadeMS)),Int32(clamping:track.preroll ?? 0),
            Int32(clamping:track.region ?? 0),pathPointers,namePointers,Int32(paths.count),
            p.config,Int32(p.config.count),&error,Int32(error.count))
        guard let opened else {
            if let dir=p.temporary {try? FileManager.default.removeItem(at:dir)}
            throw PlayerError.message(String(cString:error))
        }
        guard let pcm=AVAudioFormat(standardFormatWithSampleRate:Double(ga_rate(opened)),channels:2) else {
            ga_close(opened)
            if let dir=p.temporary {try? FileManager.default.removeItem(at:dir)}
            throw PlayerError.message("Неверный аудиоформат")
        }
        handle=opened;format=pcm;temporary=p.temporary
    }

    deinit { ga_close(handle);if let temporary {try? FileManager.default.removeItem(at:temporary)} }

    func makeNode() -> AVAudioSourceNode {
        let stream = self
        let render: AVAudioSourceNodeRenderBlock = { @Sendable isSilence, _, frames, output in
            isSilence.pointee = true
            let buffers = UnsafeMutableAudioBufferListPointer(output)
            let frameCount = Int(frames)
            guard frameCount > 0 else { return noErr }
            guard buffers.count == 2,
                  buffers[0].mNumberChannels == 1,
                  buffers[1].mNumberChannels == 1,
                  let left = buffers[0].mData,
                  let right = buffers[1].mData,
                  frameCount <= Int(Int32.max),
                  Int(buffers[0].mDataByteSize) / MemoryLayout<Float>.size >= frameCount,
                  Int(buffers[1].mDataByteSize) / MemoryLayout<Float>.size >= frameCount else {
                for buffer in buffers {
                    if let data = buffer.mData { memset(data, 0, Int(buffer.mDataByteSize)) }
                }
                return -50
            }
            let signal = ga_render_planar(
                stream.handle, left.assumingMemoryBound(to: Float.self), Int32(frameCount),
                right.assumingMemoryBound(to: Float.self), Int32(frameCount), Int32(frameCount)
            )
            isSilence.pointee = ObjCBool(signal <= 0)
            return signal < 0 ? -50 : noErr
        }
        return AVAudioSourceNode(format: format, renderBlock: render)
    }

    // The offline test uses this same graph and render block, with no audio device.
    func makeEngine(volume: Float, offlineRate: Double? = nil) throws -> (AVAudioEngine, AVAudioSourceNode) {
        try AudioGraph.make(node: makeNode(), format: format, volume: volume, offlineRate: offlineRate)
    }
}

enum AudioGraph {
    static func make(node: AVAudioSourceNode, format: AVAudioFormat, volume: Float,
                     offlineRate: Double?) throws -> (AVAudioEngine, AVAudioSourceNode) {
        let audio = AVAudioEngine()
        let mixer = audio.mainMixerNode
        audio.attach(node)
        audio.connect(node, to: mixer, format: format)
        mixer.outputVolume = volume.isFinite ? max(0, min(volume, 1)) : 0.8
        if let rate = offlineRate {
            guard let output = AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2) else {
                throw PlayerError.message("Неверный формат проверки звука")
            }
            try audio.enableManualRenderingMode(.offline, format: output, maximumFrameCount: 4096)
        } else {
            let output = audio.outputNode
            let deviceFormat = output.inputFormat(forBus: 0)
            guard deviceFormat.sampleRate.isFinite, deviceFormat.sampleRate > 0,
                  deviceFormat.channelCount > 0 else {
                throw PlayerError.message("Звуковой выход недоступен")
            }
            audio.connect(mixer, to: output, format: deviceFormat)
        }
        audio.prepare()
        return (audio, node)
    }
}
