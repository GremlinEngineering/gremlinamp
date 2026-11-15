const analysisRate = 11025;
const sourceRate = 44100;
const renderBlock = 16384;
const inputWindow = 28 * analysisRate;
const maximum = 42 * analysisRate;

function collect() {
    const samples = new Float32Array(maximum);
    let first = -1, size = 0;
    return {
        append(value) {
            if (first === -1 && Math.abs(value) > 0.004) first = size;
            samples[size++] = value;
            return size >= maximum || first !== -1 && size - first >= inputWindow;
        },
        finish() {
            if (first === -1) throw new Error('В треке нет слышимого сигнала');
            const start = Math.max(0, first - analysisRate / 5);
            return samples.slice(start, Math.min(size, start + inputWindow));
        }
    };
}

function centered(left, right) {
    const mid = (left + right) * 0.5;
    return Math.abs(mid) < 0.2 * Math.max(Math.abs(left), Math.abs(right)) ? left : mid;
}

export async function readMedia(bytes, signal) {
    const context = new OfflineAudioContext(2, 1, analysisRate);
    const audio = await context.decodeAudioData(bytes);
    if (signal.aborted) throw signal.reason;
    const left = audio.getChannelData(0), right = audio.getChannelData(Math.min(1, audio.numberOfChannels - 1));
    const input = collect();
    for (let i = 0; i < left.length; ++i) {
        if (input.append(centered(left[i], right[i]))) break;
    }
    return input.finish();
}

export function readDecoder(route, message, seconds, signal) {
    return new Promise((resolve, reject) => {
        if (signal.aborted) { reject(signal.reason); return; }
        const worker = new Worker(new URL('./runtime/' + route.worker, import.meta.url));
        const input = collect();
        const deadline = setTimeout(() => finish(new Error('Декодер не ответил для аранжировки')), 20000);
        const abort = () => finish(signal.reason);
        let limit = Math.round((seconds ?? 42) * sourceRate), requested = 0, received = 0;
        let sum = 0, pending = 0;
        function finish(error) {
            clearTimeout(deadline);
            signal.removeEventListener('abort', abort);
            worker.terminate();
            if (error) reject(error);
            else {
                try { resolve(input.finish()); }
                catch (error) { reject(error); }
            }
        }
        function render() {
            requested = Math.min(renderBlock, limit - received);
            if (requested > 0) worker.postMessage({type: 'render', frames: requested});
            else finish();
        }
        signal.addEventListener('abort', abort, {once: true});
        worker.onerror = event => finish(new Error(event.message || 'Ошибка декодера'));
        worker.onmessage = ({data}) => {
            try {
                if (data.type === 'ready') {
                    const duration = seconds ?? data.duration;
                    if (Number.isFinite(duration) && duration > 0) limit = Math.round(Math.min(42, duration) * sourceRate);
                    render();
                } else if (data.type === 'error') {
                    finish(new Error(data.message));
                } else if (data.type === 'pcm') {
                    const pcm = new Float32Array(data.pcm);
                    if (pcm.length % 2 || pcm.length > requested * 2) throw new Error('Некорректный звуковой блок');
                    for (let i = 0; i < pcm.length; i += 2) {
                        sum += centered(pcm[i], pcm[i + 1]);
                        if (++pending === sourceRate / analysisRate) {
                            if (input.append(sum / pending)) { finish(); return; }
                            sum = pending = 0;
                        }
                    }
                    received += pcm.length / 2;
                    if (data.ended || !pcm.length) finish();
                    else render();
                }
            } catch (error) { finish(error); }
        };
        try {
            worker.postMessage({...message, type: 'load', sampleRate: sourceRate},
                [message.bytes, ...message.dependencies.map(file => file.bytes)]);
        } catch (error) { finish(error); }
    });
}
