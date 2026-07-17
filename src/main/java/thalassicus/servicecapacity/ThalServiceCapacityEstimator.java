// ThalServiceCapacityEstimator.java
// Document Version 1.1.0
// Creation date: 2026/07/16
// Creator: Thalassicus

package thalassicus.servicecapacity;

import settlement.room.service.module.RoomService;

public final class ThalServiceCapacityEstimator {

  private ThalServiceCapacityEstimator() {
  }

  // hypothetical is still a stub (0.0) pending the theoretical demand-model
  // formula and its calibration correction - deliberately deferred, see
  // project notes. live is real: populationPerOccupiedSlot (this room
  // type's demand-generating population divided by its average occupied
  // slots, both from ThalAIScanner's rolling averages) multiplied by
  // slotCount. Falls back to 0.0 for live if ThalAIScanner hasn't been
  // constructed yet (e.g. at the main menu, before any save is loaded) or
  // if there are no rolling-averaged occupied slots to divide by yet (a
  // fresh scanner, or a blueprint with zero built instances so far).
  public static SupportedPopEstimate getSupportedPopEstimate(RoomService roomService, double slotCount) {
    double live = 0.0;
    ThalAIScanner scanner = ThalAIScanner.instance();
    if (scanner != null) {
      double averageOccupiedSlots = scanner.averageUsed(roomService);
      if (averageOccupiedSlots > 0.0) {
        double demandGeneratingPopulation = scanner.averageDemandGeneratingPopulation(roomService);
        double populationPerOccupiedSlot = demandGeneratingPopulation / averageOccupiedSlots;
        live = populationPerOccupiedSlot * slotCount;
      }
    }

    return new SupportedPopEstimate(live, 0.0);
  }

  // The population this service infrastructure could support if it were
  // operating at 100% utilization under current demand characteristics.
  // This is a capacity-planning metric, not a demand metric. It does NOT
  // represent: current occupancy, current utilization, throughput
  // (visits/day), number of citizens presently using the service, or
  // number of citizens presently being served. Example: a Physician
  // serving a city of 500 citizens at 10% utilization should have a HIGH
  // supported-population estimate, because it has substantial spare
  // capacity remaining.
  public static record SupportedPopEstimate(double live, double hypothetical) {
  }
}

