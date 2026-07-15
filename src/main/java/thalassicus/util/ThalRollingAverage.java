// ThalRollingAverage.java
// Document Version 1.1.0
// Creation date: 2026/07/15
// Creator: Thalassicus

package thalassicus.util;

import java.io.IOException;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;

//
// ============================================================================
// THAL ROLLING AVERAGE
// ============================================================================
// A simple, fixed-capacity circular buffer of doubles, exposing push() and
// average() in O(1) each via a running sum (subtract the evicted value,
// then add the new one, rather than resumming the whole buffer every call).
//
// Deliberately has no concept of cadence, calendar time, or what a "window"
// means in real-world or in-game terms - that is entirely the caller's
// responsibility (e.g. ThalAIScanner deriving a sample count from
// TIME.secondsPerDay() and its own scan interval). This class only knows
// how to hold N doubles and report their average.
//
// average() divides by the actual number of samples pushed so far (capped
// at capacity), not by the fixed capacity itself, so the reported average
// is correct during the initial fill-up period rather than understated by
// treating not-yet-written slots as zero.
// ============================================================================
//

public final class ThalRollingAverage {

  private final double[] buffer;
  private int writeIndex = 0;
  private int sampleCount = 0;
  private double sum = 0.0;

  public ThalRollingAverage(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive, was " + capacity);
    }
    this.buffer = new double[capacity];
  }

  public void push(double value) {
    if (this.sampleCount < this.buffer.length) {
      this.buffer[this.writeIndex] = value;
      this.sum += value;
      this.sampleCount++;
    } else {
      this.sum -= this.buffer[this.writeIndex];
      this.buffer[this.writeIndex] = value;
      this.sum += value;
    }

    this.writeIndex++;
    if (this.writeIndex >= this.buffer.length) {
      this.writeIndex = 0;
    }
  }

  public double average() {
    return this.sampleCount == 0 ? 0.0 : this.sum / this.sampleCount;
  }

  public int sampleCount() {
    return this.sampleCount;
  }

  public boolean isFull() {
    return this.sampleCount >= this.buffer.length;
  }

  public int capacity() {
    return this.buffer.length;
  }

  // Self-describing format: capacity is written first and read back before
  // anything else, so a caller with no real target for this data (e.g. an
  // obsolete NEED key from a removed mod) can still correctly consume the
  // right number of bytes via a throwaway instance, keeping the file cursor
  // correctly positioned for whatever is read next.
  //
  // On a capacity mismatch (e.g. a future version changes the scan interval
  // and therefore the window size), the saved data is silently discarded
  // rather than reconciled - this instance keeps its current, fresh, empty
  // state instead. No logging happens here deliberately: this class has no
  // outside dependencies by design, and does not know whether a mismatch is
  // a genuine version change or an intentional throwaway read: the caller
  // is better positioned to decide whether that is worth reporting.
  public void save(FilePutter file) {
    file.i(this.buffer.length);
    file.i(this.writeIndex);
    file.i(this.sampleCount);
    file.d(this.sum);
    for (double value : this.buffer) {
      file.d(value);
    }
  }

  public void load(FileGetter file) throws IOException {
    int savedCapacity = file.i();
    int savedWriteIndex = file.i();
    int savedSampleCount = file.i();
    double savedSum = file.d();
    double[] savedBuffer = new double[savedCapacity];
    for (int i = 0; i < savedCapacity; i++) {
      savedBuffer[i] = file.d();
    }

    if (savedCapacity == this.buffer.length) {
      this.writeIndex = savedWriteIndex;
      this.sampleCount = savedSampleCount;
      this.sum = savedSum;
      System.arraycopy(savedBuffer, 0, this.buffer, 0, savedCapacity);
    }
  }
}
