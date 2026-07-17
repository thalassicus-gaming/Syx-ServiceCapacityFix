// ThalAIScanner.java
// Document Version 1.2.5
// Creation date: 2026/07/16
// Creator: Thalassicus

package thalassicus.servicecapacity;

import game.time.TIME;
import init.race.RACES;
import init.race.Race;
import init.religion.Religion;
import init.type.HCLASS;
import init.type.HCLASSES;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import script.SCRIPT;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.entity.humanoid.ai.main.AI;
import settlement.main.SETT;
import settlement.room.service.module.RoomService;
import settlement.room.service.module.RoomServiceAccess;
import settlement.room.spirit.shrine.ROOM_SHRINE;
import settlement.room.spirit.temple.ROOM_TEMPLE;
import settlement.stats.STATS;
import settlement.stats.colls.StatsReligion;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import thalassicus.util.ThalRollingAverage;
import thalassicus.util.ThalsLogger;

final class ThalAIScanner implements SCRIPT.SCRIPT_INSTANCE {

  static final ThalsLogger log = new ThalsLogger(
      ThalsLogger.INFO,
      System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalAIScanner.log"
  );

  private static ThalAIScanner instance;

  static ThalAIScanner instance() {
    return instance;
  }

  // TIME's internal "second" is a compressed simulation unit, not a
  // real-world second (secondsPerHour() is itself a configurable value).
  // Calendar minutes are therefore derived from secondsPerHour() * 60.0
  // (seconds/minute is a fixed real-world fact) rather than secondsPerDay()
  // * 24.0 (which would silently assume HOURS_PER_DAY stays 24, itself a
  // configurable value with no such guarantee).
  private static final double CALENDAR_MINUTES_BETWEEN_SCANS = 10.0;
  private final double scanIntervalSeconds = TIME.secondsPerHour() * (CALENDAR_MINUTES_BETWEEN_SCANS / 60.0);

  // One calendar day's worth of samples, at whatever the scan interval above
  // actually is. One day is the shortest window that fully cancels the
  // day/night AI-module shift cycle without also canceling out genuine,
  // non-periodic change (a demolished district, a plague, a building added).
  // Known limitation: this does not fully compensate for at least one other
  // periodic pattern (ARENA/ARENAG/SPEAKER/STAGE EVENT-day boosters, whose
  // true period has not been pinned down).
  private final int windowSampleCount = (int) Math.round(TIME.secondsPerDay() / this.scanIntervalSeconds);

  private final List<BlueprintEntry> blueprintEntries = new ArrayList<>();
  private final Map<RoomService, BlueprintEntry> blueprintEntryByService = new HashMap<>();
  private final Map<String, BlueprintEntry> blueprintEntryByKey = new HashMap<>();
  private final ThalCapacityDebugLog debugLog = new ThalCapacityDebugLog();
  private double accumulatedSeconds = 0.0;

  ThalAIScanner() {
    instance = this;
    log.info(
        "Scan interval=%.3f seconds (%.1f calendar minutes), rolling window=%d samples.",
        this.scanIntervalSeconds, CALENDAR_MINUTES_BETWEEN_SCANS, this.windowSampleCount
    );
    this.buildBlueprintEntries();
  }

  private void buildBlueprintEntries() {
    for (RoomServiceAccess roomServiceAccess : RoomServiceAccess.ALL()) {
      this.addBlueprintEntry(roomServiceAccess, null);
    }

    for (ROOM_SHRINE shrine : SETT.ROOMS().TEMPLES.SHRINES) {
      this.addBlueprintEntry(shrine.service(), shrine.religion);
    }

    for (ROOM_TEMPLE temple : SETT.ROOMS().TEMPLES.ALL) {
      this.addBlueprintEntry(temple.service(), temple.religion);
    }

    int religionBlueprintCount = 0;
    for (BlueprintEntry entry : this.blueprintEntries) {
      if (entry.isReligionBlueprint()) {
        religionBlueprintCount++;
      }
    }

    log.info(
        "Enumerated %d service blueprints (%d religion, %d ordinary).",
        this.blueprintEntries.size(),
        religionBlueprintCount,
        this.blueprintEntries.size() - religionBlueprintCount
    );
  }

