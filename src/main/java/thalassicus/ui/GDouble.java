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
// Extends ThalGInput (our own fork of Jake's GInput), not GInput directly -
// ThalGInput adds the one hook (textColor()) needed to actually paint
// invalid input red; see its own header comment for why binding a color
// before calling GInput's own render() silently doesn't work.
public abstract class GDouble extends ThalGInput {

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
    // How many digits after the decimal point this box accepts and
    // displays - 0 makes this behave as a genuine integer field (no
    // decimal point accepted at all, not just rounded away later; see
    // DecimalInputSprite.acceptChar()). Supplied by the caller, same
    // reasoning as minimumValue/maximumValue above - a species/HTYPE
    // population count needing 0 rather than 2 is domain knowledge
    // belonging to the capacity-profile feature, not to this general-
    // purpose input box.
    private final int decimalPlaces;
    private final DecimalInputSprite inputSprite;

    // Dedicated to this instance alone, deliberately NOT one of Str's own
    // shared scratch buffers (Str.TMP/Str.TMP2) - StringInputSprite.
    // placeHolder(CharSequence) stores the reference it's given directly,
    // it does not copy the text out. A shared buffer would risk some other
    // code overwriting its contents between this render() call setting the
    // placeholder and StringInputSprite actually drawing it later in the
    // same call.
    private final Str defaultValueDisplay = new Str(MAXIMUM_TYPED_LENGTH);

    // Tracks whether THIS control was the mouse-focused one last frame,
    // read off Mouse.currentClicked (a stable, frame-consistent global)
    // rather than inputSprite.listening() (the keyboard-listener global,
    // which lags a frame behind and can straddle a frame boundary on
    // blur). Detecting the blur EDGE off listening() was the original bug:
    // clicking away could take this control from "never yet observed
    // focused" straight to "not focused now" without the true->false
    // transition handleFocusLost() watches for ever being visible on a
    // single frame - so an erased (emptied) field's revert-to-default
    // never fired, and the profile map entry was never removed. Typing a
    // value was immune only because it commits via render()'s own
    // per-frame VALID check, which never depended on this edge at all.
    // Mouse.currentClicked is the same signal ThalGInput's own render()
    // already trusts to keep listen() asserted each frame, so keying off
    // it is consistent with how focus actually propagates here.
    private boolean wasMouseFocusedLastFrame = false;

    // Null means "nothing committed since the last revert/load" - tracked
    // so committedValueSet() only fires on a genuine change, not every
    // single frame the buffer happens to still parse as VALID. Without
    // this, a profile with even one already-valid cell would re-fire that
    // cell's commit callback every render forever, and a caller using it
    // as an isDirty signal (as this mod's own ThalCapacityUI does) would
    // see isDirty flip back to true on the very next frame after every
    // save - confirmed the hard way in-game.
    private Double lastCommittedValue;

    // Two decimal places by default - matches this class's original,
    // pre-decimalPlaces-parameter behavior exactly, so every existing
    // caller (capacity-per-slot cells) needs no changes at all.
    protected GDouble(double minimumValue, double maximumValue) {
        this(minimumValue, maximumValue, 2);
    }

    // minimumValue/maximumValue are supplied by the caller rather than
    // hardcoded here - the [1.0, 99999.0] capacity-per-slot range is domain
    // knowledge belonging to the capacity-profile feature, not to this
    // general-purpose input box.
    protected GDouble(double minimumValue, double maximumValue, int decimalPlaces) {
        this(minimumValue, maximumValue, decimalPlaces, new DecimalInputSprite(minimumValue, maximumValue, decimalPlaces));
    }

    // Splitting construction this way exists solely to get a fully-built
    // DecimalInputSprite into GInput's own constructor - GInput has no
    // no-argument constructor to defer to, so the sprite must exist before
    // super() runs, which in turn means it can't be a normal field
    // initializer referencing this.anything. DecimalInputSprite needing
    // nothing from the outer instance (just the two bounds and
    // decimalPlaces) is what makes this possible without a two-phase,
    // set-the-owner-after-construction workaround.
    private GDouble(double minimumValue, double maximumValue, int decimalPlaces, DecimalInputSprite inputSprite) {
        super(inputSprite);
        this.minimumValue = minimumValue;
        this.maximumValue = maximumValue;
        this.decimalPlaces = decimalPlaces;
        this.inputSprite = inputSprite;
    }

    // Called every render, before the placeholder is refreshed below -
    // always re-queried rather than cached, since the live default (e.g.
    // hypotheticalCapacityPerSlot()) can genuinely change between frames.
    protected abstract double liveDefaultValue();

