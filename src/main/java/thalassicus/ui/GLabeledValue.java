// GLabeledValue.java
// Document Version 1.1.0
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.ui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import util.gui.misc.GText;

// Pairs one label with one editable value - nothing more. Deliberately does
// NOT know it's a "cell" (no row/column, no neighbors, no grid position -
// that math lives entirely in whatever suppliers/consumers the caller
// provides) and does not know what "default" means (that's GDouble's own
// job, already fully contained inside it). This class only knows how to
// display a label and a bounded decimal value together, both re-derived
// live every render from the four references below - the same
// never-cache, always-re-derive discipline GDouble already established for
// its own liveDefaultValue().
//
// No awareness of ThalCapacityProfile, blueprint keys, RoomService, or
// anything from settlement.* - same standard already set for GDouble and
// GSlidableViewportVertical.
public final class GLabeledValue extends GuiSection {

    // Purely a visual layout choice internal to pairing a label with its
    // value - not domain-specific, so a small fixed constant rather than a
    // constructor parameter. Same "expect to tune visually" caveat as
    // every other layout guess in this project.
    private static final int LABEL_VALUE_MARGIN = 4;

    private final Supplier<CharSequence> labelTextSupplier;
    private final GText label;
    private final GDouble valueInput;

    public GLabeledValue(
            double minimumValue,
            double maximumValue,
            int labelWidth,
            Supplier<CharSequence> labelTextSupplier,
            DoubleSupplier defaultValueSupplier,
            DoubleConsumer committedValueConsumer,
            Runnable revertToDefaultAction
    ) {
        this.labelTextSupplier = labelTextSupplier;

        // Started blank rather than pre-populated - the very first
        // render() call fills it in immediately, so there's no need to
        // duplicate that logic here. Matches GDouble's own placeholder,
        // which is likewise never seeded at construction, only ever
        // computed fresh on render.
        this.label = new GText(UI.FONT().S, "").setMaxWidth(labelWidth);

        this.valueInput = new GDouble(minimumValue, maximumValue) {
            @Override
            protected double liveDefaultValue() {
                return defaultValueSupplier.getAsDouble();
            }

            @Override
            protected void committedValueSet(double committedValue) {
                committedValueConsumer.accept(committedValue);
            }

            @Override
            protected void revertToDefault() {
                revertToDefaultAction.run();
            }
        };

        this.addRightC(0, new FixedWidthLabel(this.label, labelWidth));
        this.addRightC(LABEL_VALUE_MARGIN, this.valueInput);
    }

    // Pure delegation - lets a caller aggregate Save-eligibility across a
    // whole table without needing to know this is secretly two widgets,
    // same reasoning as GDouble's own isValid().
    public boolean isValid() {
        return this.valueInput.isValid();
    }

    // Pure pass-through to GDouble's own existingValueSet() - closes the
    // gap flagged when this class was first built ("no way yet to pre-
    // seed a GLabeledValue with an already-stored value"). null means
    // "no override for this key, track the live default"; non-null means
    // "this profile has an explicit stored override." See GDouble's own
    // existingValueSet() for the full reasoning on the single
    // nullable-aware method design.
    public void existingValueSet(Double existingValueOrNull) {
        this.valueInput.existingValueSet(existingValueOrNull);
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        this.label.clear().add(this.labelTextSupplier.get());
        super.render(r, ds);
    }

    // BUG FOUND AND FIXED (2026/07/19): GText is a SPRITE, not a RENDEROBJ,
    // so addRightC(0, this.label) was silently resolving to GuiSection's
    // SPRITE overload rather than its RENDEROBJ one - which wraps a plain
    // SPRITE in RENDEROBJ.Sprite. That wrapper is built to keep itself
    // sized to its sprite's CURRENT content width, re-measuring on every
    // single render() call (RENDEROBJ.Sprite.adjust(): "if body.width() !=
    // sprite.width(), reposition to match"). Since this label starts empty
    // by design and only gets real text on the first render (see render()
    // above), the reserved layout width kept collapsing toward whatever
    // the label's current text happened to measure - often near zero -
    // rather than staying at the fixed labelWidth this class was
    // explicitly built to respect. This wrapper exists specifically to
    // NOT do that: a genuinely fixed body size, set once, that renders
    // whatever the wrapped GText currently holds without ever letting the
    // GText's own content-driven width feed back into layout.
    private static final class FixedWidthLabel extends RENDEROBJ.RenderImp {

        private final GText text;

        // Height is measured once from the (possibly still-empty) GText
        // at construction and is safe to treat as fixed, unlike width -
        // font line height doesn't depend on string content the way
        // rendered width does.
        private FixedWidthLabel(GText text, int width) {
            super(width, text.height());
            this.text = text;
        }

        @Override
        public void render(SPRITE_RENDERER r, float ds) {
            // Deliberately renders within this wrapper's own fixed body
            // bounds, not any width the text itself currently reports -
            // GText.render()'s own setMaxWidth-based truncation (confirmed
            // from GText.java: it substitutes X1 + this.maxWidth
            // internally regardless of the X2 passed in) handles fitting
            // long text within that space; this wrapper's job is only to
            // guarantee that space never shrinks to begin with.
            this.text.render(r, this.body.x1(), this.body.x2(), this.body.y1(), this.body.y2());
        }
    }
}