  private void addBlueprintEntry(RoomService roomService, Religion religion) {
    BlueprintEntry entry = BlueprintEntry.create(roomService, religion, this.windowSampleCount);
    this.blueprintEntries.add(entry);
    this.blueprintEntryByService.put(roomService, entry);
    this.blueprintEntryByKey.put(entry.blueprintKey(), entry);
  }

  @Override
  public void update(double deltaSeconds) {
    this.accumulatedSeconds += deltaSeconds;
    if (this.accumulatedSeconds >= this.scanIntervalSeconds) {
      this.accumulatedSeconds -= this.scanIntervalSeconds;
      this.performScan();
    }
  }

  private void performScan() {
    Map<BlueprintEntry, Double> eligiblePopulationByEntry = this.tallyEligibleDemandPopulation();

    if (ThalCapacityDebugLog.ENABLED) {
      int eligibleHumanoids = this.countEligibleHumanoids();
      this.debugLog.beginScan((long)TIME.currentSecond(), TIME.hours().bitOfDay(), SETT.ENTITIES().Imax() + 1, eligibleHumanoids);
    }

    for (BlueprintEntry entry : this.blueprintEntries) {
      int total = entry.roomService().total();
      int available = entry.roomService().available();
      entry.totalSlotsAverage().push(total);
      entry.usedSlotsAverage().push(total - available);

      double demandPopulationRaw;
      if (entry.isReligionBlueprint()) {
        demandPopulationRaw = this.followerPopulation(entry.religion());
      } else {
        demandPopulationRaw = eligiblePopulationByEntry.get(entry);
      }
      entry.demandGeneratingPopulationAverage().push(demandPopulationRaw);

      if (ThalCapacityDebugLog.ENABLED) {
        this.debugLog.row(
            entry.blueprintKey(),
            entry.isReligionBlueprint(),
            total,
            total - available,
            entry.totalSlotsAverage().average(),
            entry.usedSlotsAverage().average(),
            demandPopulationRaw,
            entry.demandGeneratingPopulationAverage().average()
        );
      }
    }
  }

  // Count of live subjects passing the HTYPE-level ordinary-NEED gate, for
  // debug context only (a global multiplier problem would show up here).
  // Duplicates the outer filter of tallyEligibleDemandPopulation() rather
  // than threading a count back out of it, to keep that method's return
  // shape clean; only runs when debug logging is enabled.
  private int countEligibleHumanoids() {
    int count = 0;
    for (ENTITY entity : SETT.ENTITIES().getAllEnts()) {
      if (entity instanceof Humanoid humanoid && AI.modules().needs.has(humanoid.indu().hType())) {
        count++;
      }
    }

    return count;
  }

  // Sums this religion's followers across every "real" population HCLASS
  // (HCLASSES.ALLP() - Noble/Citizen/Slave, excluding OTHER) and every Race,
  // mirroring RoomServiceAccess.cityAccess()'s own summation pattern.
  // Known limitation: HCLASS/Race granularity cannot see HTYPE-level state,
  // so this total still includes individuals AIModules.java excludes from
  // real Shrine/Temple demand (Children, and anyone currently a Rioter,
  // Prisoner, or Deranged) - an acknowledged overcount until the full HTYPE
  // eligibility mapping exists.
  private double followerPopulation(Religion religion) {
    StatsReligion.StatReligion statReligion = STATS.RELIGION().ALL.get(religion.index());
    double followerTotal = 0.0;
    for (HCLASS hclass : HCLASSES.ALLP()) {
      for (Race race : RACES.all()) {
        followerTotal += statReligion.followers.data(hclass).get(race);
      }
    }

    return followerTotal;
  }

