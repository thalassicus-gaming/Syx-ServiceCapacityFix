// RoomService.java
// Document Version 1.3.1
// Creation date: 2026/07/12
// Creator: Thalassicus

package settlement.room.service.module;

import game.GameDisposable;
import game.audio.AUDIO;
import game.audio.SoundRace;
import game.time.TIME;
import init.race.RACES;
import init.race.Race;
import init.religion.Religion;
import init.type.HCLASS_RACE;
import init.type.NEED;
import init.type.NEEDS;
import init.type.NEED_E;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.entity.humanoid.ai.main.AI;
import settlement.main.SETT;
import settlement.misc.util.FSERVICE;
import settlement.path.finders.SFinderFindable;
import settlement.path.finders.SFinderRoomService;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.room.main.util.RoomInitData;
import settlement.room.spirit.shrine.ROOM_SHRINE;
import settlement.room.spirit.temple.ROOM_TEMPLE;
import settlement.stats.STATS;
import settlement.stats.colls.StatsReligion;
import snake2d.LOG;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.Json;
import snake2d.util.file.SAVABLE;
import thalassicus.capacity.ThalCapacityProfile;
import thalassicus.capacity.ThalCapacityProfileManager;
import thalassicus.util.ThalRollingData;
import thalassicus.util.ThalSavable;
import util.gui.misc.GBox;
import util.info.GFORMAT;

public abstract class RoomService {
    // Not ¤-prefixed deliberately: ModuleService.class is scanned by D.ts()
    // (see the static block below), which appears to cache ¤-prefixed field
    // text through some localization layer we haven't traced - editing
    // ¤¤CapacityD's own string content stopped taking effect in-game after a
    // rebuild, while the surrounding numeric logic updated correctly. Using a
    // plain field name sidesteps whatever that caching keys on.
    private static final CharSequence THAL_CAPACITY_DESCRIPTION =
            "Services compete for a subject's limited time, so adding new types of services will reduce demand on this one, increasing its effective capacity.";
    // Checked against the aggregate, blueprint-wide load() rather than any
    // single room instance's own occupancy - a citizen turned away from one
    // saturated room simply visits another nearby, so it's citywide load
    // that matters, and that's also the exact quantity the capacity
    // estimate's own denominator is built from.
    private static final double SATURATION_LOAD_THRESHOLD = 0.9;
    private static final CharSequence SATURATION_DISCLAIMER = "* Over-estimating capacity due to high load.";
    private static final CharSequence EVENT_DAY_DISCLAIMER = "Event Day! High usage.";
    public static final CharSequence CAPACITY_LIVE = "Capacity (Live):";
    public static final CharSequence CAPACITY_PROFILE = "Capacity (Profile):";
    public static final CharSequence CAPACITY_ESTIMATE = "Capacity (Estimate):";
    private int available = 0;
    private int total = 0;
    private double load;
    private double loadLast;
    private int day;
    // Rolling cache of countEligibleDemandPopulation(), refreshed once a day
    // in the same rollover block that finalizes loadLast, and persisted
    // separately via ThalSavable (not through this class's own saver below)
    // since adding a field to that fixed-shape byte stream would misalign
    // every read that follows it in the core save file.
    private double lastEligibleDemandPopulation = 0.0;
    // Non-null only for blueprints whose NEED carries a recurring EVENT
    // booster (Arena/Arenag/Speaker/Stage, per the "need" data files' own
    // EVENT: field) - a rolling max of daily loadLast values, spanning the
    // shared cycle length every event-boosted NEED repeats on (see
    // eventCycleWindowDays()). Needed because load()'s plain daily rollover
    // only ever remembers YESTERDAY's peak; for a room whose true peak
    // demand is concentrated on one day out of every several, that would
    // read as near-empty on every non-event day and overstate capacity
    // accordingly. Allocated eagerly in the constructor (need.event is a
    // fixed, permanent property, known before any rollover ever runs) and
    // registered with ThalSavable at that same moment, so it persists
    // across a save/load cycle - a season is only 4 in-game days here, so
    // this window would rarely fill up naturally within a single play
    // session otherwise.
    private ThalRollingData eventPeakLoadWindow;
    public final int radius;
    final RoomBlueprintImp room;
    public final SFinderRoomService finder;
    public final NEED need;
    public SoundRace usageSound;
    public double usage = 1.0;
    public final CharSequence verb;
    public final SAVABLE saver = new SAVABLE() {
        @Override
        public void save(FilePutter file) {
            file.i(RoomService.this.available);
            file.i(RoomService.this.total);
            file.d(RoomService.this.load);
            file.d(RoomService.this.loadLast);
            if (ThalSavable.instance() != null) {
                ThalSavable.instance().set(RoomService.this.room.key, RoomService.this.lastEligibleDemandPopulation);
            }
        }

        @Override
        public void load(FileGetter file) throws IOException {
            RoomService.this.available = file.i();
            RoomService.this.total = file.i();
            RoomService.this.load = file.d();
            RoomService.this.loadLast = file.d();
            if (ThalSavable.instance() != null) {
                RoomService.this.lastEligibleDemandPopulation = ThalSavable.instance().get(RoomService.this.room.key, 0.0);
                ThalSavable.log.info(
                        "RoomService[%s].saver.load(): pulled cached eligible population %.1f from ThalSavable.",
                        RoomService.this.room.key, RoomService.this.lastEligibleDemandPopulation
                );
            } else {
                ThalSavable.log.info(
                        "RoomService[%s].saver.load(): ThalSavable.instance() was null - defaulting cached eligible population to 0.0.",
                        RoomService.this.room.key
                );
            }
        }

        @Override
        public void clear() {
            RoomService.this.available = 0;
            RoomService.this.total = 0;
            RoomService.this.load = 0.0;
            RoomService.this.loadLast = 0.0;
            RoomService.this.lastEligibleDemandPopulation = 0.0;
        }
    };

