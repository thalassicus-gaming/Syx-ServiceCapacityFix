// ThalSpeciesCapacity.java
// Document Version 1.0.0
// Creation date: 2026/07/16
// Creator: Thalassicus

package thalassicus.util;

import init.race.RACES;
import init.race.Race;
import init.type.HCLASS_RACE;
import init.type.NEED;
import util.gui.misc.GBox;
import util.info.GFORMAT;

// Shows how a service's Capacity figure would look if the city's appetite
// for it matched a specific race's own rate instead of Jake's default
// (HCLASS_RACE.clP(null, null)) rate - not this city's true population-
// weighted average, which is a simpler, more limited claim than it might
// first appear, but a stable and consistent one every race is measured
// against the same way. A pure rescaling of whatever capacity figure the
// caller already computed (baseCapacity * baseRate / raceRate) - this class
// holds no state, computes no capacity of its own, and depends on nothing
// else in this mod, unlike the deprecated calculator this replaced.
public final class ThalSpeciesCapacity {

  private ThalSpeciesCapacity() {
  }

  private static final CharSequence NOT_USED_LABEL = "Never";

  // Guards against a race whose rate is nonzero but vanishingly small
  // (rather than a clean 0.0) - without this, dividing by a near-zero rate
  // would produce an enormous, borderline-nonsensical number instead of
  // correctly reporting the service as effectively unused by that race.
  // Comfortably below any real rate value seen in this codebase's race
  // files (typically in the 0.5-3.0 range).
  private static final double MINIMUM_RATE_THRESHOLD = 0.00001;

  public static void appendDivergenceLines(GBox tooltipBox, NEED serviceNeed, double baseCapacity) {
    if (serviceNeed == null) {
      return;
    }

    double baseRate = serviceNeed.rate.get(HCLASS_RACE.clP(null, null));
    for (Race currentRace : RACES.all()) {
      if (currentRace.all(serviceNeed.rate).size() > 0) {
        double raceRate = currentRace.bvalue(serviceNeed.rate);
        tooltipBox.NL();
        tooltipBox.textL(currentRace.info.names);
        tooltipBox.tab(6);
        if (raceRate <= MINIMUM_RATE_THRESHOLD) {
          tooltipBox.text(NOT_USED_LABEL);
        } else {
          tooltipBox.add(GFORMAT.i(tooltipBox.text(), (int) (baseCapacity * baseRate / raceRate)));
        }
      }
    }
  }
}
