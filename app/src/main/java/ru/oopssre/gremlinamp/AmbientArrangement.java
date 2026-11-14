package ru.oopssre.gremlinamp;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

/** Tracker rows rendered as new voices. Output position is always an absolute frame. */
final class AmbientArrangement implements PcmSource {
    static final int RATE = 44_100;
    enum Style {
        AUTO("Авто", 0), DRONE("Дрон-эмбиент", 52), DUB("Даб-эмбиент", 68), DOWNTEMPO("Даунтемпо", 82);
        final String title;
        final int bpm;
        Style(String title, int bpm) { this.title = title; this.bpm = bpm; }
    }

    enum Length {
        SHORT("Короткая", 8), NORMAL("Обычная", 12), LONG("Длинная", 20);
        final String title;
        final int bars;
        Length(String title, int bars) { this.title = title; this.bars = bars; }
    }

    private enum Voice { PAD, BELL, CHORD, KEYS, BASS, KICK, SNARE, HAT }
    private record Event(long start, long end, float[] wave, float left, float right, boolean movement) {}
    private record Row(double beat, double duration, int pitch, float level) {}
    private record Chord(int root, int third) {}
    private record Tone(Voice voice, int pitch, long duration, int detune) {}
    final Style style;
    private final List<Event> events = new ArrayList<>();
    private final List<Event> active = new ArrayList<>();
    private final Map<Tone,float[]> tones = new HashMap<>();
    private final Supplier<SpaceTracker.Motion> motion;
    private final long frames, tail;
    private final double beat;
    private long position;
    private int eventIndex;
    private float movement;
    private double walkingPhase;
    private final float[] mix = new float[4096];

    AmbientArrangement(AmbientAnalysis.Theme theme, Style requested, Length length, int variant,
                       Supplier<SpaceTracker.Motion> motion) throws IOException {
        this.motion = motion;
        style = requested == Style.AUTO ? autoStyle(theme) : requested;
        beat = 60.0 / style.bpm;
        frames = at(length.bars * 4 + 2.4 / beat);
        tail = Math.round(RATE * (style == Style.DRONE ? 2.4 : 1.75));
        build(theme, length.bars, variant);
        tones.clear();
        events.sort(Comparator.comparingLong(Event::start));
    }

    static Style autoStyle(AmbientAnalysis.Theme theme) {
        if (theme.melody().size() / theme.seconds() < 2 && theme.attacks() < 2.5) return Style.DRONE;
        return theme.attacks() >= 2.85 ? Style.DOWNTEMPO : Style.DUB;
    }

