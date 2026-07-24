// GDouble.java
// Document Version 1.2.0
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.ui;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.Mouse;
import snake2d.util.color.COLOR;
import snake2d.util.sprite.text.Str;
import snake2d.util.sprite.text.StringInputSprite;
import util.colors.GCOLOR;

/*
 * Editable decimal input with a live default value supplied by the caller.
 *
 * Unlike Jake's INT/DOUBLE helpers, this widget represents a single
 * free-form numeric value rather than a normalized parameter family.
 * Domain behavior is supplied through the abstract callbacks below.
 *
 * The underlying input has three states: NEUTRAL (empty), VALID, and
 * INVALID. Rendering the live default while neutral is handled by
 * StringInputSprite's placeholder mechanism rather than a separate state.
 */
public abstract class ThalGDouble extends ThalGInput {

    // Str reserves one internal character, so capacity must be one larger
    // than the maximum number of user-typable characters.
    private static final int MAXIMUM_TYPED_LENGTH = 10;

    private final double minimumValue;
    private final double maximumValue;
    private final int decimalPlaces;
    private final DecimalInputSprite inputSprite;

    // Dedicated buffer. StringInputSprite.placeHolder() retains the supplied
    // Str rather than copying its contents, so a shared scratch buffer
    // could be overwritten before rendering.
    private final Str defaultValueDisplay = new Str(MAXIMUM_TYPED_LENGTH);

    /*
     * Blur detection intentionally tracks Mouse.currentClicked rather than
     * inputSprite.listening().
     *
     * listening() reflects the global keyboard listener, which can lag
     * behind mouse focus changes. Detecting blur from it can miss the
     * focused-to-unfocused transition entirely, preventing an empty field
     * from reverting to its default state.
     *
     * Mouse.currentClicked is the same focus signal ThalGInput uses to
     * maintain focus, keeping both behaviors consistent.
     */
    private boolean wasMouseFocusedLastFrame = false;

    // Prevents committedValueSet() from firing repeatedly while the
    // committed value remains unchanged.
    private Double lastCommittedValue;

    protected ThalGDouble(double minimumValue, double maximumValue) {
        this(minimumValue, maximumValue, 2);
    }

    protected ThalGDouble(double minimumValue, double maximumValue, int decimalPlaces) {
        this(minimumValue, maximumValue, decimalPlaces, new DecimalInputSprite(minimumValue, maximumValue, decimalPlaces));
    }

    // The DecimalInputSprite must exist before the super() call because
    // ThalGInput has no no-argument constructor.
    private ThalGDouble(double minimumValue, double maximumValue, int decimalPlaces, DecimalInputSprite inputSprite) {
        super(inputSprite);
        this.minimumValue = minimumValue;
        this.maximumValue = maximumValue;
        this.decimalPlaces = decimalPlaces;
        this.inputSprite = inputSprite;
    }

    protected abstract double liveDefaultValue();

    // Called when a valid value is first entered or when the committed
    // value changes. Not called repeatedly for an unchanged value.
    protected abstract void committedValueSet(double committedValue);

    // Called when focus leaves an empty field, reverting to the live default.
    protected abstract void revertToDefault();

    // Initializes the widget from existing state without invoking
    // committedValueSet().
    public final void existingValueSet(Double existingValueOrNull) {
        if (existingValueOrNull == null) {
            this.inputSprite.textClear();
        } else {
            this.inputSprite.textSet(existingValueOrNull);
            this.inputSprite.currentStateSet(DecimalInputSprite.State.VALID);
        }
        this.lastCommittedValue = existingValueOrNull;
    }

    public final boolean isValid() {
        return this.inputSprite.currentState() != DecimalInputSprite.State.INVALID;
    }

    @Override
    protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
        this.defaultValueDisplay.clear().add(this.liveDefaultValue(), this.decimalPlaces);
        this.inputSprite.placeHolder(this.defaultValueDisplay);

