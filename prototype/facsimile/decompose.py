"""FACSIMILE stage 1: pull each reference loop apart into drums and a timeline.

Why this stage exists: drums in a loop overlap (a hat lands on a kick, a snare tail runs under
the next hat), so cutting a "kick moment" out of the loop would fit a kick-plus-hat. NMFD
(non-negative matrix factor deconvolution, Smaragdis 2004) explains the whole loop's spectrogram
as a few spectro-temporal templates, each fired along its own activation timeline:

    V[f, t]  ~=  sum_k sum_tau  W[k, f, tau] * H[k, t - tau]

W[k] is one drum's average spectrogram from its attack onwards; H[k] says when and how hard it
fires. The loop is periodic, so the convolution is circular: a long 808 tail from the last bar
rings into bar one, exactly as it does when the loop repeats on a sampler.

Inputs are the reference WAVs (licensed PCM, never committed). Outputs, per loop, go to an
out-of-repo work folder: the templates and activations (numbers), each component's soft-masked
isolation (PCM derived from the reference, so it stays out of the repo), and a contact sheet
image to check the split by eye.

    python3 -I decompose.py REF_DIR WORK_DIR
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
from scipy import signal

RATE = 44_100
NFFT = 2048
HOP = 256          # 5.8 ms: the template is the fitting target, so it needs attack detail
BANDS = 120        # log-spaced analysis bands, 30 Hz to 18 kHz (about a tenth of an octave)
TEMPLATE_S = 1.4   # longest drum the decomposition can describe: the 808 tails measured
                   # reach about 0.8 s to -30 dB, and the template needs room to fall further)
ITER = 220

# Components per loop, chosen by inspecting the contact sheets: one per distinct drum.
COMPONENTS = {
    "102": 6, "103": 6, "106": 7, "113": 7, "127": 6, "134": 7, "154": 6, "163": 8, "90": 6,
}


def bpm_of(name: str) -> int:
    return int(re.search(r"TRNDY_(\d+)_", name).group(1))


def filterbank(n_bins: int) -> np.ndarray:
    """Triangular log-band filterbank, rows normalized to sum 1 so band energies stay comparable."""
    freqs = np.fft.rfftfreq(NFFT, 1 / RATE)
    edges = np.geomspace(30, 18_000, BANDS + 2)
    fb = np.zeros((BANDS, n_bins))
    for b in range(BANDS):
        lo, mid, hi = edges[b], edges[b + 1], edges[b + 2]
        up = (freqs - lo) / (mid - lo)
        down = (hi - freqs) / (hi - mid)
        fb[b] = np.clip(np.minimum(up, down), 0, None)
        if fb[b].sum() == 0:  # very low bands narrower than one bin: take the nearest bin
            fb[b, np.argmin(np.abs(freqs - mid))] = 1
    return fb / fb.sum(axis=1, keepdims=True)


def onsets(mag: np.ndarray, loose: bool = False) -> tuple[np.ndarray, np.ndarray]:
    """Spectral-flux onsets on the log magnitude; returns frame indices and their strengths.
    `loose` lowers the bar so soft ghost notes and hats get a place NMFD may fire."""
    lm = np.log1p(100 * mag)
    flux = np.maximum(0, np.diff(lm, axis=1, prepend=lm[:, :1])).sum(axis=0)
    flux = flux - signal.medfilt(flux, 9)
    pk, props = signal.find_peaks(flux, height=np.percentile(flux, 55 if loose else 75),
                                 distance=6 if loose else 8)
    return pk, props["peak_heights"]


def kmeans(x: np.ndarray, k: int, seed: int = 7, iters: int = 60) -> np.ndarray:
    """Deterministic k-means++ (fixed seed): used only to seed NMFD with plausible drum groups."""
    rng = np.random.default_rng(seed)
    centres = [x[rng.integers(len(x))]]
    for _ in range(1, k):
        d = np.min([((x - c) ** 2).sum(1) for c in centres], axis=0)
        centres.append(x[rng.choice(len(x), p=d / d.sum())])
    c = np.array(centres)
    for _ in range(iters):
        lab = np.argmin(((x[:, None, :] - c[None]) ** 2).sum(-1), axis=1)
        c = np.array([x[lab == j].mean(0) if np.any(lab == j) else c[j] for j in range(k)])
    return lab


GRACE_S = 0.040   # a clap's bursts and a flam live inside this; after it a band may only decay


def decay_projection(W: np.ndarray) -> None:
    """Force every band of every template to be non-increasing after its peak (past a short
    grace window). A single drum strike decays; without this, NMFD happily learns a template
    holding two hits, i.e. a pattern instead of a drum."""
    g = int(GRACE_S * RATE / HOP)
    W[:, :, g:] = np.minimum.accumulate(
        np.concatenate([W[:, :, :g + 1].max(axis=2, keepdims=True), W[:, :, g:]], axis=2),
        axis=2)[:, :, 1:]


def nmfd(V: np.ndarray, W: np.ndarray, H: np.ndarray, support: np.ndarray,
         sparsity: float = 0.02):
    """Circular NMFD with KL-divergence multiplicative updates, computed with FFTs.

    `support` (T,) is 1 at detected onsets and 0 elsewhere: a drum may only fire where
    something starts. Multiplicative updates keep zeros at zero, so masking H once suffices.

    V: (F, T); W: (K, F, L); H: (K, T). Each W[k] is renormalized to unit sum every iteration
    and its scale moved into H[k], so 'how loud' lives only in the timeline."""
    K, F, L = W.shape
    T = V.shape[1]
    eps = 1e-12
    H *= support[None, :]
    for it in range(ITER):
        Wf = np.fft.rfft(W, n=T, axis=2)                 # (K, F, Tf)
        Hf = np.fft.rfft(H, axis=1)                      # (K, Tf)
        lam = np.fft.irfft((Wf * Hf[:, None, :]).sum(0), n=T, axis=1) + eps
        R = V / lam
        Rf = np.fft.rfft(R, axis=1)                      # (F, Tf)
        # H update: correlate the ratio with each template over frequency and lag.
        num_h = np.fft.irfft((np.conj(Wf) * Rf[None]).sum(1), n=T, axis=1)
        den_h = W.sum(axis=(1, 2))[:, None] + sparsity
        H *= np.maximum(num_h, 0) / den_h
        # W update: correlate the ratio with each timeline.
        Hf = np.fft.rfft(H, axis=1)
        lam = np.fft.irfft((Wf * Hf[:, None, :]).sum(0), n=T, axis=1) + eps
        Rf = np.fft.rfft(V / lam, axis=1)
        num_w = np.fft.irfft(Rf[None] * np.conj(Hf)[:, None, :], n=T, axis=2)[:, :, :L]
        den_w = H.sum(axis=1)[:, None, None] + eps
        W *= np.maximum(num_w, 0) / den_w
        decay_projection(W)
        s = W.sum(axis=(1, 2)) + eps
        W /= s[:, None, None]
        H *= s[:, None]
    Wf = np.fft.rfft(W, n=T, axis=2)
    Hf = np.fft.rfft(H, axis=1)
    parts = np.fft.irfft(Wf * Hf[:, None, :], n=T, axis=2)  # (K, F, T): each drum's share
    return W, H, np.maximum(parts, 0)


def main(ref_dir: Path, work: Path) -> None:
    work.mkdir(parents=True, exist_ok=True)
    summary = {}
    for path in sorted(ref_dir.glob("*.wav")):
        name = path.stem
        key = str(bpm_of(name))
        x, sr = sf.read(path, always_2d=True)
        assert sr == RATE
        mono = x.mean(axis=1)  # the house renders mono; stereo width is not replicated
        n = len(mono)
        # Analyse a whole number of hops of the loop, wrapped, so the STFT is circular too.
        T_frames = int(np.ceil(n / HOP))
        # Frame j is centred on loop sample j*HOP; samples before 0 come from the loop's end.
        wrapped = mono[(np.arange(T_frames * HOP + NFFT) - NFFT // 2) % n]
        _, _, X = signal.stft(wrapped, RATE, nperseg=NFFT, noverlap=NFFT - HOP, boundary=None,
                              padded=False)
        X = X[:, :T_frames]
        mag = np.abs(X)
        fb = filterbank(mag.shape[0])
        V = fb @ mag + 1e-9
        T = V.shape[1]
        L = int(TEMPLATE_S * RATE / HOP)
        K = COMPONENTS[key]

        # Seed: cluster the onsets by their early band profile, average what follows each one.
        on, strength = onsets(mag)
        prof = np.array([np.log(V[:, o:o + 6].mean(1) + 1e-9) for o in on])
        prof = (prof - prof.mean(0)) / (prof.std(0) + 1e-6)
        lab = kmeans(prof, K)
        W = np.full((K, BANDS, L), 1e-6)
        H = np.full((K, T), 1e-6)
        for j in range(K):
            idx = on[lab == j]
            for o in idx:
                seg = np.roll(V, -o, axis=1)[:, :L]
                W[j] += seg / max(1, len(idx))
            H[j, idx] = strength[lab == j]
        W /= W.sum(axis=(1, 2), keepdims=True)
        H *= V.sum() / max(H.sum(), 1e-9) / L

        support = np.zeros(T)
        for o in onsets(mag, loose=True)[0]:
            support[max(0, o - 1):o + 2] = 1
        H = np.maximum(H, 1e-3 * H.max() * support[None, :])
        W, H, parts = nmfd(V, W, H, support)
        recon = parts.sum(0) + 1e-9
        kl = float((V * np.log(V / recon) - V + recon).sum() / V.sum())

        # Soft masks back on the full-resolution STFT give each drum's isolated audio.
        isolated = []
        for k in range(K):
            mask_band = parts[k] / recon                       # (BANDS, T)
            mask_bin = np.clip(fb.T @ mask_band / (fb.T.sum(1, keepdims=True) + 1e-9), 0, 1)
            Xk = X * mask_bin
            Xk = np.concatenate([Xk, Xk[:, :NFFT // HOP]], axis=1)  # wrap for overlap-add
            _, y = signal.istft(Xk, RATE, nperseg=NFFT, noverlap=NFFT - HOP, boundary=False)
            y = y[NFFT // 2:NFFT // 2 + n]
            isolated.append(y)
            sf.write(work / f"{key}_c{k}.wav", y.astype(np.float32), RATE, subtype="FLOAT")

        np.savez_compressed(work / f"{key}_nmfd.npz", W=W, H=H, fb=fb, hop=HOP, rate=RATE)
        energy = [float(np.sum(p)) / float(V.sum()) for p in parts]
        summary[key] = dict(name=name, bpm=bpm_of(name), seconds=len(mono) / RATE, K=K,
                            kl_per_unit=round(kl, 4), energy_share=[round(e, 3) for e in energy])
        contact_sheet(work / f"{key}_sheet.png", name, W, H, fb, energy)
        print(key, json.dumps(summary[key]))
    (work / "decompose.json").write_text(json.dumps(summary, indent=1))


def contact_sheet(path: Path, name: str, W, H, fb, energy) -> None:
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    K = W.shape[0]
    fig, ax = plt.subplots(2, K, figsize=(2.6 * K, 5.2), gridspec_kw={"height_ratios": [3, 1]})
    edges = np.geomspace(30, 18_000, BANDS + 2)[1:-1]
    for k in range(K):
        ax[0, k].pcolormesh(np.arange(W.shape[2]) * HOP / RATE, edges,
                            20 * np.log10(W[k] + 1e-9), vmin=-90, vmax=-25, shading="auto",
                            cmap="magma")
        ax[0, k].set_yscale("log")
        ax[0, k].set_xlim(0, 0.6)
        ax[0, k].set_title(f"c{k}  {100 * energy[k]:.0f}%", fontsize=9)
        ax[1, k].plot(np.arange(H.shape[1]) * HOP / RATE, H[k], lw=0.6)
        ax[1, k].set_yticks([])
    fig.suptitle(name, fontsize=10)
    fig.tight_layout()
    fig.savefig(path, dpi=60)
    plt.close(fig)


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
