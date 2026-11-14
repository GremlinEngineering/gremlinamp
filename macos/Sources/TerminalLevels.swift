import AVFoundation

// The mixer tap publishes a small snapshot. The UI never reads AVAudio buffers.
final class TerminalLevels: @unchecked Sendable {
    private let lock = NSLock()
    private let bands: [Double] = [45, 62, 85, 115, 155, 210, 290, 400, 550, 750, 1000, 1400, 1900, 2600, 3600, 4900, 6700, 9200]
    private var values = [Float](repeating: 0, count: 18)

    func accept(_ buffer: AVAudioPCMBuffer) {
        guard let samples = buffer.floatChannelData?[0], buffer.frameLength > 0, lock.try() else { return }
        defer { lock.unlock() }
        let count = Int(buffer.frameLength)
        for (i, frequency) in bands.enumerated() {
            let factor = Float(2 * cos(2 * .pi * frequency / buffer.format.sampleRate))
            var last: Float = 0
            var previous: Float = 0
            for j in 0..<count {
                let value = samples[j] + factor * last - previous
                previous = last
                last = value
            }
            let energy = sqrt(max(0, last * last + previous * previous - factor * last * previous))
            let db = 20 * log10(max(0.00001, energy / Float(count)))
            values[i] = max(values[i] * 0.80, min(1, max(0, (db + 66) / 51)))
        }
    }

    func read() -> [Float] {
        lock.lock()
        defer { lock.unlock() }
        return values
    }

    func reset() {
        lock.lock()
        for i in values.indices { values[i] = 0 }
        lock.unlock()
    }
}
