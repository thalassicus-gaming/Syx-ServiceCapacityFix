// ThalGInput.java
// Document Version 1.0.0
// Creation date: 2026/07/22
// Creator: Thalassicus

package thalassicus.ui;

import snake2d.MButt;
import snake2d.Mouse;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.sprite.text.Font;
import snake2d.util.sprite.text.Str;
import snake2d.util.sprite.text.StringInputSprite;
import util.colors.GCOLOR;
import view.main.VIEW;

// A fork of Jake's own util.gui.misc.GInput, existing for exactly one
// reason: GDouble needs to render its text in red when the current buffer
// is INVALID, and GInput gives no hook for that at all.
//
// Binding a color before calling GInput's own render() (what GDouble tried
// first) does NOT work, confirmed by tracing the actual call sequence:
// GInput.render() itself performs TWO of its own self-contained
// COLOR.render(...) calls (the background fill, the hover overlay) BEFORE
// it ever reaches the actual text draw - each of those binds, draws, and
// unbinds its own color with no reason to restore whatever was bound
// before it ran. So a color bound outside this whole method is silently
// overwritten before the glyphs are ever painted. StringInputSprite's own
// render() confirms the other half: its unfocused/non-empty branch does
// no color binding of its own at all, it just paints in whatever's
// currently bound - so the fix has to land the bind immediately before
// that specific call, not anywhere earlier.
//
// Extending GInput instead of forking wasn't possible - its own render()
// is what needs the new hook inserted into the middle of its sequence,
// which a subclass can't do without already having the entire method's
// body to insert into.
public class ThalGInput extends CLICKABLE.ClickableAbs {
    private final StringInputSprite input;
    private boolean dragging = false;
    public static final int PADDING_WIDTH = 12;
    public static final int PADDING_HEIGHT = 12;

    public ThalGInput(StringInputSprite input) {
        this.input = input;
        int w = perCharacterAdvance(input.font()) * (input.text().length() + input.text().spaceLeft());
        this.body.setWidth(w + PADDING_WIDTH);
        this.body.setHeight(input.height() + PADDING_HEIGHT);
    }

    // The main addition this fork exists for. Called immediately before
    // the actual text draw - not any earlier - so it lands after the
    // background/hover-overlay rendering that would otherwise clobber it,
    // and the border rendering that follows doesn't run until after the
    // text has already been painted. Default matches GInput's own
    // unconditional COLOR.WHITE100, so a caller that never overrides this
    // sees identical behavior to vanilla.
    protected COLOR textColor() {
        return COLOR.WHITE100;
    }

    @Override
    protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
        GCOLOR.UI().bg(isActive, isSelected, isHovered).render(r, this.body);
        this.input.renAction();
        if (Mouse.currentClicked == this) {
            this.input.listen();
        }

        if (isHovered || Mouse.currentClicked == this) {
            GCOLOR.UI().NORMAL.hovered.render(r, this.body());
        }

        int x1 = this.body().x1() + 6;
        int y1 = this.body().y1() + (this.body().height() - this.input.height()) / 2;
        this.dragging = this.dragging & MButt.LEFT.isDown();
        if (this.dragging) {
            this.input.select(VIEW.mouse().x() - x1);
        }

        this.textColor().bind();
        this.input.render(r, x1, y1);
        COLOR.unbind();
        GCOLOR.UI().border().renderFrame(r, this.body, 0, 2);
    }

    // Multiplier fixing a residual over-width not explained by kerning alone -
    // applying the getBack() correction below reduced the overestimate but
    // didn't eliminate it; this empirical factor covers the remainder.
    // Root cause not identified.
    public static final double WIDTH_MULTIPLIER = 0.8;

    // Shared by ThalCapacityUI's own reverse calculation (available pixels ->
    // how many characters fit). Exposing this as one method both directions
    // call is what actually fixes the Description field ending up narrower
    // than intended - the two conversions were previously using two
    // DIFFERENT rates (raw maxCWidth on one side, this corrected rate on the
    // other), so converting pixels -> characters -> pixels didn't round-trip
    // back to the original value. Depends on the font staying monospaced.
    public static int perCharacterAdvance(Font font) {
        char representativeChar = 'W';
        return (int) (WIDTH_MULTIPLIER * (font.maxCWidth - font.getBack(representativeChar, representativeChar, 1.0)));
    }

    @Override
    public boolean click() {
        if (super.click()) {
            Mouse.currentClicked = this;
            if (this.input.listening() && !MButt.LEFT.isDouble()) {
                this.dragging = true;
                this.input.click(VIEW.mouse().x() - this.body().x1() - 6);
                return true;
            }

            this.input.listen();
            this.input.selectAll();
            this.dragging = false;
        }

        return false;
    }

    public void focus() {
        Mouse.currentClicked = this;
        this.input.listen();
        this.input.selectAll();
    }

    public void listen() {
        Mouse.currentClicked = this;
        this.input.listen();
    }

    public Str text() {
        return this.input.text();
    }
}
