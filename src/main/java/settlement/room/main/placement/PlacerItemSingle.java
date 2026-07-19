package settlement.room.main.placement;

import init.sprite.SPRITES;
import java.util.Iterator;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.TmpArea;
import settlement.room.main.construction.ConstructionInit;
import settlement.room.main.furnisher.FurnisherItem;
import settlement.room.main.furnisher.FurnisherItemGroup;
import settlement.room.main.furnisher.FurnisherItemTile;
import settlement.room.main.furnisher.FurnisherStat;
import settlement.tilemap.terrain.TBuilding;
import snake2d.CORE;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.AREA;
import snake2d.util.datatypes.DIR;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.datatypes.Rec;
import snake2d.util.file.Alloc;
import snake2d.util.sprite.SPRITE;
import util.gui.misc.GBox;
import util.text.D;
import view.tool.PLACABLE;
import view.tool.PLACER_TYPE;
import view.tool.PlacableFixed;
import view.tool.PlacableMessages;
import view.tool.PlacableMulti;

class PlacerItemSingle extends PlacableFixed {
   protected final RoomPlacer embryo;
   private RoomBlueprintImp blueprint;
   protected FurnisherItemGroup group;
   protected final UtilStats res;
   protected final Instance area;
   private int upgrade;
   private int[] sizes;
   private static CharSequence ¤¤undo = "¤Remove Item";
   private final PlacableMulti undo = new PlacableMulti(¤¤undo) {
      @Override
      public void place(int tx, int ty, AREA a, PLACER_TYPE t) {
         Room r = SETT.ROOMS().map.get(tx, ty);
         if (r != null && r.constructor() == PlacerItemSingle.this.blueprint.constructor()) {
            r.remove(tx, ty, true, this, false).clear();
         }
      }

      @Override
      public CharSequence isPlacable(int tx, int ty, AREA a, PLACER_TYPE t) {
         Room r = SETT.ROOMS().map.get(tx, ty);
         return r != null && r.constructor() == PlacerItemSingle.this.blueprint.constructor() ? null : E;
      }

      @Override
      public boolean expandsTo(int fromX, int fromY, int toX, int toY) {
         Room r = SETT.ROOMS().map.get(fromX, fromY);
         return r != null && r.constructor() == PlacerItemSingle.this.blueprint.constructor() && r.isSame(fromX, fromY, toX, toY);
      }
   };
   private final PlacerItemSingle.Area itemArea = new PlacerItemSingle.Area();
   AREA itemAreaCurrent = null;

   static {
      D.ts(PlacerItemSingle.class);
   }

   public PlacerItemSingle(RoomPlacer embryo) {
      this.embryo = embryo;
      this.res = embryo.resources;
      this.area = embryo.instance;
   }

   public void set(RoomBlueprintImp b, int group, int upgrade) {
      if (SETT.ROOMS() != null) {
         if (this.sizes == null) {
            this.sizes = Alloc.ii(SETT.ROOMS().AMOUNT_OF_BLUEPRINTS);
         }

         if (this.blueprint != null) {
            this.sizes[this.blueprint.index()] = this.size();
         }
      }

      this.blueprint = b;
      this.group = b.constructor().pgroups().getC(group);
      this.upgrade = upgrade;
      if (this.sizes != null) {
         this.sizeSet(this.sizes[b.index()]);
      }
   }

   @Override
   public CharSequence name() {
      return this.group.name();
   }

   @Override
   public void place(int tx, int ty, int rx, int ry) {
      FurnisherItem it = this.group.item(this.size(), this.rot());
      if (rx == 0 && ry == 0) {
         TBuilding s = this.blueprint.constructor().mustBeIndoors() ? this.embryo.structure.get() : null;
         if (s != null && this.embryo.autoWalls.is()) {
            this.embryo.instance.clear(this.blueprint);

            for (int y = 0; y < it.height(); y++) {
               for (int x = 0; x < it.width(); x++) {
                  if (it.get(x, y) != null) {
                     this.embryo.instance.set(tx + x, ty + y);
                  }
               }
            }

            this.embryo.door.build(s);
            this.embryo.instance.clear(this.blueprint);

            for (int y = 0; y < it.height(); y++) {
               for (int x = 0; x < it.width(); x++) {
                  if (it.get(x, y) != null && it.get(x, y).mustBeReachable) {
                     for (int di = 0; di < DIR.ORTHO.size(); di++) {
                        int dx = tx + x + DIR.ORTHO.get(di).x();
                        int dy = ty + y + DIR.ORTHO.get(di).y();
                        if (UtilWallPlacability.wallisReal.is(dx, dy)) {
                           UtilWallPlacability.openingBuild(dx, dy, s);
                        }
                     }
                  }
               }
            }
         }

         FurnisherItem secret = this.blueprint.constructor().secretReplacementItem(this.rot(), it);
         if (secret != null) {
            for (int y = 0; y < it.height(); y += secret.height()) {
               for (int x = 0; x < it.width(); x += secret.width()) {
                  this.place(secret, tx + x, ty + y, s);
               }
            }
         } else {
            this.place(it, tx, ty, s);
         }
      }
   }