  // Tallies, per ordinary (non-religion) blueprint, how many live subjects
  // are currently eligible to generate demand for it. Two gates apply:
  // AI.modules().needs.has(hType) - whether this subject's HTYPE
  // participates in the ordinary NEED lottery at all (false for Enemy,
  // Rioter, Soldier, Prisoner, Deranged, checked once per subject rather
  // than hardcoded per blueprint) - and, for subjects that pass,
  // accessRequest(humanoid) on each blueprint's own StatServiceRoom, which
  // resolves Nobility/Tourist auto-access, the Child-to-parent-class
  // redirect, and per-race/HCLASS permission data from that race's own
  // definition file. Every ordinary entry is present in the returned map,
  // defaulting to 0.0, even if no eligible subject was found for it.
  private Map<BlueprintEntry, Double> tallyEligibleDemandPopulation() {
    Map<BlueprintEntry, Double> eligiblePopulationByEntry = new HashMap<>();
    for (BlueprintEntry entry : this.blueprintEntries) {
      if (!entry.isReligionBlueprint()) {
        eligiblePopulationByEntry.put(entry, 0.0);
      }
    }

    for (ENTITY entity : SETT.ENTITIES().getAllEnts()) {
      if (entity instanceof Humanoid humanoid && AI.modules().needs.has(humanoid.indu().hType())) {
        for (BlueprintEntry entry : this.blueprintEntries) {
          if (!entry.isReligionBlueprint()) {
            // Safe: every non-religion entry was built from
            // RoomServiceAccess.ALL() in buildBlueprintEntries(), so
            // roomService() is always actually a RoomServiceAccess here.
            RoomServiceAccess roomServiceAccess = (RoomServiceAccess) entry.roomService();
            if (roomServiceAccess.stats().accessRequest(humanoid)) {
              eligiblePopulationByEntry.merge(entry, 1.0, Double::sum);
            }
          }
        }
      }
    }

    return eligiblePopulationByEntry;
  }

  // Public query surface. Returns 0.0 for a RoomService this scanner never
  // enumerated (e.g. Hospital/Nursery/School/Inn/Dump, which are outside the
  // NEED lottery entirely) rather than throwing, matching the "prefer a safe
  // fallback over a crash" convention used throughout this mod.
  double averageTotal(RoomService roomService) {
    BlueprintEntry entry = this.blueprintEntryByService.get(roomService);
    return entry == null ? 0.0 : entry.totalSlotsAverage().average();
  }

  double averageUsed(RoomService roomService) {
    BlueprintEntry entry = this.blueprintEntryByService.get(roomService);
    return entry == null ? 0.0 : entry.usedSlotsAverage().average();
  }

  double averageUtilization(RoomService roomService) {
    double total = this.averageTotal(roomService);
    return total <= 0.0 ? 0.0 : this.averageUsed(roomService) / total;
  }

  // How "full" (0.0-1.0) this blueprint's rolling window currently is - the
  // fraction of its capacity that has real pushed samples so far. Intended
  // as a blend weight between live data and a formula-based estimate.
  // totalSlotsAverage and usedSlotsAverage are always pushed together in
  // performScan(), so either one's sampleCount() would give an identical
  // answer; totalSlotsAverage is used arbitrarily.
  double rollingAverageFillRatio(RoomService roomService) {
    BlueprintEntry entry = this.blueprintEntryByService.get(roomService);
    return entry == null ? 0.0 : (double) entry.totalSlotsAverage().sampleCount() / entry.totalSlotsAverage().capacity();
  }

  // Every blueprint's demand-generating population is now real, rolling-
  // averaged data: a religion's follower count for Shrine/Temple, or the
  // live eligible-subject tally for every ordinary blueprint - both pushed
  // on the same schedule in performScan(), so no branching is needed here.
  double averageDemandGeneratingPopulation(RoomService roomService) {
    BlueprintEntry entry = this.blueprintEntryByService.get(roomService);
    return entry == null ? 0.0 : entry.demandGeneratingPopulationAverage().average();
  }

