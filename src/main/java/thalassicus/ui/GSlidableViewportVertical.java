// GSlidableViewportVertical.java
// Document Version 1.0.2
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.ui;

import snake2d.MButt;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.SPRITE;
import util.data.INT;
import util.gui.slider.GSliderVer;
import view.main.VIEW;

// A general-purpose vertical panning viewport: a fixed-size window showing
// part of a taller content area, with a GSliderVer, click-drag, and mouse
// wheel all able to pan it. Modeled on view.ui.tech.Tree - the only place
// in this codebase found to build a real translate-based pannable viewport,
// as opposed to the row-recycling approach GTableBuilder/Scrollable use, or
// GBox's own render-time clip-and-offset approach. See the chat discussion
// this was designed in for why those two alternatives were rejected for
// this use case.
//
// Deliberately knows nothing about what it contains - no Cell, no
// ThalCapacityProfile, no blueprint keys, nothing from settlement.* or the
// rest of thalassicus.capacity. It only knows how to stack arbitrary
// RENDEROBJs vertically and let the player pan over the result. This
// mirrors the same "low-level data structure, no interpretation" standard
// set for Cell.
public class GSlidableViewportVertical extends GuiSection {

    // GSliderVer's own "size" constructor parameter only sizes its middle
    // draggable track - the two end-arrow buttons (b1/b2 in GSliderVer's
    // own source) each add further height on top of that, stacked above
    // and below via addDownC. This is budget reserved for that chrome, so
    // the slider's own total rendered height fits within our chosen
    // height rather than overshooting it - NOT a content margin, and
    // nothing to do with any padding before the first row of content
    // (that would be Tree/NodeCreator's own concern, not this class's).
    // "32" itself is still an unverified constant, borrowed from Tree's
    // own GSliderVer(target, height - 32) call and never independently
    // measured against the real end-button sprite heights - expect to
    // need visual tuning, same as the layout guesses in ThalCapacityUI.
    private static final int SLIDER_END_BUTTON_HEIGHT_ALLOWANCE = 32;

    // How many pixels one mouse wheel notch scrolls. Tree uses
    // Node.HEIGHT() for this - a domain-specific "one notch = one node"
    // constant that only makes sense because Tree knows what it contains.
    // This class deliberately doesn't know that about its own content, so
    // the caller supplies this instead of it being guessed here.
    private final int wheelScrollStep;

    // Captured once here rather than re-read from contentSection.body()
    // later - GuiSection.clear() (confirmed from its own source) resets
    // width AND height to 0, so contentClear() below needs its own
    // memory of what the fixed width should be restored to.
    private final int contentWidth;

    private final GuiSection contentSection = new GuiSection();
    private final ScrollOffset scrollOffset = new ScrollOffset();
    private final GSliderVer verticalSlider;

    // How much taller contentSection currently is than this viewport's own
    // fixed height. Recalculated after every contentAdd() call, not just
    // once at construction like Tree does - this class has to tolerate
    // content arriving incrementally, unlike Tree's one-shot build.
    private int overflowHeight = 0;

    private boolean isDragging = false;
    private int dragStartMouseY;
    private int dragStartOffset;

    // wheelScrollStep has no default - guessing a "reasonable" pixel
    // amount here would be exactly the kind of premature, content-blind
    // guess this class is trying to avoid. The caller knows its own row
    // height; this class does not.
    public GSlidableViewportVertical(int width, int height, int wheelScrollStep) {
        this.wheelScrollStep = wheelScrollStep;
        this.body().setDim(width, height);

        // Reserving exact slider width via GSliderVer.WIDTH() rather than
        // Tree's own hardcoded "width -= 24" guess - GSliderVer.WIDTH()
        // gives the real number instead of an approximation.
        this.contentWidth = width - GSliderVer.WIDTH();
        this.contentSection.body().setDim(this.contentWidth, 0);
        this.add(this.contentSection);

        this.verticalSlider = new GSliderVer(this.scrollOffset, height - SLIDER_END_BUTTON_HEIGHT_ALLOWANCE);
        // Using body().x2() rather than Tree's own body().width() for the
        // slider's right-edge position - Tree's version only produces a
        // correct on-screen position if its own body happens to sit at
        // x1() == 0, which isn't something this class can assume about
        // itself. x2() is correct regardless of where this viewport is
        // ultimately positioned.
        this.verticalSlider.body().moveX2(this.body().x2());
        this.verticalSlider.body().moveY1(this.body().y1());
        this.add(this.verticalSlider);
    }

    // Stacks content below whatever's already been added, exactly the way
    // Tree populates its own content section - the caller is responsible
    // for building rows/cells of the right width themselves; this class
    // does not resize or otherwise reach into whatever it's handed.
    public void contentAdd(RENDEROBJ content) {
        this.contentSection.addDown(0, content);
        this.recalculateOverflow();
    }

