// RoomServiceInstance.java
// Document Version 1.0.1
// Creation date: 2026/07/17
// Creator: Thalassicus

package settlement.room.service.module;

import java.io.Serializable;
import settlement.misc.util.FSERVICE;

public class RoomServiceInstance implements Serializable {
   private static final long serialVersionUID = 1L;
   private short available;
   private short reserved = 0;
   private final short total;
   public byte currentHigh;
   public byte lastHigh;

   public RoomServiceInstance(int total, RoomService data) {
      this.total = (short)total;
      data.increServices(this.total, 0);
   }

   public void report(FSERVICE s, RoomService data, int delta) {
      this.report(s, data, delta, true);
   }

   public void report(FSERVICE s, RoomService data, int delta, boolean load) {
      if (s.findableReservedCanBe()) {
         this.available = (short)(this.available + delta);
         data.increServices(0, delta);
         if (delta < 0) {
            data.finder.report(s, -1);
         } else {
            data.finder.report(s, 1);
         }
      } else if (s.findableReservedIs()) {
         this.reserved = (short)(this.reserved + delta);
      }

      if (load) {
         byte h = (byte)((double)(127 * (this.total() - this.available())) / this.total());
         if (h > this.currentHigh) {
            this.currentHigh = h;
         }

         if (h > this.lastHigh) {
            this.lastHigh = h;
         }
      }
   }

   public void report(FSERVICE s, RoomService data, int delta, int reservable, int reserved) {
      this.reserved = (short)(this.reserved + reserved * delta);
      this.available = (short)(this.available + reservable * delta);
      data.increServices(0, reservable * delta);
      if (s.findableReservedCanBe()) {
         if (delta < 0) {
            data.finder.report(s, -1);
         } else {
            data.finder.report(s, 1);
         }
      }

      byte h = (byte)((double)(127 * (this.total() - this.available())) / this.total());
      if (h > this.currentHigh) {
         this.currentHigh = h;
      }

      if (h > this.lastHigh) {
         this.lastHigh = h;
      }
   }

   // ============================================================================
   // MODDED BY THALASSICUS - START
   // ============================================================================
   // Fixes a citywide load() distortion affecting any room whose backing
   // FSERVICE replaces its ENTIRE assigned service count in one step -
   // currently only SpeakerInstance/StageInstance's setServices(), which
   // previously called report() twice per change (once with delta =
   // -oldCount, once with delta = +newCount) rather than incrementing or
   // decrementing by one unit at a time the way every ordinary room's
   // FSERVICE does.
   //
   // Only the CITYWIDE AGGREGATE was ever affected by this, not this
   // instance's own currentHigh/lastHigh sampling - the original two-call
   // pattern already protected the per-instance figure by passing load=false
   // on the first (subtract-old-count) call, so the per-instance sample was
   // only ever taken once, on the second call, against the correct final
   // state. RoomService.increServices() (the aggregate) has no equivalent
   // gate: it was called unconditionally on both report() invocations,
   // so it observed an artificial, momentary "this instance contributes
   // zero services" state between the two calls - a state that never
   // actually existed in the room's real behavior - spuriously spiking the
   // aggregate's load() to 100% on essentially every subject reservation or
   // release in a Speaker/Stage room.
   //
   // This method computes the NET delta (newCount - this.available) and
   // applies it to the aggregate exactly once, so it never observes the
   // artificial intermediate trough. The finder presence/absence toggle is
   // deliberately left calling data.finder.report() the same number of
   // times, in the same order, with the same signs as the original two-call
   // version would have produced (an oldCount-based check, then a
   // newCount-based check) - NOT simplified to a single "did availability
   // cross zero" check, since FindableDataSingle's internals are opaque to
   // this mod and any change to finder call count/order/sign is a real
   // pathfinding-correctness risk, not merely a cosmetic one. The per-
   // instance load sampling below is also collapsed to a single computation
   // against the final state, matching what the original code already
   // achieved via its load=false flag - not a behavior change, just the
   // same correct result reached in one step instead of two.
   //
   // Existing report() methods above are entirely unmodified and remain the
   // correct path for any ordinary, single-unit-delta caller.
   public void employeeDrivenSlotCountUpdate(FSERVICE s, RoomService data, int newCount) {
      int oldCount = this.available;
      int delta = newCount - oldCount;
      if (delta == 0) {
         return;
      }

      if (oldCount > 0) {
         data.finder.report(s, -1);
      }

      if (newCount > 0) {
         data.finder.report(s, 1);
      }

      this.available = (short) newCount;
      data.increServices(0, delta);

      byte h = (byte) ((double) (127 * (this.total() - this.available())) / this.total());
      if (h > this.currentHigh) {
         this.currentHigh = h;
      }

      if (h > this.lastHigh) {
         this.lastHigh = h;
      }
   }
   // ============================================================================
   // MODDED BY THALASSICUS - END
   // ============================================================================

   public int available() {
      return this.available;
   }

   public int total() {
      return this.total;
   }

   public int reserved() {
      return this.reserved;
   }

   public double load() {
      return this.lastHigh / 127.0;
   }

   public void updateDay() {
      this.lastHigh = this.currentHigh;
      this.currentHigh = 0;
   }

   public void clearLoad() {
      this.lastHigh = 0;
      this.currentHigh = 0;
   }

   public void dispose(RoomService data) {
      data.increServices(-this.total, -this.available);
      this.available = 0;
   }
}
