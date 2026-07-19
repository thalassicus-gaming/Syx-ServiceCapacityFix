// ROOM_BENCH.java
// Document Version 1.0.4
// Creation date: 2026/07/13
// Creator: Thalassicus

package settlement.room.infra.bench;

import game.time.TIME;
import init.sprite.game.SheetType;
import java.io.IOException;
import settlement.main.SETT;
import settlement.misc.util.FSERVICE;
import settlement.path.AVAILABILITY;
import settlement.path.finders.SFinderFindable;
import settlement.path.finders.SFinderRoomService;
import settlement.room.main.ROOMA;
import settlement.room.main.ROOMS;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomSingleton;
import settlement.room.main.TmpArea;
import settlement.room.main.category.RoomCategorySub;
import settlement.room.main.furnisher.Furnisher;
import settlement.room.main.furnisher.FurnisherItem;
import settlement.room.main.furnisher.FurnisherItemTile;
import settlement.room.main.util.RoomInit;
import settlement.room.main.util.RoomInitData;
import settlement.room.service.module.RoomFinderHaser;
import settlement.room.sprite.RoomSprite;
import settlement.room.sprite.RoomSprite1x1;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.DIR;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.Json;
import snake2d.util.misc.CLAMP;
import snake2d.util.sets.LISTE;
import util.gui.misc.GBox;
import util.gui.misc.GText;
import util.info.GFORMAT;
import util.rendering.RenderData;
import util.rendering.ShadowBatch;
import util.text.Dic;
import view.sett.ui.room.UIRoomModule;

//
// ============================================================================
// BENCH CAPACITY ESTIMATE
// ============================================================================
// Benches sit in the Decorations category and have no NEED, no rate, and no
// competing-total - subjects seek one out through AIModule_Idle's bench gate,
// a per-hour, per-subject roughly-50% coin flip checked only when a subject
// has no higher-priority task at all, not through the weighted NEED lottery
// (S_Plans.getPlan()) every other service in this mod is built around. There
// is no equivalent of "how much of a subject's day this competes for" to
// compute for a bench, since idle-time availability itself isn't something
// derivable from source.
//
// From the player's perspective, though, a bench is functionally the same
// question as a hearth or well: "how many subjects can this serve, and how
// many should I build?" This file deliberately does NOT call
// thalassicus.ThalCapacityCalculator or anything else from that system - the
// two are unrelated calculations that only coincidentally produce a number
// in the same units, and coupling this file to that class would buy nothing
// but a dependency on internals a bench has no real relationship to. The
// "present (all)" display convention is reproduced here as a few lines of
// plain text formatting, which is simple enough that a shared helper isn't
// worth the coupling either.
//
// AVERAGE_VISIT_SECONDS is derived directly from AIModule_Idle's bench plan:
// 17 passes (d.planByte1 initialized to 16, decremented while >= 0) of
// AI.SUBS().STAND.activateTime(a, d, 1 + RND.rInt(10)) each - mean 5.5
// seconds per pass, confirmed via RND.rInt(int) -> java.util.Random.nextInt
// (uniform over [0, max), same confirmation used throughout this mod) -
// giving 17 * 5.5 = 93.5 seconds. This is a derivation from the loop
// structure, not a live measurement; if AIModule_Idle's bench plan is ever
// reworked, this constant would need revisiting.
//
// Because every tile in every one of Bench's 8 size variants is the same
// single seat tile (confirmed: MConstructor places nothing but "tt" in a
// 1xN row, no walls or filler tiles), seat count is simply width * height
// of the placed or previewed shape - no per-tile-type counting is needed
// the way ArenaConstructor's spectator count required.
//
// Two independent display paths read this, and both are exercised here:
// - Post-construction: ROOM_BENCH is a RoomSingleton (one citywide instance
//   spanning every physical bench cluster), so there is no per-cluster
//   detail panel the way RoomBlueprintIns-based rooms have one; the existing
//   hover() override (originally showing only Upgrade%) is the only surface
//   this reaches, using RoomSingleton's own inherited area(tx, ty).
// - Construction preview: NOT done via a FurnisherStat, deliberately.
//   PlacerItemSingle.placeInfo() does iterate blueprint.stats(), but each
//   value it reads comes from FurnisherItem.stat(FurnisherStat), which
//   returns a fixed value from a JSON "STATS" array parsed at flush() time -
//   it never calls FurnisherStat's own get(AREA, double[]) override at all.
//   _BENCH.txt's only ITEMS entry declares an empty STATS array, and this
//   mod does not modify Jake's data files, so a FurnisherStat cannot supply
//   a live-computed value here without either editing that file or hitting
//   an array-size mismatch. Instead, this overrides Furnisher.placeInfo(GBox,
//   FurnisherItem, int, int) - a no-op hook PlacerItemSingle.placeInfo()
//   already calls unconditionally at the end of its own method, passing the
//   exact FurnisherItem being previewed, whose width()/height() give the
//   seat count directly with no JSON involvement at all.
// ============================================================================
//

