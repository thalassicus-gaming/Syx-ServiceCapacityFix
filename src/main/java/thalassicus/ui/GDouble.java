// GDouble.java
// Document Version 1.0.2
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.ui;

import init.sprite.UI.UI;
import snake2d.SPRITE_RENDERER;
import snake2d.util.color.COLOR;
import snake2d.util.sprite.text.Str;
import snake2d.util.sprite.text.StringInputSprite;
import util.colors.GCOLOR;
import util.gui.misc.GInput;

// A free-typed decimal input box for a value that has a live, changeable
// default (e.g. RoomService.hypotheticalCapacityPerSlot()) unless the player
// has overridden it with their own number. Deliberately NOT built on Jake's
// own INT/DOUBLE interfaces - see the modding reference discussion for why
// those exist for a different shape of value (a normalized fraction against
// a fixed min/max, or a per-key family of doubles) than a single free-
// standing decimal a player edits directly.
//
// Knows nothing about profiles, blueprint keys, or ThalCapacityProfileManager
// - every piece of domain meaning is supplied by the caller through the two
// abstract methods below, via an anonymous subclass at the construction
// site. This mirrors the pattern Jake's own GButt.ButtPanel (clickA()) and
// SFinderRoomService (get(tx, ty)) already use elsewhere in this codebase,
// rather than introducing a new callback-interface style of our own.
//
// Three states, tracked entirely inside the private DecimalInputSprite:
// NEUTRAL (buffer empty - either genuinely untouched, or the player is
// mid-edit after clearing it), VALID (buffer parses within bounds), INVALID
// (buffer doesn't parse, or parses outside bounds). There is no explicit
// fourth "showing the default" state to track - that's a rendering side
// effect StringInputSprite already provides on its own (its placeholder only
// ever appears while the buffer is empty AND unfocused), not something this
// class needs to compute or store separately.
public abstract class GDouble extends GInput {

    // Two decimal places, matching the convention Jake's own UI uses
    // elsewhere for decimal display.
    private static final int DECIMAL_PLACES = 2;

    // Buffer capacity, in characters. Str reserves one slot (spaceLeft()
    // is chars.length - last - 1, not chars.length - last) - almost
    // certainly for the '|' cursor marker StringInputSprite renders while
    // focused - so this needs to be one MORE than the actual typable
    // character count we want. "99999.999" (7 valid digits, 1 decimal
    // point, 1 overtype digit that gets rounded away on blur) is 9
    // characters, so capacity is 10, not 9.
    private static final int MAXIMUM_TYPED_LENGTH = 10;

    private final double minimumValue;
    private final double maximumValue;
    private final DecimalInputSprite inputSprite;

    // Dedicated to this instance alone, deliberately NOT one of Str's own
    // shared scratch buffers (Str.TMP/Str.TMP2) - StringInputSprite.
    // placeHolder(CharSequence) stores the reference it's given directly,
    // it does not copy the text out. A shared buffer would risk some other
    // code overwriting its contents between this render() call setting the
    // placeholder and StringInputSprite actually drawing it later in the
    // same call.
    private final Str defaultValueDisplay = new Str(MAXIMUM_TYPED_LENGTH);

    // Compared against inputSprite.listening() every render to detect the
    // exact frame focus is lost - this class's only hand-rolled piece of
    // state tracking, everything else reads directly off the sprite.
    private boolean wasFocusedLastFrame = false;

    // minimumValue/maximumValue are supplied by the caller rather than
    // hardcoded here - the [1.0, 99999.0] capacity-per-slot range is domain
    // knowledge belonging to the capacity-profile feature, not to this
    // general-purpose input box.
    protected GDouble(double minimumValue, double maximumValue) {
        this(minimumValue, maximumValue, new DecimalInputSprite(minimumValue, maximumValue));
    }