   private void place(FurnisherItem it, int tx, int ty, TBuilding s) {
      TmpArea tmp = SETT.ROOMS().tmpArea(this);

      for (int y = 0; y < it.height(); y++) {
         for (int x = 0; x < it.width(); x++) {
            if (it.get(x, y) != null) {
               tmp.set(tx + x, ty + y);
            }
         }
      }

      SETT.ROOMS().fData.itemSet(tx, ty, it, tmp.room());
      ConstructionInit init = new ConstructionInit(0, this.blueprint.constructor(), s, 0, this.blueprint.constructor().getConstructionState());
      SETT.ROOMS().construction.createClean(tmp, init);
   }

   @Override
   public void renderPlaceHolder(SPRITE_RENDERER r, int mask, int x, int y, int tx, int ty, int rx, int ry, boolean isPlacable, boolean areaIsPlacable) {
      FurnisherItem it = this.group.item(this.size(), this.rot());
      FurnisherItemTile t = it.get(rx, ry);
      if (t != null) {
         if (t.mustBeReachable) {
            SPRITES.cons().BIG.filled.render(r, 0, x, y);
            COLOR c = CORE.renderer().colorGet();
            COLOR.unbind();
            int ri = -1;
            if (t.sprite() != null) {
               int d = t.sprite().getData(tx, ty, rx, ry, it, 0);
               ri = t.sprite.rotation(d, it) - 1;
            }

            if (ri < 0) {
               SPRITES.cons().ICO.arrows_inward.render(r, x, y);
            } else {
               SPRITES.cons().ICO.arrows_inwards.get(ri).render(r, x, y);
            }

            c.bind();
         } else if (t.sprite() != null) {
            int d = t.sprite().getData(tx, ty, rx, ry, it, 0);
            t.sprite().renderPlaceholder(r, x, y, d, tx, ty, rx, ry, it);
         } else {
            SPRITES.cons().BIG.dashed.render(r, mask, x, y);
         }
      }

      this.group.blueprint.renderExtra(r, x, y, tx, ty, rx, ry, it);
      if (this.blueprint.constructor().mustBeIndoors() && SETT.ROOMS().placement.placer.autoWalls.is()) {
         SETT.ROOMS().placement.placer.door.renderWall(r, it, tx, ty, rx, ry, x, y);
      }
   }

   @Override
   public int width() {
      return this.group.item(this.size(), this.rot()).width();
   }

   @Override
   public int height() {
      return this.group.item(this.size(), this.rot()).height();
   }

   @Override
   public CharSequence placableWhole(int tx1, int ty1) {
      this.itemAreaCurrent = null;
      FurnisherItem it = this.group.item(this.size(), this.rot());
      CharSequence s = it.placable(tx1, ty1);
      if (s != null) {
         return s;
      }

      this.itemAreaCurrent = this.itemArea.set(this.group.item(this.size(), this.rot()), tx1, ty1);
      return null;
   }

   @Override
   public CharSequence placable(int tx, int ty, int rx, int ry) {
      FurnisherItem it = this.group.item(this.size(), this.rot());
      if (it.get(rx, ry) == null) {
         return null;
      } else {
         CharSequence s = PLACEMENT.placable(tx, ty, this.blueprint, true);
         if (s != null) {
            return s;
         } else {
            s = this.group.blueprint.placable(tx, ty, it, it == null ? null : it.get(rx, ry));
            if (s != null) {
               return s;
            } else if (it.get(rx, ry).mustBeReachable && SETT.PLACA().willBeBlocked(tx, ty, rx, ry, it)) {
               return PlacableMessages.¤¤BLOCKED_WILL;
            } else {
               return it.get(rx, ry).isBlocker() && SETT.PLACA().willBlock.is(tx, ty)
                  ? PlacableMessages.¤¤BLOCK_WILL
                  : it.get(rx, ry).isPlacable(tx, ty, this.embryo.instance, it, rx, ry);
            }
         }
      }
   }