    public RoomService(RoomBlueprintImp b, RoomInitData data, NEED need) {
        this.verb = data.text().json("SERVICE").text("VERB");
        this.need = need;
        Json jd = data.data().json("SERVICE");
        this.usageSound = AUDIO.race("ROOM_SERVICE_" + b.key);
        this.room = b;
        this.radius = jd.has("RADIUS") ? jd.i("RADIUS", 0, 50000) : 150;
        this.finder = new SFinderRoomService(b.info.name) {
            public FSERVICE get(int tx, int ty) {
                return RoomService.this.service(tx, ty);
            }
        };
        this.day = -1;

        if (this.need != null && this.need.event > 1.0) {
            this.eventPeakLoadWindow = new ThalRollingData(eventCycleWindowDays());
            ThalSavable.register(this.room.key + "_eventPeak", this.eventPeakLoadWindow);
        }
    }

    public double load() {
        if (this.total == 0) {
            return 1.0;
        }

        if (this.day != TIME.days().bitsSinceStart()) {
            this.loadLast = this.load;
            this.load = 0.0;
            this.day = TIME.days().bitsSinceStart();

            // Piggybacks the population refresh onto the same once-a-day
            // rollover load() already does, rather than a separate scheduled
            // scan - no new cadence, no new apparatus. Guarded on need != null
            // so Hospital/Nursery/Dump (RoomServiceAccess instances outside
            // the NEED lottery entirely) never pay for a scan whose result
            // nothing reads. Ordinary blueprints go through
            // countEligibleDemandPopulation(); religion blueprints (Shrine/
            // Temple - RoomService but not RoomServiceAccess) go through
            // countEligibleReligionPopulation() instead, since their
            // eligibility is governed by STATS.RELIGION() rather than
            // accessRequest(). A religion RoomService whose owning Religion
            // can't be resolved (religionOf() returns null - should not
            // happen in practice, see that method's own note) keeps its
            // previous cached value rather than being silently zeroed.
            if (this.need != null) {
                if (this instanceof RoomServiceAccess roomServiceAccess) {
                    this.lastEligibleDemandPopulation = countEligibleDemandPopulation(roomServiceAccess);
                    if (ThalSavable.instance() != null) {
                        ThalSavable.instance().set(this.room.key, this.lastEligibleDemandPopulation);
                    }
                } else if (this.need == NEEDS.TYPES().TEMPLE || this.need == NEEDS.TYPES().SHRINE) {
                    Religion religion = religionOf(this);
                    if (religion != null) {
                        this.lastEligibleDemandPopulation = countEligibleReligionPopulation(religion, this.need == NEEDS.TYPES().TEMPLE);
                        if (ThalSavable.instance() != null) {
                            ThalSavable.instance().set(this.room.key, this.lastEligibleDemandPopulation);
                        }
                    }
                }

                // Event-boosted NEEDs (Arena/Arenag/Speaker/Stage) have their
                // true peak concentrated on one day out of every several - see
                // the eventPeakLoadWindow field comment. Fed with the SAME
                // loadLast just finalized above, on the same daily cadence,
                // rather than any separate scan. Already allocated in the
                // constructor for any qualifying blueprint - non-null here is
                // exactly "this blueprint qualifies", nothing further to check.
                if (this.eventPeakLoadWindow != null) {
                    this.eventPeakLoadWindow.push(this.loadLast);
                }
            }
        }

        return this.loadLast;
    }

