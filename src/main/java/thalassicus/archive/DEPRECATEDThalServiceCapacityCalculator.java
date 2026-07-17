// ThalServiceCapacityCalculator.java
// Document Version 1.1.0
// Creation date: 2026/07/12
// Creator: Thalassicus

package thalassicus.archive;

import game.time.TIME;
import init.race.RACES;
import init.race.Race;
import init.type.HCLASS_RACE;
import init.type.NEED;
import init.type.NEEDS;
import init.type.NEED_E;

import java.util.HashSet;
import java.util.Set;
import settlement.main.SETT;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.service.module.RoomService;
import settlement.room.service.module.RoomServiceAccess;
import settlement.stats.STATS;
import snake2d.util.sets.LIST;
import thalassicus.util.ThalServiceVisitDurations;
import thalassicus.util.ThalsLogger;
import util.gui.misc.GBox;
import util.gui.misc.GText;

//
// ============================================================================
// THAL SERVICE CAPACITY CALCULATOR
// ============================================================================
// Produces a room service's "Capacity" estimate (subjects served per day) and
// a supporting breakdown of the expected number of days between a subject
// using this service, both by default and per diverging race.
//
// Every competing-total calculation below is computed in two forms at once,
// carried in small record types rather than two separately-named methods:
//   - "All Services": every NEED in NEEDS.ALL(), regardless of whether the
//     player has built anything to satisfy it. This is Jake's own implicit
//     assumption, and the only version this class originally computed.
//   - "Present Services": only NEEDs with at least one currently-existing,
//     built room instance (RoomServiceAccess.ALL(), cross-referenced against
//     each entry's RoomBlueprintIns.instancesSize() > 0). Most cities are
//     missing at least one service category (Tavern/Thirst is a tech-locked,
//     confirmed example), so "All Services" systematically overstates the
//     real competition a subject's attention faces in most actual cities.
// Both are shown, formatted as "present (all)", so the player can see the
// honest current-city number alongside a stable reference point rather than
// only one or the other.
//
// A service's daily capacity depends on how often subjects actually want to
// use it relative to every other need competing for their attention. This
// competing total is summed across NEEDS.ALLSIMPLE() specifically, NOT
// NEEDS.ALL() - confirmed via AIModules.java's module-selection loop, which
// compares AIModule_Service against AIModule_Consumption (Hunger, Thirst,
// Shopping's wrapper), AIModule_Danger, AIModule_Work, etc. head-to-head by
// simple max-priority, once per tick. The NEED_E needs (Hunger, Thirst,
// Shopping) never enter S_Plans.getPlan()'s rate-weighted lottery at all -
// they compete at this outer, module-selection layer instead, against Work
// and Battle, not against Lavatory and Hearth. S_Plans's registered room
// list matches NEEDS.ALLSIMPLE() exactly, need-for-need, which is why
// Jake's original scope for this sum was already correct. An earlier
// version of this class widened the sum to NEEDS.ALL() on the mistaken
// assumption that a subject weighs every need - simple and event alike -
// against one shared pool; AIModules.java disproves that directly, and the
// change was reverted. A real, separate competition effect from Food/Drink/
// Shop does exist, but it operates on module priority, not a rate sum, and
// is not modeled here.
//
// RoomService.totalMultiplier() is shadowed as a one-line wrapper around
// correctedCapacityMultipliers(...).calibratedTotal() specifically - not
// presentTotal() - so every existing, unknown, or future call site that
// assumes totalMultiplier() returns a single double keeps getting exactly
// the value it was already built and validated against. Present Services is
// purely additive information, surfaced only where this class's own display
// methods are called directly (not through totalMultiplier()).
//
// The "Need Rates" tooltip section this class also builds does NOT display
// a raw rate - a subject's need-rate is not an accumulator (nothing in
// S_Plans.getPlan() stores or grows a per-subject value over time). Instead,
// each time a subject spends a service credit, S_Plans.getPlan() draws from
// a weighted lottery across every competing need; a need's SHARE of that
// lottery is rate / (sum of every competing need's rate), a dimensionless
// probability with no time unit of its own.
//
// Converting that share into an expected number of DAYS requires a second,
// independently-confirmed factor: how many lottery draws (service credits)
// a subject gets per day. AIModule_Service.update() grants new credits via
// RND.rInt(TIME.servicePerDay()) exactly once per day (on newDay), and
// RND.rInt(int) is a direct pass-through to java.util.Random.nextInt(int),
// confirmed uniform over [0, max). With TIME.servicePerDay() confirmed as
// the constant 4, the mean credits granted per day is (0+1+2+3)/4 = 1.5.
// Expected days between visits is therefore 1.0 / (share * 1.5) - both the
// "1 day" and the "1.5" in that conversion are confirmed from source, not
// assumed. This deliberately ignores the possibility of a granted credit
// going unspent (e.g. a saturated service with no reservable instance in
// range); the player is generally trying to avoid that scenario, not plan
// around it, so this stays an optimistic-but-honestly-labeled figure rather
// than one entangled with the same gridlock uncertainty this project has
// consistently declined to model elsewhere.
//
// This is displayed once using the default (race-agnostic) rate for the
// "Base" row, and again per diverging race using that race's own rate AND
// that race's own total competing weight - a race's override on any other
// NEED changes its true expected-days figure on THIS need too, even without
// an override here, since the competing pool it's measured against is
// itself different.
//
// Several day-cached totals support all of this (defaultCompetingTotals,
// raceCompetingTotals[], presentNeedKeys), invalidated at least once every
// CACHE_REFRESH_INTERVAL_MILLIS of real (wall-clock) time, not once per game
// day. presentNeedKeys tracks something the player changes directly, at
// whatever pace they build or demolish - a fundamentally different kind of
// staleness than an EVENT booster changing a rate on a specific game-day.
// Gating on game-days conflates the two, and is actively wrong while the
// game is paused: a player can plan and rebuild a city for many real minutes
// with the game paused, during which no game-day ever passes at all, so a
// day-gated cache would never reflect any of it. Wall-clock invalidation
// keeps the display honestly connected to what the player just did,
// regardless of whether the simulation is currently advancing.
//
// Several NEEDs (Arena, Arenag, Speaker, Stage) have an EVENT booster that
// changes their rate on specific recurring game-days; wall-clock refresh
// still catches these eventually (within one interval of real time while
// unpaused), just not tied to the day boundary specifically - acceptable,
// since the interval is short enough that this is not a meaningfully worse
// lag than day-gating was for that specific case.
//
// Deliberately NOT modeled here: travel time between a subject and a
// service, the compounding effect of reservations failing and retrying as
// a service approaches saturation, the per-need usage weighting
// S_Plans.getPlan() actually applies (rate * usageI[need], where usageI is
// built from a private field this class has no access to), and whether a
// "present" resource-distribution hub (Eatery/Tavern/Market) is actually
// stocked and staffed rather than merely built - instancesSize() > 0 only
// confirms a blueprint exists, not that it is currently functional.
// ============================================================================
//