public final class ROOM_BENCH extends RoomBlueprintImp implements RoomFinderHaser {
   // Not ¤-prefixed, deliberately: these are our own strings, not part of
   // Jake's own text, and ¤ appears to trigger some localization/caching
   // mechanism we haven't fully traced (confirmed to interfere with edits to
   // an existing ¤-prefixed field in ModuleService.java). This file has no
   // D.ts() call today, but the safer rule is to never use ¤ for anything we
   // author ourselves, regardless of whether a given file currently opts in.
   private static CharSequence CapacityLabel = "Capacity: ";
   private static CharSequence CapacityDescription = "The number of subjects this bench can serve per day. Bench visits do not compete with other services for subject attention.";

   // See the class-level comment above for the full derivation.
   private static final double AVERAGE_VISIT_SECONDS = 93.5;

   private final ROOM_BENCH.MConstructor constructor;
   private final ROOM_BENCH.Instance instance;
   public final SFinderRoomService finder = new SFinderRoomService("Bench") {
      private int x;
      private int y;
      private final FSERVICE s = new FSERVICE() {
         @Override
         public int y() {
            return y;
         }

         @Override
         public int x() {
            return x;
         }

         @Override
         public boolean findableReservedIs() {
            return SETT.ROOMS().fData.spriteData2.get(x, y) == 1;
         }

         @Override
         public boolean findableReservedCanBe() {
            return SETT.ROOMS().fData.spriteData2.get(x, y) == 0;
         }

         @Override
         public void findableReserveCancel() {
            if (this.findableReservedIs()) {
               ROOM_BENCH.this.finder.report(x, y, 1);
            }

            SETT.ROOMS().fData.spriteData2.set(x, y, 0);
         }

         @Override
         public void findableReserve() {
            if (!this.findableReservedIs()) {
               ROOM_BENCH.this.finder.report(x, y, -1);
            }

            SETT.ROOMS().fData.spriteData2.set(x, y, 1);
         }

         @Override
         public void consume() {
            this.findableReserveCancel();
         }
      };

      public FSERVICE get(int tx, int ty) {
         if (ROOM_BENCH.this.is(tx, ty)) {
            this.x = tx;
            this.y = ty;
            return this.s;
         } else {
            return null;
         }
      }
   };

   public ROOM_BENCH(RoomInitData init, RoomCategorySub cat) throws IOException {
      super(init, 0, "_BENCH", cat);
      this.constructor = new ROOM_BENCH.MConstructor(this, init);
      this.instance = new ROOM_BENCH.Instance(init.m, this);
   }

   // Converts a seat count into the "present (all)" capacity pair. Both
   // values are always identical for benches - there is no present/all
   // distinction to make when there is no NEED-based competing total at
   // all - but the pair is shown anyway, in the same visual format used
   // everywhere else, since a player has no reason to know (or need to
   // know) that a bench's estimate is computed differently under the hood.
   private static double estimatedCapacity(double seatCount) {
      return seatCount * (TIME.secondsPerDay() / AVERAGE_VISIT_SECONDS);
   }

   private static void appendCapacityLine(GBox box, double seatCount) {
      int capacity = (int)estimatedCapacity(seatCount);
      GText t = box.text();
      t.add(CapacityLabel);
      GFORMAT.i(t, capacity);
      t.add(" (always)");
      box.add(t);
      box.NL();
      box.text(CapacityDescription);
   }

   @Override
   protected void save(FilePutter f) {
   }

   @Override
   protected void load(FileGetter f) throws IOException {
   }

   @Override
   protected void clear() {
   }

   @Override
   public Room get(int tx, int ty) {
      return SETT.ROOMS().map.get(tx, ty) == this.instance ? this.instance : null;
   }

   @Override
   protected void update(double ds) {
   }

   public SFinderRoomService service(int tx, int ty) {
      return this.finder;
   }

   @Override
   public Furnisher constructor() {
      return this.constructor;
   }

   @Override
   public void appendView(LISTE<UIRoomModule> mm) {
      mm.add(new UIRoomModule() {
         @Override
         public void hover(GBox box, Room room, int rx, int ry) {
            box.NL();
            ROOM_BENCH.appendCapacityLine(box, room.area(rx, ry));
            box.NL();
            if (ROOM_BENCH.this.upgrades().max() > 0) {
               box.NL();
               box.text(Dic.¤¤Upgrade);
               box.tab(6);
               box.add(GFORMAT.iofkInv(box.text(), room.upgrade(rx, ry), ROOM_BENCH.this.upgrades().max()));
               box.NL();
            }
         }
      });
   }

   public DIR benchDir(int tx, int ty, DIR d) {
      FurnisherItem it = SETT.ROOMS().fData.item.get(tx, ty);
      return it == null ? d : DIR.ORTHO.get(it.rotation);
   }

