#!/usr/bin/env python3
"""Convert a mono 16 kHz, signed 16-bit WAV to a tracker source profile.

Usage: space-features.py output.json source.wav [source.wav ...]
Each recording yields 96 rows of peak band, RMS level and spectral centroid.
"""
import argparse
import json
import math
import struct
import wave
from pathlib import Path

RATE = 16000
SIZE = 1024
WINDOW = [0.5 - 0.5 * math.cos(2 * math.pi * i / (SIZE - 1)) for i in range(SIZE)]
FREQUENCIES = [100 * 1.10**index for index in range(45)]
COEFFICIENTS = [2 * math.cos(2 * math.pi * frequency / RATE) for frequency in FREQUENCIES]


def spectral_power(samples, coefficient):
    prev, older = 0.0, 0.0
    for sample in samples:
        current = sample + coefficient * prev - older
        older, prev = prev, current
    return max(0.0, prev * prev + older * older - coefficient * prev * older)


def profile(path):
    with wave.open(str(path)) as wav:
        if (wav.getnchannels(), wav.getframerate(), wav.getsampwidth()) != (1, RATE, 2):
            raise ValueError(f"{path}: expected mono 16 kHz signed 16-bit WAV")
        samples = list(value[0] / 32768 for value in struct.iter_unpack("<h", wav.readframes(wav.getnframes())))
    if len(samples) < SIZE:
        raise ValueError(f"{path}: insufficient samples")

    rows = []
    for index in range(96):
        start = index * (len(samples) - SIZE) // 95
        raw = samples[start:start + SIZE]
        mean = sum(raw) / SIZE
        centered = [value - mean for value in raw]
        rms = math.sqrt(sum(value * value for value in centered) / SIZE)
        windowed = [value * weight for value, weight in zip(centered, WINDOW)]
        bands = [spectral_power(windowed, coefficient) for coefficient in COEFFICIENTS]
        peak = max(range(len(bands)), key=bands.__getitem__)
        total = sum(bands)
        centroid = sum(i * value for i, value in enumerate(bands)) / total if total else 0
        rows.append((peak, math.log10(max(rms, 1e-6)), centroid))

    result = bytearray()
    scales = []
    for column in zip(*rows):
        ordered = sorted(column)
        low, high = ordered[4], ordered[-5]
        scales.append((low, max(high - low, 1e-6)))
    for row in rows:
        for value, (low, width) in zip(row, scales):
            result.append(round(255 * min(1, max(0, (value - low) / width))))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("sources", type=Path, nargs="+")
    args = parser.parse_args()
    values = b"".join(profile(source) for source in args.sources)
    rows = [json.dumps(list(values[i:i + 24]))[1:-1] for i in range(0, len(values), 24)]
    args.output.write_text("[\n  " + ",\n  ".join(rows) + "\n]\n", encoding="utf-8")


if __name__ == "__main__":
    main()
