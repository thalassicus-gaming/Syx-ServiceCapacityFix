// ThalAIScanner.java
// Document Version 3.3.3
// Creation date: 2026/07/14
// Creator: Thalassicus

package thalassicus.serviceestimate;

import game.time.TIME;
import init.type.NEED;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import script.SCRIPT;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.entity.humanoid.ai.main.AI;
import settlement.entity.humanoid.ai.main.AIManager;
import settlement.entity.humanoid.ai.main.AIModule;
import settlement.entity.humanoid.ai.main.AIModules;
import settlement.main.SETT;
import settlement.room.service.module.RoomServiceAccess;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import thalassicus.util.ThalRollingAverage;
import thalassicus.util.ThalsLogger;

//
// ============================================================================
// THAL AI SCANNER
// ============================================================================
// Exploratory data-collection tool, not yet feeding into anything. Writes a
// CSV row every SCAN_INTERVAL_SECONDS of in-game (simulated) time, via the
// SCRIPT_INSTANCE.update(double ds) hook - see ThalAIScannerScript.java for
// why this is the confirmed-safe entry point, and the class-level comment
// there for the full derivation.
//
// Two independent measurements per row, for two independent reasons:
//
// 1. AI module tally (entity-side): counts how many Humanoid entities are
//    currently in each AIModule, city-wide, via AIModules.current(). This
//    was the original motivating question - whether NEED_E (Hunger/Thirst/
//    Shopping) and other outer-layer modules meaningfully compete against
//    AIModule_Service for a subject's time. No HTYPE filtering is applied:
//    AIModules.java confirmed nearly every HTYPE (Subject, Slave, Retiree,
//    Guard, Recruit, Student, Nobility, Tourist, Parent, Parent_Slave,
//    Child/Child_Slave) includes AIModule_Service in its own module array,
//    so filtering by HTYPE would risk silently excluding real participants;
//    letting each module's own name column absorb whichever HTYPEs actually
//    use it is simpler and more honest than guessing a filter.
//
// 2. Room-service occupancy tally (room-side): sums total()/available()
//    across every RoomServiceAccess sharing the same NEED. Confirmed from
//    RoomService.java that these are already continuously-maintained,
//    city-wide running totals (RoomServiceInstance reports its own deltas
//    into them via increServices() on every reservation change) - no per-
//    instance enumeration is needed, and the working set here is bounded by
//    the number of registered room *blueprints*, not by population size at
//    all, unlike the AI module tally above. This is the data source for the
//    "measure real throughput instead of modeling it" idea - the room-side
//    approach was chosen over an entity-side occupancy tally specifically
//    because it never needs to touch SETT.ENTITIES() at all for this half
//    of the row.
//
// Both tallies are logged as raw counts only, at this stage - no rolling
// average, no derived capacity formula. The goal right now is purely to see
// what real numbers come out before deciding how to smooth or apply them.
// ============================================================================
//

final class ThalAIScanner implements SCRIPT.SCRIPT_INSTANCE {

  static final ThalsLogger log = new ThalsLogger(
      ThalsLogger.INFO,
      System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalAIScanner.log"
  );

  // 10 calendar minutes' worth of TIME's internal seconds, derived live from
  // TIME.secondsPerHour() rather than hardcoded. An earlier version used a
  // literal 600.0, guessed to mean "10 in-game minutes" - it did not. TIME's
  // own internal "second" is a compressed simulation unit (confirmed from
  // Sett.txt: SECONDS_PER_HOUR=48), so 600 of those units was actually about
  // half a calendar day, not ten minutes. This is deliberately built from
  // secondsPerHour() and a literal 60.0 (seconds/minute is a fixed, real-
  // world unit fact, not a game assumption) rather than secondsPerDay() and
  // a literal 24.0 (which would silently assume HOURS_PER_DAY stays 24,
  // even though it is itself a configurable Sett.txt value with no
  // guarantee of staying at its current setting). Computed at construction
  // time - after TIME is confirmed live, since this runs from
  // ThalAIScannerScript.createInstance() - so it stays correct even if Jake
  // changes either SECONDS_PER_HOUR or HOURS_PER_DAY in a future patch.
  private static final double CALENDAR_MINUTES_BETWEEN_SCANS = 10.0;
  private final double scanIntervalSeconds = TIME.secondsPerHour() * (CALENDAR_MINUTES_BETWEEN_SCANS / 60.0);