    public void loadFix(RoomBlueprintIns<?> blue) {
        this.total = 0;
        this.available = 0;

        for (int i = 0; i < blue.instancesSize(); i++) {
            RoomInstance ins = blue.getInstance(i);
            ROOM_SERVICER ss = (ROOM_SERVICER)ins;
            this.available = this.available + ss.service().available();
            this.total = this.total + ss.service().total();
        }
    }

    public int available() {
        return this.available;
    }

    public int total() {
        return this.total;
    }

    void increServices(int total, int available) {
        if (this.total == 0) {
            this.load = 1.0;
            this.loadLast = 1.0;
        } else {
            double d = 1.0 - (double)this.available / this.total;
            if (d > this.load) {
                this.load = d;
            }

            if (d > this.loadLast) {
                this.loadLast = d;
            }
        }

        this.available += available;
        this.total += total;
    }

    public RoomBlueprintImp room() {
        return this.room;
    }

    // Public in case this cached eligibility figure is useful to other code
    // beyond this class's own totalMultiplier() pipeline. 0.0 for any
    // blueprint whose daily rollover hasn't run yet this session, and for a
    // religion blueprint whose owning Religion couldn't be resolved via
    // religionOf() (see that method's own note - should not happen in
    // practice).
    public double lastEligibleDemandPopulation() {
        return this.lastEligibleDemandPopulation;
    }

    // Not required by this class's own wiring (load() and the saver already
    // keep this field current) - provided for symmetry and for any external
    // caller that wants to override or seed the cached value directly.
    public void lastEligibleDemandPopulationSet(double value) {
        this.lastEligibleDemandPopulation = value;
    }