        // Blur detection intentionally uses Mouse.currentClicked; see
        // wasMouseFocusedLastFrame for the rationale.
        boolean isMouseFocusedNow = Mouse.currentClicked == this;
        if (this.wasMouseFocusedLastFrame && !isMouseFocusedNow) {
            this.handleFocusLost();
        }
        this.wasMouseFocusedLastFrame = isMouseFocusedNow;

        if (this.inputSprite.currentState() == DecimalInputSprite.State.VALID) {
            this.commitIfChanged(this.inputSprite.parsedValue());
        }

        super.render(r, ds, isActive, isSelected, isHovered);
    }

    @Override
    protected COLOR textColor() {
        return this.inputSprite.currentState() == DecimalInputSprite.State.INVALID
                ? GCOLOR.UI().BAD.hovered
                : COLOR.WHITE100;
    }

    private void handleFocusLost() {
        switch (this.inputSprite.currentState()) {
            case VALID -> {
                double rounded = this.round(this.inputSprite.parsedValue());
                this.inputSprite.textSet(rounded);
                this.commitIfChanged(rounded);
            }
            case NEUTRAL -> {
                this.revertToDefault();
                this.lastCommittedValue = null;
            }
            case INVALID -> {
                // Leave invalid input untouched until the user corrects
                // or clears it.
            }
        }
    }

    // Centralizes the definition of "value changed" so all commit paths
    // apply the same behavior.
    private void commitIfChanged(double value) {
        if (this.lastCommittedValue == null || this.lastCommittedValue != value) {
            this.committedValueSet(value);
            this.lastCommittedValue = value;
        }
    }

    private double round(double value) {
        double scale = Math.pow(10, this.decimalPlaces);
        return Math.round(value * scale) / scale;
    }

    /*
     * Self-contained parser and state machine for the editable text.
     *
     * This class validates and classifies input but has no knowledge of
     * committing or reverting values.
     */
    private static final class DecimalInputSprite extends StringInputSprite {

        private enum State {
            NEUTRAL,
            VALID,
            INVALID
        }

        private final double minimumValue;
        private final double maximumValue;
        private final int decimalPlaces;
        private DecimalInputSprite.State currentState = DecimalInputSprite.State.NEUTRAL;

        private DecimalInputSprite(double minimumValue, double maximumValue, int decimalPlaces) {
            super(MAXIMUM_TYPED_LENGTH, UI.FONT().S);
            this.minimumValue = minimumValue;
            this.maximumValue = maximumValue;
            this.decimalPlaces = decimalPlaces;
        }

        // decimalPlaces == 0 produces a true integer field by rejecting
        // decimal points entirely.
        @Override
        protected void acceptChar(char c) {
            if (Character.isDigit(c)) {
                super.acceptChar(c);
            } else if (c == '.' && this.decimalPlaces > 0 && !this.containsDecimalPoint()) {
                super.acceptChar(c);
            }
        }

        private boolean containsDecimalPoint() {
            for (int i = 0; i < this.text().length(); i++) {
                if (this.text().charAt(i) == '.') {
                    return true;
                }
            }
            return false;
        }

        // Reclassifies the current buffer after each edit.
        @Override
        protected void change() {
            if (this.text().length() == 0) {
                this.currentState = DecimalInputSprite.State.NEUTRAL;
                return;
            }

            Double parsed = tryParse(this.text());
            boolean inBounds = parsed != null && parsed >= this.minimumValue && parsed <= this.maximumValue;
            this.currentState = inBounds ? DecimalInputSprite.State.VALID : DecimalInputSprite.State.INVALID;
        }

        private DecimalInputSprite.State currentState() {
            return this.currentState;
        }

        private void currentStateSet(DecimalInputSprite.State state) {
            this.currentState = state;
        }

        private Double parsedValue() {
            return tryParse(this.text());
        }

        private void textSet(double value) {
            this.text().clear().add(value, this.decimalPlaces);
        }

        private void textClear() {
            this.text().clear();
            this.currentState = DecimalInputSprite.State.NEUTRAL;
        }

        private static Double tryParse(Str characters) {
            try {
                return Double.parseDouble(characters.toString());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }
}