  // One calendar day's worth of samples, at whatever the scan interval above
  // actually is - deliberately computed from TIME.secondsPerDay() and this
  // scanner's own interval, not a hardcoded sample count, so the rolling
  // averages below always span exactly one calendar day regardless of what
  // CALENDAR_MINUTES_BETWEEN_SCANS is set to. One day was chosen because it
  // is the shortest window that fully cancels the day/night shift cycle
  // (Work/Go Home/Bide Time all swing hard by hour-of-day) without also
  // canceling out genuine, non-periodic change - a demolished district, a
  // plague, a building added - which should and does flow through the
  // average within one window's length, exactly like the day/night cycle
  // itself flows out. A fixed window cannot fully compensate for at least
  // one other known periodic pattern (the ARENA/ARENAG/SPEAKER/STAGE EVENT-
  // day boosters, whose true period was never pinned down) - documented
  // here as a known limitation rather than solved.
  private final int windowSampleCount = (int) Math.round(TIME.secondsPerDay() / this.scanIntervalSeconds);

  // Simple on/off switch for the raw CSV output, independent of the
  // rolling averages/save-load persistence above (which keep running
  // regardless). Flip to false to stop CSV growth entirely without
  // touching anything else - a straight hand edit, not exposed as a data-
  // file setting yet, since this is expected to change frequently while
  // this part of the mod is still under active development.
  private static final boolean CSV_OUTPUT_ENABLED = true;

