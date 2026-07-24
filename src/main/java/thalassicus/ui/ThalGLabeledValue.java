// GLabeledValue.java
// Document Version 1.3.0
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

/*
 * Simple composition of a label and a ThalGDouble.
 *
 * Responsible only for layout and delegation; value semantics remain within
 * ThalGDouble.
 */
public final class ThalGLabeledValue extends GuiSection {

    private static final int LABEL_VALUE_MARGIN = 4;

    private final Supplier<CharSequence> labelTextSupplier;
    private final GText label;
    private final ThalGDouble valueInput;

    public ThalGLabeledValue(
            double minimumValue,
            double maximumValue,
            int labelWidth,
            Supplier<CharSequence> labelTextSupplier,
            DoubleSupplier defaultValueSupplier,
            DoubleConsumer committedValueConsumer,
            Runnable revertToDefaultAction
    ) {
        this(minimumValue, maximumValue, 2, labelWidth, labelTextSupplier, defaultValueSupplier, committedValueConsumer, revertToDefaultAction);
    }

    public ThalGLabeledValue(
            double minimumValue,
            double maximumValue,
            int decimalPlaces,
            int labelWidth,
            Supplier<CharSequence> labelTextSupplier,
            DoubleSupplier defaultValueSupplier,
            DoubleConsumer committedValueConsumer,
            Runnable revertToDefaultAction
    ) {
        this.labelTextSupplier = labelTextSupplier;

        // Updated every render from labelTextSupplier.
        this.label = new GText(UI.FONT().S, "").setMaxWidth(labelWidth);

        this.valueInput = new ThalGDouble(minimumValue, maximumValue, decimalPlaces) {
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

        this.addRightC(0, this.valueInput);
        this.addRightC(LABEL_VALUE_MARGIN, new FixedWidthLabel(this.label, labelWidth));
    }

    public boolean isValid() {
        return this.valueInput.isValid();
    }

    // Read directly from the supplier rather than the rendered GText,
    // which is refreshed only during render.
    public CharSequence labelText() {
        return this.labelTextSupplier.get();
    }

    public void existingValueSet(Double existingValueOrNull) {
        this.valueInput.existingValueSet(existingValueOrNull);
    }

    @Override
    public void render(SPRITE_RENDERER r, float ds) {
        this.label.clear().add(this.labelTextSupplier.get());
        super.render(r, ds);
    }

    /*
     * Wraps GText in a RENDEROBJ with a fixed layout width.
     *
     * GuiSection's SPRITE overload sizes itself from the sprite's current
     * width. This wrapper decouples layout width from rendered text width.
     */
    private static final class FixedWidthLabel extends RENDEROBJ.RenderImp {

        private final GText text;

        private FixedWidthLabel(GText text, int width) {
            super(width, text.height());
            this.text = text;
        }

        @Override
        public void render(SPRITE_RENDERER r, float ds) {
            // Render within the wrapper's fixed bounds.
            this.text.render(r, this.body.x1(), this.body.x2(), this.body.y1(), this.body.y2());
        }
    }
}