// RoomService.java
// Document Version 1.0.5
// Creation date: 2026/07/12
// Creator: Thalassicus

package settlement.room.service.module;

import game.audio.AUDIO;
import game.audio.SoundRace;
import game.time.TIME;
import init.type.HCLASS_RACE;
import init.type.NEED;
import init.type.NEEDS;
import init.type.NEED_E;

import java.io.IOException;
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
import settlement.stats.STATS;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.Json;
import snake2d.util.file.SAVABLE;
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
         // nothing reads. Religion blueprints (RoomService but not
         // RoomServiceAccess) are skipped by the instanceof check and keep
         // their default 0.0, which totalMultiplier() already treats as
         // "fall through to Jake's original formula."
         if (this.need != null && this instanceof RoomServiceAccess roomServiceAccess) {
            this.lastEligibleDemandPopulation = countEligibleDemandPopulation(roomServiceAccess);
            if (ThalSavable.instance() != null) {
               ThalSavable.instance().set(this.room.key, this.lastEligibleDemandPopulation);
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
   // this mapping too). 0.0 for religion blueprints and for any ordinary
   // blueprint whose daily rollover hasn't run yet this session.
   public double lastEligibleDemandPopulation() {
      return this.lastEligibleDemandPopulation;
   }

   // Not required by this class's own wiring (load() and the saver already
   // keep this field current) - provided for symmetry and for any external
   // caller that wants to override or seed the cached value directly.
   public void lastEligibleDemandPopulationSet(double value) {
      this.lastEligibleDemandPopulation = value;
   }

   // Tries a direct, self-contained estimate first: if this blueprint has
   // recorded any real occupancy (load() > 0.0) and is an ordinary
   // RoomServiceAccess (not a religion Shrine/Temple, which use
   // STATS.RELIGION() for eligibility instead of accessRequest()), divide a
   // live count of eligible subjects by the aggregate peak-occupied
   // fraction load() already tracks citywide. Both this.total() terms
   // cancel out algebraically (supportedPopulation = demand / load(), then
   // divided again by total() to convert back to a multiplier), so
   // total() never actually appears in the result - only its cancellation
   // matters. Falls through to Jake's original NEED-rate-based formula for
   // religion blueprints, and for any blueprint with no load data yet
   // (load() == 0.0, e.g. never used since the last save/load).
   public double totalMultiplier() {
      if (this.need == null || this.total == 0) {
         return 1.0;
      }

      double aggregateLoad = this.load();
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