    // Splitting construction this way exists solely to get a fully-built
    // DecimalInputSprite into GInput's own constructor - GInput has no
    // no-argument constructor to defer to, so the sprite must exist before
    // super() runs, which in turn means it can't be a normal field
    // initializer referencing this.anything. DecimalInputSprite needing
    // nothing from the outer instance (just the two bounds) is what makes
    // this possible without a two-phase, set-the-owner-after-construction
    // workaround.
    private GDouble(double minimumValue, double maximumValue, DecimalInputSprite inputSprite) {
        super(inputSprite);
        this.minimumValue = minimumValue;
        this.maximumValue = maximumValue;
        this.inputSprite = inputSprite;
    }

    // Called every render, before the placeholder is refreshed below -
    // always re-queried rather than cached, since the live default (e.g.
    // hypotheticalCapacityPerSlot()) can genuinely change between frames.
    protected abstract double liveDefaultValue();

    // Fires every render while the buffer currently parses to a valid,
    // in-bounds number - matches this mod's existing immediate-update input
    // convention (RoomService's own tiers commit the moment a value is
    // known, nothing here waits for an explicit confirmation step) rather
    // than only committing on blur. Fires with the RAW typed value on every
    // such frame, and then ONCE MORE with the rounded value the instant
    // focus is lost - typing a third-or-later decimal digit is allowed
    // right up until then, not blocked at the keystroke level.
    protected abstract void committedValueSet(double committedValue);

    // Fires exactly once, the frame focus is lost while the buffer is
    // empty - the player's chosen way to revert a profile entry back to
    // tracking the live default rather than a fixed override, in place of a
    // per-row reset button.
    protected abstract void revertToDefault();

    // Pre-populates this box from a value the profile already has stored,
    // or clears it back to tracking the live default if null - distinct
    // from committedValueSet() above, which only ever flows outward (this
    // box telling the caller what the player typed). A single nullable-
    // aware method rather than two separate ones (a "set" and a "revert")
    // so every call site collapses to one line instead of an if/else -
    // null meaning "no override" matches the same convention
    // ThalCapacityProfile itself already uses (absence in the map, not a
    // sentinel value, means "track the default"), rather than inventing a
    // different one here. Bypasses acceptChar()'s own filtering entirely
    // either way, since this is the caller supplying a known state, not a
    // player keystroke.
    public final void existingValueSet(Double existingValueOrNull) {
        if (existingValueOrNull == null) {
            this.inputSprite.textClear();
        } else {
            this.inputSprite.textSet(existingValueOrNull);
            this.inputSprite.currentStateSet(DecimalInputSprite.State.VALID);
        }
    }

    // True whenever the current buffer would NOT block a profile save -
    // covers both VALID (a real committed number) and NEUTRAL (empty,
    // meaning "no override, use the default"). Only INVALID blocks saving.
    public final boolean isValid() {
        return this.inputSprite.currentState() != DecimalInputSprite.State.INVALID;
    }

    @Override
    protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
        this.defaultValueDisplay.clear().add(this.liveDefaultValue(), DECIMAL_PLACES);
        this.inputSprite.placeHolder(this.defaultValueDisplay);

        boolean isFocusedNow = this.inputSprite.listening();
        if (this.wasFocusedLastFrame && !isFocusedNow) {
            this.handleFocusLost();
        }
        this.wasFocusedLastFrame = isFocusedNow;

        if (this.inputSprite.currentState() == DecimalInputSprite.State.VALID) {
            this.committedValueSet(this.inputSprite.parsedValue());
        }