public class DEPRECATEDThalServiceCapacityCalculator {

  // A separate log file from ThalServiceVisitDurations's ThalsLogger instance,
  // deliberately: each ThalsLogger instance is expected to truncate its own
  // file on its own first write this session, so two independent instances
  // sharing one filename could clobber each other's earlier output. Worth
  // consolidating into a single shared logger later if that assumption is
  // confirmed one way or the other.
  private static final ThalsLogger log = new ThalsLogger(
      ThalsLogger.INFO,
      System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalServiceEstimateFix-Capacity.log"
  );

  /*
   The population this service infrastructure could support if it were
   operating at 100% utilization under current demand characteristics.

   This is a capacity-planning metric, not a demand metric.

   It does NOT represent:
   - current occupancy
   - current utilization
   - throughput (visits/day)
   - number of citizens presently using the service
   - number of citizens presently being served

   Example:
   A Physician serving a city of 500 citizens at 10% utilization should
   have a HIGH supported population estimate, because it has substantial
   spare capacity remaining.
  */
  public record SupportedPopEstimate(
    double live, // derived from live data sampling in the current settlement
    double hypothetical // derived from the calibrated demand model
  ) {}

  private static final CharSequence AVERAGE_DAYS_BETWEEN_VISITS_HEADER = "Average Days Between Visits";
  private static final CharSequence BASE_RATE_LABEL = "Base";
  private static final CharSequence NEVER_LABEL = "Never";

  // mean(RND.rInt(TIME.servicePerDay())) = mean(RND.rInt(4)) = (0+1+2+3)/4.
  // Confirmed via RND.rInt(int) -> java.util.Random.nextInt(int), uniform
  // over [0, max). See the class-level doc comment for the full derivation.
  private static final double EXPECTED_SERVICE_CREDITS_PER_DAY = 1.5;

