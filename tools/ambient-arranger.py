#!/usr/bin/env python3
"""Render tracker-style ambient patterns from decoded stereo WAV, with optional WAV voices.

Requires Python 3 and NumPy. The source waveform is used for analysis only. Output is
synthesized from notes and pattern events. No game-specific settings are required.
"""
import argparse
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import wave

import numpy as np


RATE = 44_100
ANALYSIS_RATE = 11_025
FFT = 4096
HOP = 220
FRAME_SECONDS = HOP / ANALYSIS_RATE
MIN_NOTE, MAX_NOTE = 29, 93


@dataclass(frozen=True)
class Note:
    start: float
    end: float
    pitch: int
    level: float


@dataclass(frozen=True)
class Theme:
    melody: list[Note]
    bass: list[Note]
    seconds: float
    bpm: int
    seed: int
    confidence: float
    attack_density: float
    mix_note_density: float


@dataclass(frozen=True)
class Style:
    label: str
    bpm: int
    lead: str
    spread: float
    delay: float
    echo: float


STYLES = {
    "drone": Style("Дрон-эмбиент", 52, "bell", 0.22, 1.5, 0.32),
    "dub": Style("Даб-эмбиент", 68, "chord", 0.34, 0.75, 0.43),
    "downtempo": Style("Даунтемпо", 82, "keys", 0.18, 0.50, 0.25),
}


def read_wav(path):
    with wave.open(str(path), "rb") as f:
        if f.getsampwidth() != 2 or f.getnchannels() not in (1, 2):
            raise ValueError(f"{path}: expected 16-bit mono or stereo WAV")
        rate = f.getframerate()
        samples = np.frombuffer(f.readframes(f.getnframes()), dtype="<i2")
        return samples.reshape(-1, f.getnchannels()), rate


def hz(pitch):
    return 440.0 * np.exp2((pitch - 69) / 12.0)


