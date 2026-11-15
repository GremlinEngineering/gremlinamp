// Port of the theme analysis and arrangement in GremlinAmp_Mac/Sources/QueueArrangement.swift.
const analysisRate = 11025;
const fftSize = 4096;
const windowSize = 2048;
const hop = 441;
const tau = 2 * Math.PI;
const bpmByStyle = {drone: 52, dub: 68, downtempo: 82};
const frequency = pitch => 440 * 2 ** ((pitch - 69) / 12);

function spectrum() {
    const re = new Float64Array(fftSize), im = new Float64Array(fftSize);
    const magnitudes = new Float64Array(fftSize / 2);
    const order = new Uint16Array(fftSize);
    const cos = new Float64Array(fftSize / 2), sin = new Float64Array(fftSize / 2);
    for (let i = 1; i < fftSize; ++i) {
        order[i] = (order[i >> 1] >> 1) | ((i & 1) * fftSize / 2);
    }
    for (let i = 0; i < cos.length; ++i) {
        cos[i] = Math.cos(-tau * i / fftSize);
        sin[i] = Math.sin(-tau * i / fftSize);
    }
    const window = Float64Array.from({length: windowSize}, (_, i) => 0.5 - 0.5 * Math.cos(tau * i / (windowSize - 1)));
    return (samples, start) => {
        re.fill(0);
        im.fill(0);
        for (let i = 0; i < windowSize; ++i) re[order[i]] = (samples[start + i] ?? 0) * window[i];
        for (let length = 2; length <= fftSize; length *= 2) {
            const half = length / 2, step = fftSize / length;
            for (let start = 0; start < fftSize; start += length) {
                for (let i = start, j = 0; j < half; ++i, ++j) {
                    const other = i + half, k = j * step;
                    const real = re[other] * cos[k] - im[other] * sin[k];
                    const imaginary = re[other] * sin[k] + im[other] * cos[k];
                    re[other] = re[i] - real;
                    im[other] = im[i] - imaginary;
                    re[i] += real;
                    im[i] += imaginary;
                }
            }
        }
        for (let i = 1; i < magnitudes.length; ++i) magnitudes[i] = Math.sqrt(re[i] ** 2 + im[i] ** 2);
        return bin => {
            const i = Math.floor(bin), fraction = bin - i;
            return i < 1 || i + 1 >= magnitudes.length ? 0 : (1 - fraction) * magnitudes[i] + fraction * magnitudes[i + 1];
        };
    };
}

function analyze(samples) {
    if (samples.length <= analysisRate / 5) throw new Error('Слишком мало звука для аранжировки');
    const transform = spectrum(), onsets = [], notes = [], bass = [];
    const bins = Array.from({length: 65}, (_, i) => frequency(i + 29) * fftSize / analysisRate);
    const dt = hop / analysisRate;
    let prior = 0, previousFlux = 0;
    function record(out, start, pitch, level) {
        if (pitch < 0) return;
        const last = out[out.length - 1];
        if (last?.pitch === pitch && start - last.end < dt * 1.5) last.end = start + dt;
        else out.push({start, end: start + dt, pitch, level});
    }
    for (let from = 0; from < samples.length; from += hop) {
        const start = from - windowSize / 2, time = from / analysisRate;
        let energy = 0;
        for (let i = Math.max(0, start); i < Math.min(samples.length, start + windowSize); ++i) energy += samples[i] ** 2;
        const rms = Math.sqrt(energy / windowSize), flux = Math.max(0, rms - prior);
        if (flux > Math.max(0.003, 0.12 * rms) && previousFlux <= flux && time - (onsets[onsets.length - 1] ?? -Infinity) > 0.12) onsets.push(time);
        prior = rms;
        previousFlux = flux;
        if (rms < 0.003) continue;
        const magnitude = transform(samples, start);
        let lead = -1, leadScore = 0, root = -1, rootScore = 0;
        for (let i = 0; i < bins.length; ++i) {
            const bin = bins[i], pitch = i + 29;
            const direct = magnitude(bin);
            const score = direct + 0.34 * magnitude(bin * 2) + 0.15 * magnitude(bin * 3) - 0.45 * magnitude(bin * 0.5);
            if (pitch >= 45 && score > leadScore) { lead = pitch; leadScore = score; }
            if (pitch <= 63 && direct > rootScore) { root = pitch; rootScore = direct; }
        }
        const level = Math.min(1, Math.max(0.45, rms * 10));
        record(notes, time, lead, level);
        record(bass, time, root, level);
    }
    if (!notes.length) throw new Error('Не удалось выделить мотив');
    const sustained = notes.filter(note => note.end - note.start >= dt * 1.5);
    let tempo = 96, best = 0;
    for (let bpm = 64; bpm <= 166; ++bpm) {
        let score = 0;
        for (let i = 1; i < onsets.length; ++i) {
            for (let j = Math.max(0, i - 4); j < i; ++j) {
                const beats = (onsets[i] - onsets[j]) * bpm / 60;
                if (beats > 0.22 && beats < 4) score += Math.max(0, 1 - Math.abs(beats * 2 - Math.round(beats * 2)) * 3) / Math.sqrt(beats);
            }
        }
        score *= 1 - Math.abs(bpm - 100) * 0.0025;
        if (score > best) { tempo = bpm; best = score; }
    }
    return {notes: sustained.length ? sustained : notes, bass, tempo, density: onsets.length * analysisRate / samples.length};
}

