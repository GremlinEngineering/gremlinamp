const chunkFrames = 4096;
const queueDepth = 4;

class DecoderPlayback {
    constructor(viewWindow, onChange, {position = 0, progress = null, paused = false, context = null} = {}) {
        this.window = viewWindow;
        this.onChange = onChange;
        this.state = 'loading';
        this.sources = new Set();
        this.clock = 0;
        this.target = position;
        this.progress = progress;
        this.revision = 0;
        this.startPaused = paused;
        this.context = context ?? new viewWindow.AudioContext();
        this.ownsContext = context === null;
        this.gain = this.context.createGain();
        this.gain.connect(this.context.destination);
        this.activation = this.context.resume().then(() => {
            if (this.startPaused && this.state !== 'stopped') return this.context.suspend();
        });
        // The first decoder response may take some time.
        this.activation.catch(() => {});
    }

    async start(route, message, duration) {
        if (this.state === 'stopped') return;
        try {
            await this.activation;
            if (this.state === 'stopped') return;
            this.duration = duration;
            this.decoder = startDecoder(this.window, route.worker, decoderAssetsByWorker[route.worker]);
            this.decoder.worker.onerror = event => this.fail(new Error(event.message || 'Worker error'));
            this.decoder.worker.onmessage = ({data}) => {
                try {
                    if (data.revision != null && data.revision !== this.revision) return;
                    if (data.type === 'error') this.fail(new Error(data.message));
                    if (data.type === 'ready') this.ready(data);
                    if (data.type === 'positioned') this.bufferFrom(Math.round(data.seconds * this.context.sampleRate));
                    if (data.type === 'pcm') this.receive(data);
                } catch (error) { this.fail(error); }
            };
            this.timeout = this.window.setTimeout(() => this.fail(new Error('Decoder timed out')), 120000);
            // Keep the vault buffers available for restarting the decoder at a new position.
            this.decoder.worker.postMessage({...message, type: 'load', sampleRate: this.context.sampleRate});
        } catch (error) { this.fail(error); }
    }

    ready(metadata) {
        this.metadata = metadata;
        if (Number.isFinite(metadata.duration) && metadata.duration > 0) this.duration ??= metadata.duration;
        if (this.progress != null && this.duration != null) this.target = this.progress * this.duration;
        this.target = Math.min(this.target, this.duration ?? Infinity);
        if (this.target === 0) {
            this.bufferFrom(0);
        } else if (metadata.seek) {
            this.decoder.worker.postMessage({type: 'seek', seconds: this.target, revision: this.revision});
        } else {
            this.framesToSkip = Math.round(this.target * this.context.sampleRate);
            this.skip();
        }
        this.onChange();
    }

    skip() {
        if (this.framesToSkip) {
            this.decoder.worker.postMessage({type: 'render', frames: Math.min(16384, this.framesToSkip)});
        } else {
            this.bufferFrom(Math.round(this.target * this.context.sampleRate));
        }
    }

    bufferFrom(frame) {
        this.clock = frame;
        for (let index = 0; index < queueDepth; ++index) this.request();
    }

    receive(message) {
        if (this.framesToSkip) {
            const frames = message.pcm.byteLength / (Float32Array.BYTES_PER_ELEMENT * 2);
            if (!Number.isInteger(frames) || frames <= 0 || frames > this.framesToSkip || message.ended)
                throw new PlayerError('seekError');
            this.framesToSkip -= frames;
            this.skip();
        } else {
            this.enqueue(message);
        }
    }

    request() {
        if (this.decoder && !this.finished) this.decoder.worker.postMessage({type: 'render', frames: chunkFrames, revision: this.revision});
    }

    seek(seconds) {
        if (!this.metadata?.liveSeek || !['playing', 'paused'].includes(this.state)) return false;
        this.target = seconds;
        this.finished = false;
        this.clearSources();
        this.decoder.worker.postMessage({type: 'seek', seconds, revision: ++this.revision});
        this.onChange();
        return true;
    }

    enqueue(message) {
        if (this.finished || this.state === 'stopped' || this.state === 'error') return;
        const samples = new Float32Array(message.pcm);
        if (samples.length % 2 || samples.length > chunkFrames * 2) throw Error('Некорректный звуковой блок');
        const context = this.context;
        const remaining = this.duration == null ? Infinity :
            Math.max(0, Math.ceil(this.duration * context.sampleRate - this.clock));
        const length = Math.min(samples.length / 2, remaining);
        this.finished = message.ended === true || remaining <= length;
        if (length > 0) {
            const buffer = context.createBuffer(2, length, context.sampleRate);
            const left = buffer.getChannelData(0), right = buffer.getChannelData(1);
            for (let i = 0; i < length; ++i) {
                left[i] = samples[i * 2];
                right[i] = samples[i * 2 + 1];
            }
            const source = context.createBufferSource();
            source.buffer = buffer;
            source.connect(this.gain);
            this.nextTime = Math.max(this.nextTime ?? context.currentTime + 0.06, context.currentTime + 0.01);
            this.spans ??= [];
            this.spans.push({start: this.nextTime, offset: this.clock / context.sampleRate, length: buffer.duration});
            source.start(this.nextTime);
            this.clock += length;
            this.nextTime += buffer.duration;
            this.sources.add(source);
            source.onended = () => {
                this.sources.delete(source);
                source.disconnect();
                if (this.finished) {
                    if (!this.sources.size) this.end();
                } else this.request();
            };
        }
        if (this.state === 'loading' && length) {
            this.window.clearTimeout(this.timeout);
            this.state = this.startPaused ? 'paused' : 'playing';
            this.timer = this.window.setInterval(() => this.onChange(), 200);
            this.onChange();
        }
        if (this.finished && !this.sources.size) this.end();
    }

    get seconds() {
        if (this.state === 'ended') return this.clock / this.context.sampleRate;
        if (!this.spans?.length) return this.target;
        const now = this.context.currentTime;
        while (this.spans.length > 1 && this.spans[1].start <= now) this.spans.shift();
        const span = this.spans[0];
        return span.offset + Math.max(0, Math.min(span.length, now - span.start));
    }

    async toggle() {
        if (this.transition || !['playing', 'paused'].includes(this.state)) return;
        const pause = this.state === 'playing';
        this.transition = true;
        try {
            if (pause) await this.context.suspend();
            else await this.context.resume();
            if (['playing', 'paused'].includes(this.state)) this.state = pause ? 'paused' : 'playing';
        } catch (error) { this.fail(error); }
        finally { this.transition = false; this.onChange(); }
    }

    volume(value) { this.gain.gain.value = value; }

    end() {
        this.state = 'ended';
        this.disposeAudio();
        this.onChange();
    }

    fail(error) {
        if (['stopped', 'ended', 'error'].includes(this.state)) return;
        this.error = error;
        this.state = 'error';
        this.disposeAudio();
        this.onChange();
    }

    stop() {
        this.state = 'stopped';
        this.disposeAudio();
    }

    clearSources() {
        for (const source of this.sources) {
            source.onended = null;
            source.stop();
            source.disconnect();
        }
        this.sources.clear();
        this.spans = [];
        this.nextTime = undefined;
    }

    disposeAudio() {
        this.window.clearTimeout(this.timeout);
        this.window.clearInterval(this.timer);
        this.decoder?.dispose();
        this.decoder = null;
        this.clearSources();
        this.gain.disconnect();
        if (this.ownsContext && this.context.state !== 'closed') void this.context.close().catch(() => {});
    }
}
