#!/usr/bin/env python3
"""
Generates every sound effect used by the game as small mono 16-bit WAV files.
Everything is synthesized (no samples, no licensing issues).

    pip install numpy
    python3 tools/generate_sounds.py

Tweak the parameters below and re-run to change how the sounds feel.
"""
import os
import wave

import numpy as np

SR = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")


# --------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------
def tvec(dur):
    return np.arange(int(SR * dur)) / SR


def norm(x, peak=0.85):
    m = np.max(np.abs(x))
    return x / m * peak if m > 0 else x


def fade(x, ms=4):
    n = int(SR * ms / 1000)
    x = x.copy()
    x[:n] *= np.linspace(0, 1, n)
    x[-n:] *= np.linspace(1, 0, n)
    return x


def spectral(x, curve):
    """Shape a signal by multiplying its spectrum with curve(freq_hz)."""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1 / SR)
    return np.fft.irfft(spec * curve(freqs), len(x))


def lowpass(x, cutoff):
    return spectral(x, lambda f: 1 / (1 + (f / cutoff) ** 4))


def bandpass(x, lo, hi):
    return spectral(x, lambda f: (1 / (1 + (lo / np.maximum(f, 1)) ** 4)) * (1 / (1 + (f / hi) ** 4)))


def formants(x, fmts, floor=0.03):
    def curve(f):
        g = np.full_like(f, floor)
        for freq, bw, gain in fmts:
            g += gain * np.exp(-0.5 * ((f - freq) / bw) ** 2)
        return g

    return spectral(x, curve)


def noise(n, rng):
    return rng.standard_normal(n)


def saw(f0):
    phase = np.cumsum(f0) / SR
    return 2 * (phase % 1.0) - 1


def smooth_random(n, rng, cutoff_hz):
    return lowpass(rng.standard_normal(n), cutoff_hz) * 4


def write(name, x):
    x = fade(np.clip(x, -1, 1))
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())
    print(f"{name}.wav  {len(x) / SR:.2f}s  {os.path.getsize(path) / 1024:.0f} KB")


# --------------------------------------------------------------------------
# zombie voices
# --------------------------------------------------------------------------
def groan(dur, f_start, f_end, seed, grit=2.5, breath=0.35, vowel_a=(320, 900), vowel_b=(750, 1250)):
    rng = np.random.default_rng(seed)
    t = tvec(dur)
    n = len(t)
    prog = t / dur
    f0 = f_start + (f_end - f_start) * prog ** 0.8
    f0 = f0 * (1 + 0.035 * np.sin(2 * np.pi * 5.2 * t)) * (1 + 0.04 * smooth_random(n, rng, 8))
    src = saw(f0) * 0.8 + breath * noise(n, rng) * (0.4 + 0.6 * prog)

    def vowel(f1, f2):
        return formants(src, [(f1, 90, 1.0), (f2, 140, 0.6), (2400, 300, 0.12)])

    a = vowel(*vowel_a)
    b = vowel(*vowel_b)
    mix = np.clip(np.sin(np.pi * prog) ** 1.2, 0, 1)        # mouth opens, then closes
    x = a * (1 - mix) + b * mix
    env = np.minimum(1, t / 0.09) * np.exp(-((prog - 0.35) ** 2) / 0.22)
    env *= 1 + 0.15 * np.sin(2 * np.pi * 7 * t)
    x = np.tanh(norm(x, 1.0) * grit) * env
    return norm(lowpass(x, 3500))


def roar(dur=1.5, seed=11):
    rng = np.random.default_rng(seed)
    t = tvec(dur)
    n = len(t)
    prog = t / dur
    f0 = 62 - 22 * prog
    f0 = f0 * (1 + 0.05 * np.sin(2 * np.pi * 6 * t)) * (1 + 0.05 * smooth_random(n, rng, 10))
    src = saw(f0) + 0.6 * noise(n, rng)
    x = formants(src, [(450, 110, 1.0), (1000, 180, 0.7), (1900, 300, 0.25)])
    growl = 0.6 + 0.4 * np.sign(np.sin(2 * np.pi * 26 * t)) * 0.5 + 0.2 * np.sin(2 * np.pi * 26 * t)
    env = np.minimum(1, t / 0.12) * np.exp(-((prog - 0.3) ** 2) / 0.18)
    x = np.tanh(norm(x, 1.0) * 3.5) * env * growl
    return norm(lowpass(x, 3000))


