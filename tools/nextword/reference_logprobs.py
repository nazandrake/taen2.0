#!/usr/bin/env python3
"""Reference log-probabilities for the ONNX Runtime wrapper of the next-word model (OrtNextWord), made with the Python ONNX Runtime 1.27.0 and the model's
own tokenizer (Hugging Face tokenizers 0.22.1) on the build machine.

usage: reference_logprobs.py <model_int8.onnx> <tokenizer.json> <out.tsv>

One fixed sentence is fed the way OrtNextWord feeds it: at once in chunks of 8 tokens (a fresh window), and word by word with the attention cache
(4, then 5, then the rest). The model is dynamically quantised: the activation scales are computed per run, so the two feedings give somewhat different
numbers, and each one must be matched exactly. Output lines: feeding, token id, log-probability (the 10 most likely tokens of each feeding).
"""
import sys

import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

TEXT = "Thank you very much for the lovely dinner and the great"


def main():
    model, tokjson, out = sys.argv[1:4]
    ids = Tokenizer.from_file(tokjson).encode(TEXT, add_special_tokens=False).ids
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED
    so.intra_op_num_threads = 4
    s = ort.InferenceSession(model, so, providers=["CPUExecutionProvider"])
    ins = {i.name: i for i in s.get_inputs()}
    kv = sorted(n for n in ins if n.startswith("past_key_values"))
    h, d = ins[kv[0]].shape[1], ins[kv[0]].shape[3]
    outs = [o.name for o in s.get_outputs()]

    def run(chunks):
        past = {k: np.zeros((1, h, 0, d), dtype=np.float32) for k in kv}
        pos = 0
        for c in chunks:
            n = len(c)
            feed = {"input_ids": np.array([c], dtype=np.int64), "attention_mask": np.ones((1, pos + n), dtype=np.int64),
                    "position_ids": np.arange(pos, pos + n, dtype=np.int64)[None, :]}
            feed.update(past)
            r = s.run(outs, feed)
            past = {k: r[outs.index(k.replace("past_key_values", "present"))] for k in kv}
            pos += n
            logits = r[0][0, -1].astype(np.float64)
        m = logits.max()
        return logits - (m + np.log(np.exp(logits - m).sum()))

    feedings = {"fresh": [ids[i:i + 8] for i in range(0, len(ids), 8)], "stepwise": [ids[:4], ids[4:9], ids[9:]]}
    with open(out, "w") as f:
        f.write("# %s | %d tokens: %s\n" % (TEXT, len(ids), " ".join(map(str, ids))))
        for name, chunks in feedings.items():
            lp = run(chunks)
            for t in np.argsort(-lp)[:10]:
                f.write("%s\t%d\t%.6f\n" % (name, t, lp[t]))


if __name__ == "__main__":
    main()