    private void build(AmbientAnalysis.Theme theme, int bars, int variant) throws IOException {
        List<AmbientAnalysis.Note> melody = theme.melody();
        if (melody.isEmpty()) throw new IOException("Нет устойчивых нот для аранжировки");
        double sourceBeat = 60 / theme.bpm();
        double start = phraseStart(melody, theme.seconds(), sourceBeat * 16);
        double grid = style == Style.DRONE ? .5 : .25;
        Row[] rows = new Row[64];
        for (AmbientAnalysis.Note note : melody) {
            if (note.start() < start - .025 || note.start() >= start + 16 * sourceBeat) continue;
            int row = (int) Math.round(Math.max(0, note.start() - start) / (sourceBeat * grid));
            if (row * grid >= 16) continue;
            double duration = Math.max(grid * .72, Math.min(3.5, (note.end() - note.start()) / sourceBeat));
            Row previous = rows[row];
            if (previous == null || duration * note.level() > previous.duration * previous.level)
                rows[row] = new Row(row * grid, duration, octave(note.pitch(), 57, 82), note.level());
        }
        Chord[] chords = new Chord[4];
        for (int bar = 0; bar < 4; bar++) {
            double from = start + bar * 4 * sourceBeat;
            chords[bar] = chord(theme, from, from + 4 * sourceBeat);
        }
        Random rng = new Random(theme.seed() ^ (long) variant * 0x54D049BB133111EBL);
        float[] kick = drums(Voice.KICK, rng), snare = drums(Voice.SNARE, rng), hat = drums(Voice.HAT, rng);
        for (int bar = 0; bar < bars; bar++) {
            AmbientAnalysis.checkInterrupted();
            int at = bar * 4;
            Chord chord = chords[bar % chords.length];
            int root = chord.root;
            int[] intervals = chord.third == 0 ? new int[]{0, 7} : new int[]{0, chord.third, 7};
            for (int i = 0; i < intervals.length; i++) {
                int pitch = i == 0 ? octave(root, 37, 48) : octave(root + intervals[i], 47, 60);
                note(at, pitch, style == Style.DRONE ? 5.1 : 4.35, Voice.PAD,
                        style == Style.DRONE ? .073f : .038f, i % 2 == 0 ? -.29f : .29f, .42f, false, rng);
            }
            int bass = octave(root, 31, 43);
            switch (style) {
                case DRONE -> {
                    if (bar % 2 == 0) note(at, bass, 5.6, Voice.BASS, .072f, 0, .1f, false, rng);
                }
                case DUB -> {
                    note(at, bass, 1.5, Voice.BASS, .192f, 0, .1f, false, rng);
                    note(at + (bar % 2 == 0 ? 2 : 2.5), bass, 1.1, Voice.BASS, .192f, 0, .1f, false, rng);
                    double first = bar % 2 == 0 ? .5 : 1.5;
                    for (double pos : new double[]{first, first + 2}) for (int interval : intervals)
                        note(at + pos, octave(root + interval, 57, 68), .34, Voice.CHORD, .039f, -.20f, .95f, false, rng);
                    hit(at, kick, .16f, 0, 0, false);
                    if (bar % 2 == 1) hit(at + 3, snare, .085f, .12f, .56f, false);
                    hit(at + 2.5, hat, .039f, -.31f, .3f, false);
                }
                case DOWNTEMPO -> {
                    for (double pos : new double[]{0, 2.5, bar % 2 == 0 ? 1.75 : 3.25})
                        note(at + pos, bass, .75, Voice.BASS, .132f, 0, .10f, false, rng);
                    for (double pos : new double[]{0, 1.75, 2.5}) hit(at + pos, kick, .13f, 0, 0, false);
                    for (double pos : new double[]{1, 3}) hit(at + pos, snare, .088f, .05f, .23f, false);
                    for (double pos : new double[]{.5, 1.5, 2.25, 3.5})
                        hit(at + pos, hat, .024f + rng.nextFloat() * .023f, pos < 2 ? -.26f : .28f, 0, false);
                }
                default -> throw new IllegalStateException("Unresolved style");
            }
            // These notes and hits are silent at rest. Cadence opens extra subdivisions.
            for (int i = 0; i < 4; i++) {
                hit(at + i + .5, hat, style == Style.DRONE ? .017f : .046f, i % 2 == 0 ? -.28f : .28f, .1f, true);
            }
            if (style != Style.DRONE) hit(at + 3.75, snare, .05f, .14f, .2f, true);
        }
        Voice lead = style == Style.DRONE ? Voice.BELL : style == Style.DUB ? Voice.CHORD : Voice.KEYS;
        for (int section = 0; section < bars * 4; section += 16) {
            AmbientAnalysis.checkInterrupted();
            int index = 0;
            for (Row row : rows) {
                if (row == null || section + row.beat >= bars * 4) continue;
                int i = index++;
                if (section != 0 && style == Style.DRONE && (row.duration < .63 || rng.nextFloat() < .23f)) continue;
                int pitch = row.pitch;
                if (section != 0 && i % 6 == variant % 6 && (style != Style.DUB || row.beat > 7)) pitch = octave(pitch - 12, 53, 80);
                float gain = style == Style.DRONE ? .101f : style == Style.DUB ? .113f : .155f;
                double stretch = style == Style.DRONE ? 1.52 : style == Style.DUB ? .77 : .97;
                float pan = i % 2 == 0 ? -.13f : .15f;
                double when = section + row.beat;
                note(when, pitch, row.duration * stretch + .33, lead, gain * row.level, pan,
                        style == Style.DOWNTEMPO ? .55f : .82f, false, rng);
                if (i % 3 == 1) {
                    note(when + .5, octave(pitch + 12, 64, 88), .55, lead,
                            .049f * row.level, -pan, .55f, true, rng);
                }
            }
        }
    }