# --------------------------------------------------------------------------
# effects
# --------------------------------------------------------------------------
def shot():
    rng = np.random.default_rng(3)
    t = tvec(0.16)
    crack = bandpass(noise(len(t), rng), 1200, 6000) * np.exp(-t * 70)
    body = bandpass(noise(len(t), rng), 150, 1500) * np.exp(-t * 32)
    thump = np.sin(2 * np.pi * np.cumsum(60 + 200 * np.exp(-t * 45)) / SR) * np.exp(-t * 28)
    return norm(crack * 0.8 + body * 0.6 + thump * 0.9)


def hit():
    rng = np.random.default_rng(4)
    t = tvec(0.11)
    wet = lowpass(noise(len(t), rng), 1400) * np.exp(-t * 45)
    thud = np.sin(2 * np.pi * np.cumsum(150 - 60 * (t / 0.11)) / SR) * np.exp(-t * 32)
    return norm(wet * 0.9 + thud * 0.9)


def zombie_death():
    rng = np.random.default_rng(5)
    dur = 0.5
    t = tvec(dur)
    n = len(t)
    prog = t / dur
    f0 = 95 - 60 * prog
    src = saw(f0 * (1 + 0.06 * smooth_random(n, rng, 12)))
    voice = formants(src, [(500, 100, 1), (900, 150, 0.6)])
    squelch = lowpass(noise(n, rng), 900) * (0.5 + 0.5 * np.sin(2 * np.pi * 19 * t + 6 * smooth_random(n, rng, 20)))
    x = (np.tanh(norm(voice, 1) * 2.5) * 0.8 + squelch * 0.9) * np.exp(-prog * 3.2)
    return norm(lowpass(x, 3200))


def hurt():
    rng = np.random.default_rng(6)
    t = tvec(0.28)
    f0 = 120 - 55 * (t / 0.28)
    x = np.tanh(saw(f0) * 3) * np.exp(-t * 9)
    x += bandpass(noise(len(t), rng), 300, 3000) * np.exp(-t * 40) * 0.7
    return norm(lowpass(x, 2500))


def pickup():
    t = tvec(0.32)

    def note(freq, start, length):
        m = (t >= start) & (t < start + length)
        tt = np.where(m, t - start, 0)
        y = (np.sin(2 * np.pi * freq * tt) + 0.3 * np.sin(4 * np.pi * freq * tt)) * np.exp(-tt * 14)
        return y * m

    return norm(note(660, 0.0, 0.16) + note(990, 0.09, 0.23))


def wave_start():
    t = tvec(1.0)
    prog = t / 1.0
    f = 110 + 36 * prog
    horn = saw(f) * 0.8 + saw(f * 1.5) * 0.4
    horn = lowpass(horn, 1000) * (0.6 + 0.4 * prog) + lowpass(horn, 1800) * 0.4 * prog
    env = np.minimum(1, t / 0.15) * np.exp(-((prog - 0.45) ** 2) / 0.12)
    return norm(horn * env)


def game_over():
    rng = np.random.default_rng(7)
    dur = 1.8
    t = tvec(dur)
    prog = t / dur
    f = 220 * (0.25 ** prog)
    tone = saw(f) * 0.7 + saw(f * 1.5) * 0.3
    tone = lowpass(tone, 1200)
    rumble = lowpass(noise(len(t), rng), 220) * 3
    x = (tone * 0.8 + rumble * 0.5) * np.exp(-prog * 2.2)
    return norm(x * np.minimum(1, t / 0.05))


# --------------------------------------------------------------------------
if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    write("groan1", groan(1.0, 95, 65, seed=1))
    write("groan2", groan(1.35, 78, 48, seed=2, breath=0.45, vowel_a=(300, 800), vowel_b=(650, 1100)))
    write("groan3", groan(0.8, 120, 82, seed=3, grit=3.2, vowel_a=(380, 1000), vowel_b=(820, 1400)))
    write("roar", roar())
    write("shot", shot())
    write("hit", hit())
    write("zombie_death", zombie_death())
    write("hurt", hurt())
    write("pickup", pickup())
    write("wave_start", wave_start())
    write("game_over", game_over())