  // Mirrors ScriptEngine.save()/.load()'s own key-then-payload pattern (a
  // count, then per-entry: a string key, then the entry's own data) -
  // matching a precedent already proven to work for this save/load cycle.
  // Keyed by each blueprint's own string key rather than array position, so
  // save files stay readable and stable if blueprints are added, removed,
  // or reordered by other mods. Every entry has the same shape (three
  // rolling averages), so no per-entry marker is needed to describe what
  // follows the key.
  @Override
  public void save(FilePutter file) {
    log.info("Saving %d blueprint entries.", this.blueprintEntries.size());
    file.i(this.blueprintEntries.size());
    for (BlueprintEntry entry : this.blueprintEntries) {
      file.chars(entry.blueprintKey());
      entry.totalSlotsAverage().save(file);
      entry.usedSlotsAverage().save(file);
      entry.demandGeneratingPopulationAverage().save(file);
    }
  }

  // A saved key with no matching entry this session (e.g. a mod that
  // registered a blueprint was removed) is read into a throwaway capacity-1
  // sink instead of a real target - ThalRollingAverage's format is
  // self-describing (it writes its own capacity first), so the sink
  // correctly consumes the right number of bytes and keeps the file cursor
  // correctly positioned for whatever is read next. A blueprint present
  // this session with no matching saved entry (a mod was added, or this is
  // the first save with the scanner installed) simply keeps its fresh,
  // empty rolling averages.
  @Override
  public void load(FileGetter file) throws IOException {
    int savedCount = file.i();
    int recognizedCount = 0;
    for (int entryIndex = 0; entryIndex < savedCount; entryIndex++) {
      String blueprintKey = file.chars();
      BlueprintEntry entry = this.blueprintEntryByKey.get(blueprintKey);
      boolean recognized = entry != null;

      ThalRollingAverage totalSlotsAverage = recognized ? entry.totalSlotsAverage() : new ThalRollingAverage(1);
      totalSlotsAverage.load(file);
      ThalRollingAverage usedSlotsAverage = recognized ? entry.usedSlotsAverage() : new ThalRollingAverage(1);
      usedSlotsAverage.load(file);
      ThalRollingAverage demandGeneratingPopulationAverage = recognized ? entry.demandGeneratingPopulationAverage() : new ThalRollingAverage(1);
      demandGeneratingPopulationAverage.load(file);

      if (recognized) {
        recognizedCount++;
      } else {
        log.info("Discarded saved rolling-average data for blueprint key '%s' - not a recognized blueprint this session.", blueprintKey);
      }
    }

    log.info("Loaded save data: %d entries found, %d restored into recognized blueprints this session.", savedCount, recognizedCount);
  }

  // Total/used occupancy is tracked for every blueprint.
  // demandGeneratingPopulationAverage holds this blueprint's eligible
  // demand-side population - a religion's rolling-averaged follower count
  // for Shrine/Temple, or the live eligible-subject tally for every
  // ordinary blueprint - unified under one field since both represent the
  // same concept from the estimator's point of view.
  private record BlueprintEntry(
      String blueprintKey,
      RoomService roomService,
      Religion religion,
      ThalRollingAverage totalSlotsAverage,
      ThalRollingAverage usedSlotsAverage,
      ThalRollingAverage demandGeneratingPopulationAverage
  ) {
    static BlueprintEntry create(RoomService roomService, Religion religion, int windowSampleCount) {
      return new BlueprintEntry(
          roomService.room().key,
          roomService,
          religion,
          new ThalRollingAverage(windowSampleCount),
          new ThalRollingAverage(windowSampleCount),
          new ThalRollingAverage(windowSampleCount)
      );
    }

    boolean isReligionBlueprint() {
      return this.religion != null;
    }
  }
}