    // Mirrors GuiSection's own dual RENDEROBJ/SPRITE overloads for
    // addDown() - GText (a Text, used for section headers) is a SPRITE,
    // not a RENDEROBJ (confirmed elsewhere in this project: converting a
    // GText into something returnable as RENDEROBJ required wrapping it
    // via .r(DIR) into a GTextR first). Without this overload, a caller
    // wanting to add plain text content would need to know that wrapping
    // trick themselves - this way it's handled the same way GuiSection
    // already handles it.
    public void contentAdd(SPRITE content) {
        this.contentSection.addDown(0, content);
        this.recalculateOverflow();
    }

    // Empties every child previously added via contentAdd() and resets
    // scroll position back to the top. GuiSection.clear() (confirmed from
    // its own source) also zeroes width/height, so the fixed width is
    // restored from contentWidth afterward - height is left at 0
    // deliberately, since an empty content section correctly has none.
    public void contentClear() {
        this.contentSection.clear();
        this.contentSection.body().setWidth(this.contentWidth);
        this.scrollToTop();
        this.recalculateOverflow();
    }

    public void scrollToTop() {
        this.scrollOffset.set(0);
    }

    private void recalculateOverflow() {
        this.overflowHeight = Math.max(0, this.contentSection.body().height() - this.body().height());
        // IntImp's min/max are public, mutable fields (confirmed from
        // INT.java) - no method call needed to update the bound.
        this.scrollOffset.max = this.overflowHeight;
        // Re-clamps the current offset in case content just shrank and it
        // no longer fits within the new, smaller overflow range. Tree
        // never needs this, since its content never changes size after
        // construction.
        this.scrollOffset.set(this.scrollOffset.get());
    }

    private void applyScrollOffset() {
        this.contentSection.body().moveY1(this.body().y1() - this.scrollOffset.get());
    }

    // GuiSection's own default moveCallback() behavior isn't something
    // this class wants running - matches Tree's own identical override,
    // which suggests (without this having been independently confirmed
    // against GuiSection's own source) that the default would otherwise
    // interfere with the manual positioning applyScrollOffset() already
    // does.
    @Override
    protected void moveCallback() {
    }

    @Override
    public boolean click() {
        boolean consumedByChild = super.click();
        // CONFIRMED (2026/07/19) against GuiSection.click()'s own real
        // source: dispatching to a hovered child and returning true means
        // only "a hovered CLICKABLE existed and I fired its click action"
        // - there's no way for a child (a button, a tech node) to signal
        // "I fully handled this, don't do anything else." That's exactly
        // why Tree's own click() deliberately starts tracking a drag even
        // when a node was also clicked - its second OR condition exists
        // specifically to layer dragging on top of a click regardless,
        // which is the real cause of the "click and drag both fire" quirk
        // noticed in-game. This class deliberately does NOT reproduce that
        // - a drag only starts when nothing inside consumed the click at
        // all, avoiding the awkwardness rather than inheriting it.
        if (!consumedByChild && this.hoveredIs()) {
            this.isDragging = true;
            this.dragStartMouseY = VIEW.mouse().y();
            this.dragStartOffset = this.scrollOffset.get();
        }
        return true;
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        this.isDragging = this.isDragging && MButt.LEFT.isDown();
        if (this.isDragging) {
            int candidateOffset = this.dragStartOffset + (this.dragStartMouseY - VIEW.mouse().y());
            this.scrollOffset.set(candidateOffset);
        } else if (this.body().holdsPoint(VIEW.mouse())) {
            double wheelSpin = MButt.clearWheelSpin();
            if (wheelSpin != 0.0) {
                int candidateOffset = this.scrollOffset.get() - (int) (wheelSpin * this.wheelScrollStep);
                this.scrollOffset.set(candidateOffset);
            }
        }

        super.render(r, ds);
    }

    // Backs scrollOffset with INT.IntImp rather than a bare INT.INTE, per
    // the earlier discussion: IntImp's own set() already clamps
    // (CLAMP.i(t, min, max)) before storing, so every caller of set() -
    // click(), render()'s drag/wheel handling, and recalculateOverflow()'s
    // re-clamp - gets correct clamping for free from ONE implementation,
    // rather than needing its own separate clamp step the way Tree's own
    // render() logic does for drag/wheel (duplicating the clamping Tree's
    // INTE-based vertical slider already does for button clicks).
    //
    // get() is deliberately NOT overridden to compute live from
    // contentSection's actual position the way Tree's own INTE does -
    // that trick exists in Tree only because Tree bypasses its own INTE
    // for drag/wheel and needed get() to stay truthful anyway. Here,
    // EVERY position change (slider buttons, drag, wheel) routes through
    // this same set() override, so the inherited IntImp.get() (a plain
    // field read) stays correct automatically - one path in, no
    // reconciliation needed.
    private final class ScrollOffset extends INT.IntImp {

        private ScrollOffset() {
            super(0, 0);
        }

        @Override
        public void set(int t) {
            super.set(t);
            GSlidableViewportVertical.this.applyScrollOffset();
        }
    }
}
