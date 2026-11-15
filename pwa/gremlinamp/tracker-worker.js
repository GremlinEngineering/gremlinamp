'use strict';
importScripts('tracker-core.js');

const originalRate = 44100;
let core, tracker, ready, left, right, rate, phase = 0;
let carryLeft = [], carryRight = [];
const maximum = 16384;
const fail = error => postMessage({type: 'error', message: error.message || String(error)});

async function load(input) {
    core = await GremlinTracker();
    const resource = name => new URL('../trackers/' + name, self.location.href);
    const response = async name => {
        const result = await fetch(resource(name));
        if (!result.ok) throw new Error('Ресурс трекера недоступен');
        return result;
    };
    const profiles = {4: 'voyager', 5: 'stars', 6: 'solar'};
    const [profile, pcm] = await Promise.all([
        profiles[input.mode] ? (await response(profiles[input.mode] + '.json')).json() : [],
        input.mode === 4 ? (await response('voyager-plasma.pcm')).arrayBuffer() : new ArrayBuffer(0)
    ]);
    const p = core._malloc(profile.length || 1), s = core._malloc(pcm.byteLength || 1);
    if (!p || !s) throw new Error('Недостаточно памяти для трекера');
    try {
        core.HEAPU8.set(profile, p);
        core.HEAPU8.set(new Uint8Array(pcm), s);
        tracker = core._gwt_create(input.mode, input.seed, p, profile.length, s, pcm.byteLength / 2);
    } finally {
        core._free(p);
        core._free(s);
    }
    if (!tracker) throw new Error('Не удалось запустить трекер');
    if (!Number.isFinite(input.sampleRate) || input.sampleRate < 8000 || input.sampleRate > 192000)
        throw new Error('Частота аудио не поддерживается');
    rate = input.sampleRate;
    const size = Math.ceil(maximum * Math.max(1, originalRate / rate)) + 2;
    left = core._malloc(size * 4);
    right = core._malloc(size * 4);
    if (!left || !right) throw new Error('Недостаточно памяти для аудиобуфера');
    update(input);
    postMessage({type: 'ready', bpm: core._gt_bpm(tracker)});
}

function update({options, minute, weather, orbits, street}) {
    if (options) core._gt_options(tracker, +options.clock, +options.accents, +options.noise, +options.plasma);
    if (Number.isInteger(minute)) core._gt_clock(tracker, minute);
    if (weather) core._gt_weather(tracker, ...weather);
    if (orbits) core._gwt_orbit(tracker, ...orbits);
    if (street?.length) {
        const ptr = core._malloc(street.length * 4);
        if (!ptr) return;
        core.HEAPF32.set(street, ptr >>> 2);
        core._gt_street(tracker, ptr, street.length);
        core._free(ptr);
    }
}

function render(frames) {
    if (!tracker || !Number.isInteger(frames) || frames < 1 || frames > maximum) return;
    const factor = originalRate / rate;
    const next = phase + frames * factor;
    const consumed = Math.floor(next);
    const size = Math.max(consumed + 1, Math.floor(phase + (frames - 1) * factor) + 2);
    const carry = carryLeft.length;
    const inputCount = size - carry;
    if (inputCount) core._gt_render(tracker, left, right, inputCount);
    const heap = core.HEAPF32, l = left >>> 2, r = right >>> 2;
    const pcm = new Float32Array(2 * frames);
    const value = (offset, previous, index) => index < carry ? previous[index] : heap[offset + index - carry];
    for (let i = 0; i < frames; ++i) {
        const pos = phase + i * factor, index = Math.floor(pos), mix = pos - index;
        const a = value(l, carryLeft, index), b = value(r, carryRight, index);
        pcm[i * 2] = a + (value(l, carryLeft, index + 1) - a) * mix;
        pcm[i * 2 + 1] = b + (value(r, carryRight, index + 1) - b) * mix;
    }
    carryLeft = Array.from({length: size - consumed}, (_, index) => value(l, carryLeft, consumed + index));
    carryRight = Array.from({length: size - consumed}, (_, index) => value(r, carryRight, consumed + index));
    phase = next % 1;
    postMessage({type: 'pcm', pcm: pcm.buffer}, [pcm.buffer]);
}

onmessage = ({data}) => {
    if (data.type === 'load') ready = load(data).catch(error => { fail(error); return false; });
    else ready?.then(result => {
        if (result === false) return;
        if (data.type === 'render') render(data.frames);
        if (data.type === 'settings') update(data);
    }).catch(fail);
};
