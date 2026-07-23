// ThalIPromtButtons.java
// Document Version 1.0.0
// Creation date: 2026/07/22
// Creator: Thalassicus

package thalassicus.ui;

import init.constant.C;
import init.sprite.UI.UI;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.misc.ACTION;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GTextR;
import util.gui.panel.GPanel;
import view.interrupter.Interrupter;
import view.interrupter.InterManager;
import view.keyboard.KEYS;

// A fork of Jake's own view.interrupter.IPromtYesNO, generalizing its
// fixed two-icon-button layout into an arbitrary list of labeled GButt's.
// Exists because nothing in Interrupters offers a NORMAL-SIZED
// confirmation (matching IPromtYesNO's own modest 800x400 boxed GPanel,
// not IPromtScreen's deliberately full-screen treatment) with more than
// two buttons, or with real text labels instead of fixed icons.
//
// Extending IPromtYesNO wasn't possible - its own fields (text, section,
// box, yes, no) are all private, unreachable even from a subclass, same
// reason ThalGDropDown had to fork GDropDown rather than extend it.
public class ThalIPromtButtons extends Interrupter {

    private final GTextR text = new GTextR(UI.FONT().M, 1000, DIR.C);
    private final GuiSection section = new GuiSection();
    private final GPanel box = new GPanel().setDim(800, 400);
    private boolean dismissable;
    private final InterManager m;

    // Fires on EVERY exit path - any button click, or ESC/right-click
    // dismiss - not just one specific button. Mirrors IPromtScreen's own
    // deactivateAction. IPromtYesNO (the vanilla class this forks) has no
    // equivalent hook at all - only its own two hardcoded buttons' own
    // clickA() calls hide() directly, which never fires on a dismiss/ESC
    // exit at all. Without this, a caller relying on "something always
    // runs when this closes" would get silently, permanently stuck the
    // moment a player dismissed via ESC instead of clicking a button.
    private ACTION onDismissed;

    private final ACTION close = new ACTION() {
        @Override
        public void exe() {
            ThalIPromtButtons.this.deactivate();
        }
    };

    public ThalIPromtButtons(InterManager manager) {
        this.m = manager;
        this.text.text().lablify();
        this.section.add(this.box);
        this.section.body().centerIn(C.DIM());
        this.box.setBig();
        this.text.text().setMaxWidth(800);
    }

    // buttons' own clickA()/clickActionSet() logic is untouched by this
    // class - each one is expected to already carry whatever it should do
    // when clicked, the same way every button built elsewhere in this
    // project already works. This class's own job is only sizing/showing
    // the box and guaranteeing onDismissed fires exactly once per exit,
    // regardless of which of those buttons (or ESC/right-click) closed it.
    public void activate(CharSequence message, ACTION onDismissed, boolean dismissable, GButt... buttons) {
        this.show(this.m);
        this.dismissable = dismissable;
        this.onDismissed = onDismissed;
        this.section.clear();
        this.text.text().set(message);
        this.text.adjust();
        if (this.text.body().width() < 600) {
            this.section.body().setDim(600.0, 1.0);
        }

        this.section.addDownC(0, this.text);

        // Evenly spaced, centered row - mirrors IPromtScreen's own
        // button-layout math (a width/12 slot per button, centered on
        // total count) rather than IPromtYesNO's hardcoded two-button
        // left/right split, since this needs to work for any button
        // count, not just two.
        int slotWidth = C.WIDTH() / 12;
        int x = this.section.body().cX() - buttons.length * slotWidth / 2;
        int y = this.section.getLastY2() + 16;
        for (GButt button : buttons) {
            button.body().moveX1Y1(x - button.body().width() / 2, y);
            this.section.add(button);
            x += 2 * slotWidth;
        }

        this.section.body().centerIn(C.DIM());
        this.box.setCloseAction(dismissable ? this.close : null);
        this.box.inner().set(this.section.body());
        this.section.add(this.box);
        // Box added and pushed behind everything else added above (text,
        // buttons) - matches IPromtYesNO's own identical sequencing,
        // confirmed necessary so a button's own hoverable area takes
        // priority over the box's own broader area during hover/click
        // detection, rather than clicks on a button being swallowed by
        // the box underneath it.
        this.section.moveLastToBack();
    }

    public void deactivate() {
        if (this.onDismissed != null) {
            this.onDismissed.exe();
        }
        this.hide();
    }

    @Override
    protected void hoverTimer(GBox text) {
        this.section.hoverInfoGet(text);
    }

    @Override
    protected boolean render(Renderer r, float ds) {
        this.section.render(r, ds);
        return true;
    }

    // Deliberately does NOT delegate to this.section.click() the way
    // IPromtYesNO's own mouseClick() effectively does through its two
    // hardcoded buttons' own clickA() - that approach only works because
    // vanilla hardcodes "hide() runs first" into each of its exactly two
    // buttons individually. Since this class accepts an arbitrary,
    // caller-built button array instead, it can't rely on every button
    // having been individually wired to call deactivate() itself.
    // Reading section.getHovered() directly and calling deactivate()
    // BEFORE the hovered element's own click() mirrors IPromtScreen's own
    // confirmed "deactivate first, then let the specific clicked thing's
    // own action run" ordering, generalized to work for any button
    // (or, incidentally, the box's own close-X, which is equally
    // CLICKABLE and handled identically here without special-casing it).
    @Override
    protected void mouseClick(MButt button) {
        if (button == MButt.LEFT) {
            RENDEROBJ hovered = this.section.getHovered();
            if (hovered instanceof CLICKABLE clickableHovered) {
                this.deactivate();
                clickableHovered.click();
            }
        } else if (this.dismissable && button == MButt.RIGHT) {
            this.deactivate();
        }
    }

    @Override
    protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
        this.section.hover(mCoo);
        return true;
    }

    @Override
    protected boolean update(float ds) {
        if (KEYS.MAIN().ESCAPE.consumeClick()) {
            this.deactivate();
            return true;
        } else {
            KEYS.clear();
            return false;
        }
    }

    @Override
    public boolean canSave() {
        return this.dismissable;
    }
}
