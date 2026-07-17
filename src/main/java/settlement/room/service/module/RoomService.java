// RoomService.java
// Document Version 1.2.1
// Creation date: 2026/07/12
// Creator: Thalassicus

package settlement.room.service.module;

import game.audio.AUDIO;
import game.audio.SoundRace;
import game.time.TIME;
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
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.Json;
import snake2d.util.file.SAVABLE;
import thalassicus.util.ThalRollingData;
import thalassicus.util.ThalSavable;

public abstract class RoomService {
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

    // Public in case this cached eligibility figure is useful elsewhere
    // (matching the brief's own note that Jake's code might benefit from
    // this mapping too). 0.0 for any blueprint whose daily rollover hasn't
    // run yet this session, and for a religion blueprint whose owning
    // Religion couldn't be resolved via religionOf() (see that method's own
    // note - should not happen in practice).
    public double lastEligibleDemandPopulation() {
        return this.lastEligibleDemandPopulation;
    }

    // Not required by this class's own wiring (load() and the saver already
    // keep this field current) - provided for symmetry and for any external
    // caller that wants to override or seed the cached value directly.
    public void lastEligibleDemandPopulationSet(double value) {
        this.lastEligibleDemandPopulation = value;
    }

    // The peak-load figure actually used for the capacity estimate: the
    // rolling max over the event-cycle window for event-boosted blueprints
    // (Arena/Arenag/Speaker/Stage), or plain load() (yesterday's peak) for
    // every other blueprint. Kept as a single accessor, rather than reading
    // load() directly in two places, so the saturation disclaimer
    // (ModuleService.appendCapacityDisclaimer) and the capacity formula
    // itself (totalMultiplier(), below) can never disagree about which
    // "peak" is under discussion. Always calls load() first regardless -
    // that call's rollover side effects (finalizing loadLast, refreshing
    // lastEligibleDemandPopulation, pushing into eventPeakLoadWindow) must
    // still happen on schedule even when its return value ends up
    // overridden below.
    public double capacityLoad() {
        double dailyPeakLoad = this.load();
        return this.eventPeakLoadWindow != null ? this.eventPeakLoadWindow.max() : dailyPeakLoad;
    }

    // Tries a direct, self-contained estimate first: if this blueprint has
    // recorded any real occupancy (load() > 0.0) and has a cached eligible
    // population figure (lastEligibleDemandPopulation, refreshed daily in
    // load() - via countEligibleDemandPopulation() for ordinary
    // RoomServiceAccess blueprints, or countEligibleReligionPopulation() for
    // religion Shrine/Temple blueprints), divide that population by the
    // aggregate peak-occupied fraction load() already tracks citywide. Both
    // this.total() terms cancel out algebraically (supportedPopulation =
    // demand / load(), then divided again by total() to convert back to a
    // multiplier), so total() never actually appears in the result - only
    // its cancellation matters. Falls through to Jake's original NEED-rate-
    // based formula for any blueprint with no cached population yet (e.g.
    // never used since the last save/load, or - for religion blueprints -
    // religionOf() unable to resolve an owning Religion).
    public double totalMultiplier() {
        if (this.need == null || this.total == 0) {
            return 1.0;
        }

        double aggregateLoad = this.capacityLoad();
        if (aggregateLoad > 0.0 && this.lastEligibleDemandPopulation > 0.0) {
            double supportedPopulation = this.lastEligibleDemandPopulation / aggregateLoad;
            return supportedPopulation / this.total;
        }

        double ne = this.usage / STATS.SERVICE().needTot(this.need);
        if (this.need instanceof NEED_E) {
            return 1.0 / (ne * this.need.rate.get(HCLASS_RACE.clP(null, null)));
        }

        double tot = 0.0;

        for (int ni = 0; ni < NEEDS.ALLSIMPLE().size(); ni++) {
            NEED o = NEEDS.ALLSIMPLE().get(ni);
            tot += o.rate.get(HCLASS_RACE.clP(null, null));
        }

        if (STATS.SERVICE().needTot(this.need) == 0.0) {
            ne = this.usage;
        }

        return 1.0 / (ne * TIME.servicePerDay() * 0.5 * this.need.rate.get(HCLASS_RACE.clP(null, null)) / tot);
    }

    // Lazily built once and cached for the process lifetime: ROOM_TEMPLE and
    // ROOM_SHRINE both hold their own Religion directly, but the RoomService
    // instance each one constructs (anonymously, inline) has no back-
    // reference to it - this map supplies that missing link without
    // shadowing ROOM_TEMPLE/ROOM_SHRINE just to add one field (a deliberate
    // choice: adding the field directly on RoomService would be the more
    // "correct" fix, but this mod scopes its shadow footprint deliberately
    // narrow). Safe to build lazily rather than statically at class-load
    // time, since totalMultiplier() is only ever called from UI code after
    // SETT.ROOMS() is fully constructed.
    private static Map<RoomService, Religion> religionByService;

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
    // demand for this specific blueprint: passes AI.modules().needs.has(hType)
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