        // GCOLOR.UI().BAD.hovered is confirmed safe to use here despite its
        // name - GColorUIModel.hovered is just the brightest of four fixed
        // shades (normal/hovered/selected/inactive), not something gated on
        // actual mouse hover. Using .hovered for maximum visibility; .normal
        // (a dimmer red) is the other reasonable choice here and is purely
        // a UX call, not a technical one - worth Victoria's own judgment
        // once this renders in-game.
        COLOR textColor = this.inputSprite.currentState() == DecimalInputSprite.State.INVALID
                ? GCOLOR.UI().BAD.hovered
                : COLOR.WHITE100;
        textColor.bind();
        super.render(r, ds, isActive, isSelected, isHovered);
        COLOR.unbind();
    }

    private void handleFocusLost() {
        switch (this.inputSprite.currentState()) {
            case VALID -> {
                double rounded = round(this.inputSprite.parsedValue());
                this.inputSprite.textSet(rounded);
                this.committedValueSet(rounded);
            }
            case NEUTRAL -> this.revertToDefault();
            case INVALID -> {
                // Left untouched on purpose - the box stays red and Save
                // stays blocked until the player returns and either fixes
                // or clears it themselves.
            }
        }
    }

    private static double round(double value) {
        double scale = Math.pow(10, DECIMAL_PLACES);
        return Math.round(value * scale) / scale;
    }

    // Self-contained on purpose - holds only the two bounds, never a
    // reference back to the owning GDouble. That is what lets it be built
    // before GDouble's own super() call completes (see the private
    // constructor's own comment above), and it is also what keeps this
    // class ignorant of what committing or reverting actually means -
    // GDouble reads this sprite's state every render instead of this
    // sprite ever calling back out to GDouble.
    private static final class DecimalInputSprite extends StringInputSprite {

        private enum State {
            NEUTRAL,
            VALID,
            INVALID
        }

        private final double minimumValue;
        private final double maximumValue;
        private DecimalInputSprite.State currentState = DecimalInputSprite.State.NEUTRAL;

        private DecimalInputSprite(double minimumValue, double maximumValue) {
            super(MAXIMUM_TYPED_LENGTH, UI.FONT().S);
            this.minimumValue = minimumValue;
            this.maximumValue = maximumValue;
        }

        // Gatekeeps which characters ever reach StringInputSprite's own
        // acceptChar() (which does the actual cursor/selection/insertion
        // work and calls change() itself once a character is genuinely
        // accepted) - a rejected character here simply never happens, no
        // buffer-reverting cleanup step required afterward.
        @Override
        protected void acceptChar(char c) {
            if (Character.isDigit(c)) {
                super.acceptChar(c);
            } else if (c == '.' && !this.containsDecimalPoint()) {
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

        // Fires after every accepted keystroke and after backspace/delete
        // (StringInputSprite's own machinery, not overridden here). Purely
        // a classification step - never itself responsible for committing
        // anything outward; GDouble.render() reads currentState()/
        // parsedValue() every frame and decides what to do with them.
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

        // Null only if currentState() == INVALID from a genuinely
        // unparseable buffer - callers checking currentState() first (as
        // GDouble.render()/handleFocusLost() both do) never actually see
        // that null case in practice, since VALID is the only state either
        // caller acts on.
        private Double parsedValue() {
            return tryParse(this.text());
        }

        // Str.add(double, int) is Jake's own idiom for this (confirmed in
        // Str.java) - it rounds and writes both the whole and decimal
        // digits from raw arithmetic, never touching String.format or any
        // locale.
        private void textSet(double value) {
            this.text().clear().add(value, DECIMAL_PLACES);
        }

        // Counterpart to textSet() above - empties the buffer and resets
        // to NEUTRAL directly, rather than going through change() (which
        // would work, since change() already treats an empty buffer as
        // NEUTRAL, but this is more direct about what's actually happening:
        // an external caller declaring "no override," not a player
        // clearing a field themselves).
        private void textClear() {
            this.text().clear();
            this.currentState = DecimalInputSprite.State.NEUTRAL;
        }

        // Confirmed against Str.java: it does implement CharSequence, and
        // toString() does return the buffer's own text content - so the
        // concrete Str parameter type here was extra caution rather than a
        // real risk, but there's no reason to loosen it back to CharSequence
        // now that it's already written this way.
        private static Double tryParse(Str characters) {
            try {
                return Double.parseDouble(characters.toString());
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }
}