    private static double phraseStart(List<AmbientAnalysis.Note> melody, double seconds, double span) {
        double best = -Double.MAX_VALUE, start = melody.get(0).start();
        for (int i = 0; i < Math.min(32, melody.size()); i++) {
            double from = melody.get(i).start();
            if (from >= Math.min(5.5, seconds * .24)) break;
            int count = 0, pitches = 0;
            for (AmbientAnalysis.Note note : melody) {
                if (note.start() >= from && note.start() < from + span) {
                    count++;
                    pitches |= 1 << (note.pitch() % 12);
                }
            }
            double score = Math.min(12, count) + Math.min(9, Integer.bitCount(pitches)) * .9 - .72 * from;
            if (score > best) { best = score; start = from; }
        }
        return start;
    }

    private static Chord chord(AmbientAnalysis.Theme theme, double start, double end) {
        double[] bass = pitchClasses(theme.bass(), start, end), melody = pitchClasses(theme.melody(), start, end);
        double[] support = bass;
        double loudest = 0;
        for (double value : bass) loudest = Math.max(loudest, value);
        if (loudest == 0) support = melody;
        int root = theme.melody().get(0).pitch() % 12;
        double strongest = 0;
        for (int i = 0; i < support.length; i++) if (support[i] > strongest) { strongest = support[i]; root = i; }
        double minor = melody[(root + 3) % 12], major = melody[(root + 4) % 12];
        return new Chord(root, Math.max(minor, major) < .18 ? 0 : minor >= major ? 3 : 4);
    }

    private static double[] pitchClasses(List<AmbientAnalysis.Note> notes, double start, double end) {
        double[] weights = new double[12];
        for (AmbientAnalysis.Note note : notes) {
            double overlap = Math.min(note.end(), end) - Math.max(note.start(), start);
            if (overlap > 0) weights[note.pitch() % 12] += overlap * note.level();
        }
        return weights;
    }

    private static int octave(int pitch, int low, int high) {
        while (pitch < low) pitch += 12;
        while (pitch > high) pitch -= 12;
        return pitch;
    }

    private long at(double beats) { return Math.round(beats * beat * RATE); }

    private void note(double when, int pitch, double beats, Voice voice, float gain, float pan,
                      float wet, boolean moving, Random rng) {
        Tone key = new Tone(voice, pitch, Math.round(beats * beat * RATE), voice == Voice.PAD ? rng.nextInt(3) : 0);
        float[] wave = tones.computeIfAbsent(key, tone -> synth(tone.voice, tone.pitch, tone.duration / (double) RATE,
                tone.detune * Math.PI / 3));
        hit(when, wave, gain, pan, wet, moving);
    }

    private void hit(double when, float[] wave, float gain, float pan, float wet, boolean moving) {
        add(at(when), wave, gain, pan, moving);
        if (wet == 0) return;
        double interval = style == Style.DRONE ? 1.5 : style == Style.DUB ? .75 : .5;
        float feedback = style == Style.DRONE ? .32f : style == Style.DUB ? .43f : .25f;
        float level = wet * gain;
        for (int i = 1; i <= 4; i++) {
            level *= feedback;
            add(at(when + interval * i), wave, level, i % 2 == 0 ? pan : -pan, moving);
        }
    }

    private void add(long start, float[] wave, float gain, float pan, boolean moving) {
        if (start < frames) events.add(new Event(start, Math.min(frames, start + wave.length), wave,
                gain * (1 - pan), gain * (1 + pan), moving));
    }

    private static float[] synth(Voice voice, int pitch, double seconds, double phase) {
        float[] wave = new float[(int) Math.round(seconds * RATE)];
        double omega = 2 * Math.PI * AmbientAnalysis.frequency(pitch) / RATE;
        double attack, release;
        switch (voice) {
            case PAD -> { attack = .80; release = 1.65; }
            case BELL -> { attack = .044; release = .67; }
            case CHORD -> { attack = .016; release = .36; }
            case KEYS -> { attack = .011; release = .49; }
            default -> { attack = .037; release = .30; }
        }
        for (int i = 0; i < wave.length; i++) {
            double t = i / (double) RATE, w = omega * i;
            double y = switch (voice) {
                case PAD -> .55 * Math.sin(w + .09 * Math.sin(t * 4.4)) + .32 * Math.sin(w * 1.0029 + phase)
                        + .28 * Math.sin(w * .9977 + phase * .6) + .18 * Math.sin(2 * w) + .08 * Math.sin(3 * w + phase);
                case BELL -> .76 * Math.sin(w) + .29 * Math.sin(w * 2.001) * Math.exp(-t * 3)
                        + .16 * Math.sin(w * 3.02) * Math.exp(-t * 6);
                case CHORD -> .69 * Math.sin(w + 1.7 * Math.exp(-t * 5.7) * Math.sin(2 * w)) + .23 * Math.sin(w * 1.004);
                case KEYS -> .77 * Math.sin(w) + .31 * Math.sin(2 * w) * Math.exp(-t * 4.5)
                        + .18 * Math.sin(3 * w) * Math.exp(-t * 7) + .10 * Math.sin(w * 4.01) * Math.exp(-t * 13);
                default -> .88 * Math.sin(w) + .18 * Math.sin(2 * w) + .09 * Math.sin(3 * w);
            };
            wave[i] = (float) (y * Math.min(1, t / attack) * Math.min(1, (seconds - t) / release));
        }
        return wave;
    }