    // Returns the peak-occupied fraction this blueprint should be judged
    // against for capacity purposes, preferring a longer, event-cycle-aware
    // signal wherever one is actually available:
    //   1. eventPeakLoadWindow's rolling max, once it has at least one real
    //      sample (sampleCount() > 0) - not merely non-null, since the
    //      window is allocated eagerly in the constructor for any
    //      qualifying blueprint, well before its first real push.
    //   2. dailyPeakLoad (load()'s own loadLast) otherwise - correct for
    //      every ordinary blueprint always, and the right interim reading
    //      for an event-boosted one before its window has filled in.
    // Contract: a return of 0.0 means no real peak has been observed yet -
    // callers must treat that as "no live signal available" and fall back
    // to a lower-priority estimate, never as a literal 0% load. This is
    // also the single figure every other Load-related display in this mod
    // should read from (rather than calling load() directly a second time),
    // so none of them can ever disagree about which "peak" is under
    // discussion.
    // Always calls load() first regardless of which tier ends up used -
    // that call's rollover side effects (finalizing loadLast, refreshing
    // lastEligibleDemandPopulation, pushing into eventPeakLoadWindow) must
    // still happen on schedule even when its return value ends up
    // overridden below.
    public double eventAdjustedDailyPeakLoad() {
        double dailyPeakLoad = this.load();
        if (this.eventPeakLoadWindow != null && this.eventPeakLoadWindow.sampleCount() > 0) {
            return this.eventPeakLoadWindow.max();
        }

        return dailyPeakLoad;
    }

    // Determines if this RoomService has the capability to satisfy NEEDs.
    private boolean hasCapacity() {
        return (this.need != null && this.total != 0);
    }

    // Capacity-per-slot can never fall below 1.0; it would indicate a slot is available, but cannot be filled.
    // Schrodinger's physician bed. Any result at or below this threshold is therefore not a real capacity figure.
    private static final double MIN_CAPACITY_PER_SLOT = 0.99;

    // Returns a single, default capacity-per-slot value.
    // DEPRECATED; call sites that still use this should be changed to call individual methods below.
    @Deprecated
    public double totalMultiplier() {
        if (!hasCapacity()) {
            return 1.0;
        }

        double capacityPerSlot = liveCapacityPerSlot();
        if (capacityPerSlot >= MIN_CAPACITY_PER_SLOT) {
            return capacityPerSlot;
        }

        capacityPerSlot = profileCapacityPerSlot();
        if (capacityPerSlot >= MIN_CAPACITY_PER_SLOT) {
            return capacityPerSlot;
        }

        capacityPerSlot = hypotheticalCapacityPerSlot();
        if (capacityPerSlot >= MIN_CAPACITY_PER_SLOT) {
            return capacityPerSlot;
        }

        return 1.0;
    }

    // An estimate built entirely from this session's observed data.
    // Returns a -1 sentinel if no estimate is available.
    public double liveCapacityPerSlot() {
        if (!hasCapacity()) {
            return -1.0;
        }

        // aggregate city load = city used slots / city total slots
        // peak city load = peak city used slots / city total slots
        // city capacity = eligible population / peak city load
        // capacity per slot = city capacity / total slots
        double aggregateLoad = this.eventAdjustedDailyPeakLoad();
        if (aggregateLoad > 0.0 && this.lastEligibleDemandPopulation > 0.0) {
            double supportedPopulation = this.lastEligibleDemandPopulation / aggregateLoad;
            return supportedPopulation / this.total;
        }
        return -1.0;
    }

    // Player-selectable capacity profiles for planning a city that doesn't
    // exist yet. Returns a -1 sentinel if no manager exists yet, no profile
    // is currently active, or the active profile has no entry for this
    // blueprint - all three collapse to the same sentinel deliberately,
    // since totalMultiplier() only needs to know whether to try this tier
    // or fall through, not why it came up empty. Never validates the
    // returned value against MIN_CAPACITY_PER_SLOT itself - a hand-edited
    // profile file could contain an invalid entry, and totalMultiplier()'s
    // own threshold check already catches that case and falls through to
    // hypotheticalCapacityPerSlot(), the same safety net liveCapacityPerSlot()
    // relies on rather than duplicating.
    public double profileCapacityPerSlot() {
        if (!hasCapacity()) {
            return -1.0;
        }

        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        if (manager == null) {
            return -1.0;
        }

        ThalCapacityProfile activeProfile = manager.activeProfile();
        if (activeProfile == null) {
            return -1.0;
        }

        return activeProfile.capacityPerSlot(this.room.key, -1.0);
    }