function randomForTrack(id) {
    const u64 = value => BigInt.asUintN(64, value);
    let state = 14695981039346656037n;
    for (const byte of new TextEncoder().encode(id)) state = u64((state ^ BigInt(byte)) * 1099511628211n);
    return () => {
        state = u64(state + 0x9e3779b97f4a7c15n);
        let n = state;
        n = u64((n ^ (n >> 30n)) * 0xbf58476d1ce4e5b9n);
        n = u64((n ^ (n >> 27n)) * 0x94d049bb133111ebn);
        return Number((n ^ (n >> 31n)) >> 11n) / 9007199254740992;
    };
}

function synth(voice, pitch, frames, rate, random) {
    const wave = new Float32Array(frames), angular = tau * frequency(pitch);
    const duration = frames / rate, phase = tau * random();
    let low = 0;
    for (let i = 0; i < frames; ++i) {
        const t = i / rate, p = angular * t;
        let y;
        switch (voice) {
        case 'pad':
            y = (0.63 * Math.sin(p) + 0.18 * Math.sin(p * 1.004 + phase) + 0.12 * Math.sin(p * 2.001) + 0.07 * Math.sin(p * 3 + phase))
                * Math.min(1, t / 0.7) * Math.min(1, duration - t);
            break;
        case 'bass':
            y = (0.82 * Math.sin(p) + 0.18 * Math.sin(p * 2)) * Math.min(1, t / 0.018) * Math.exp(-t * 1.6);
            break;
        case 'bell':
            y = (0.78 * Math.sin(p + 0.6 * Math.exp(-t * 3) * Math.sin(p * 2.01)) + 0.22 * Math.sin(p * 3.003) * Math.exp(-t * 5))
                * Math.min(1, t / 0.035) * Math.exp(-t * 1.85);
            break;
        case 'stab':
            y = (0.65 * Math.sin(p) + 0.20 * Math.sin(p * 2) + 0.10 * Math.sin(p * 3) + 0.05 * Math.sin(p * 5))
                * Math.min(1, t / 0.012) * Math.exp(-t * 5.6);
            break;
        case 'keys':
            y = (0.70 * Math.sin(p + 0.7 * Math.sin(p * 2) * Math.exp(-t * 3)) + 0.2 * Math.sin(p * 2.003))
                * Math.min(1, t / 0.018) * Math.exp(-t * 2.7);
            break;
        case 'kick':
            y = Math.sin(tau * (44 * t + 7.6 * (1 - Math.exp(-t * 18)))) * Math.exp(-t * 18) * Math.min(1, t / 0.003);
            break;
        case 'snare':
        case 'hat': {
            const noise = random() * 2 - 1;
            low += 0.23 * (noise - low);
            y = voice === 'hat' ? (noise - low) * Math.exp(-t * 34) :
                (0.64 * (noise - low) + 0.36 * Math.sin(tau * 168 * t) * Math.exp(-t * 45)) * Math.exp(-t * 19);
            break;
        }
        }
        wave[i] = y * Math.min(1, (duration - t) / 0.05);
    }
    return wave;
}

function octave(pitch, low, high) {
    while (pitch < low) pitch += 12;
    while (pitch > high) pitch -= 12;
    return pitch;
}

function chords(theme, first, beat) {
    function weights(notes, start, end) {
        const byPitch = new Float64Array(12);
        for (const note of notes) {
            if (note.start < end && note.end > start) byPitch[note.pitch % 12] += Math.min(note.end, end) - Math.max(note.start, start);
        }
        return byPitch;
    }
    return Array.from({length: 4}, (_, bar) => {
        const start = first + bar * 4 * beat, end = start + 4 * beat;
        const lead = weights(theme.notes, start, end), bass = weights(theme.bass, start, end);
        let root = 0, best = 0;
        for (let pitch = 0; pitch < 12; ++pitch) {
            const score = bass[pitch] + 0.35 * lead[pitch];
            if (score > best) { root = pitch; best = score; }
        }
        if (best === 0) return [theme.bass[0]?.pitch ?? theme.notes[0].pitch, 7];
        const minor = lead[(root + 3) % 12], major = lead[(root + 4) % 12];
        return [root, Math.max(minor, major) < 0.07 ? 7 : minor > major ? 3 : 4];
    });
}

