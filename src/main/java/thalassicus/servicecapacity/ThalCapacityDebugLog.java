// ThalCapacityDebugLog.java
// Document Version 1.0.1
// Creation date: 2026/07/16
// Creator: Thalassicus

package thalassicus.servicecapacity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

// Diagnostic CSV writer for capacity-estimate debugging. Writes one row per
// blueprint per scan, exposing every intermediate input to the live estimate
// side by side (raw and rolling-averaged slot counts, eligible population,
// derived per-slot rate, final estimate) so an out-of-range result can be
// traced to whichever input is off rather than guessed at from the final
// number alone. Controlled by ENABLED - flip to false to stop all file
// activity without touching call sites. Not part of the shipped feature.
final class ThalCapacityDebugLog {

  static final boolean ENABLED = true;

  private static final Path CSV_PATH =
      Path.of(System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalCapacityDebug.csv");
  private static final String DELIMITER = ",";
  private static final String HEADER = String.join(DELIMITER,
      "game_seconds",
      "time_of_day",
      "blueprint_key",
      "is_religion",
      "entities_scanned",
      "eligible_humanoids",
      "slot_total_raw",
      "slot_used_raw",
      "slot_total_avg",
      "slot_used_avg",
      "demand_pop_raw",
      "demand_pop_avg",
      "population_per_occupied_slot",
      "live_estimate_at_total"
  );

  private boolean truncatedThisSession = false;

  // A scan-wide row builder: the two context values (entities scanned,
  // eligible humanoids) are the same for every row in a given scan, so they
  // are set once per scan and reused across each blueprint's row.
  private long gameSeconds;
  private double timeOfDay;
  private int entitiesScanned;
  private int eligibleHumanoids;

  void beginScan(long gameSeconds, double timeOfDay, int entitiesScanned, int eligibleHumanoids) {
    this.gameSeconds = gameSeconds;
    this.timeOfDay = timeOfDay;
    this.entitiesScanned = entitiesScanned;
    this.eligibleHumanoids = eligibleHumanoids;
  }

  void row(
      String blueprintKey,
      boolean isReligion,
      int slotTotalRaw,
      int slotUsedRaw,
      double slotTotalAverage,
      double slotUsedAverage,
      double demandPopulationRaw,
      double demandPopulationAverage
  ) {
    if (!ENABLED) {
      return;
    }

    double populationPerOccupiedSlot = slotUsedAverage <= 0.0 ? 0.0 : demandPopulationAverage / slotUsedAverage;
    double liveEstimateAtTotal = populationPerOccupiedSlot * slotTotalRaw;

    String line = String.join(DELIMITER,
        Long.toString(this.gameSeconds),
        format(this.timeOfDay),
        blueprintKey,
        Boolean.toString(isReligion),
        Integer.toString(this.entitiesScanned),
        Integer.toString(this.eligibleHumanoids),
        Integer.toString(slotTotalRaw),
        Integer.toString(slotUsedRaw),
        format(slotTotalAverage),
        format(slotUsedAverage),
        format(demandPopulationRaw),
        format(demandPopulationAverage),
        format(populationPerOccupiedSlot),
        format(liveEstimateAtTotal)
    );

    this.append(line);
  }

  private static String format(double value) {
    return String.format("%.3f", value);
  }

  private void append(String line) {
    try {
      if (!this.truncatedThisSession) {
        Files.write(CSV_PATH, (HEADER + System.lineSeparator()).getBytes(),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        this.truncatedThisSession = true;
      }

      Files.write(CSV_PATH, (line + System.lineSeparator()).getBytes(),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      ThalAIScanner.log.warn("Could not append capacity-debug CSV row: %s", e.getMessage());
    }
  }
}
