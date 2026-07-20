// GLabeledValue.java
// Document Version 1.0.0
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.ui;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.gui.GuiSection;
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

        this.addRightC(0, this.label);
        this.addRightC(LABEL_VALUE_MARGIN, this.valueInput);
    }

    // Pure delegation - lets a caller aggregate Save-eligibility across a
    // whole table without needing to know this is secretly two widgets,
    // same reasoning as GDouble's own isValid().
    public boolean isValid() {
        return this.valueInput.isValid();
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        this.label.clear().add(this.labelTextSupplier.get());
        super.render(r, ds);
    }
}