function arrange(samples, preference, id, rate) {
    const theme = analyze(samples);
    const style = Object.hasOwn(bpmByStyle, preference) ? preference :
        theme.density < 1.5 ? 'drone' : theme.density < 2.85 ? 'dub' : 'downtempo';
    const random = randomForTrack(id);
    const bars = 12, beat = 60 / bpmByStyle[style], sourceBeat = 60 / theme.tempo;
    const first = theme.notes[0].start, grid = style === 'drone' ? 0.5 : 0.25;
    const frames = Math.floor((bars * 4 * beat + 2.4) * rate);
    const rows = new Map(), waves = new Map(), events = [];
    for (const note of theme.notes) {
        if (note.start >= first + sourceBeat * 16) break;
        const key = Math.round((note.start - first) / (sourceBeat * grid));
        const prior = rows.get(key);
        if (key * grid < 16 && (!prior || prior.end - prior.start < note.end - note.start)) rows.set(key, note);
    }
    const progression = chords(theme, first, sourceBeat);
    function add(when, voice, pitch, duration, gain, pan = 0, send = 0) {
        const length = Math.max(1, Math.floor(duration * rate)), key = [voice, pitch, length].join('/');
        let wave = waves.get(key);
        if (!wave) {
            wave = synth(voice, pitch, length, rate, random);
            waves.set(key, wave);
        }
        function place(time, gain, pan) {
            events.push({start: Math.floor(time * beat * rate), wave,
                left: gain * Math.sqrt((1 - pan) * 0.5), right: gain * Math.sqrt((1 + pan) * 0.5)});
        }
        place(when, gain, pan);
        if (send > 0) {
            place(when + 0.75, gain * send * 0.38, -pan - 0.22);
            place(when + 1.5, gain * send * 0.20, pan + 0.22);
        }
    }
    for (let bar = 0; bar < bars; ++bar) {
        const base = bar * 4, [root, third] = progression[bar % 4];
        for (const interval of new Set([0, third, 7])) {
            add(base, 'pad', octave(root + interval, 45, 59), beat * 4.4, style === 'drone' ? 0.095 : 0.060, interval === 0 ? -0.32 : 0.32, 0.24);
        }
        const low = octave(root, 31, 43);
        if (style === 'drone') {
            if (bar % 2 === 0) add(base, 'bass', low, beat * 5, 0.09);
            if (bar % 4 === 3) add(base + 3.5, 'hat', 0, 0.36, 0.025, 0.25, 0.4);
        } else if (style === 'dub') {
            for (const t of [0, bar % 2 === 0 ? 2 : 2.5]) add(base + t, 'bass', low, 1.05, 0.23);
            for (const t of [0.5, 2.5]) {
                for (const interval of [0, third, 7]) add(base + t, 'stab', octave(root + interval, 57, 72), 0.49, 0.046, -0.22, 0.95);
            }
            add(base, 'kick', 0, 0.32, 0.20);
            add(base + 2.5, 'hat', 0, 0.17, 0.058, -0.3, 0.4);
            if (bar % 2 === 1) add(base + 3, 'snare', 0, 0.25, 0.13, 0.18, 0.52);
        } else {
            for (const t of [0, bar % 2 === 0 ? 1.75 : 3.25, 2.5]) {
                add(base + t, 'bass', low, 0.52, 0.17);
                add(base + t, 'kick', 0, 0.29, 0.14);
            }
            for (const t of [1, 3]) add(base + t, 'snare', 0, 0.22, 0.13, 0.13, 0.22);
            for (const t of [0.5, 1.5, 2.25, 3.5]) add(base + t, 'hat', 0, 0.13, 0.039 + 0.025 * random(), t < 2 ? -0.3 : 0.3);
        }
    }
    const motif = [...rows].sort(([a], [b]) => a - b);
    for (let section = 0; section < bars * 4; section += 16) {
        motif.forEach(([key, note], index) => {
            if (section > 0 && style === 'drone' && random() < 0.28) return;
            const shift = section > 0 && index % 6 === 0 ? -12 : 0;
            const pitch = octave(note.pitch + shift, style === 'drone' ? 53 : 57, 82);
            const voice = style === 'drone' ? 'bell' : style === 'dub' ? 'stab' : 'keys';
            const stretch = style === 'drone' ? 1.55 : style === 'dub' ? 0.78 : 1;
            const duration = beat * (0.34 + Math.min(3.5, Math.max(grid * 0.7, (note.end - note.start) / sourceBeat)) * stretch);
            add(section + key * grid, voice, pitch, duration, 0.16 * note.level, index % 2 === 0 ? -0.2 : 0.2, style === 'downtempo' ? 0.46 : 0.82);
        });
    }
    events.sort((a, b) => a.start - b.start);
    return {style, frames, render(start, count) {
        const output = new Float32Array(2 * count);
        for (const event of events) {
            if (event.start >= start + count) break;
            const from = Math.max(start, event.start), end = Math.min(start + count, event.start + event.wave.length);
            for (let frame = from; frame < end; ++frame) {
                const value = event.wave[frame - event.start], index = (frame - start) * 2;
                output[index] += value * event.left;
                output[index + 1] += value * event.right;
            }
        }
        for (let i = 0; i < count; ++i) {
            const tail = Math.min(1, (frames - start - i) / rate);
            output[i * 2] = Math.tanh(output[i * 2] * 1.4) * tail;
            output[i * 2 + 1] = Math.tanh(output[i * 2 + 1] * 1.4) * tail;
        }
        return output;
    }};
}
