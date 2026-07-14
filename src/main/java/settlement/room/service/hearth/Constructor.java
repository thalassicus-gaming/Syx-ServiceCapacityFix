// Constructor.java
// Document Version 1.0.2
// Creation date: 2026/07/13
// Creator: Thalassicus

package settlement.room.service.hearth;

import init.resources.RESOURCES;
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
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.DIR;
import snake2d.util.file.Json;
import thalassicus.ThalServiceFurnisherStat;
import util.rendering.RenderData;
import util.rendering.ShadowBatch;

final class Constructor extends Furnisher {
   private final ROOM_HEARTH blue;
   // Was: new FurnisherStat.FurnisherStatI(this) - a bare integer with no
   // capacity estimate at all, which is exactly why Hearth's construction
   // preview and post-construction panel never showed one. Swapped for a
   // NEED-based estimating stat, matching how every other service room
   // (Lavatory, Speaker, etc.) already displays capacity.
   final FurnisherStat services;
   static final int codeService = 1;
   static final int codeFire = 2;

   protected Constructor(final ROOM_HEARTH blue, RoomInitData init) throws IOException {
      super(init, 1, 1, 88, 44);
      this.blue = blue;
      this.services = new ThalServiceFurnisherStat(this, this.blue);
      Json sp = init.data().json("SPRITES");
      RoomSprite sBench = new RoomSprite1x1(sp, "BENCH_1X1") {
         @Override
         protected boolean joins(int tx, int ty, int rx, int ry, DIR d, FurnisherItem item) {
            DIR d2 = DIR.ORTHO.getC(item.rotation + 1);
            return d2.x() * d.x() == 0 && d2.y() * d.y() == 0 ? false : item.get(rx + -d.x() * 4, ry - d.y() * 4) == null;
         }
      };
      RoomSprite sHearth = new RoomSpriteCombo(sp, "HEARTH_COMBO");
      RoomSprite sFire = new RoomSpriteCombo(sHearth) {
         @Override
         public void renderAbove(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade) {
         }

         @Override
         public boolean render(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade, boolean isCandle) {
            super.render(r, s, data, it, degrade, false);
            if (blue.is(it.tile())) {
               HearthInstance ins = blue.getter.get(it.tile());
               RESOURCES.WOOD().renderLaying(r, it.x(), it.y(), it.ran(), 5.0);
               SETT.LIGHTS().hide(it.tx(), it.ty(), ins.used == 0);
            }

            return false;
         }
      };
      FurnisherItemTile ff = new FurnisherItemTile(this, sFire, AVAILABILITY.SOLID, false).setData(2);
      FurnisherItemTile fe = new FurnisherItemTile(this, sHearth, AVAILABILITY.SOLID, false);
      FurnisherItemTile bb = new FurnisherItemTile(this, false, sBench, AVAILABILITY.PENALTY4, false).setData(1);
      FurnisherItemTile __ = new FurnisherItemTile(this, null, AVAILABILITY.ROOM, false);
      new FurnisherItem(new FurnisherItemTile[][]{{__, bb, __, bb, __}, {__, bb, ff, bb, __}, {__, bb, __, bb, __}}, 6.0, 6.0);
      new FurnisherItem(
         new FurnisherItemTile[][]{{__, bb, __, bb, __}, {__, bb, __, bb, __}, {__, bb, ff, bb, __}, {__, bb, __, bb, __}, {__, bb, __, bb, __}}, 10.0, 10.0
      );
      new FurnisherItem(
         new FurnisherItemTile[][]{
            {__, bb, __, bb, __},
            {__, bb, __, bb, __},
            {__, bb, fe, bb, __},
            {__, bb, ff, bb, __},
            {__, bb, fe, bb, __},
            {__, bb, __, bb, __},
            {__, bb, __, bb, __}
         },
         14.0,
         14.0
      );
      new FurnisherItem(
         new FurnisherItemTile[][]{
            {__, bb, bb, __, bb, bb, __},
            {__, bb, bb, fe, bb, bb, __},
            {__, bb, bb, ff, bb, bb, __},
            {__, bb, bb, fe, bb, bb, __},
            {__, bb, bb, ff, bb, bb, __},
            {__, bb, bb, fe, bb, bb, __},
            {__, bb, bb, __, bb, bb, __}
         },
         28.0,
         28.0
      );
      this.flush(1, 3);
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
      return new HearthInstance(this.blue, area, init);
   }

   @Override
   public RoomBlueprintImp blue() {
      return this.blue;
   }
}
