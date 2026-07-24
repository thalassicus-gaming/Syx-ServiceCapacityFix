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

/*
 * Fork of GInput adding a hook to customize the rendered text color.
 *
 * GInput performs its own color binding immediately before drawing text,
 * leaving no extension point for callers to override the text color.
 */
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

    /*
     * Returns the color used to render the input text.
     *
     * Called immediately before the text draw, after the background and
     * hover overlay have completed their own color binding.
     */
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

    // Empirical correction factor producing a closer match between
    // character count and rendered width than font metrics alone.
    public static final double WIDTH_MULTIPLIER = 0.8;

    // Both directions of the character-count/pixel conversion must use
    // this calculation to remain consistent.
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
