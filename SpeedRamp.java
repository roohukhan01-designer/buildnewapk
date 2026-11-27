package com.roohu.clipforge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Speed ramps for one clip: a list of segments that cover the whole clip, each with its own speed (1 = normal).
 * Times are seconds from the clip start. The page already cleans the AI's ramps (shared/core/motion.js cleanRamps);
 * this class checks them again, because Java never trusts the page. The maths of out() matches motion.js mapTime().
 */
final class SpeedRamp {
    final double[] from, to, speed;

    private SpeedRamp(double[] from, double[] to, double[] speed) { this.from = from; this.to = to; this.speed = speed; }

    /** Returns null when there is nothing usable, so the clip is exported at normal speed. */
    static SpeedRamp parse(JSONArray ramps, double dur) {
        if (ramps == null || ramps.length() == 0 || dur < 3) return null;
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < ramps.length(); i++) {
            JSONObject o = ramps.optJSONObject(i);
            if (o == null) continue;
            double a = o.optDouble("a", -1), b = o.optDouble("b", -1), s = o.optDouble("speed", 1);
            if (Double.isNaN(a) || Double.isNaN(b) || Double.isNaN(s)) continue;
            s = Math.max(0.3, Math.min(3.0, s));
            a = Math.max(0.5, a);
            b = Math.min(dur - 0.3, b);
            if (b - a < 0.4 || Math.abs(s - 1) < 0.05) continue;
            r.add(new double[]{a, b, s});
        }
        Collections.sort(r, (p, q) -> Double.compare(p[0], q[0]));
        List<double[]> kept = new ArrayList<>();
        for (double[] x : r) {
            if (!kept.isEmpty() && x[0] < kept.get(kept.size() - 1)[1] + 0.3) continue;   // no overlaps
            kept.add(x);
            if (kept.size() >= 4) break;
        }
        if (kept.isEmpty()) return null;
        // fill the gaps with normal-speed segments so the segments cover 0..dur without holes
        List<double[]> seg = new ArrayList<>();
        double pos = 0;
        for (double[] x : kept) {
            if (x[0] - pos > 0.05) seg.add(new double[]{pos, x[0], 1});
            seg.add(x);
            pos = x[1];
        }
        if (dur - pos > 0.05) seg.add(new double[]{pos, dur, 1});
        else seg.get(seg.size() - 1)[1] = dur;
        double[] f = new double[seg.size()], t = new double[seg.size()], v = new double[seg.size()];
        for (int i = 0; i < seg.size(); i++) { f[i] = seg.get(i)[0]; t[i] = seg.get(i)[1]; v[i] = seg.get(i)[2]; }
        return new SpeedRamp(f, t, v);
    }

    int count() { return from.length; }

    /** Length of the finished clip in seconds. */
    double outDuration() { return out(to[to.length - 1]); }

    /** Where a time in the original clip lands in the finished clip. */
    double out(double t) {
        double acc = 0;
        for (int i = 0; i < from.length; i++) {
            if (t <= from[i]) return acc;
            double end = Math.min(t, to[i]);
            acc += (end - from[i]) / speed[i];
            if (t <= to[i]) return acc;
        }
        return acc + (t - to[to.length - 1]);
    }

    /** New list of times (seconds from clip start) moved into finished-clip time. */
    double[] mapTimes(double[] t) {
        double[] r = new double[t.length];
        for (int i = 0; i < t.length; i++) r[i] = out(t[i]);
        return r;
    }

    /** Same for caption cues. Cue and word times are absolute (video time), so the clip start is removed and added back. */
    JSONArray mapCues(JSONArray cues, double clipStart) throws Exception {
        if (cues == null) return null;
        JSONArray res = new JSONArray();
        for (int i = 0; i < cues.length(); i++) {
            JSONObject c = cues.getJSONObject(i);
            JSONObject n = new JSONObject();
            n.put("a", clipStart + out(c.getDouble("a") - clipStart));
            n.put("b", clipStart + out(c.getDouble("b") - clipStart));
            JSONArray w = c.optJSONArray("w"), nw = new JSONArray();
            if (w != null) for (int k = 0; k < w.length(); k++) {
                JSONArray x = w.getJSONArray(k);
                nw.put(new JSONArray().put(x.getString(0))
                        .put(clipStart + out(x.getDouble(1) - clipStart))
                        .put(clipStart + out(x.getDouble(2) - clipStart)));
            }
            n.put("w", nw);
            res.put(n);
        }
        return res;
    }
}
