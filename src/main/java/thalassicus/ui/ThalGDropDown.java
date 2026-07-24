// ThalGDropDown.java
// Document Version 1.3.0
// Creation date: 2026/07/21
// Creator: Thalassicus

package thalassicus.ui;

import init.constant.C;
import init.sprite.SPRITES;
import init.sprite.UI.UI;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.ArrayListResize;
import util.data.GETTER;
import util.gui.misc.GBox;
import util.gui.panel.GPanel;
import util.gui.table.GTableBuilder;
import view.interrupter.InterManager;
import view.interrupter.Interrupter;
import view.keyboard.KEYS;
import view.main.VIEW;

/*
 * Fork of GDropDown that supports dynamic entry lists.
 *
 * Vanilla GDropDown assumes its entries are fixed after initialization and
 * provides no way to remove them. This fork allows the entry list to change
 * at runtime while otherwise preserving the original behavior.
 *
 * Extending GDropDown is not possible because its backing collection is
 * private, so maintaining a small fork is simpler than working around that
 * limitation.
 */
public class ThalGDropDown<E extends CLICKABLE & ThalDropDownEntry> extends CLICKABLE.ClickableAbs implements CLICKABLE {

    // Padding above and below the selected entry.
    private static final int VERTICAL_MARGIN = 5;
    // Left padding for the selected entry's text.
    private static final int HORIZONTAL_TEXT_MARGIN = 4;
    // Reserve space so the arrow never overlaps the selected text.
    private static final int ARROW_ICON_RESERVED_WIDTH = 24;

