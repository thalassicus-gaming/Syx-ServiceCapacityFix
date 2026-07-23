// ThalDropDownEntry.java
// Document Version 1.0.0
// Creation date: 2026/07/22
// Creator: Thalassicus

package thalassicus.ui;

// Implemented by any entry type used with ThalGDropDown - lets the
// dropdown tell an entry how much horizontal space it actually has when
// rendered as the SELECTED entry in the closed box, as opposed to its own
// natural (popup-row) width.
//
// Exists because RECTANGLEE - what CLICKABLE.body() narrows its return
// type down to - has no resize capability at all. Confirmed from
// DIR.reposition(Rec, int, int)'s own source: every RECTANGLEE mutator
// (moveX1, moveX2, moveC, centerX, etc.) is a pure TRANSLATION that
// preserves the rectangle's CURRENT width, never a resize - reposition()
// only works at all because it calls setWidth() first (only available on
// the concrete Rec type, not through RECTANGLEE) and then USES moveX2()
// purely to slide the already-resized rectangle back into position.
// ThalGDropDown, knowing an entry only as E extends CLICKABLE, can never
// reach a resizable Rec from outside - only the entry itself, with direct
// access to its own body field, can.
//
// This is also the confirmed root cause of a real bug: an earlier version
// of ThalGDropDown.render() tried to constrain a selected entry's
// available width by calling moveX1(...) then moveX2(...) in sequence,
// assuming the first call would resize and the second would just nudge
// the right edge. Since both are pure translations, the second call
// silently overwrote whatever the first one did entirely - the entry's
// left edge ended up wherever ITS OWN unconstrained width placed it
// relative to the final moveX2() call, not where moveX1() had put it.
// Symptom: every entry's text rendered flush against the box's right
// edge, overflowing off the LEFT side for any name wider than the box -
// confirmed as reproducible and consistent regardless of entry length,
// which is exactly what "last translation wins" predicts.
public interface ThalDropDownEntry {
    void availableWidthSet(int width);
}
