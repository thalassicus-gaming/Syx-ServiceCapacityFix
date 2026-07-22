// ThalGDropDown.java
// Document Version 1.1.0
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
import snake2d.util.sprite.SPRITE;
import util.data.GETTER;
import util.gui.misc.GBox;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import util.gui.table.GTableBuilder;
import view.interrupter.InterManager;
import view.interrupter.Interrupter;
import view.keyboard.KEYS;
import view.main.VIEW;

// A fork of Jake's own util.gui.misc.GDropDown, existing for exactly one
// reason: vanilla GDropDown has no way to remove an entry once added - its
// own backing list (an ArrayListResize, confirmed from that class's own
// source) actually supports removal fine (removeOrdered(E), remove(E),
// iterator()), but GDropDown itself never exposes any of that. Extending
// GDropDown instead of forking wasn't possible - es is a private field,
// unreachable even from a subclass. Everything else here is otherwise
// unchanged from vanilla; only remove() below is new.
//
// Reflecting into GDropDown's own private es field (matching
// ThalReflectionUtil's existing pattern for InterManager.inters) was
// considered and rejected in favor of this fork - reflection into a
// private field two layers removed from where it's actually used read as
// a more fragile, harder-to-follow coupling than owning a small, fully
// understood copy of a ~200-line class used in exactly one place in the
// base game to begin with.
public class ThalGDropDown<E extends CLICKABLE> extends CLICKABLE.ClickableAbs implements CLICKABLE {
    private final SPRITE title;
    private final int mX = 4;
    private final int mY = 1;
    private E selected;
    private GuiSection expansion = new GuiSection();
    private final ThalGDropDown<E>.Inter inter;
    private final ArrayListResize<E> es = new ArrayListResize<>(20, 500);
    private final CLICKABLE.ClickableAbs dummy = new CLICKABLE.ClickableAbs() {
        @Override
        protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
        }
    };

    // Null by default, meaning "use VIEW.current().uiManager" (vanilla
    // GDropDown's own, only, behavior) - a caller showing this dropdown
    // inside a GLOBAL overlay (VIEW.inters().manager, not tied to
    // whichever view happens to be current) needs to explicitly point the
    // dropdown's own popup at that SAME manager via expansionManager()
    // below, or the popup registers with a different InterManager than
    // the one actually driving frames for that overlay - it would exist,
    // but never render or receive clicks. Discovered the hard way: this
    // exact mismatch was why ThalCapacityUI's own dropdown did nothing
    // when clicked, the first time this fork was actually tested in-game.
    private InterManager expansionManager;

    public ThalGDropDown<E> expansionManager(InterManager manager) {
        this.expansionManager = manager;
        return this;
    }

    public ThalGDropDown(SPRITE title) {
        this.title = title;
        this.body.setHeight(UI.FONT().S.height() + 2);
        this.inter = new ThalGDropDown.Inter();
    }

    // Explicit (SPRITE) cast is required, not stylistic - GText (via Text)
    // implements both SPRITE and CharSequence, so without it this call
    // matches two ThalGDropDown constructors at once (this one included)
    // and fails to compile as "reference ... is ambiguous". Vanilla
    // GDropDown's own decompiled source had this identical line with no
    // visible cast and compiled fine there - decompilation doesn't always
    // preserve a disambiguating cast that was present in the original,
    // pre-compiled source; copying the clean-looking decompiled text
    // brought the ambiguity back to the surface here.
    public ThalGDropDown(CharSequence title) {
        this((SPRITE) new GText(UI.FONT().S, title).lablify());
    }

    public ThalGDropDown(CharSequence title, int width) {
        this(sp(title, width));
    }

    private static SPRITE sp(CharSequence title, int width) {
        final GText t = new GText(UI.FONT().S, title).lablify();
        return new SPRITE.Imp(width, t.height() + 4) {
            @Override
            public void render(SPRITE_RENDERER r, int X1, int X2, int Y1, int Y2) {
                t.render(r, X1 + 2, Y1 + 2);
            }
        };
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

        COLOR.WHITE05.render(r, this.body.x1() + this.title.width() + 8, this.body.x1() + this.title.width() + 8 + 1, this.body.y1(), this.body.y2());
        if (!isActive) {
            COLOR.WHITE50.bind();
        } else if (isHovered) {
            SPRITES.icons().s.arrowDown.render(r, this.body.x2() - 16 - 4, this.body.y1() + (this.body.height() - 16) / 2);
            COLOR.WHITE150.bind();
        }

        this.title.render(r, this.body.x1() + 4, this.body.y1() + (this.body.height() - this.title.height()) / 2);
        COLOR.unbind();
        if (this.selected != null) {
            int x1 = this.selected.body().x1();
            int y1 = this.selected.body().y1();
            this.selected.body().centerY(this.body);
            this.selected.body().moveX1(this.body.x1() + this.title.width() + 12);
            this.selected.render(r, ds);
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

    // The one capability this fork exists to add. Uses removeOrdered()
    // rather than remove(E) (an unordered swap-with-last, per
    // ArrayListResize's own source) - dropdown entries have a meaningful
    // visual order (alphabetical, per ThalCapacityUI's own callers), and
    // silently reordering the remaining entries on every removal would be
    // a visible, surprising side effect. Rebuilds the rendered popup via
    // init() immediately - unlike add(), which the caller is expected to
    // batch and call init() once after (see init()'s own comment), a
    // single removal is a one-off action safe to immediately re-render
    // for, matching how callers actually use this in practice.
    //
    // If e was the current selection, selected becomes null rather than
    // left dangling on an entry no longer in the list - matches
    // ThalCapacityProfileManager.removeProfile()'s own identical
    // reasoning for activeProfile. The caller is expected to explicitly
    // setSelected() to whatever's appropriate afterward, same discipline
    // already used everywhere selection changes in this project.
    public ThalGDropDown<E> remove(E e) {
        this.es.removeOrdered(e);
        if (this.selected == e) {
            this.selected = null;
        }

        this.init();
        return this;
    }

    // Batches every add() call before rebuilding the popup once, rather
    // than after each individual add() - callers doing a bulk initial
    // population (ThalCapacityUI's own buildProfileDropdown()) call this
    // once at the end, not per-entry. remove() above does NOT follow this
    // convention - see its own comment for why an immediate rebuild is
    // the right default there instead.
    public ThalGDropDown<E> init() {
        this.expansion.clear();
        int w = 0;
        int h = 0;

        for (E e : this.es) {
            if (e.body().width() > w) {
                w = e.body().width();
            }

            if (e.body().height() > h) {
                h = e.body().height();
            }
        }

        this.body.setWidth(this.title.width() + 16 + w);
        this.dummy.body.setWidth(w).setHeight(h);
        // Deliberately NOT calling this.es.trim() here, unlike vanilla
        // GDropDown's own init(). trim() shrinks the backing array to
        // EXACTLY the current entry count, leaving zero spare capacity.
        // ArrayListResize.increase() only grows when
        // "last == es.length - 1" (one slot of headroom expected) - after
        // a trim(), that condition can never be true again (length
        // already equals last), so the very next add() writes to
        // es[last] one slot past the actual array length -
        // ArrayIndexOutOfBoundsException, confirmed the hard way the
        // first time a profile was saved after this dropdown's initial
        // population. Vanilla GDropDown never hits this, because vanilla
        // never adds anything after its own init() runs - this fork
        // exists specifically so that's no longer true, so the trim()
        // that was harmless there is actively broken here.
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