  // Real (wall-clock) milliseconds between cache rebuilds - long enough to
  // avoid recomputing on every tooltip render, short enough that a player
  // action (e.g. demolishing a building) shows up on the very next tooltip
  // check a few seconds later, whether or not the game is currently paused.
  private static final long CACHE_REFRESH_INTERVAL_MILLIS = 5000L;

  private static long lastCacheRefreshMillis;
  private static boolean cacheEverInitialized = false;
  private static Set<String> presentNeedKeys;
  private static CompetingTotals defaultCompetingTotals;
  private static CompetingTotals[] raceCompetingTotals;

  public record CompetingTotals(double presentTotal, double calibratedTotal) {
  }

  public record CapacityMultipliers(double presentMultiplier, double calibratedMultiplier) {
  }

  public static CapacityMultipliers correctedCapacityMultipliers(RoomService roomService) {
    NEED serviceNeed = roomService.need;
    if (serviceNeed == null) {
      return new CapacityMultipliers(1.0, 1.0);
    }

    String needKey = serviceNeed.key;
    String blueprintKey = roomService.room().key;

    double needShare = roomService.usage / STATS.SERVICE().needTot(serviceNeed);
    if (serviceNeed instanceof NEED_E) {
      double needEMultiplier = 1.0 / (needShare * serviceNeed.rate.get(HCLASS_RACE.clP(null, null)));
      double blendedNeedEMultiplier = blendWithLiveData(needEMultiplier, needKey, blueprintKey);
      return new CapacityMultipliers(blendedNeedEMultiplier, needEMultiplier);
    }

    CompetingTotals totals = getDefaultCompetingTotals();

    if (STATS.SERVICE().needTot(serviceNeed) == 0.0) {
      needShare = roomService.usage;
    }

    double rate = serviceNeed.rate.get(HCLASS_RACE.clP(null, null));
    double visitDurationFactor = ThalServiceVisitDurations.servicePerDay(blueprintKey) * 0.5 * rate * needShare;
    double presentMultiplier = 1.0 / (visitDurationFactor / totals.presentTotal());
    double calibratedMultiplier = 1.0 / (visitDurationFactor / totals.calibratedTotal());
    double blendedPresentMultiplier = blendWithLiveData(presentMultiplier, needKey, blueprintKey);
    return new CapacityMultipliers(blendedPresentMultiplier, calibratedMultiplier);
  }

  // Blends the formula-based Present multiplier with a live-measured one,
  // derived from ThalAIScanner's rolling occupancy data via Little's Law
  // (L = lambda * W, rearranged to a per-slot daily rate: utilization *
  // secondsPerDay / visitDurationSeconds). The blend weight ramps linearly
  // from 0.0 (pure formula) to 1.0 (pure live data) as ThalAIScanner's
  // rolling window for this NEED fills up, avoiding a visible jump the
  // moment live data first becomes available. Falls back to the pure
  // formula value, unblended, in three distinct cases, each logged
  // separately at trace level for diagnosability:
  //   - the scanner has not been constructed yet (e.g. main menu, before a
  //     save is loaded - ThalAIScanner.instance() is null until then);
  //   - no measured visit duration exists for this specific blueprint key
  //     (ThalServiceVisitDurations.visitSeconds() returns -1), since
  //     converting a utilization fraction into a rate requires a real
  //     duration, and guessing one would be silently wrong rather than
  //     simply less precise;
  //   - the rolling window has no samples at all yet for this NEED.
  // The Calibrated multiplier is deliberately never blended - it describes
  // a hypothetical fully-built city that can never be measured directly, so
  // there is no live data for it to blend with in the first place.
  private static double blendWithLiveData(double formulaMultiplier, String needKey, String blueprintKey) {
    DEPRECATEDThalAIScanner scanner = DEPRECATEDThalAIScanner.instance();
    if (scanner == null) {
      log.trace("blendWithLiveData(%s, %s): scanner not yet constructed, using formula value %.4f unblended",
          needKey, blueprintKey, formulaMultiplier);
      return formulaMultiplier;
    }

    double measuredVisitSeconds = ThalServiceVisitDurations.visitSeconds(blueprintKey);
    if (measuredVisitSeconds <= 0.0) {
      log.trace("blendWithLiveData(%s, %s): no measured visit duration for this blueprint, using formula value %.4f unblended",
          needKey, blueprintKey, formulaMultiplier);
      return formulaMultiplier;
    }

    double liveWeight = scanner.liveDataWeight(needKey);
    if (liveWeight <= 0.0) {
      log.trace("blendWithLiveData(%s, %s): rolling window has no samples yet, using formula value %.4f unblended",
          needKey, blueprintKey, formulaMultiplier);
      return formulaMultiplier;
    }

    double liveMultiplier = scanner.averageUtilization(needKey) * TIME.secondsPerDay() / measuredVisitSeconds;
    double blended = formulaMultiplier * (1.0 - liveWeight) + liveMultiplier * liveWeight;
    log.trace("blendWithLiveData(%s, %s): formula=%.4f, live=%.4f, weight=%.3f -> blended=%.4f",
        needKey, blueprintKey, formulaMultiplier, liveMultiplier, liveWeight, blended);
    return blended;
  }