    // The original NEED-rate-based capacity formula, unmodified in substance.
    // Only local variable names were changed for readability.
    // Functionality should be untouched for future compatibility.
    // ALWAYS returns a valid capacity-per-slot. If a valid number cannot be found, logs an error and returns 1.0.
    public double hypotheticalCapacityPerSlot() {
        if (!hasCapacity()) {
            return 1.0;
        }

        double needRateModifier = this.usage / STATS.SERVICE().needTot(this.need);
        if (this.need instanceof NEED_E) {
            return 1.0 / (needRateModifier * this.need.rate.get(HCLASS_RACE.clP(null, null)));
        }

        double needSum = 0.0;

        for (int needIndex = 0; needIndex < NEEDS.ALLSIMPLE().size(); needIndex++) {
            NEED myNeed = NEEDS.ALLSIMPLE().get(needIndex);
            needSum += myNeed.rate.get(HCLASS_RACE.clP(null, null));
        }

        if (STATS.SERVICE().needTot(this.need) == 0.0) {
            needRateModifier = this.usage;
        }

        double capacityPerSlot = 1.0 / (needRateModifier * TIME.servicePerDay() * 0.5 * this.need.rate.get(HCLASS_RACE.clP(null, null)) / needSum);
        if (capacityPerSlot < MIN_CAPACITY_PER_SLOT){
            // A slot cannot have a capacity less than 1; it would indicate the slot is available but unfillable.
            LOG.err("hypotheticalCapacityPerSlot() returning invalid capacity per slot: Type=" + room.type + ", capacityPerSlot="  + capacityPerSlot);
        }
        return capacityPerSlot >= MIN_CAPACITY_PER_SLOT ? capacityPerSlot : 1.0;
    }

    // Lazily built once and cached for the process lifetime: ROOM_TEMPLE and
    // ROOM_SHRINE both hold their own Religion directly, but the RoomService
    // instance each one constructs (anonymously, inline) has no back-
    // reference to it - this map supplies that missing link without
    // shadowing ROOM_TEMPLE/ROOM_SHRINE just to add one field (a deliberate
    // choice: adding the field directly would be the more
    // "correct" fix, but this mod scopes its shadow footprint deliberately
    // narrow). Safe to build lazily rather than statically at class-load
    // time, since totalMultiplier() is only ever called from UI code after
    // SETT.ROOMS() is fully constructed.
    //
    // Keyed by RoomService object identity (no equals()/hashCode() override
    // on this class), so this cache MUST be reset whenever the game world is
    // rebuilt (a new game, or returning to the main menu and loading a save)
    // - otherwise it silently keeps referring to the previous session's now-
    // discarded RoomService objects, and every lookup against the new
    // session's genuinely-different objects misses forever. Reset via the
    // same GameDisposable mechanism RoomServiceAccess.all/AIModule.all/
    // ThalSavable.registry already use for the identical reason.
    private static Map<RoomService, Religion> religionByService;

    static {
        new GameDisposable() {
            @Override
            protected void dispose() {
                religionByService = null;
            }
        };
    }

    private static Religion religionOf(RoomService roomService) {
        if (religionByService == null) {
            religionByService = new HashMap<>();
            for (ROOM_TEMPLE temple : SETT.ROOMS().TEMPLES.ALL) {
                religionByService.put(temple.service(), temple.religion);
            }
            for (ROOM_SHRINE shrine : SETT.ROOMS().TEMPLES.SHRINES) {
                religionByService.put(shrine.service(), shrine.religion);
            }
        }

        return religionByService.get(roomService);
    }

    // Mirrors NEEDS' own constructor arithmetic exactly (events.size() * 2
    // + 1) to reproduce the shared window length every event-boosted NEED
    // cycles through, without needing NEEDS itself to expose it. Computed
    // once and cached, since it depends only on static game data (which
    // NEEDs have event > 1.0), never on anything about a specific
    // RoomService instance.
    private static int eventCycleWindowDays = -1;

