package settlement.room.service.stage;

import settlement.misc.job.JOBMANAGER_HASER;
import settlement.misc.job.JOB_MANAGER;
import settlement.misc.job.SETT_JOB;
import settlement.misc.util.FSERVICE;
import settlement.room.main.RoomInstance;
import settlement.room.main.TmpArea;
import settlement.room.main.job.JobPositions;
import settlement.room.main.util.RoomInit;
import settlement.room.service.module.ROOM_SERVICER;
import settlement.room.service.module.RoomServiceInstance;
import snake2d.Renderer;
import snake2d.util.misc.CLAMP;
import snake2d.util.rnd.RND;
import util.rendering.RenderData;
import util.rendering.ShadowBatch;

final class StageInstance extends RoomInstance implements JOBMANAGER_HASER, ROOM_SERVICER {
   private static final long serialVersionUID = 1L;
   final RoomServiceInstance service;
   final byte off = (byte)RND.rInt(64);
   private final StageInstance.Job job = new StageInstance.Job(this);
   private short workers;
   private short services = 0;

   protected StageInstance(ROOM_STAGE b, TmpArea area, RoomInit init) {
      super(b, area, init);
      this.service = new RoomServiceInstance((int)b.constructor.spectators.get(this), this.blueprintI().data);
      this.employees().maxSet(this.job.size());
      this.employees().neededSet(this.job.size());
      this.activate();
   }

   @Override
   protected boolean render(Renderer r, ShadowBatch shadowBatch, RenderData.RenderIterator it) {
      it.lit();
      return super.render(r, shadowBatch, it);
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
   public JOB_MANAGER getWork() {
      return this.job;
   }

   @Override
   protected void dispose() {
      this.service.dispose(this.blueprintI().data);
   }

   public ROOM_STAGE blueprintI() {
      return (ROOM_STAGE)this.blueprint();
   }

   @Override
   public RoomServiceInstance service() {
      return this.service;
   }

   @Override
   public double quality() {
      return ROOM_SERVICER.defQuality(this, (double)this.employees().employed() / this.employees().max());
   }

   private static class Job extends JobPositions<StageInstance> {
      private static final long serialVersionUID = 1L;

      public Job(StageInstance ins) {
         super(ins);
      }

      @Override
      protected boolean isAndInit(int tx, int ty) {
         return this.ins.blueprintI().work.job(tx, ty) != null;
      }

      @Override
      protected SETT_JOB get(int tx, int ty) {
         return this.ins.blueprintI().work.job(tx, ty);
      }
   }
}
