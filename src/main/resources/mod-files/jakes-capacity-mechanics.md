# Jake's Capacity/Services Display - Reference

Document Version 1.0.0

This document records how the unmodified game computes and displays the
"Capacity" (also labeled "Services" in one location) figure for service
rooms (Physician, Lavatory, Hearth, Well, etc.), confirmed by direct
decompiled-source reading and cross-checked against live screenshots. This
is Jake's own mechanism, independent of any of this project's mods - useful
background for anyone trying to understand, replace, or improve upon it.

## Three Display Locations, Three Labels, Two Underlying Numbers

The unmodified game shows a Capacity-type figure in three separate places,
using three different labels, computed from only two distinct sources of
truth:

| Location | Label shown | Data source | Scope |
|---|---|---|---|
| Hovering an individual built room's Service bar | "Capacity" | `RoomServiceInstance.total()` (this room's own slot count) | Per-building |
| Hovering the small Service bar above the room-type list (easy to miss - sits above the individual room list, not inside any individual room's row) | "Capacity" | `RoomService.total()` (city-wide sum across every built instance of this room type) | City-wide |
| Construction/placement preview popup, before a room is built | "Services" (not "Capacity") | The proposed room's declared slot count from its furniture item stat block (no built instance exists yet) | Per-planned-building |

All three multiply their respective slot count by the **same** city-wide
rate, `RoomService.totalMultiplier()` (see formula below). None of the
three UI locations label which scope (per-building vs. city-wide) the
number in front of the player actually represents - the wording is
identical or near-identical across all three ("An rough estimate of how
many subjects that can be served" / "Total amount of people that can be
served simultaneously... derived from your subjects' properties"). This is
confirmed by direct play testing: 596 hours of play preceded ever noticing
the city-wide display exists as a separate figure from the per-building
one, because nothing on-screen marks the distinction.

## Confirmed Rounding Mismatch Between Locations

The per-building panel and the construction-preview popup round
differently, which produces a visibly different number for the identical
floor plan even while the game is paused (city-wide rate and slot count
both frozen, so the underlying value truly is identical):

- Per-building panel: `(int)(total * multiplier)` - **truncates** toward zero.
- Construction preview: `(int)Math.ceil(total * multiplier)` - **rounds up**.

A confirmed live example: the same floor plan showed `503` in the
per-building panel and `504` in the construction preview, a one-unit
difference attributable entirely to `(int)` cast vs. `Math.ceil()`, not to
any difference in underlying data.

## The `totalMultiplier()` Formula

```java
public double totalMultiplier() {
    if (need != null) {
        double ne = usage / STATS.SERVICE().needTot(need);
        if (need instanceof NEED_E) {
            return 1.0 / (ne * need.rate.get(HCLASS_RACE.clP(null, null)));
        } else {
            double tot = 0;
            for (int ni = 0; ni < NEEDS.ALLSIMPLE().size(); ni++) {
                NEED o = NEEDS.ALLSIMPLE().get(ni);
                tot += o.rate.get(HCLASS_RACE.clP(null, null));
            }
            if (STATS.SERVICE().needTot(need) == 0) ne = usage;
            return 1.0 / (ne * TIME.servicePerDay() * 0.5
                * need.rate.get(HCLASS_RACE.clP(null, null)) / tot);
        }
    }
    return 1;
}
```

Terms, in plain language:

- **`need.rate.get(HCLASS_RACE.clP(null, null))`** - this NEED's weight in
  the weighted lottery a subject's AI uses to decide which service to seek
  out next (e.g. Health Care ~0.1, Dirtiness ~0.25). This is a lottery
  weight, not a literal visit rate. The `(null, null)` overload returns the
  species-agnostic default weight rather than any race-specific override.
- **`NEEDS.ALLSIMPLE()`** - the list of every NEED competing in that same
  lottery. Summing every NEED's weight (`tot`) and dividing this NEED's own
  weight by that sum gives this NEED's *share* of total lottery activity.
- **`usage`** - a per-blueprint configurable multiplier (JSON, default
  1.0), letting a room type's overall weighting be tuned without touching
  its NEED directly.
- **`STATS.SERVICE().needTot(need)`** - a city-wide total associated with
  this NEED, used as a denominator; when it is `0` (no rooms of this type
  registering data yet), the formula falls back to `ne = usage` as a
  simple default.
- **`TIME.servicePerDay() * 0.5`** - an assumed constant number of
  service-seeking opportunities a subject generates per day (evaluates to
  `2`), not a measured or configurable rate.
- **`NEED_E`** (Hunger/Thirst/Shopping) uses a separate, simpler branch of
  the formula entirely, consistent with these NEEDs competing at a
  different AI decision layer (module-selection) rather than in the
  `S_Plans` rate-lottery that the rest of this formula models.

## The Central Gap: No Visit Duration Term

Nowhere in this formula does visit duration appear. A subject's NEED-rate
weight and the citywide lottery-share fraction determine how *often* this
NEED gets selected, but the formula has no notion of how *long* a resulting
visit actually occupies a slot. Confirmed from the mod's own visit-duration
research data: some services (e.g. Eatery) have visit durations around 14
seconds, others (e.g. certain religious buildings) around 130+ seconds -
nearly a 10x difference - yet `totalMultiplier()` treats them identically.
This is the single largest source of the formula's real-world inaccuracy,
not the scope-mixing between per-building and city-wide totals (which, on
its own, is a defensible modeling choice - the per-slot rate for a service
type is a property of that type, not of any specific room instance, so
applying a shared rate to a specific room's own slot count is not
inherently wrong).

## Why This Matters for Any Replacement

Deriving a fully corrected version of this formula from first principles
(adding real visit duration, real observed lottery-share, per-race
variation, etc.) runs into the same wall the rest of this project already
hit: doing so accurately would require reconstructing the AI's entire
decision tree, which is what the simulation itself already computes and is
not practical to duplicate. This is part of why this project's own
Capacity replacement takes a live-data, calibration-based approach instead
of trying to extend or correct `totalMultiplier()`'s formula directly.
