// ThalDropDownEntry.java
// Document Version 1.0.0
// Creation date: 2026/07/22
// Creator: Thalassicus

package thalassicus.ui;

/*
 * Allows ThalGDropDown to resize the selected entry's available text width.
 *
 * CLICKABLE.body() exposes only RECTANGLEE, whose public API supports
 * translation but not resizing. Implementations can resize their own
 * concrete body, so this callback provides the missing capability without
 * exposing implementation details.
 */
public interface ThalDropDownEntry {
    void availableWidthSet(int width);
}
