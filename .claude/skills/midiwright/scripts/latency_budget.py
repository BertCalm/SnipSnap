#!/usr/bin/env python3
"""Sum a latency chain from cited or measured numbers and print the ledger line.

Every term carries a label (cited, measured, proposed, arithmetic) so the total can be
quoted honestly. Cited numbers are the ones in references/timing-and-scheduling.md §1 and
references/ble-midi.md §3, with their dates; override any term with a measurement from
the device in hand.

Usage:
  python3 latency_budget.py usb
  python3 latency_budget.py ble
  python3 latency_budget.py touch --buffer 2 --burst 96 --rate 48000
  python3 latency_budget.py touch --measured output=22 --measured touch=12
  python3 latency_budget.py ble --mode steady       # one extra buffer for jitter-free placement
"""
from __future__ import annotations

import argparse

CITED = {
    # name: (ms, label, source)
    "touch": (20.0, "cited", "Google, Android Developers Blog, March 2021: 10 to 35 ms, 20 typical"),
    "usb_in": (1.0, "cited", "Phil Burk (Google), March 2021: about 1 ms"),
    "ble_in": (13.0, "cited", "NIME 2019 7.5 ms at the minimum interval; Adafruit 10 to 20 ms; mid value"),
    "ble_jitter": (7.5, "cited", "up to one connection interval (Nordic); 7.5 ms minimum interval"),
    "parse_and_queue": (0.2, "proposed", "service thread copy into a ring, drained next block"),
    "output": (20.0, "cited", "conservative stand-in: Google, March 2021, average round trip 39 ms on popular phones; output side taken as about half; replace with the device's latencyMillis"),
}

CHAINS = {
    "usb": ["usb_in", "parse_and_queue", "block_wait", "buffer", "output"],
    "ble": ["ble_in", "ble_jitter", "parse_and_queue", "block_wait", "buffer", "output"],
    "touch": ["touch", "parse_and_queue", "block_wait", "buffer", "output"],
}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("chain", choices=CHAINS.keys())
    ap.add_argument("--burst", type=int, default=64, help="framesPerBurst (default 64)")
    ap.add_argument("--rate", type=int, default=44100, help="sample rate (default 44100)")
    ap.add_argument("--buffer", type=int, default=2, help="buffer size in bursts (OboeOutput.h uses 2)")
    ap.add_argument("--mode", choices=["fastest", "steady"], default="fastest",
                    help="steady adds one buffer so events land where they happened, jitter-free")
    ap.add_argument("--measured", action="append", default=[],
                    help="name=ms override from a device, e.g. output=22 (label becomes measured)")
    args = ap.parse_args()

    burst_ms = args.burst / args.rate * 1000.0
    terms = dict(CITED)
    terms["block_wait"] = (burst_ms / 2.0, "arithmetic", f"average wait for the next block: half a burst of {args.burst} frames at {args.rate} Hz")
    terms["buffer"] = (burst_ms * args.buffer, "arithmetic", f"{args.buffer} bursts queued ahead of the DAC")
    if args.mode == "steady":
        terms["steady_offset"] = (burst_ms * args.buffer, "proposed", "one buffer of constant delay so arrival jitter inside the buffer vanishes")
    for m in args.measured:
        name, ms = m.split("=")
        _, _, src = terms.get(name, (0, "", "device measurement"))
        terms[name] = (float(ms), "measured", f"measured on device (was: {src})")

    chain = list(CHAINS[args.chain])
    if args.mode == "steady":
        # Steady mode trades the jitter term for a constant offset: events are placed at
        # the frame they happened, one buffer late, so arrival jitter inside the buffer
        # vanishes. The BLE connection-interval jitter is the term it removes.
        chain = [t for t in chain if t != "ble_jitter"]
        chain.insert(chain.index("buffer") + 1, "steady_offset")

    total = 0.0
    labels = set()
    print(f"chain: {args.chain}  mode: {args.mode}  burst: {args.burst} frames at {args.rate} Hz ({burst_ms:.2f} ms)")
    print(f"{'term':<18}{'ms':>8}  {'label':<11}source")
    for name in chain:
        ms, label, src = terms[name]
        total += ms
        labels.add(label)
        print(f"{name:<18}{ms:>8.2f}  {label:<11}{src}")
    print(f"{'total':<18}{total:>8.2f}")
    worst = "measured" if labels == {"measured", "arithmetic"} else ("proposed" if "proposed" in labels else "cited")
    print()
    print(f"ledger line: about {total:.0f} ms {args.chain} chain in {args.mode} mode; the weakest label in the sum is '{worst}'.")
    print("Replace cited terms with OboeTester or WALT numbers from the device in hand before quoting this as a bound.")


if __name__ == "__main__":
    main()
