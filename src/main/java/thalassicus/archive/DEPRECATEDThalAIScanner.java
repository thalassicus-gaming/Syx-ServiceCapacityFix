// ThalAIScanner.java
// Document Version 3.4.0
// Creation date: 2026/07/14
// Creator: Thalassicus

package thalassicus.archive;

import game.time.TIME;
import init.type.NEED;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

final class DEPRECATEDThalAIScanner implements SCRIPT.SCRIPT_INSTANCE {
  static final ThalsLogger log = new ThalsLogger(
          ThalsLogger.INFO,
          System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalAIScanner.log"
  );
  private static final double CALENDAR_MINUTES_BETWEEN_SCANS = 10.0;
  private final double scanIntervalSeconds = TIME.secondsPerHour() * (CALENDAR_MINUTES_BETWEEN_SCANS / 60.0);
  private final int windowSampleCount = (int) Math.round(TIME.secondsPerDay() / this.scanIntervalSeconds);
  private static final boolean CSV_OUTPUT_ENABLED = false;
  private static final Path CSV_PATH = Path.of(System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalAIScanner.csv");
  private static final String DELIMITER = ",";
  private final List<AIModule> moduleColumns = new ArrayList<>();
  private final Map<AIModule, Integer> moduleColumnIndex = new HashMap<>();
  private final List<NEED> needColumns = new ArrayList<>();
  private final Map<String, ThalRollingAverage> totalSlotsByNeed = new HashMap<>();
  private final Map<String, ThalRollingAverage> usedSlotsByNeed = new HashMap<>();
  private double accumulatedSeconds = 0.0;
  private boolean loggedFirstUpdate = false;
  private static DEPRECATEDThalAIScanner instance;
  static DEPRECATEDThalAIScanner instance() {
    return instance;
  }

  //
  // Public Methods
  //
  DEPRECATEDThalAIScanner() {
    instance = this;
    log.info("ThalAIScanner constructed via ThalAIScannerScript.createInstance().");
    log.info("TIME.secondsPerHour()=%d, scan interval=%.3f seconds (%.1f calendar minutes), rolling window=%d samples",
            TIME.secondsPerHour(), this.scanIntervalSeconds, CALENDAR_MINUTES_BETWEEN_SCANS, this.windowSampleCount);
    this.buildModuleColumns();
    this.buildNeedColumns();
    this.buildRollingAverages();
    this.writeHeaderIfNeeded();
  }
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
  public double liveDataWeight(String needKey) {
    ThalRollingAverage avg = this.totalSlotsByNeed.get(needKey);
    return avg == null ? 0.0 : (double) avg.sampleCount() / avg.capacity();
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

  //
  // Private Methods
  //
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

}
