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

/*
 * Generalizes IPromtYesNO into a normal-sized confirmation dialog with an
 * arbitrary number of labeled buttons.
 *
 * Forked rather than extended because the required state in IPromtYesNO is
 * private.
 */
public class ThalIPromtButtons extends Interrupter {

    private final GTextR text = new GTextR(UI.FONT().M, 1000, DIR.C);
    private final GuiSection section = new GuiSection();
    private final GPanel box = new GPanel().setDim(800, 400);
    private boolean dismissable;
    private final InterManager m;

    /*
     * Invoked exactly once whenever the dialog closes, regardless of whether
     * it was dismissed by a button, ESC, or right-click.
     *
     * IPromtYesNO provides no equivalent lifecycle hook.
     */
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

    // Buttons retain ownership of their own actions. This method presents
    // the dialog and guarantees onDismissed executes once when it closes.
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
        // Keep the background panel behind the controls so they receive
        // hover and click events.
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

    /*
     * Close the dialog before dispatching the clicked control's action.
     *
     * This guarantees consistent dismissal behavior regardless of which
     * clickable control initiated it.
     */
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