    // CORRECTED (2026/07/22): fires the moment the buffer first parses to
    // a valid, in-bounds number, and again each time that value genuinely
    // changes - NOT every render regardless of change, which is what this
    // comment used to say and what the code used to do. That unconditional
    // per-frame firing was a real bug: any caller treating a commit as "the
    // player changed something" (this mod's own ThalCapacityUI does, for
    // isDirty) would see it fire forever on every already-valid cell, long
    // after the player had stopped touching it - confirmed the hard way,
    // saving a profile and then immediately seeing an "unsaved changes"
    // prompt with nothing having actually changed. Still matches this
    // mod's immediate-update convention (nothing waits for an explicit
    // confirmation step) - it's the redundant re-firing that was wrong,
    // not the immediacy itself. Also fires once more with the rounded
    // value the instant focus is lost, if rounding changed it from the raw
    // typed value - typing a third-or-later decimal digit is allowed right
    // up until then, not blocked at the keystroke level.
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
        // Set directly, not through commitIfChanged() below - this is the
        // caller declaring our own state, not a player edit, and must
        // never itself be treated as a "value just changed" event.
        this.lastCommittedValue = existingValueOrNull;
    }

    // True whenever the current buffer would NOT block a profile save -
    // covers both VALID (a real committed number) and NEUTRAL (empty,
    // meaning "no override, use the default"). Only INVALID blocks saving.
    public final boolean isValid() {
        return this.inputSprite.currentState() != DecimalInputSprite.State.INVALID;
    }

    @Override
    protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
        this.defaultValueDisplay.clear().add(this.liveDefaultValue(), this.decimalPlaces);
        this.inputSprite.placeHolder(this.defaultValueDisplay);

        // Blur is detected off Mouse.currentClicked, NOT inputSprite.listening()
        // - see wasMouseFocusedLastFrame's own comment for why the latter
        // silently missed this edge. super.render() below (ThalGInput's own)
        // still re-asserts listen() every frame while this stays the clicked
        // control, so actual keyboard focus continues to track correctly;
        // this class just no longer relies on that volatile global to detect
        // the blur moment.
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

    // CORRECTED (2026/07/22): this used to be a COLOR bound before calling
    // super.render(), then unbound after - confirmed the hard way that it
    // never actually painted red, since GInput's own render() (what
    // super.render() called into, before this class extended ThalGInput
    // instead) does its own color-binding work first and clobbers anything
    // bound outside it. ThalGInput.render() now calls this method at
    // exactly the right moment - immediately before the actual text draw -
    // so this override just needs to answer the question, not manage
    // when it applies.
    //
    // GCOLOR.UI().BAD.hovered is confirmed safe to use here despite its
    // name - GColorUIModel.hovered is just the brightest of four fixed
    // shades (normal/hovered/selected/inactive), not something gated on
    // actual mouse hover. Using .hovered for maximum visibility; .normal
    // (a dimmer red) is the other reasonable choice here and is purely
    // a UX call, not a technical one - worth Victoria's own judgment
    // once this renders in-game.
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
                // Reset, not left stale - otherwise re-typing the exact
                // same number this box held before being cleared would be
                // silently treated as "unchanged" and never re-committed.
                this.lastCommittedValue = null;
            }
            case INVALID -> {
                // Left untouched on purpose - the box stays red and Save
                // stays blocked until the player returns and either fixes
                // or clears it themselves.
            }
        }
    }

    // The one place committedValueSet() is ever actually invoked from -
    // both render()'s own per-frame check and handleFocusLost()'s VALID
    // case route through here, so there is exactly one definition of
    // "changed" to maintain, not two. Fires (and records) only when value
    // genuinely differs from whatever was last committed; a caller using
    // this as a dirty-tracking signal (this mod's own ThalCapacityUI does)
    // depends on that distinction to mean something real.
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
        private final int decimalPlaces;
        private DecimalInputSprite.State currentState = DecimalInputSprite.State.NEUTRAL;

        private DecimalInputSprite(double minimumValue, double maximumValue, int decimalPlaces) {
            super(MAXIMUM_TYPED_LENGTH, UI.FONT().S);
            this.minimumValue = minimumValue;
            this.maximumValue = maximumValue;
            this.decimalPlaces = decimalPlaces;
        }

        // Gatekeeps which characters ever reach StringInputSprite's own
        // acceptChar() (which does the actual cursor/selection/insertion
        // work and calls change() itself once a character is genuinely
        // accepted) - a rejected character here simply never happens, no
        // buffer-reverting cleanup step required afterward. decimalPlaces
        // == 0 rejects '.' outright, making this a genuine integer field -
        // the player can never type a decimal point in the first place,
        // not merely have one rounded away later on blur.
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
            this.text().clear().add(value, this.decimalPlaces);
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