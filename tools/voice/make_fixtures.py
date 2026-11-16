#!/usr/bin/env python3
"""Writes the golden feature matrix for FbankTest from the numpy oracle (parakeet_ref.py).

usage: make_fixtures.py <out file>
The test signal is a fixed formula (three sines, one with a ramp), 0.4 s at 16 kHz in float32; FbankTest builds the same signal in Kotlin.
Output: first line "<frames>", then one line per mel bin with the frames' normalised features, 4 decimals.
"""
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import parakeet_ref  # noqa: E402


def signal():
    n = np.arange(6400)
    x = (0.3 * np.sin(2 * np.pi * 440.0 * n / 16000) + 0.2 * np.sin(2 * np.pi * 1234.5 * n / 16000 + 0.5)
         + 0.1 * np.sin(2 * np.pi * 3000.0 * n / 16000) * ((n % 800) / 800.0))
    return x.astype(np.float32)


if __name__ == "__main__":
    feats = parakeet_ref.features(signal())
    with open(sys.argv[1], "w") as out:
        out.write(f"{feats.shape[1]}\n")
        for row in feats:
            out.write(" ".join(f"{v:.4f}" for v in row) + "\n")
    print(feats.shape)
