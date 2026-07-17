// Constructor.java
// Document Version 1.0.2
// Creation date: 2026/07/13
// Creator: Thalassicus

package settlement.room.service.hygine.well;

import game.time.TIME;
import java.io.IOException;
import settlement.main.SETT;
import settlement.path.AVAILABILITY;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.TmpArea;
import settlement.room.main.furnisher.Furnisher;
import settlement.room.main.furnisher.FurnisherItem;
import settlement.room.main.furnisher.FurnisherItemTile;
import settlement.room.main.furnisher.FurnisherStat;
import settlement.room.main.util.RoomInit;
import settlement.room.main.util.RoomInitData;
import settlement.room.sprite.RoomSprite;
import settlement.room.sprite.RoomSprite1x1;
import settlement.room.sprite.RoomSpriteCombo;
import snake2d.CORE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.color.ColorImp;
import snake2d.util.datatypes.DIR;
import snake2d.util.file.Alloc;
import snake2d.util.file.Json;
import snake2d.util.rnd.RND;
import thalassicus.util.ThalServiceFurnisherStat;
import util.rendering.RenderData;
import util.rendering.ShadowBatch;

final class Constructor extends Furnisher {
   private final ROOM_WELL blue;
   // Was: new FurnisherStat.FurnisherStatI(this) - a bare integer with no
   // capacity estimate at all, which is exactly why Well's construction
   // preview and post-construction panel never showed one. Swapped for a
   // NEED-based estimating stat, matching how every other service room
   // (Lavatory, Speaker, etc.) already displays capacity.
   final FurnisherStat services;
   private static final Constructor.Founatain fountain = new Constructor.Founatain();
   static final int codeService = 1;

   protected Constructor(final ROOM_WELL blue, RoomInitData init) throws IOException {
      super(init, 1, 1, 88, 44);
      this.blue = blue;
      this.services = new ThalServiceFurnisherStat(this, this.blue);
      Json sp = init.data().json("SPRITES");
      final RoomSpriteCombo sStencil = new RoomSpriteCombo(sp, "STONE_RING_STENCIL_COMBO");
      final RoomSprite sRoof = new RoomSprite1x1(sp, "ROOF_EDGE_1X1") {
         @Override
         public boolean render(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade, boolean isCandle) {
            FurnisherItem i = SETT.ROOMS().fData.item.get(it.tile());
            if ((i.width() & 1) == 0) {
               it.setOff(0, -32);
            }

            return super.render(r, s, data, it, degrade, isCandle);
         }

         @Override
         protected boolean joins(int tx, int ty, int rx, int ry, DIR d, FurnisherItem item) {
            return rx - d.x() >= item.width() / 2 ? d.x() < 0 : d.x() > 0;
         }
      };
      final RoomSprite sRoofMid = new RoomSprite1x1(sp, "ROOF_MID_1X1") {
         @Override
         public boolean render(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade, boolean isCandle) {
            FurnisherItem i = SETT.ROOMS().fData.item.get(it.tile());
            if ((i.width() & 1) == 0) {
               it.setOff(0, -32);
            }

            return super.render(r, s, data, it, degrade, isCandle);
         }

         @Override
         protected boolean joins(int tx, int ty, int rx, int ry, DIR d, FurnisherItem item) {
            return d.x() > 0;
         }
      };
      final RoomSprite sFountain = new RoomSprite1x1(sp, "FOUNTAIN_1X1") {
         @Override
         public boolean render(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade, boolean isCandle) {
            Constructor.fountain.render(r, s, it.x() + 32, it.y() + 32);
            return super.render(r, s, data, it, degrade, isCandle);
         }
      };
      RoomSprite sWellR = new RoomSpriteCombo(sp, "STONE_RING_COMBO") {
         @Override
         public boolean render(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade, boolean isCandle) {
            if (blue.is(it.tile())) {
               sStencil.render(r, s, data, it, degrade, false);
               SETT.TERRAIN().WATER.renderOverlayed(it);
            }

            it.countWater();
            it.countWater();
            return false;
         }

         @Override
         public void renderAbove(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade) {
            super.render(r, s, data, it, degrade, false);
            int up = blue.is(it.tile()) && blue.getter.get(it.tile()).upgrade() > 0 ? 1 : 0;
            RoomSprite roo = this.eSprite(SETT.ROOMS().fData.item.get(it.tile()), data, up);
            if (roo != null) {
               roo.render(r, s, this.getData2(it), it, degrade, false);
            }
         }

         @Override
         public byte getData2(int tx, int ty, int rx, int ry, FurnisherItem item, int itemRan) {
            RoomSprite roo = this.eSprite(item, this.getData(tx, ty, rx, ry, item, itemRan), 0);
            return roo != null ? roo.getData(tx, ty, rx, ry, item, itemRan) : 0;
         }

         private RoomSprite eSprite(FurnisherItem item, int data, int up) {
            if (item.width() == 4) {
               if ((data & DIR.S.mask()) == 0) {
                  return sRoof;
               }
            } else if (item.width() == 5) {
               if (up > 0) {
                  if ((data & 15) == 15) {
                     return sFountain;
                  }
               } else if ((data & DIR.S.mask()) != 0 && (data & DIR.N.mask()) != 0) {
                  if ((data & 15) == 15) {
                     return sRoofMid;
                  }

                  return sRoof;
               }
            }

            return null;
         }
      };
      RoomSprite sService = new RoomSprite1x1(sp, "BUCKET_1X1") {
         @Override
         protected boolean joins(int tx, int ty, int rx, int ry, DIR d, FurnisherItem item) {
            rx -= d.x() * 2;
            ry -= d.y() * 2;
            return item.get(rx, ry) == null;
         }

         @Override
         public boolean render(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade, boolean isCandle) {
            return blue.is(it.tile()) && blue.bed.isUsed(it.tile()) ? super.render(r, s, data, it, degrade, isCandle) : false;
         }
      };
      FurnisherItemTile ww = new FurnisherItemTile(this, sWellR, AVAILABILITY.SOLID, false);
      FurnisherItemTile ss = new FurnisherItemTile(this, false, sService, AVAILABILITY.ROOM, false).setData(1);
      FurnisherItemTile __ = new FurnisherItemTile(this, false, null, AVAILABILITY.ROOM, false);
      new FurnisherItem(new FurnisherItemTile[][]{{__, ss, __}, {ss, ww, ss}, {__, ss, __}}, 3.0, 1.0);
      new FurnisherItem(new FurnisherItemTile[][]{{__, ss, ss, __}, {ss, ww, ww, ss}, {ss, ww, ww, ss}, {__, ss, ss, __}}, 5.0, 2.0);
      new FurnisherItem(
         new FurnisherItemTile[][]{{__, ss, ss, ss, __}, {ss, ww, ww, ww, ss}, {ss, ww, ww, ww, ss}, {ss, ww, ww, ww, ss}, {__, ss, ss, ss, __}}, 6.0, 3.0
      );
      this.flush(1, 1);
   }