   @Override
   public void placeInfo(GBox box, int x1, int y1) {
      box.add(box.text().add(this.width()).add('x').add(this.height()));
      box.NL();

      for (int i = 0; i < this.group.blueprint.resources(); i++) {
         if (this.group.item(this.size(), this.rot()).cost2(i, this.upgrade) > 0.0) {
            box.setResource(this.group.blueprint.resource(i), Math.ceil(this.group.item(this.size(), this.rot()).cost2(i, this.upgrade)));
            box.space();
         }
      }

      if (this.blueprint.constructor().mustBeIndoors() && SETT.ROOMS().placement.placer.autoWalls.is() && this.embryo.structure.get() != null) {
         FurnisherItem it = this.group.item(this.size(), this.rot());
         int roofs = 0;
         int walls = 0;

         for (int y = -1; y <= it.height(); y++) {
            label75:
            for (int x = -1; x <= it.width(); x++) {
               if (it.get(x, y) != null) {
                  roofs++;
               } else if (UtilWallPlacability.wallCanBe.is(x1 + x, y1 + y)) {
                  boolean roof = false;

                  for (DIR d : DIR.ORTHO) {
                     roof |= it.get(x, y, d) != null && it.get(x, y, d).mustBeReachable;
                  }

                  Iterator var19 = DIR.ALL.iterator();

                  DIR d;
                  do {
                     if (!var19.hasNext()) {
                        continue label75;
                     }

                     d = (DIR)var19.next();
                  } while (it.get(x, y, d) == null);

                  if (roof) {
                     roofs++;
                  } else {
                     walls++;
                  }
               }
            }
         }

         int am = roofs * SETT.JOBS().build_structure.get(this.embryo.structure.get().structure.index()).ceiling.resAmount();
         am += walls * SETT.JOBS().build_structure.get(this.embryo.structure.get().structure.index()).wall.resAmount();
         box.setResource(this.embryo.structure.get().structure.resource, am);
         box.space();
      }

      for (FurnisherStat s : this.group.blueprint.stats()) {
         double am = this.group.item(this.size(), this.rot()).stat(s);
         if (am != 0.0) {
            box.NL();
            // MOD START box.add(box.text().lablify().add(s.name()).add(": "));

            // MOD END
            box.add(s.format(box.text(), am));
         }
      }

      box.NL(8);
      this.group.blueprint.placeInfo(box, this.group.item(this.size(), this.rot()), x1, y1);
   }

   @Override
   public void hoverDesc(GBox box) {
      box.title(this.group.name);
      box.text(this.group.desc);
      box.NL();

      for (int i = 0; i < this.group.blueprint.resources(); i++) {
         if (this.group.item(0, 0).cost2(i, this.upgrade) > 0.0) {
            box.setResource(this.group.blueprint.resource(i), Math.ceil(this.group.item(0, 0).cost2(i, this.upgrade)));
            box.space();
         }
      }

      for (FurnisherStat s : this.group.blueprint.stats()) {
         if (this.group.item(0, 0).stat(s) > 0.0) {
            box.NL();
            box.add(box.text().lablify().add(s.name()));
            box.add(s.format(box.text(), this.group.item(0, 0).stat(s)));
         }
      }
   }

   @Override
   public PLACABLE getUndo() {
      return this.undo;
   }

   @Override
   public int rotations() {
      return this.group.rotations();
   }

   @Override
   public int sizes() {
      return this.group.size();
   }

   @Override
   public SPRITE getIcon() {
      return null;
   }

   final class Area implements AREA {
      private final Rec area = new Rec();
      private int size = 0;
      private FurnisherItem item;

      AREA set(FurnisherItem item, int x1, int y1) {
         this.item = item;
         this.area.setDim(item.width(), item.height());
         this.area.moveX1Y1(x1, y1);
         this.size = this.area.width() * this.area.height();
         return this;
      }

      @Override
      public RECTANGLE body() {
         return this.area;
      }

      @Override
      public boolean is(int tile) {
         return false;
      }

      @Override
      public boolean is(int tx, int ty) {
         return this.area.holdsPoint(tx, ty) && this.item.get(tx - this.body().x1(), ty - this.body().y1()) != null;
      }

      @Override
      public int area() {
         return this.size;
      }
   }
}