def analyze(samples, rate):
    # A short box filter before decimation attenuates high partials above the analysis band.
    mono = samples.astype(np.float32).mean(axis=1) / 32768
    if rate == RATE:
        mono = mono[: len(mono) // 4 * 4].reshape(-1, 4).mean(axis=1)
    elif rate != ANALYSIS_RATE:
        target = np.arange(0, len(mono), rate / ANALYSIS_RATE)
        mono = np.interp(target, np.arange(len(mono)), mono)
    size = 2048
    padded = np.pad(mono, (size // 2, size // 2))
    frames = np.lib.stride_tricks.sliding_window_view(padded, size)[::HOP]
    spectrum = abs(np.fft.rfft(frames * np.hanning(size), n=FFT))
    rms = np.sqrt(np.mean(frames ** 2, axis=1))
    pitch = np.arange(MIN_NOTE, MAX_NOTE + 1)
    frequency = hz(pitch) * FFT / ANALYSIS_RATE
    harmonics = np.array([1, 2, 3, 4, 5])
    bins = frequency[:, None] * harmonics[None, :]
    audible = bins < spectrum.shape[1] - 1
    bins = np.minimum(bins, spectrum.shape[1] - 2)
    lower = bins.astype(int)
    weight = bins - lower
    levels = (spectrum[:, lower] * (1 - weight) + spectrum[:, lower + 1] * weight) * audible
    def neighboring(hz_ratio):
        offsets = np.clip(np.round(frequency * hz_ratio).astype(int), 0, spectrum.shape[1] - 1)
        return spectrum[:, offsets]
    # Score a fundamental and its harmonic support, reducing octave errors.
    f = levels[:, :, 0]
    support = levels[:, :, 1:] @ np.array([.42, .25, .13, .08])
    valleys = .24 * np.maximum(neighboring(.94), neighboring(1.06))
    octave = .55 * neighboring(.5)
    scores = np.maximum(0, f + support - valleys - octave)
    scores *= np.clip(f / (levels.max(axis=2) + 1e-7), .1, 1.0) ** .35
    flux = np.maximum(0, np.diff(np.log1p(spectrum[:, 12:900]), axis=0, prepend=0)).mean(axis=1)
    return scores, rms, flux


def contour(scores, rms, low, high):
    notes = np.arange(low, high + 1)
    band = scores[:, low - MIN_NOTE : high - MIN_NOTE + 1]
    peak = band.max(axis=1)
    amplitude = np.percentile(rms, 80)
    # An explicit rest state wins when there is little energy or no clear pitch.
    strength = np.log1p(band / np.maximum(1e-6, peak[:, None]) * 12)
    concentration = peak / (band.sum(axis=1) + 1e-6)
    silence = (rms < max(.0012, amplitude * .093)) | (peak < .12) | (concentration < .049)
    strength[silence] -= 5
    strength = np.column_stack((strength, np.where(silence, 2.4, .65)))
    distance = abs(notes[:, None] - notes[None, :])
    movement = np.where(distance == 0, 0, .22 + np.minimum(distance, 18) * .047)
    transition = np.full((len(notes) + 1, len(notes) + 1), .40)
    transition[:-1, :-1] = movement
    transition[-1, -1] = 0
    state = strength[0]
    parents = np.empty(strength.shape, dtype=np.int16)
    for i in range(1, len(strength)):
        candidates = state[:, None] - transition
        parents[i] = candidates.argmax(axis=0)
        state = strength[i] + candidates[parents[i], np.arange(len(state))]
    labels = np.empty(len(strength), dtype=np.int16)
    labels[-1] = state.argmax()
    for i in range(len(labels) - 1, 0, -1):
        labels[i - 1] = parents[i, labels[i]]
    labels = np.where(labels == len(notes), -1, labels + low)
    result = []
    for first, last in zip(np.r_[0, np.flatnonzero(np.diff(labels)) + 1], np.r_[np.flatnonzero(np.diff(labels)) + 1, len(labels)]):
        pitch = int(labels[first])
        if pitch < 0 or last - first < 3:
            continue
        purity = np.mean(concentration[first:last])
        energy = min(1., np.mean(rms[first:last]) / max(amplitude, .0001))
        result.append(Note(first * FRAME_SECONDS, last * FRAME_SECONDS, pitch, float(.52 + .48 * energy * min(1., purity * 4))))
    if not result:
        return result, 0
    sounding = sum(n.end - n.start for n in result) / (len(rms) * FRAME_SECONDS)
    voiced = labels >= 0
    clarity = float(np.median(concentration[voiced])) if voiced.any() else 0
    # Durations and coverage keep isolated percussion harmonics from winning a voice selection.
    quality = sounding * clarity * min(1., len({n.pitch for n in result}) / 7)
    return result, quality


def estimate_tempo(flux):
    if len(flux) < 140:
        return 100
    flux = np.maximum(0, flux - np.quantile(flux, .68))
    grid = np.arange(70, 161)
    lag = np.round(60 / FRAME_SECONDS / grid).astype(int)
    scores = []
    for index, t in enumerate(lag):
        score = sum(weight * np.mean(flux[delta:] * flux[:-delta])
                    for delta, weight in [(t, 1.), (t * 2, .65), (t * 4, .35)] if delta < len(flux))
        scores.append(score * (1 - .26 * abs(np.log2(grid[index] / 110))))
    return int(grid[np.argmax(scores)])


def extract(mix, rate, channels=()):
    if len(mix) < rate // 2:
        raise ValueError("The input needs at least half a second of audio")
    candidates = []
    scores, rms, flux = analyze(mix, rate)
    seconds = len(mix) / rate
    melody, main_quality = contour(scores, rms, 54, 90)
    mix_note_density = len(melody) / seconds
    bass, bass_quality = contour(scores, rms, 32, 57)
    for channel, channel_rate in channels:
        channel_scores, channel_rms, _ = analyze(channel, channel_rate)
        mel, mel_quality = contour(channel_scores, channel_rms, 54, 90)
        low, low_quality = contour(channel_scores, channel_rms, 32, 57)
        if not mel and not low:
            continue
        loudness = min(1., np.quantile(channel_rms, .85) / max(.001, np.quantile(rms, .85)) * 2.1)
        candidates.append((mel, mel_quality * loudness, low, low_quality * loudness))
    if candidates:
        ranked = []
        for index, (mel, quality, _, _) in enumerate(candidates):
            if not mel:
                continue
            avg = np.mean([n.pitch for n in mel])
            onsets = len(mel) / seconds
            # Prefer a developed contour in the lead register over either a pedal or constant ornament.
            score = quality * np.clip((avg - 53) / 14, .35, 1.) * np.clip(onsets / 1.5, .25, 1.) * np.clip(8 / onsets, .5, 1.)
            ranked.append((score, index, mel))
        if ranked:
            score, chosen, lead = max(ranked, key=lambda row: row[0])
            if score > main_quality * .38:
                melody, main_quality = lead, score
                other = [(low_q, low) for i, (_, _, low, low_q) in enumerate(candidates) if i != chosen]
                if other:
                    best_q, best = max(other, key=lambda row: row[0])
                    if best_q > bass_quality * .35:
                        bass = best
    if len(melody) < 4 or main_quality < .012:
        raise ValueError("Could not extract a stable melodic phrase")
    seed = int.from_bytes(hashlib.sha256(mix.tobytes()).digest()[:8], "big")
    peaks = (flux[1:-1] > flux[:-2]) & (flux[1:-1] >= flux[2:])
    strong = flux[1:-1] > max(.002, float(np.quantile(flux, .88)))
    return Theme(melody, bass, seconds, estimate_tempo(flux), seed, float(main_quality),
                 float(np.count_nonzero(peaks & strong) / seconds), mix_note_density)


def choose_style(theme):
    if theme.mix_note_density < 2.0 and theme.attack_density < 2.5:
        return "drone"
    if theme.attack_density >= 2.85:
        return "downtempo"
    return "dub"


def octave(pitch, low, high):
    while pitch < low:
        pitch += 12
    while pitch > high:
        pitch -= 12
    return pitch


def phrase(theme, beats, style):
    # Keep the first coherent phrase, allowing a short instrumental intro to pass.
    melody = theme.melody
    step = 60 / theme.bpm
    phrase_beats = beats
    starts = [n.start for n in melody[:32] if n.start < min(5.5, theme.seconds * .24)]
    if not starts:
        starts = [melody[0].start]
    horizon = phrase_beats * step
    def substance(at):
        phrase_notes = [n for n in melody if at <= n.start < at + horizon]
        pitches = len({n.pitch for n in phrase_notes})
        return min(12, len(phrase_notes)) + min(9, pitches) * .9 - .72 * at
    start = max(starts, key=substance)
    duration = min(horizon, theme.seconds - start)
    if duration <= 0:
        raise ValueError("Melody starts after the analysis window")
    quantum = .25 if style != "drone" else .5
    rows = {}
    for n in melody:
        if n.start < start - .03 or n.start >= start + duration:
            continue
        row = round(max(0, (n.start - start) / step) / quantum) * quantum
        if row >= phrase_beats:
            continue
        if style == "drone" and n.end - n.start < .085 and row in rows:
            continue
        length = np.clip((n.end - n.start) / step, quantum * .72, 3.5)
        old = rows.get(row)
        if old is None or length * n.level > old[1] * old[3]:
            rows[row] = (octave(n.pitch, 57, 82), float(length), n.pitch, n.level)
    if len(rows) < 3:
        raise ValueError("Too few note events on the tracker grid")
    harmonies = []
    for bar in range(0, phrase_beats, 4):
        begin, end = start + bar * step, start + (bar + 4) * step
        low = [n for n in theme.bass if n.start < end and n.end > begin]
        local = [n for n in melody if n.start < end and n.end > begin]
        if not local:
            local = melody[:3]
        notes = low if low else local
        scores = np.zeros(12)
        for note in notes:
            scores[note.pitch % 12] += max(.025, min(note.end, end) - max(note.start, begin)) * note.level
        root = int(np.argmax(scores))
        melody_pc = np.zeros(12)
        for note in local:
            melody_pc[note.pitch % 12] += min(.5, note.end - note.start) * note.level
        third = 3 if melody_pc[(root + 3) % 12] >= melody_pc[(root + 4) % 12] else 4
        # Use an open fifth when the phrase does not establish a third.
        voiced = [0, 7] if max(melody_pc[(root + 3) % 12], melody_pc[(root + 4) % 12]) < .18 else [0, third, 7]
        harmonies.append((root, voiced))
    return sorted(rows.items()), harmonies, start


def oscillator(kind, pitch, seconds, seed):
    t = np.arange(round(seconds * RATE)) / RATE
    w = 2 * np.pi * hz(pitch) * t
    if kind == "pad":
        y = (.55*np.sin(w + .09*np.sin(t * 4.4)) + .32*np.sin(w * 1.0029 + seed)
             + .28*np.sin(w * .9977 + seed*.6) + .18*np.sin(2*w + .4*np.sin(t*3))
             + .08*np.sin(3*w + seed))
        attack, release = .80, 1.65
    elif kind == "bell":
        y = .76*np.sin(w) + .29*np.sin(w * 2.001) * np.exp(-t*3) + .16*np.sin(w * 3.02) * np.exp(-t*6)
        attack, release = .044, .67
    elif kind == "chord":
        y = .69*np.sin(w + 1.7*np.exp(-t*5.7)*np.sin(2*w)) + .23*np.sin(w * 1.004)
        attack, release = .016, .36
    elif kind == "keys":
        y = (.77*np.sin(w) + .31*np.sin(2*w) * np.exp(-t*4.5) + .18*np.sin(3*w) * np.exp(-t*7)
             + .10*np.sin(w * 4.01) * np.exp(-t*13))
        attack, release = .011, .49
    else:
        y = .88*np.sin(w) + .18*np.sin(2*w) + .09*np.sin(3*w)
        attack, release = .037, .30
    env = np.minimum(1, t/attack) * np.minimum(1, (seconds-t)/release)
    return (y * env).astype(np.float32)


def percussion(kind, rng):
    t = np.arange(round(.40 * RATE)) / RATE
    noise = rng.normal(0, .49, len(t))
    if kind == "kick":
        phase = 2*np.pi*(43*t + 60*(1-np.exp(-t*44))/44)
        y = np.sin(phase)*np.exp(-t*16) + .13*noise*np.exp(-t*95)
    elif kind == "snare":
        y = (.5*noise + .30*np.sin(2*np.pi*157*t)) * np.exp(-t*25)
    else:
        y = np.diff(noise, prepend=0)*np.exp(-t*78)
    return (y * np.minimum(1, t/.0025)).astype(np.float32)


def render(theme, style_id, length, variant):
    if style_id == "auto":
        style_id = choose_style(theme)
    style = STYLES[style_id]
    step = 60 / style.bpm
    phrase_beats = 16
    rows, harmony, start = phrase(theme, phrase_beats, style_id)
    bars = {"short": 8, "normal": 12, "long": 20}[length]
    dry = np.zeros((round((bars * 4 * step + 2.4) * RATE), 2), dtype=np.float32)
    echo = np.zeros_like(dry)
    rng = np.random.default_rng(np.random.SeedSequence([theme.seed, variant]))
    events = []
    def put(beat, pitch, beats, channel, kind, gain, pan=0, wet=0):
        position = max(0, round(beat * step * RATE))
        clip = oscillator(kind, pitch, beats * step, float(rng.uniform(0, np.pi)))
        end = min(len(dry), position + len(clip))
        if end <= position:
            return
        stereo = clip[:end-position, None] * np.array([gain * (1-pan), gain * (1+pan)], np.float32)
        dry[position:end] += stereo
        if wet:
            echo[position:end] += stereo * wet
        events.append((round(beat, 3), int(pitch), channel))

    kick, snare, hat = [percussion(voice, rng) for voice in ("kick", "snare", "hat")]
    def strike(beat, voice, gain, pan=0, wet=0):
        position = round(beat * step * RATE)
        end = min(len(dry), position + len(voice))
        stereo = voice[:end-position, None] * np.array([gain*(1-pan), gain*(1+pan)], np.float32)
        dry[position:end] += stereo
        if wet:
            echo[position:end] += stereo * wet
        channel = "kick" if voice is kick else "snare" if voice is snare else "hat"
        events.append((round(beat, 3), None, channel))

    for bar in range(bars):
        beat = bar * 4
        root, chord = harmony[bar % len(harmony)]
        for index, interval in enumerate(chord):
            note = octave(root + interval, 47 if index else 37, 60 if index else 48)
            pan = style.spread * (-1 if index % 2 == 0 else 1)
            weight = .073 if style_id == "drone" else .038
            put(beat, note, 5.1 if style_id == "drone" else 4.35, "pad", "pad", weight, pan, .42)
        bass = octave(root, 31, 43)
        if style_id == "drone":
            if bar % 2 == 0:
                put(beat, bass, 5.6, "bass", "bass", .072, 0, .1)
        elif style_id == "dub":
            for pos, duration in [(0, 1.5), (2.5 if bar % 2 else 2, 1.1)]:
                put(beat+pos, bass, duration, "bass", "bass", .192, 0, .1)
            for pos in ((.5, 2.5) if bar % 2 == 0 else (1.5, 3.5)):
                for interval in chord:
                    put(beat+pos, octave(root+interval, 57, 68), .34, "chord", "chord", .039, -.20, .95)
            strike(beat, kick, .16)
            if bar % 2:
                strike(beat+3, snare, .085, .12, .56)
            strike(beat+2.5, hat, .039, -.31, .3)
        else:
            for pos in ((0, 2.5) if bar % 2 else (0, 1.75, 3.25)):
                put(beat+pos, bass, .75, "bass", "bass", .132, 0, .10)
            for pos in (0, 1.75, 2.5):
                strike(beat+pos, kick, .13)
            for pos in (1, 3):
                strike(beat+pos, snare, .088, .05, .23)
            for pos in (.5, 1.5, 2.28, 3.5):
                strike(beat+pos, hat, float(rng.uniform(.024,.047)), -.26 if pos < 2 else .28)

    for section in range(0, bars * 4, phrase_beats):
        for index, (pos, (note, duration, _, level)) in enumerate(rows):
            if section + pos >= bars * 4:
                continue
            if section and style_id == "drone" and (duration < .63 or rng.random() < .23):
                continue
            if style_id == "dub":
                phrase_gain, length_factor, wet = .113, .77, .84
                pan = -.08 if index % 2 == 0 else .14
            elif style_id == "drone":
                phrase_gain, length_factor, wet = .101, 1.52, .82
                pan = -.15 if index % 2 == 0 else .17
            else:
                phrase_gain, length_factor, wet = .155, .97, .55
                pan = -.09 if index % 2 == 0 else .09
            # The first pass retains the extracted pitch order. Later passes can answer in a different octave.
            if section and index % 6 == (variant % 6) and (style_id != "dub" or pos > 7):
                note = octave(note - 12, 53, 80)
            put(section+pos, note, max(.20, duration*length_factor) + .33,
                "melody", style.lead, phrase_gain*level, pan, wet)
    for i in range(1, 5):
        lag = round(style.delay * step * i * RATE)
        dry[lag:] += echo[:-lag, ::-1 if i % 2 else 1] * style.echo ** i
    seconds = len(dry) / RATE
    ramp = np.linspace(0, 1, round(.31 * RATE), dtype=np.float32)
    dry[:len(ramp)] *= ramp[:, None]
    ramp = np.linspace(1, 0, round((2.4 if style_id == "drone" else 1.75) * RATE), dtype=np.float32)
    dry[-len(ramp):] *= ramp[:, None]
    peak = float(abs(dry).max())
    level = float(np.sqrt(np.mean(dry ** 2)))
    dry *= min(.88 / max(peak, .01), .115 / max(level, .01))
    return dry, {"style": style_id, "bpm": style.bpm, "source_bpm": theme.bpm, "source_phrase": round(start, 2),
                 "seconds": round(seconds, 2), "confidence": round(theme.confidence, 4), "events": events}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--voice", type=Path, action="append", default=[])
    parser.add_argument("--style", choices=("auto", *STYLES), default="auto")
    parser.add_argument("--length", choices=("short", "normal", "long"), default="short")
    parser.add_argument("--variant", type=int, default=0)
    parser.add_argument("--patterns", type=Path)
    args = parser.parse_args()
    if args.variant < 0:
        parser.error("--variant must be non-negative")
    mix, rate = read_wav(args.source)
    channels = [read_wav(voice) for voice in args.voice]
    output, metadata = render(extract(mix, rate, channels), args.style, args.length, args.variant)
    with wave.open(str(args.output), "wb") as f:
        f.setparams((2, 2, RATE, 0, "NONE", "not compressed"))
        f.writeframes(np.rint(output * 32767).astype("<i2").tobytes())
    if args.patterns:
        args.patterns.write_text(json.dumps(metadata, indent=2, ensure_ascii=False) + "\n")
    print(f"{STYLES[metadata['style']].label} · {metadata['bpm']} BPM · {metadata['seconds']} s · {len(metadata['events'])} events")


if __name__ == "__main__":
    main()
