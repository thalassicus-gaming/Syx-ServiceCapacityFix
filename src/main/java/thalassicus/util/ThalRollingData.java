// ThalRollingData.java
// Document Version 1.2.1
// Creation date: 2026/07/15
// Creator: Thalassicus

package thalassicus.util;

import java.io.IOException;
import java.util.Arrays;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.SAVABLE;

//
// ============================================================================
// THAL ROLLING DATA
// ============================================================================
// A simple, fixed-capacity circular buffer of doubles - a general rolling-
// window statistics utility, not just an average. push()/average()/
// sampleCount() remain O(1) via a running sum. The additional statistics
// below (max/min/median/mode/range/stdev) are O(n) in the window size,
// computed fresh on each call rather than incrementally maintained, since
// none of them admit an O(1) update under eviction the way a running sum
// does for the mean - this is a deliberate simplicity-over-throughput
// choice, acceptable given this class's actual call sites are UI-tooltip-
// rate, not per-tick.
//
// Deliberately has no concept of cadence, calendar time, or what a "window"
// means in real-world or in-game terms - that is entirely the caller's
// responsibility (e.g. ThalAIScanner deriving a sample count from
// TIME.secondsPerDay() and its own scan interval). This class only knows
// how to hold N doubles and report statistics about them.
//
// average() divides by the actual number of samples pushed so far (capped
// at capacity), not by the fixed capacity itself, so the reported average
// is correct during the initial fill-up period rather than understated by
// treating not-yet-written slots as zero. Every statistic below follows the
// same convention via validSamples(), which returns only the slots actually
// written so far - never the buffer's stale/default zero-filled tail during
// the initial fill-up period.
//
// Design choices worth naming explicitly:
// - stdev() is the POPULATION standard deviation (divides by sampleCount,
//   not sampleCount - 1), matching average() already being a population
//   mean rather than a sample-based estimate of some larger population.
// - median() of an even-sized window averages the two middle values, the
//   standard convention.
// - mode() breaks ties (multiple values sharing the same max frequency) by
//   returning the smallest such value, for determinism.
// ============================================================================
//

public final class ThalRollingData implements SAVABLE {

    private final double[] buffer;
    private int writeIndex = 0;
    private int sampleCount = 0;
    private double sum = 0.0;

    public ThalRollingData(int capacity) {
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

    // Returns only the slots actually written so far: the whole buffer once
    // isFull(), or just buffer[0..sampleCount) during the initial fill-up
    // period. A defensive copy, since max()/min()/median()/mode() all sort or
    // otherwise mutate their working array.
    private double[] validSamples() {
        return this.sampleCount < this.buffer.length
                ? Arrays.copyOfRange(this.buffer, 0, this.sampleCount)
                : this.buffer.clone();
    }

    public double max() {
        if (this.sampleCount == 0) {
            return 0.0;
        }

        double result = this.buffer[0];
        for (double value : this.validSamples()) {
            if (value > result) {
                result = value;
            }
        }

        return result;
    }

    public double min() {
        if (this.sampleCount == 0) {
            return 0.0;
        }

        double result = this.buffer[0];
        for (double value : this.validSamples()) {
            if (value < result) {
                result = value;
            }
        }

        return result;
    }

    public double range() {
        return this.sampleCount == 0 ? 0.0 : this.max() - this.min();
    }

    public double median() {
        if (this.sampleCount == 0) {
            return 0.0;
        }

        double[] samples = this.validSamples();
        Arrays.sort(samples);
        int sampleTotal = samples.length;
        return sampleTotal % 2 == 1
                ? samples[sampleTotal / 2]
                : (samples[sampleTotal / 2 - 1] + samples[sampleTotal / 2]) / 2.0;
    }

    // Ties (multiple values sharing the same max frequency) resolve to the
    // smallest such value, for determinism - see the class-level note.
    public double mode() {
        if (this.sampleCount == 0) {
            return 0.0;
        }

        double[] samples = this.validSamples();
        Arrays.sort(samples);
        double bestValue = samples[0];
        int bestCount = 1;
        double currentValue = samples[0];
        int currentCount = 1;
        for (int sampleIndex = 1; sampleIndex < samples.length; sampleIndex++) {
            if (samples[sampleIndex] == currentValue) {
                currentCount++;
            } else {
                currentValue = samples[sampleIndex];
                currentCount = 1;
            }

            if (currentCount > bestCount) {
                bestCount = currentCount;
                bestValue = currentValue;
            }
        }

        return bestValue;
    }

    // Population standard deviation - see the class-level note on why this
    // divides by sampleCount rather than sampleCount - 1.
    public double stdev() {
        if (this.sampleCount == 0) {
            return 0.0;
        }

        double mean = this.average();
        double sumSquaredDifference = 0.0;
        for (double value : this.validSamples()) {
            double difference = value - mean;
            sumSquaredDifference += difference * difference;
        }

        return Math.sqrt(sumSquaredDifference / this.sampleCount);
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

    // Resets to the same fresh, empty state as a brand-new instance -
    // required by SAVABLE, and used by SuperSaver (via ThalSavable's
    // registry) whenever a saved entry's key isn't recognized this
    // session, so that entry doesn't silently retain stale data from
    // whatever was loaded before this method ran.
    public void clear() {
        Arrays.fill(this.buffer, 0.0);
        this.writeIndex = 0;
        this.sampleCount = 0;
        this.sum = 0.0;
    }
}