    private static int eventCycleWindowDays() {
        if (eventCycleWindowDays < 0) {
            int eventNeedCount = 0;
            for (NEED candidate : NEEDS.ALL()) {
                if (candidate.event > 1.0) {
                    eventNeedCount++;
                }
            }

            eventCycleWindowDays = eventNeedCount * 2 + 1;
        }

        return eventCycleWindowDays;
    }

    // True only on the specific day, out of every eventCycleWindowDays()-day
    // cycle, that this NEED's EVENT booster is actually active - mirrors
    // NEEDS.Event.vGet() exactly: this NEED's position among every event-
    // boosted NEED (in NEEDS.ALL() iteration order) determines which single
    // day its rate gets boosted, the same day*2 offset NEEDS' own
    // constructor assigns each qualifying NEED in turn.
    private static boolean isEventDayToday(NEED need) {
        int eventIndex = 0;
        for (NEED candidate : NEEDS.ALL()) {
            if (candidate == need) {
                break;
            }

            if (candidate.event > 1.0) {
                eventIndex++;
            }
        }

        int eventDay = eventIndex * 2;
        return TIME.days().bitsSinceStart() % eventCycleWindowDays() == eventDay;
    }

    // Public surface for the tooltip disclaimer. eventPeakLoadWindow being
    // non-null is the same "this blueprint qualifies" check used
    // throughout this class, and doubles as a free fast-path: only Arena/
    // Arenag/Speaker/Stage ever need the NEEDS.ALL() scan above at all.
    public boolean isEventDayToday() {
        return this.eventPeakLoadWindow != null && isEventDayToday(this.need);
    }

    // Self-contained live tally of subjects currently eligible to generate
    // demand for this specific blueprint: passes AI.modules().needs.has(HTYPE)
    // (excludes Enemy/Rioter/Soldier/Prisoner/Deranged, checked once per
    // subject) and accessRequest(...) (Nobility/Tourist auto-access, the
    // Child-to-parent-class redirect, and per-race/HCLASS permission data
    // from that race's own definition file). A full live-entity scan on
    // every call, deliberately - no rolling average, no scheduled cadence -
    // an instantaneous snapshot, matching this method's own already-per-call
    // cost. Performance precedent: UISubjectsList already does an equivalent
    // full-entity scan every render with no observed issue.
    private static double countEligibleDemandPopulation(RoomServiceAccess roomServiceAccess) {
        double count = 0.0;
        for (ENTITY entity : SETT.ENTITIES().getAllEnts()) {
            if (entity instanceof Humanoid humanoid
                    && AI.modules().needs.has(humanoid.indu().hType())
                    && roomServiceAccess.stats().accessRequest(humanoid)) {
                count += 1.0;
            }
        }

        return count;
    }

