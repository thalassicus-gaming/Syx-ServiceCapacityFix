// ThalServiceFurnisherStat.java
// Document Version 1.0.3
// Creation date: 2026/07/13
// Creator: Thalassicus

package thalassicus.util;

import settlement.room.main.furnisher.Furnisher;
import settlement.room.main.furnisher.FurnisherStat;
import settlement.room.service.module.RoomService;
import snake2d.util.datatypes.AREA;
import thalassicus.archive.DEPRECATEDThalServiceCapacityCalculator;
import util.gui.misc.GText;
import util.info.GFORMAT;

//
// ============================================================================
// THAL SERVICE FURNISHER STAT
// ============================================================================
// A drop-in replacement for FurnisherStat.FurnisherStatServices, for any
// NEED-based service room whose Constructor.java currently uses the generic,
// non-estimating FurnisherStat.FurnisherStatI instead (confirmed: Hearth and
// Well both do, which is why their construction preview and post-
// construction panel only ever showed a bare slot count, never a capacity
// estimate, until now). Unlike Bench, which has no NEED and is deliberately
// kept fully isolated from this mod's calculation logic, Hearth and Well are
// mechanically identical to every other NEED-based room this mod already
// corrects (Lavatory, Speaker, etc.) - so coupling to
// ThalServiceCapacityCalculator here is the right call: any future formula
// correction there should apply to these rooms automatically, not require a
// second synchronized edit.
//
// This does not extend FurnisherStat.FurnisherStatServices directly, even
// though it is a near-identical replacement for it: that class's room
// reference and label/description constants are private, unreachable from
// another package, so this instead extends FurnisherStat directly with its
// own field and its own label text, matching FurnisherStatServices's
// external behavior without needing access to its internals.
// ============================================================================
//

public class ThalServiceFurnisherStat extends FurnisherStat {

  private static final CharSequence LABEL = "Capacity";
  private static final CharSequence DESCRIPTION =
      "Services compete for a subject's limited time, so adding new types of services will reduce demand on this one.";

  private final RoomService.ROOM_SERVICE_HASER room;

  public ThalServiceFurnisherStat(Furnisher furnisher, RoomService.ROOM_SERVICE_HASER room) {
    super(furnisher, LABEL, DESCRIPTION, 0.0);
    this.room = room;
  }

  @Override
  public double get(AREA area, double acc) {
    return acc;
  }

  @Override
  public GText format(GText t, double slotCount) {
    DEPRECATEDThalServiceCapacityCalculator.CapacityMultipliers multipliers =
        DEPRECATEDThalServiceCapacityCalculator.correctedCapacityMultipliers(this.room.service());
    int presentCapacity = (int)(slotCount * multipliers.presentMultiplier());
    int allCapacity = (int)(slotCount * multipliers.calibratedMultiplier());

    GFORMAT.i(t, presentCapacity);
    t.add(" presently (");
    GFORMAT.i(t, allCapacity);
    t.add(" with all services)");
    return t;
  }
}