    private E selected;
    private GuiSection expansion = new GuiSection();
    private final ThalGDropDown<E>.Inter inter;
    private final ArrayListResize<E> es = new ArrayListResize<>(20, 500);
    private final CLICKABLE.ClickableAbs dummy = new CLICKABLE.ClickableAbs() {
        @Override
        protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
        }
    };

    /*
     * Defaults to VIEW.current().uiManager.
     *
     * Callers displaying the dropdown from another InterManager must set
     * this so the popup is registered with the same manager driving the UI.
     */
    private InterManager expansionManager;

    public ThalGDropDown<E> expansionManager(InterManager manager) {
        this.expansionManager = manager;
        return this;
    }

    // Fixed-width dropdown. Entries are truncated rather than resizing
    // the control.
    public ThalGDropDown(int width) {
        this.body.setDim(width, UI.FONT().S.height() + 2 * VERTICAL_MARGIN);
        this.inter = new ThalGDropDown.Inter();
    }

    @Override
    protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
        COLOR.WHITE05.render(r, this.body);
        if (!isActive) {
            COLOR.WHITE15.render(r, this.body.x1() + 1, this.body.x2() - 1, this.body.y1() + 1, this.body.y2() - 1);
        } else if (isHovered) {
            COLOR.WHITE30.render(r, this.body.x1() + 1, this.body.x2() - 1, this.body.y1() + 1, this.body.y2() - 1);
        } else {
            COLOR.WHITE20.render(r, this.body.x1() + 1, this.body.x2() - 1, this.body.y1() + 1, this.body.y2() - 1);
        }

        if (isActive && isHovered) {
            SPRITES.icons().s.arrowDown.render(r, this.body.x2() - 16 - 4, this.body.y1() + (this.body.height() - 16) / 2);
        }

        if (this.selected != null) {
            int x1 = this.selected.body().x1();
            int y1 = this.selected.body().y1();
            int width = this.selected.body().width();
            this.selected.body().centerY(this.body);
            // Temporarily constrain the selected entry while rendering
            // inside the closed dropdown.
            this.selected.body().moveX1(this.body.x1() + HORIZONTAL_TEXT_MARGIN);
            this.selected.availableWidthSet(this.body.width() - HORIZONTAL_TEXT_MARGIN - ARROW_ICON_RESERVED_WIDTH);
            this.selected.render(r, ds);
            this.selected.availableWidthSet(width);
            this.selected.body().moveX1Y1(x1, y1);
        }
    }

    @Override
    public boolean click() {
        if (super.click()) {
            if (!this.inter.isActivated()) {
                this.inter.show();
            } else {
                this.inter.hide();
            }

            return true;
        } else {
            return false;
        }
    }

    public E selected() {
        return this.selected;
    }

    public void setSelected(E s) {
        this.selected = s;
    }

    public ThalGDropDown<E> add(E e) {
        this.es.add(e);
        if (this.selected == null) {
            this.selected = e;
        }

        return this;
    }

    /*
     * Removes an entry while preserving display order.
     *
     * If the removed entry was selected, the selection becomes null and
     * the popup is rebuilt immediately.
     */
    public ThalGDropDown<E> remove(E e) {
        this.es.removeOrdered(e);
        if (this.selected == e) {
            this.selected = null;
        }

        this.init();
        return this;
    }

    private int minExpansionWidth = 0;

    public ThalGDropDown<E> minExpansionWidth(int width) {
        this.minExpansionWidth = width;
        return this;
    }

    // Rebuild once after bulk add() operations rather than after every
    // individual addition.
    public ThalGDropDown<E> init() {
        this.expansion.clear();
        int w = this.minExpansionWidth;
        int h = 0;

        for (E e : this.es) {
            if (e.body().width() > w) {
                w = e.body().width();
            }

            if (e.body().height() > h) {
                h = e.body().height();
            }
        }

        this.dummy.body.setWidth(w).setHeight(h);
        /*
         * Deliberately do not call ArrayListResize.trim().
         *
         * Unlike vanilla GDropDown, this fork allows entries to be added
         * after initialization. trim() removes the spare capacity that
         * ArrayListResize's growth logic expects, causing later additions
         * to fail.
         */
        GTableBuilder builder = new GTableBuilder() {
            @Override
            public int nrOFEntries() {
                return ThalGDropDown.this.es.size();
            }
        };
        final int width = w;
        final int height = h;
        builder.column(null, w + 8, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                return new CLICKABLE.ClickWrap(width, height) {
                    protected CLICKABLE pget() {
                        if (ier.get() == null) {
                            return ThalGDropDown.this.dummy;
                        }

                        int i = ier.get();
                        return (CLICKABLE) (i >= ThalGDropDown.this.es.size() ? ThalGDropDown.this.dummy : ThalGDropDown.this.es.get(i));
                    }

                    @Override
                    public boolean click() {
                        int i = ier.get();
                        ThalGDropDown.this.setSelected(ThalGDropDown.this.es.get(i));
                        if (super.click()) {
                            if (i < ThalGDropDown.this.es.size()) {
                                ThalGDropDown.this.inter.hide();
                            }

                            return true;
                        } else {
                            return false;
                        }
                    }
                };
            }
        });
        int rows = Math.min(10, this.es.size());
        this.expansion = builder.create(rows, true);
        GPanel p = new GPanel();
        p.inner().set(this.expansion);
        this.expansion.add(p);
        this.expansion.moveLastToBack();
        return this;
    }

    private final class Inter extends Interrupter {
        private Inter() {
        }

        @Override
        protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
            return ThalGDropDown.this.expansion.hover(mCoo);
        }

        @Override
        protected void mouseClick(MButt button) {
            if (button == MButt.LEFT) {
                ThalGDropDown.this.expansion.click();
            } else if (button == MButt.RIGHT) {
                this.hide();
            }
        }

        private void show() {
            if (!this.isActivated()) {
                ThalGDropDown.this.expansion.body().moveC(ThalGDropDown.this.body().cX(), 0.0);
                ThalGDropDown.this.expansion.body().moveY1(ThalGDropDown.this.body().y2());
                if (ThalGDropDown.this.expansion.body().y2() > C.HEIGHT()) {
                    ThalGDropDown.this.expansion.body().moveY2(ThalGDropDown.this.body().y2());
                }

                InterManager manager = ThalGDropDown.this.expansionManager != null
                        ? ThalGDropDown.this.expansionManager
                        : VIEW.current().uiManager;
                super.show(manager);
            }
        }

        @Override
        protected boolean otherClick(MButt button) {
            this.hide();
            return false;
        }

        @Override
        public void hide() {
            super.hide();
        }

        @Override
        protected void hoverTimer(GBox text) {
            ThalGDropDown.this.expansion.hoverInfoGet(text);
        }

        @Override
        protected boolean render(Renderer r, float ds) {
            ThalGDropDown.this.expansion.render(r, ds);
            return true;
        }

        @Override
        protected boolean update(float ds) {
            if (KEYS.anyDown()) {
                this.hide();
            }

            return true;
        }
    }
}