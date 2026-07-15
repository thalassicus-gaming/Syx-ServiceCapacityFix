// ThalWelfordMean.java
// Document Version 1.0.1
// Creation date: 2026/07/15
// Creator: Thalassicus

package thalassicus.util;

import java.io.IOException;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;

//
// ============================================================================
// THAL WELFORD MEAN
// ============================================================================
// A simple, dependency-free running mean over an unbounded sequence of
// doubles, updated one value at a time via Welford's online algorithm
// (mean += (value - mean) / n) rather than accumulating a sum and dividing
// at the end. Mathematically equivalent to sum/n at every step - confirmed
// directly, not just asserted - but numerically steadier over very long
// sequences, since it never needs to hold a single large running sum that
// could itself accumulate floating-point drift.
//
// Deliberately unrelated to ThalRollingAverage, not a variant of it: that
// class holds a FIXED-size window (old samples evicted as new ones arrive),
// built for a short, responsive, always-current average (one calendar day,
// for Live capacity). This class holds an UNBOUNDED, whole-lifetime average
// (built for savefile-wide calibration, averaged over the entire time a
// mod has been observing a city) - two genuinely different statistics for
// two genuinely different purposes, not one generalized into the other.
//
// Has no concept of cadence, calendar time, or what the sequence of pushed
// values actually represents - exactly like ThalRollingAverage, that is
// entirely the caller's responsibility. This class only knows how to
// maintain a running mean of whatever doubles it's given.
// ============================================================================
//

public final class ThalWelfordMean {

  private double mean = 0.0;
  private long sampleCount = 0;

  public void push(double value) {
    this.sampleCount++;
    this.mean += (value - this.mean) / this.sampleCount;
  }

  public double mean() {
    return this.sampleCount == 0 ? 0.0 : this.mean;
  }

  public long sampleCount() {
    return this.sampleCount;
  }

  public void save(FilePutter file) {
    // Cast to int for serialization: FilePutter.i(...) only accepts int, and
    // sampleCount as a long exists purely to avoid any overflow risk during
    // runtime accumulation, not because a save file could plausibly need to
    // persist a count anywhere near Integer.MAX_VALUE (over 2 billion) at
    // whatever cadence a caller actually pushes at.
    file.i((int) this.sampleCount);
    file.d(this.mean);
  }

  public void load(FileGetter file) throws IOException {
    this.sampleCount = file.i();
    this.mean = file.d();
  }
}