  private static final Path CSV_PATH =
      Path.of(System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalAIScanner.csv");
  private static final String DELIMITER = ",";

  // Fixed once at construction, so every row's columns line up with the
  // header regardless of what happens to exist or be built later.
  //
  // Built by filtering out null entries, NOT by iterating AI.modules().ALL()
  // directly: AIModule's own constructor calls all.add(null) before
  // all.add(this), so AIModule.all is genuinely sparse (roughly half of its
  // 48 entries are null placeholders) - confirmed the hard way, via a
  // NullPointerException the first time this ran. AIModules.current()
  // already null-checks internally and falls back to idle, but the raw list
  // itself carries no such guarantee. Because the null padding makes real
  // modules' AIModule.index() values non-contiguous (real modules land on
  // scattered indices like 1, 3, 5... with nulls at 0, 2, 4...), a filtered
  // list can't reuse index() as a column position either - moduleColumnIndex
  // below is this scanner's own explicit module-to-column mapping, built
  // from the filtered list's own positional order instead.
  private final List<AIModule> moduleColumns = new ArrayList<>();
  private final Map<AIModule, Integer> moduleColumnIndex = new HashMap<>();
  private final List<NEED> needColumns = new ArrayList<>();

  // One rolling average per NEED for total slot count and for used
  // (occupied) slot count separately, rather than averaging a pre-computed
  // utilization ratio each scan. Averaging the raw sums first and dividing
  // afterward (in averageUtilization() below) is the statistically sound
  // order, and also sidesteps a real edge case a per-sample ratio average
  // would hit: a NEED with zero built rooms at some point during the window
  // would otherwise produce a divide-by-zero on every sample from that
  // period. Each map is keyed by NEED key, matching needColumns above.
  private final Map<String, ThalRollingAverage> totalSlotsByNeed = new HashMap<>();
  private final Map<String, ThalRollingAverage> usedSlotsByNeed = new HashMap<>();

  private double accumulatedSeconds = 0.0;
  private boolean loggedFirstUpdate = false;

  ThalAIScanner() {
    log.info("ThalAIScanner constructed via ThalAIScannerScript.createInstance().");
    log.info("TIME.secondsPerHour()=%d, scan interval=%.3f seconds (%.1f calendar minutes), rolling window=%d samples",
        TIME.secondsPerHour(), this.scanIntervalSeconds, CALENDAR_MINUTES_BETWEEN_SCANS, this.windowSampleCount);
    this.buildModuleColumns();
    this.buildNeedColumns();
    this.buildRollingAverages();
    this.writeHeaderIfNeeded();
  }

  private void buildModuleColumns() {
    for (AIModule module : AI.modules().ALL()) {
      if (module != null) {
        this.moduleColumnIndex.put(module, this.moduleColumns.size());
        this.moduleColumns.add(module);
      }
    }
  }

  private void buildNeedColumns() {
    Set<String> seenKeys = new HashSet<>();
    for (RoomServiceAccess room : RoomServiceAccess.ALL()) {
      NEED need = room.need;
      if (need != null && seenKeys.add(need.key)) {
        this.needColumns.add(need);
      }
    }
    log.info("Enumerated %d AIModule columns and %d NEED columns.", this.moduleColumns.size(), this.needColumns.size());
  }

  private void buildRollingAverages() {
    for (NEED need : this.needColumns) {
      this.totalSlotsByNeed.put(need.key, new ThalRollingAverage(this.windowSampleCount));
      this.usedSlotsByNeed.put(need.key, new ThalRollingAverage(this.windowSampleCount));
    }
  }

  // Public query surface for the rolling averages, ready for
  // ThalServiceCapacityCalculator (a different package) to eventually
  // consume, once that wiring is deliberately decided on as its own step -
  // not part of this change. Returns 0.0 for an unrecognized or not-yet-
  // built NEED key rather than throwing, matching the "prefer a safe
  // fallback over a crash" convention used throughout this mod.
  public double averageTotal(String needKey) {
    ThalRollingAverage avg = this.totalSlotsByNeed.get(needKey);
    return avg == null ? 0.0 : avg.average();
  }

  public double averageUsed(String needKey) {
    ThalRollingAverage avg = this.usedSlotsByNeed.get(needKey);
    return avg == null ? 0.0 : avg.average();
  }

  public double averageUtilization(String needKey) {
    double total = this.averageTotal(needKey);
    return total <= 0.0 ? 0.0 : this.averageUsed(needKey) / total;
  }

  @Override
  public void update(double ds) {
    if (!this.loggedFirstUpdate) {
      this.loggedFirstUpdate = true;
      log.info("ThalAIScanner.update() called for the first time - SCRIPT_INSTANCE ticking confirmed.");
    }

    this.accumulatedSeconds += ds;
    if (this.accumulatedSeconds >= this.scanIntervalSeconds) {
      this.accumulatedSeconds -= this.scanIntervalSeconds;
      this.performScan();
    }
  }

  private void performScan() {
    int[] moduleCounts = this.tallyAIModules();
    Map<String, long[]> roomSums = this.tallyRoomServices();

    for (NEED need : this.needColumns) {
      long[] sum = roomSums.get(need.key);
      this.totalSlotsByNeed.get(need.key).push(sum[0]);
      this.usedSlotsByNeed.get(need.key).push(sum[0] - sum[1]);
    }

    StringBuilder row = new StringBuilder();
    row.append(TIME.currentSecond());

    for (int i = 0; i < this.moduleColumns.size(); i++) {
      row.append(DELIMITER).append(moduleCounts[i]);
    }

    for (NEED need : this.needColumns) {
      long[] sum = roomSums.get(need.key);
      row.append(DELIMITER).append(sum[0]).append(DELIMITER).append(sum[1]);
    }

    this.appendLine(row.toString());
  }

  private int[] tallyAIModules() {
    int[] counts = new int[this.moduleColumns.size()];
    for (ENTITY e : SETT.ENTITIES().getAllEnts()) {
      if (e instanceof Humanoid h) {
        AIModule current = AIModules.current((AIManager) h.ai());
        Integer columnIndex = this.moduleColumnIndex.get(current);
        if (columnIndex != null) {
          counts[columnIndex]++;
        }
      }
    }
    return counts;
  }

  private Map<String, long[]> tallyRoomServices() {
    Map<String, long[]> sums = new LinkedHashMap<>();
    for (NEED need : this.needColumns) {
      sums.put(need.key, new long[2]); // [0] = total, [1] = available
    }

    for (RoomServiceAccess room : RoomServiceAccess.ALL()) {
      NEED need = room.need;
      if (need != null) {
        long[] sum = sums.get(need.key);
        sum[0] += room.total();
        sum[1] += room.available();
      }
    }

    return sums;
  }

  private void writeHeaderIfNeeded() {
    if (Files.exists(CSV_PATH)) {
      return;
    }

    StringBuilder header = new StringBuilder();
    header.append("game_seconds");

    for (AIModule module : this.moduleColumns) {
      header.append(DELIMITER).append("AIModule:").append(sanitize(module.name));
    }

    for (NEED need : this.needColumns) {
      header.append(DELIMITER).append("NEED:").append(need.key).append("_total");
      header.append(DELIMITER).append("NEED:").append(need.key).append("_available");
    }

    this.appendLine(header.toString());
  }

  private void appendLine(String line) {
    if (!CSV_OUTPUT_ENABLED) {
      return;
    }

    try {
      Files.write(
          CSV_PATH,
          (line + System.lineSeparator()).getBytes(),
          StandardOpenOption.CREATE, StandardOpenOption.APPEND
      );
    } catch (IOException e) {
      log.warn("Could not append CSV row at %s: %s", CSV_PATH, e.getMessage());
    }
  }

  private static String sanitize(CharSequence text) {
    return text.toString().replace(',', ' ').replace('\n', ' ');
  }

  // Mirrors ScriptEngine.save()/.load()'s own key-then-payload pattern (a
  // count, then per-entry: a string key, then the entry's own data) -
  // matching a precedent already proven to work for exactly this save/load
  // cycle, rather than inventing a different format.
  @Override
  public void save(FilePutter file) {
    log.info("Saving %d rolling-average entries.", this.needColumns.size());
    file.i(this.needColumns.size());
    for (NEED need : this.needColumns) {
      file.chars(need.key);
      this.totalSlotsByNeed.get(need.key).save(file);
      this.usedSlotsByNeed.get(need.key).save(file);
    }
  }

  // A saved key with no matching entry in this session's needColumns (e.g.
  // a mod that registered a NEED was removed) is read into a throwaway
  // instance instead - ThalRollingAverage's format is self-describing (it
  // writes its own capacity first), so a capacity-1 sink correctly consumes
  // the right number of bytes and keeps the file cursor correctly
  // positioned for whatever the game reads next, without needing a real
  // target to load into. A NEED present this session with no matching saved
  // entry (a mod was added, or this is the first save with the scanner
  // installed) simply keeps its fresh, empty ThalRollingAverage - no action
  // needed, that is already its default constructed state.
  @Override
  public void load(FileGetter file) throws IOException {
    int savedCount = file.i();
    int recognizedCount = 0;
    for (int i = 0; i < savedCount; i++) {
      String key = file.chars();
      boolean recognized = this.totalSlotsByNeed.containsKey(key);

      ThalRollingAverage total = recognized ? this.totalSlotsByNeed.get(key) : new ThalRollingAverage(1);
      total.load(file);
      ThalRollingAverage used = recognized ? this.usedSlotsByNeed.get(key) : new ThalRollingAverage(1);
      used.load(file);

      if (recognized) {
        recognizedCount++;
      } else {
        log.info("Discarded saved rolling-average data for NEED key '%s' - not a recognized NEED this session.", key);
      }
    }
    log.info("Loaded save data: %d entries found, %d restored into recognized NEEDs this session.", savedCount, recognizedCount);

    for (Map.Entry<String, ThalRollingAverage> entry : this.totalSlotsByNeed.entrySet()) {
      log.info("Post-load sample count for NEED '%s': %d / %d", entry.getKey(), entry.getValue().sampleCount(), entry.getValue().capacity());
    }
  }
}