   @Override
   public SFinderFindable finder() {
      return this.finder;
   }

   @Override
   public int radius() {
      return 64;
   }

   @Override
   public boolean registersEnvironment() {
      return true;
   }

   static final class Instance extends RoomSingleton {
      private static final long serialVersionUID = 1L;

      Instance(ROOMS m, RoomBlueprint p) {
         super(m, p);
      }

      protected Object readResolve() {
         return this.blueprintI().instance;
      }

      public ROOM_BENCH blueprintI() {
         return (ROOM_BENCH)this.blueprint();
      }

      @Override
      protected void addAction(ROOMA ins) {
         for (COORDINATE c : ins.body()) {
            if (ins.is(c)) {
               SETT.ROOMS().fData.spriteData2.set(c.x(), c.y(), 0);
               this.blueprintI().finder.report(this.blueprintI().finder.get(c), 1);
            }
         }
      }

      @Override
      protected void removeAction(ROOMA ins) {
         for (COORDINATE c : ins.body()) {
            if (ins.is(c) && SETT.ROOMS().fData.spriteData2.get(c) == 0) {
               this.blueprintI().finder.report(this.blueprintI().finder.get(c), -1);
            }
         }

         super.removeAction(ins);
      }

      @Override
      public int upgrade(int tx, int ty) {
         return CLAMP.i(SETT.ROOMS().extraBit.get(this.mX(tx, ty), this.mY(tx, ty)), 0, this.blueprintI().upgrades().max());
      }

      @Override
      public void upgradeSet(int tx, int ty, int upgrade) {
         int up = CLAMP.i(upgrade, 0, this.blueprintI().upgrades().max());
         SETT.ROOMS().extraBit.set(tx, ty, up);
         ROOMA a = SETT.ROOMS().map.rooma.get(tx, ty);

         for (COORDINATE c : a.body()) {
            if (a.is(c)) {
               SETT.MAINTENANCE().setChanged(c.x(), c.y());
            }

            this.constructor().floor(up).placeFixed(c.x(), c.y());
         }
      }
   }

   private static class MConstructor extends Furnisher {
      private final ROOM_BENCH blue;

      MConstructor(ROOM_BENCH blue, RoomInitData init) throws IOException {
         super(init, 1, 0, 88, 44);
         this.blue = blue;
         Json sData = init.data().json("SPRITES");
         RoomSprite ssmall = new RoomSprite1x1(sData, "BENCH_1X1") {
            @Override
            public boolean render(SPRITE_RENDERER r, ShadowBatch s, int data, RenderData.RenderIterator it, double degrade, boolean isCandle) {
               DIR rot = this.rot(data);
               data &= -4;
               data |= rot.perpendicular().orthoID();
               super.render(r, s, data, it, degrade, isCandle);
               return false;
            }

            @Override
            protected boolean joins(int tx, int ty, int rx, int ry, DIR d, FurnisherItem item) {
               return d == DIR.ORTHO.get(item.rotation);
            }

            @Override
            public void renderPlaceholder(SPRITE_RENDERER r, int x, int y, int data, int tx, int ty, int rx, int ry, FurnisherItem item) {
               int var10006 = this.rotates ? data : -1;
               SheetType.s1x1.renderOverlay(x, y, r, item.get(rx, ry).availability, 0, var10006, true);
            }
         };
         FurnisherItemTile tt = new FurnisherItemTile(this, true, ssmall, AVAILABILITY.AVOID_LIKE_FUCK, false);
         new FurnisherItem(new FurnisherItemTile[][]{{tt}}, 1.0);
         new FurnisherItem(new FurnisherItemTile[][]{{tt, tt}}, 2.0);
         new FurnisherItem(new FurnisherItemTile[][]{{tt, tt, tt}}, 3.0);
         new FurnisherItem(new FurnisherItemTile[][]{{tt, tt, tt, tt}}, 4.0);
         new FurnisherItem(new FurnisherItemTile[][]{{tt, tt, tt, tt, tt}}, 5.0);
         new FurnisherItem(new FurnisherItemTile[][]{{tt, tt, tt, tt, tt, tt}}, 6.0);
         new FurnisherItem(new FurnisherItemTile[][]{{tt, tt, tt, tt, tt, tt, tt}}, 7.0);
         new FurnisherItem(new FurnisherItemTile[][]{{tt, tt, tt, tt, tt, tt, tt, tt}}, 8.0);
         this.flush(3);
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
         return this.blue.instance.place(area);
      }

      @Override
      public RoomBlueprintImp blue() {
         return this.blue;
      }

      @Override
      public void placeInfo(GBox box, FurnisherItem item, int x1, int y1) {
         box.NL(8);
         ROOM_BENCH.appendCapacityLine(box, item.width() * item.height());
      }
   }
}