  public static void appendDivergenceLines(GBox tooltipBox, NEED serviceNeed) {
    if (serviceNeed == null) {
      return;
    }

    double baseRate = serviceNeed.rate.get(HCLASS_RACE.clP(null, null));
    CompetingTotals defaultTotals = getDefaultCompetingTotals();

    tooltipBox.textLL(AVERAGE_DAYS_BETWEEN_VISITS_HEADER);
    tooltipBox.NL();

    tooltipBox.textL(BASE_RATE_LABEL);
    tooltipBox.tab(6);
    appendExpectedDaysPair(tooltipBox, baseRate, defaultTotals);
    tooltipBox.NL();

    for (Race currentRace : RACES.all()) {
      if (currentRace.all(serviceNeed.rate).size() > 0) {
        double raceRate = currentRace.bvalue(serviceNeed.rate);
        CompetingTotals raceTotals = getRaceCompetingTotals(currentRace);

        tooltipBox.textL(currentRace.info.names);
        tooltipBox.tab(6);
        appendExpectedDaysPair(tooltipBox, raceRate, raceTotals);
        tooltipBox.NL();
      }
    }

    tooltipBox.NL(8);
  }

  private static void appendExpectedDaysPair(GBox tooltipBox, double numerator, CompetingTotals totals) {
    GText valueText = tooltipBox.text();
    appendExpectedDays(valueText, numerator, totals.presentTotal());
    valueText.s();
    valueText.add('(');
    appendExpectedDays(valueText, numerator, totals.calibratedTotal());
    valueText.add(')');
    valueText.normalify();
    tooltipBox.add(valueText);
  }

  private static void appendExpectedDays(GText valueText, double numerator, double denominator) {
    double share = denominator > 0.0 ? numerator / denominator : 0.0;
    if (share <= 0.0) {
      valueText.add(NEVER_LABEL);
    } else {
      double expectedDays = 1.0 / (share * EXPECTED_SERVICE_CREDITS_PER_DAY);
      valueText.add(expectedDays, 1);
    }
  }

  private static void ensureCacheCurrent() {
    long now = System.currentTimeMillis();
    // cacheEverInitialized, not a sentinel value compared via subtraction:
    // now - Long.MIN_VALUE overflows a 64-bit long (now is positive, and
    // Long.MIN_VALUE's magnitude alone already exceeds Long.MAX_VALUE by 1),
    // wrapping around to an unpredictable result that silently failed the
    // >= CACHE_REFRESH_INTERVAL_MILLIS check on the very first call ever,
    // leaving presentNeedKeys permanently null. An explicit boolean sidesteps
    // the overflow risk entirely rather than hunting for a "safe" sentinel.
    if (!cacheEverInitialized || now - lastCacheRefreshMillis >= CACHE_REFRESH_INTERVAL_MILLIS) {
      log.info("Rebuilding cache: %d ms since last refresh", cacheEverInitialized ? now - lastCacheRefreshMillis : -1);
      cacheEverInitialized = true;
      lastCacheRefreshMillis = now;
      defaultCompetingTotals = null;
      raceCompetingTotals = new CompetingTotals[RACES.all().size()];
      presentNeedKeys = computePresentNeedKeys();
      log.info("presentNeedKeys after rebuild: %s", presentNeedKeys);
    }
  }