   @Override
   public boolean usesArea() {
      return false;
   }

   @Override
   public boolean mustBeIndoors() {
      return false;
   }

   @Override
   public Room create(TmpArea area, RoomInit init) {
      return new WellInstance(this.blue, area, init);
   }

   @Override
   public RoomBlueprintImp blue() {
      return this.blue;
   }

   private static class Founatain {
      private final int AM = 64;
      private byte[] xs = Alloc.bb(64);
      private byte[] ys = Alloc.bb(64);
      private double[] rans = new double[64];
      private COLOR[] cols = new COLOR[64];

      Founatain() {
         for (int i = 0; i < 64; i++) {
            double rad = RND.rFloat() * Math.PI * 0.5;
            double dx = Math.cos(rad);
            double dy = Math.sin(rad);
            this.xs[i] = (byte)(dx * (16.0F + RND.rFloat() * 64.0F));
            this.ys[i] = (byte)(dy * (16.0F + RND.rFloat() * 64.0F));
            this.rans[i] = RND.rInt(128) + RND.rFloat();
         }

         this.cols = COLOR.interpolate(new ColorImp(20, 60, 127), COLOR.WHITE100, 64);
      }

      void render(SPRITE_RENDERER r, ShadowBatch s, int cx, int cy) {
         double time = TIME.currentSecond() * 1.5;
         this.render(r, s, cx, cy, time, 1, 1);
         time += 0.3;
         this.render(r, s, cx - 4, cy, time, -1, 1);
         time += 0.3;
         this.render(r, s, cx, cy - 4, time, 1, -1);
         time += 0.3;
         this.render(r, s, cx - 4, cy - 4, time, -1, -1);
      }

      void render(SPRITE_RENDERER r, ShadowBatch s, int cx, int cy, double time, int dx, int dy) {
         int a = 64;
         if (TIME.light().nightIs()) {
            a = (int)(a * (1.0 - TIME.light().partOf() * 10.0));
         }

         for (int i = 0; i < a; i++) {
            double d = this.rans[i] + time;
            int k = (int)d;
            d -= k;
            int x = (int)(this.xs[i] * d);
            int y = (int)(this.ys[i] * d);
            this.cols[k & 63].bind();
            CORE.renderer().renderParticle(cx + x * dx, cy + y * dy);
         }
      }
   }
}
