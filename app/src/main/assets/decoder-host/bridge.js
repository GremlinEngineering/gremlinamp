import {decoderFor, virtualPath} from '/site/decoders.js';

let worker;
let epoch;
const sendError = (id, error) => {
    const message = String(error?.message || error);
    AndroidAudio.error(id, message === '[object Object]'
        ? 'Ошибка музыкального декодера. Проверьте файл и общие библиотеки.' : message);
};

window.gremlinLoad = async function (id, serialized) {
    epoch = id;
    if (worker) worker.terminate();
    worker = null;
    try {
        const input = JSON.parse(serialized);
        const buffers = [];
        for (let index = 0; index < input.paths.length; index++) {
            const response = await fetch('/input/' + index, {cache: 'no-store'});
            if (!response.ok) throw Error('Не удалось открыть проверенный музыкальный файл');
            buffers.push(await response.arrayBuffer());
        }
        if (id !== epoch) return;
        const [bytes, ...libraries] = buffers;
        const route = decoderFor(input.track, bytes);
        const dependencies = libraries.map((data, index) => ({
            path: virtualPath(input.paths[index + 1]),
            bytes: data
        }));
        const source = new Worker('/site/' + route.worker);
        worker = source;
        source.onerror = event => {
            if (worker === source) sendError(id, event.message || 'Ошибка WASM-декодера');
        };
        source.onmessage = ({data}) => {
            if (worker !== source) return;
            if (data.type === 'error') sendError(id, data.message);
            else if (data.type === 'ready') AndroidAudio.loaded(id);
            else if (data.type === 'pcm') {
                const pcm = new Float32Array(data.pcm);
                const output = new Uint8Array(pcm.length * 2);
                for (let index = 0; index < pcm.length; index++) {
                    const value = Math.max(-32768, Math.min(32767, Math.round(pcm[index] * input.gain * 32768)));
                    output[index * 2] = value & 255;
                    output[index * 2 + 1] = value >> 8 & 255;
                }
                let dataString = '';
                for (let offset = 0; offset < output.length; offset += 4096)
                    dataString += String.fromCharCode(...output.subarray(offset, offset + 4096));
                AndroidAudio.pcm(id, btoa(dataString));
            }
        };
        source.postMessage({
            type: 'load',
            bytes,
            decoder: route.id,
            format: route.format,
            filename: virtualPath(input.paths[0]),
            dependencies,
            subsong: input.track.subsong,
            sampleRate: input.rate,
            atari: input.track.atari ?? input.track.playback?.atari,
            native_preroll_vgm_samples: input.track.native_preroll_vgm_samples
                ?? input.track.playback?.native_preroll_vgm_samples,
            renderMs: input.renderMs
        }, [bytes, ...libraries]);
    } catch (error) {
        if (id === epoch) sendError(id, error);
    }
};

window.gremlinRender = function (id, frames) {
    if (id === epoch && worker) worker.postMessage({type: 'render', frames});
};

AndroidAudio.ready();
