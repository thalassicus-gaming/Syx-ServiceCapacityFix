// RoomService.java
// Document Version 1.0.1
// Creation date: 2026/07/12
// Creator: Thalassicus

package settlement.room.service.module;

import game.audio.AUDIO;
import game.audio.SoundRace;
import game.time.TIME;
import init.type.NEED;

import java.io.IOException;
import settlement.misc.util.FSERVICE;
import settlement.path.finders.SFinderFindable;
import settlement.path.finders.SFinderRoomService;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomBlueprintIns;
import settlement.room.main.RoomInstance;
import settlement.room.main.util.RoomInitData;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.Json;
import snake2d.util.file.SAVABLE;
import thalassicus.ThalServiceCapacityCalculator;

public abstract class RoomService {
   private int available = 0;
   private int total = 0;
   private double load;
   private double loadLast;
   private int day;
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
      }

      @Override
      public void load(FileGetter file) throws IOException {
         RoomService.this.available = file.i();
         RoomService.this.total = file.i();
         RoomService.this.load = file.d();
         RoomService.this.loadLast = file.d();
      }

      @Override
      public void clear() {
         RoomService.this.available = 0;
         RoomService.this.total = 0;
         RoomService.this.load = 0.0;
         RoomService.this.loadLast = 0.0;
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

   // Converts this room's raw service-slot count into an estimated number of
   // subjects it can support per day. The full calculation returns both an
   // "All Services" and a "Present Services" figure (see
   // ThalServiceCapacityCalculator.CapacityMultipliers), but this method
   // specifically returns the All Services value, since that is the figure
   // every existing, unknown, or future call site of this method has always
   // been built and validated against. Present Services is surfaced only
   // where this class's tooltip-building code calls
   // correctedCapacityMultipliers(...) directly, not through this method.
   public double totalMultiplier() {
      return ThalServiceCapacityCalculator.correctedCapacityMultipliers(this).allServices();
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