    // Religion counterpart to countEligibleDemandPopulation(): a subject is
    // eligible for a specific Shrine/Temple only if BOTH (a) their own
    // current religion matches targetReligion - Shrine/Temple rooms are a
    // hard per-religion partition, confirmed via S_PlanTemple.services()
    // only ever considering temples matching the subject's own religion in
    // the first place - and (b) STATS.RELIGION().getter.get(...) (the
    // subject's OWN StatReligion, which only represents the right
    // permission data once (a) is already confirmed true) grants permission
    // via permissionTemple or permissionShrine, matching
    // S_PlanTemple/S_PlanShrine's own allowed() checks exactly. The HTYPE
    // gate is identical to the ordinary case, since Shrine/Temple are
    // driven by the same AIModule_Service/S_Plans machinery as every other
    // room.
    private static double countEligibleReligionPopulation(Religion targetReligion, boolean isTemple) {
        double count = 0.0;
        for (ENTITY entity : SETT.ENTITIES().getAllEnts()) {
            if (entity instanceof Humanoid humanoid && AI.modules().needs.has(humanoid.indu().hType())) {
                StatsReligion.StatReligion subjectReligion = STATS.RELIGION().getter.get(humanoid.indu());
                if (subjectReligion.religion == targetReligion) {
                    boolean permitted = isTemple ? subjectReligion.permissionTemple.has(humanoid) : subjectReligion.permissionShrine.has(humanoid);
                    if (permitted) {
                        count += 1.0;
                    }
                }
            }
        }

        return count;
    }
    public void appendCapacityTooltip(GBox box, long totalSlots) {
        box.NL();
        box.textLL(CAPACITY_LIVE);
        box.tab(6);
        double liveCapacityPerSlot = liveCapacityPerSlot();
        if (liveCapacityPerSlot >= 1.0) {
            box.add(GFORMAT.i(box.text(), (int)(totalSlots * liveCapacityPerSlot)));
        } else {
            box.text("N/A");
        }

        appendCapacityDisclaimer(box);
        appendEventDayDisclaimer(box);

        box.NL();
        box.textLL(CAPACITY_PROFILE);
        box.tab(6);
        double profileCapacityPerSlot = profileCapacityPerSlot();
        if (profileCapacityPerSlot >= 1.0) {
            box.add(GFORMAT.i(box.text(), (int)(totalSlots * profileCapacityPerSlot)));
        } else {
            box.text("N/A");
        }

        box.NL();
        box.textLL(CAPACITY_ESTIMATE);
        box.tab(6);
        double estimatedCapacityPerSlot = hypotheticalCapacityPerSlot();
        box.add(GFORMAT.i(box.text(), (int)(totalSlots * estimatedCapacityPerSlot)));

        appendDivergenceLines(box, need, total() * hypotheticalCapacityPerSlot());

        box.NL();
        box.text(THAL_CAPACITY_DESCRIPTION);
    }

    // Shared by every tooltip site that displays Capacity, appended directly
    // below the number itself. Checked against capacityLoad() (the same
    // figure totalMultiplier() itself divides by - a rolling max over the
    // event cycle for Arena/Arenag/Speaker/Stage, plain load() for every
    // other blueprint) rather than load() directly, so this disclaimer can
    // never disagree with the number it's warning about. At or above
    // SATURATION_LOAD_THRESHOLD, capacityPerSlot is derived from an
    // artificially-capped observed peak rather than true (unconstrained)
    // demand, which can only push the estimate too high, never too low - so
    // the number itself is left untouched here (no soft cap, no formula
    // switch, which would risk the displayed number flickering between two
    // formulas near the threshold) and a plain disclaimer is appended
    // instead.
    public void appendCapacityDisclaimer(GBox b) {
        if (eventAdjustedDailyPeakLoad() > SATURATION_LOAD_THRESHOLD) {
            b.NL();
            b.text(SATURATION_DISCLAIMER);
        }
    }

    // Companion to appendCapacityDisclaimer, appended right alongside it -
    // the two are not mutually exclusive, since they answer different
    // questions ("is this number possibly too optimistic" vs. "is today
    // just an unusually busy day, nothing to worry about"). Only ever true
    // for Arena/Arenag/Speaker/Stage (RoomService.isEventDayToday() returns
    // false immediately for every other blueprint).
    public void appendEventDayDisclaimer(GBox b) {
        if (isEventDayToday()) {
            b.NL();
            b.text(EVENT_DAY_DISCLAIMER);
        }
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


    public abstract FSERVICE service(int var1, int var2);

    public SFinderFindable finder() {
        return this.finder;
    }

    public int radius() {
        return this.radius;
    }

    public interface ROOM_SERVICE_HASER extends RoomFinderHaser {
        RoomService service();

        @Override
        default SFinderFindable finder() {
            return this.service().finder;
        }

        @Override
        default int radius() {
            return this.service().radius;
        }
    }
}