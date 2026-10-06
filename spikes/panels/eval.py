#!/usr/bin/env python3
"""Scores panel detections against hand-labelled ground truth.

usage: eval.py <gt.json> <detections.json> [--pages]
Greedy one-to-one matching by IoU >= 0.5. Reports precision, recall, mean IoU of matches,
reading-order correctness and fully correct pages, overall and without approximate labels.
"""
import json
import sys


def iou(a, b):
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / union if union > 0 else 0.0


def match(gt, det):
    pairs = sorted(((iou(g, d), gi, di) for gi, g in enumerate(gt) for di, d in enumerate(det)), reverse=True)
    used_g, used_d, out = set(), set(), []
    for v, gi, di in pairs:
        if v < 0.5:
            break
        if gi in used_g or di in used_d:
            continue
        used_g.add(gi)
        used_d.add(di)
        out.append((gi, di, v))
    return out


def score(gt_pages, det_pages, names):
    tp = n_gt = n_det = 0
    ious, order_ok, order_n, full = [], 0, 0, 0
    for p in names:
        g, d = gt_pages[p]["panels"], det_pages[p]["panels"]
        m = match(g, d)
        tp += len(m)
        n_gt += len(g)
        n_det += len(d)
        ious += [v for _, _, v in m]
        if len(m) >= 2:
            order_n += 1
            seq = [gi for gi, di, _ in sorted(m, key=lambda t: t[1])]
            order_ok += seq == sorted(seq)
        full += len(m) == len(g) == len(d)
    return dict(
        pages=len(names), gt=n_gt, det=n_det, tp=tp,
        precision=round(tp / n_det, 3) if n_det else 0, recall=round(tp / n_gt, 3) if n_gt else 0,
        mean_iou=round(sum(ious) / len(ious), 3) if ious else 0,
        order=f"{order_ok}/{order_n}", fully_correct=f"{full}/{len(names)}",
    )


def main():
    gt = json.load(open(sys.argv[1]))["pages"]
    det = {d["page"]: d for d in json.load(open(sys.argv[2]))}
    names = sorted(gt)
    print("all     ", score(gt, det, names))
    print("exact   ", score(gt, det, [p for p in names if not gt[p].get("approx")]))
    ms = sorted(d["ms"] for d in det.values())
    print("jvm ms   p50", ms[len(ms) // 2], "p90", ms[int(len(ms) * 0.9)], "max", ms[-1])
    if "--pages" in sys.argv:
        for p in names:
            g, d = gt[p]["panels"], det[p]["panels"]
            m = match(g, d)
            flag = "ok " if len(m) == len(g) == len(d) else "   "
            print(f"  {flag}{p} gt {len(g):2} det {len(d):2} tp {len(m):2} fb {str(det[p]['fallback'])[0]} {gt[p].get('kind', '')}{' (approx)' if gt[p].get('approx') else ''}")


main()