    private static float[] drums(Voice voice, Random rng) {
        float[] wave = new float[RATE * 2 / 5];
        double previous = 0;
        for (int i = 0; i < wave.length; i++) {
            double t = i / (double) RATE, noise = rng.nextDouble() * 1.4 - .7;
            double value = switch (voice) {
                case KICK -> Math.sin(2 * Math.PI * (43 * t + 60 * (1 - Math.exp(-t * 44)) / 44)) * Math.exp(-t * 16)
                        + .13 * noise * Math.exp(-t * 95);
                case SNARE -> (.5 * noise + .30 * Math.sin(2 * Math.PI * 157 * t)) * Math.exp(-t * 25);
                default -> (noise - previous) * Math.exp(-t * 78);
            };
            previous = noise;
            wave[i] = (float) (value * Math.min(1, t / .0025));
        }
        return wave;
    }

    @Override public int sampleRate() { return RATE; }
    @Override public long duration() { return frames; }
    @Override public boolean ended() { return position >= frames; }

    @Override public long seek(long frame) {
        position = Math.min(frames, Math.max(0, frame));
        active.clear();
        eventIndex = 0;
        while (eventIndex < events.size() && events.get(eventIndex).start < position) {
            Event event = events.get(eventIndex++);
            if (event.end > position) active.add(event);
        }
        movement = 0;
        walkingPhase = 0;
        return position;
    }

    @Override public int render(short[] pcm, int requested) {
        int length = (int) Math.min(Math.min(requested, mix.length / 2), frames - position);
        if (length == 0) return 0;
        long end = position + length;
        while (eventIndex < events.size() && events.get(eventIndex).start < end) active.add(events.get(eventIndex++));
        active.removeIf(event -> event.end <= position);
        java.util.Arrays.fill(mix, 0, length * 2, 0);
        SpaceTracker.Motion walking = motion.get();
        float extra = walking.cadence() > 0
                ? Math.max(.25f, Math.min(1, (walking.cadence() - 60) / 100f + walking.movement() * .55f)) : 0;
        float before = movement;
        movement += (extra - movement) * Math.min(1, length / (RATE * .22f));
        double footRate = walking.cadence() / (60.0 * RATE);
        double phase = walkingPhase;
        walkingPhase = (walkingPhase + length * footRate) % 1;
        for (Event event : active) {
            int begin = (int) (Math.max(position, event.start) - position);
            int finish = (int) (Math.min(end, event.end) - position);
            int source = (int) (position + begin - event.start);
            for (int i = begin; i < finish; i++) {
                float gain = 1;
                if (event.movement) {
                    double foot = (phase + i * footRate) % 1;
                    double pulse = 1 - 2 * Math.min(foot, 1 - foot);
                    gain = (before + (movement - before) * i / length) * (float) (.25 + .75 * pulse * pulse);
                }
                float value = event.wave[source++] * gain;
                mix[i * 2] += value * event.left;
                mix[i * 2 + 1] += value * event.right;
            }
        }
        for (int i = 0; i < length; i++) {
            float env = Math.min(1, Math.min((position + i) / (RATE * .29f), (frames - position - i) / (float) tail));
            pcm[i * 2] = saturate(mix[i * 2] * env);
            pcm[i * 2 + 1] = saturate(mix[i * 2 + 1] * env);
        }
        position = end;
        return length;
    }

    private static short saturate(float value) {
        float shaped = value * 1.52f;
        return (short) Math.round(30_400 * shaped / (1 + Math.abs(shaped)));
    }
}
