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

/*
 * A vertically scrollable viewport that preserves the identity of every
 * child widget.
 *
 * Unlike GTableBuilder/Scrollable, this class does not recycle row objects
 * while scrolling. Children are constructed once and the entire content
 * section is translated, allowing stateful widgets to safely retain
 * per-object callbacks.
 *
 * The implementation is derived from view.ui.tech.Tree's translate-based
 * viewport while extracting only the generic scrolling behavior.
 */
public class ThalGSlidableViewportVertical extends GuiSection {

    // GSliderVer's height excludes its end buttons, so reserve space to
    // keep the complete slider within the viewport.
    private static final int SLIDER_END_BUTTON_HEIGHT_ALLOWANCE = 32;
    public static final int VERTICAL_CONTENT_MARGIN = 10;

    // Mouse-wheel distance supplied by the caller because this class
    // has no knowledge of its content's row height.
    private final int wheelScrollStep;

    // GuiSection.clear() resets dimensions, so preserve the fixed width
    // for contentClear().
    private final int contentWidth;

    private final GuiSection contentSection = new GuiSection();
    private final ScrollOffset scrollOffset = new ScrollOffset();
    private final GSliderVer verticalSlider;

    // Cached amount by which the content exceeds the viewport height.
    private int overflowHeight = 0;

    private boolean isDragging = false;
    private int dragStartMouseY;
    private int dragStartOffset;

    public ThalGSlidableViewportVertical(int width, int height, int wheelScrollStep) {
        this.wheelScrollStep = wheelScrollStep;
        this.body().setDim(width, height);

        // Reserve the slider's actual width rather than hardcoding it.
        this.contentWidth = width - GSliderVer.WIDTH();
        this.contentSection.body().setDim(this.contentWidth, 0);
        this.add(this.contentSection);

        this.verticalSlider = new GSliderVer(this.scrollOffset, height - SLIDER_END_BUTTON_HEIGHT_ALLOWANCE);
        // Position relative to x2() so the viewport may be placed anywhere.
        this.verticalSlider.body().moveX2(this.body().x2());
        this.verticalSlider.body().moveY1(this.body().y1());
        this.add(this.verticalSlider);
    }

    public void contentAdd(RENDEROBJ content) {
        this.contentAdd(content, 0);
    }
    public void contentAdd(RENDEROBJ content, int topMargin) {
        this.contentSection.addDown(topMargin, content);
        this.recalculateOverflow();
    }

    // Convenience overload matching GuiSection's API so callers can add
    // SPRITEs without wrapping them as RENDEROBJs.
    public void contentAdd(SPRITE content) {
        this.contentAdd(content, 0);
    }
    public void contentAdd(SPRITE content, int topMargin) {
        this.contentSection.addDown(topMargin, content);
        this.recalculateOverflow();
    }

    // GuiSection.clear() also resets dimensions, so restore the fixed
    // width after clearing.
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
        this.scrollOffset.max = this.overflowHeight;
        // Clamp the current offset in case the content shrank.
        this.scrollOffset.set(this.scrollOffset.get());
    }

    private void applyScrollOffset() {
        this.contentSection.body().moveY1(this.body().y1() - this.scrollOffset.get());
    }

    // Positioning is managed explicitly by applyScrollOffset().
    @Override
    protected void moveCallback() {
    }

    @Override
    public boolean click() {
        boolean consumedByChild = super.click();
        // Begin dragging only if no child consumed the click.
        // Clicking a child widget never simultaneously starts a drag.
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

    /*
     * Centralizes all scroll-position changes so clamping and viewport
     * translation always occur together.
     */
    private final class ScrollOffset extends INT.IntImp {

        private ScrollOffset() {
            super(0, 0);
        }

        @Override
        public void set(int t) {
            super.set(t);
            ThalGSlidableViewportVertical.this.applyScrollOffset();
        }
    }
}