  private static Set<String> computePresentNeedKeys() {
    Set<String> keys = new HashSet<>();
    for (RoomServiceAccess roomService : RoomServiceAccess.ALL()) {
      if (roomService.need == null) {
        log.info("Room check skipped (no NEED): blueprintKey=%s", roomService.room().key);
        continue;
      }

      if (!(roomService.room() instanceof RoomBlueprintIns<?> blueprint)) {
        log.info("Room check skipped (not a RoomBlueprintIns): need=%s, blueprintKey=%s, roomClass=%s",
            roomService.need.key, roomService.room().key, roomService.room().getClass().getSimpleName());
        continue;
      }

      int instances = blueprint.instancesSize();
      log.info("Room check: need=%s, blueprintKey=%s, instancesSize=%d", roomService.need.key, blueprint.key, instances);
      if (instances > 0) {
        keys.add(roomService.need.key);
      }
    }

    // Skinnydip is satisfied at any open-water tile (SETT.PATH().finders.water),
    // not a constructed room, so there is no instancesSize() to check at all.
    // Treated as always present - a map with zero water tiles is a rare,
    // degenerate case rather than a normal playstyle.
    keys.add(NEEDS.TYPES().SKINNYDIP.key);

    // Temple and Shrine are registered per-religion (SETT.ROOMS().TEMPLES),
    // a separate registry from RoomServiceAccess.ALL() entirely - confirmed
    // via StatsReligion.ReligionTot (whose NEED is NEEDS.TYPES().TEMPLE /
    // .SHRINE directly, not looked up per-room) and ROOM_TEMPLES itself.
    // ALL and SHRINES are already flat lists spanning every religion (perRel
    // and perRelShrine are just a religion-indexed partition of those same
    // lists), so no per-religion loop is needed.
    if (hasAnyInstance(NEEDS.TYPES().TEMPLE.key, SETT.ROOMS().TEMPLES.ALL)) {
      keys.add(NEEDS.TYPES().TEMPLE.key);
    }
    if (hasAnyInstance(NEEDS.TYPES().SHRINE.key, SETT.ROOMS().TEMPLES.SHRINES)) {
      keys.add(NEEDS.TYPES().SHRINE.key);
    }

    return keys;
  }

  private static boolean hasAnyInstance(CharSequence needKeyForLogging, LIST<?> blueprints) {
    int totalInstances = 0;
    for (Object blueprint : blueprints) {
      if (blueprint instanceof RoomBlueprintIns<?> instances) {
        int count = instances.instancesSize();
        log.info("Room check: need=%s, blueprintKey=%s, instancesSize=%d", needKeyForLogging, instances.key, count);
        totalInstances += count;
      } else {
        log.info("Room check skipped (not a RoomBlueprintIns): need=%s, roomClass=%s", needKeyForLogging, blueprint.getClass().getSimpleName());
      }
    }
    return totalInstances > 0;
  }

  private static CompetingTotals getDefaultCompetingTotals() {
    ensureCacheCurrent();
    if (defaultCompetingTotals == null) {
      double presentTotal = 0.0;
      double calibratedTotal = 0.0;
      Set<String> countedNeedKeys = new HashSet<>();
      for (NEED competingNeed : NEEDS.ALLSIMPLE()) {
        // Guards against NEEDS.ALLSIMPLE() potentially containing duplicate
        // entries for the same NEED (unconfirmed either way from source), so
        // a rate is never summed twice regardless of which case is true.
        if (countedNeedKeys.add(competingNeed.key)) {
          double rate = competingNeed.rate.get(HCLASS_RACE.clP(null, null));
          calibratedTotal += rate;
          if (presentNeedKeys.contains(competingNeed.key)) {
            presentTotal += rate;
          }
        }
      }
      defaultCompetingTotals = new CompetingTotals(presentTotal, calibratedTotal);
    }
    return defaultCompetingTotals;
  }

  private static CompetingTotals getRaceCompetingTotals(Race race) {
    ensureCacheCurrent();
    if (raceCompetingTotals[race.index()] == null) {
      double presentTotal = 0.0;
      double calibratedTotal = 0.0;
      Set<String> countedNeedKeys = new HashSet<>();
      for (NEED competingNeed : NEEDS.ALLSIMPLE()) {
        if (countedNeedKeys.add(competingNeed.key)) {
          double rate = race.bvalue(competingNeed.rate);
          calibratedTotal += rate;
          if (presentNeedKeys.contains(competingNeed.key)) {
            presentTotal += rate;
          }
        }
      }
      raceCompetingTotals[race.index()] = new CompetingTotals(presentTotal, calibratedTotal);
    }
    return raceCompetingTotals[race.index()];
  }
}
