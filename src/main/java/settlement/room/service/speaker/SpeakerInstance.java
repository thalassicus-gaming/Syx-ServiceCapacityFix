// SpeakerInstance.java
// Document Version 1.0.0
// Creation date: 2026/07/17
// Creator: Thalassicus

package settlement.room.service.speaker;

import settlement.misc.job.JOBMANAGER_HASER;
import settlement.misc.job.JOB_MANAGER;
import settlement.misc.util.FSERVICE;
import settlement.room.main.RoomInstance;
import settlement.room.main.TmpArea;
import settlement.room.main.util.RoomInit;
import settlement.room.service.module.ROOM_SERVICER;
import settlement.room.service.module.RoomServiceInstance;
import snake2d.Renderer;
import snake2d.util.misc.CLAMP;
import snake2d.util.rnd.RND;
import util.rendering.RenderData;
import util.rendering.ShadowBatch;

final class SpeakerInstance extends RoomInstance implements JOBMANAGER_HASER, ROOM_SERVICER {
   private static final long serialVersionUID = 1L;
   final RoomServiceInstance service;
   final byte off = (byte)RND.rInt(64);
   byte workers = 0;
   private short services = 0;

   protected SpeakerInstance(ROOM_SPEAKER b, TmpArea area, RoomInit init) {
      super(b, area, init);
      this.service = new RoomServiceInstance((int)b.constructor.spectators.get(this), this.blueprintI().data);
      this.employees().maxSet(1);
      this.employees().neededSet(1);
      this.activate();
   }

   @Override
   protected boolean render(Renderer r, ShadowBatch shadowBatch, RenderData.RenderIterator it) {
      it.lit();
      return super.render(r, shadowBatch, it);
   }

   @Override
   protected void activateAction() {
      if (this.workers > 0) {
         this.setServices(this.service.total());
      }
   }

   @Override
   protected void deactivateAction() {
      this.setServices(0);
      this.workers = 0;
   }

   void incServices(int s) {
      if (this.workers > 0) {
         this.setServices(this.services + s);
      }
   }

   boolean hasService() {
      return this.workers > 0;
   }

   // ============================================================================
   // MODDED BY THALASSICUS - START
   // ============================================================================
   // Was previously two sequential RoomServiceInstance.report() calls (one
   // subtracting this instance's entire old service count, one adding back
   // the entire new count), which let RoomService.increServices() (the
   // citywide aggregate) observe an artificial, momentary "zero services"
   // state between the two calls - a state this room was never actually
   // in. See RoomServiceInstance.employeeDrivenSlotCountUpdate()'s own
   // comment for the full explanation. Replaced with a single call to that
   // method, which computes and applies the true net change atomically.
   private void setServices(int s) {
      int newCount = CLAMP.i(s, 0, this.service.total());
      this.service.employeeDrivenSlotCountUpdate(
          this.blueprintI().work.service(this.body().cX(), this.body().cY()), this.blueprintI().data, newCount
      );
      this.services = (short) newCount;
   }
   // ============================================================================
   // MODDED BY THALASSICUS - END
   // ============================================================================

   int services() {
      return this.services;
   }

   @Override
   protected void updateAction(double updateInterval, boolean day) {
      if (this.active()) {
         if (this.employees().employed() == 0) {
            if (this.workers > 0) {
               this.workers--;
               if (this.workers == 0) {
                  this.setServices(0);
               }
            }
         } else if (this.workers < 10) {
            this.workers = 10;
            this.setServices(this.service.total());
         }
      }

      if (day) {
         this.service.updateDay();
      }
   }

   @Override
   public JOB_MANAGER getWork() {
      return this.blueprintI().work.manager(this);
   }

   @Override
   protected void dispose() {
      this.service.dispose(this.blueprintI().data);
   }

   public ROOM_SPEAKER blueprintI() {
      return (ROOM_SPEAKER)this.blueprint();
   }

   @Override
   public RoomServiceInstance service() {
      return this.service;
   }

   @Override
   public double quality() {
      return ROOM_SERVICER.defQuality(this, 1.0);
   }
}
