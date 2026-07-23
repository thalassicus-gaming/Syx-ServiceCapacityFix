// ThalCapacityUI.java
// Document Version 1.21.0
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.capacity;
import game.GAME;
import init.constant.C;
import init.race.RACES;
import init.sprite.UI.UI;
import init.type.HTYPES;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import script.SCRIPT;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.DIR;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.sprite.SPRITE;
import snake2d.util.sprite.text.StringInputSprite;
import thalassicus.ui.*;
import thalassicus.util.ThalReflectionUtil;
import thalassicus.util.ThalsLogger;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import view.interrupter.InterManager;
import view.interrupter.Interrupter;
import view.main.VIEW;
public final class ThalCapacityUI implements SCRIPT, SCRIPT.SCRIPT_INSTANCE {
    private static final ThalsLogger log = new ThalsLogger(
            ThalsLogger.INFO,
            System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalCapacityUI.log"
    );
    public static final double FONT_CHARACTER_WIDTH = (0.75 * UI.FONT().S.maxCWidth);
    private static ThalCapacityUI instance;
    public static ThalCapacityUI instance() {
        return instance;
    }
    private static final CharSequence SCRIPT_NAME = "Thal Capacity UI";
    private static final CharSequence SCRIPT_DESCRIPTION =
            "Internal utility script for Service Estimate Fix. Displays the Capacity Profiles panel. Not a gameplay-affecting script.";
    private static final int LABEL_COLUMN_WIDTH = 180;
    private static final int CELLS_PER_ROW = 4;
    private static final int CELL_HORIZONTAL_MARGIN = 16;
    private static final int HORIZONTAL_INTER_PADDING = 8;
    private static final int SECTION_MARGIN = 16;
    public static final int DROPDOWN_WIDTH = 320;
    public static final int DROPDOWN_POPUP_WIDTH = 320;
    private static final double PANEL_WIDTH_FRACTION = 0.6;
    private static final double PANEL_HEIGHT_FRACTION = 0.6;
    private static final int VIEWPORT_WHEEL_SCROLL_STEP = 40;
    public static final int PROFILE_NAME_MAX_CHARACTERS = 30;
    private static final double CAPACITY_MINIMUM = 1.0;
    private static final double CAPACITY_MAXIMUM = 99999.0;
    private static final double POPULATION_MINIMUM = 0.0;
    private static final double POPULATION_MAXIMUM = 999999.0;
    private static final int CAPACITY_DECIMAL_PLACES = 2;
    public static final int DESCRIPTION_MIN_CHARACTERS = 10;
    private static final int HEADER_TABLE_MARGIN_TOP = 32;
    private static final int HEADER_TABLE_MARGIN_BOTTOM = 8;
    private static final String CAPACITY_EXPLANATION = "Multiplied by slot count to get room capacity.";
    private static final String SPECIES_EXPLANATION = "Information about city. Not used for calculations.";
    private static final String HTYPE_EXPLANATION = "Information about city. Not used for calculations.";
    // Species/HTYPE population counts are genuinely whole numbers - 0
    // decimal places makes their own GDouble cells reject a typed decimal
    // point outright, not just round one away after the fact.
    private static final int POPULATION_DECIMAL_PLACES = 0;
    // Shared by the two dropdown sentinels' own labels and attemptSave()'s
    // reserved-name guard below - one definition each, rather than
    // repeated literals that could drift out of sync with what the guard
    // actually checks against. Chevrons are a deliberate visual marker
    // (per the design spec) that these are sentinels, not real profiles -
    // a plain "New Profile"/"Live Data" without them is a perfectly valid,
    // ordinary name a player can still save under; only the exact
    // chevron-wrapped strings are reserved.
    private static final String RESERVED_NAME_NEW_PROFILE = "<New Profile>";
    private static final String RESERVED_NAME_LIVE_DATA = "<Live Data>";
    private GPanel mainPanel;
    private final GuiSection topSection = new GuiSection();
    private ThalGSlidableViewportVertical contentViewport;
    private Inter inter;
    // A single, persistent instance reused across every activation, the
    // same way VIEW.inters().yesNo/.fullScreen are also long-lived shared
    // fields rather than recreated per-use - not "define, use, forget"
    // like a fire-and-forget popup, since Interrupter-family objects are
    // meant to be constructed once and shown repeatedly. Assigned in
    // buildUI(), not eagerly, since it needs VIEW.inters().manager.
    private ThalIPromtButtons confirmationBox;
    // Refreshed every render via refreshActiveProfileLabel() - not a
    // FixedWidthLabel wrapper the way the table label column needed
    // (that solved a DIFFERENT problem: needing a STABLE reserved width so
    // sibling grid cells wouldn't shift as content changed). This is the
    // LAST element in its row with nothing positioned after it depending
    // on a stable width, so letting it naturally resize to fit its own
    // changing "Active Profile: X" text is the correct, simpler choice
    // here, not the same bug in a new location.
    private GText activeProfileLabel;
    private ThalGDropDown<ProfileDropdownEntry> profileDropdown;
    // Built once, referenced directly wherever code needs to force the
    // dropdown's visual selection to one of these specific sentinels
    // (transitionToLiveData()'s own collapse-to-New-Profile resync) -
    // ThalRoomServiceRegistry/RACES/HTYPES-style permanent objects, not
    // something rebuilt per session. Assigned in buildUI(), not eagerly -
    // see buildUI()'s own comment for why.
    private ProfileDropdownEntry newProfileEntry;
    private ProfileDropdownEntry liveDataEntry;
    // Keyed by object identity (ThalCapacityProfile has no equals()/
    // hashCode() override, so HashMap already does identity comparison) -
    // lets Save find and update an existing entry in place after a
    // rename/overwrite, and Delete find which entry to remove. ThalGDropDown
    // (our own fork) does support remove(E) directly now, but still has no
    // way to search its own contents by anything other than object
    // identity of the entry itself - this map is what actually answers
    // "which entry represents THIS profile" in the first place.
    private final Map<ThalCapacityProfile, ProfileDropdownEntry> dropdownEntriesByProfile = new HashMap<>();
    private ThalGInput displayNameField;
    private ThalGInput descriptionField;
    private final List<ThalGLabeledValue> allLabeledValues = new ArrayList<>();
    private Map<String, ThalGLabeledValue> capacityCellsByKey;
    private Map<String, ThalGLabeledValue> speciesCellsByKey;
    private Map<String, ThalGLabeledValue> htypeCellsByKey;
    private final ThalCapacityProfile scratchProfile = ThalCapacityProfile.blank("", "");

    // The real state per the design spec - null means the editor isn't
    // associated with any stored profile (State B: New/Duplicate/Live
    // Data). Set by all three dropdown-reachable transitions
    // (selectExistingProfile/transitionToNew/transitionToLiveData) - still
    // unread by anything else until Save/Delete/Activate get wired up.
    private ThalCapacityProfile selectedStoredProfile;

    private boolean isDirty = false;
    private ProfileDropdownEntry lastKnownSelection;
    private boolean isDiscardPromptPending = false;
    private boolean isContentBuilt = false;
    // Guards syncStoredProfilesIntoDropdown() below - runs exactly once,
    // on this SCRIPT_INSTANCE's own first update() call, not during
    // createInstance()/buildUI(). See that method's own comment for why.
    private boolean hasSyncedStoredProfiles = false;
    // Deliberately empty. ThalCapacityUI serves BOTH roles SCRIPT
    // requires: a pure descriptor (name()/desc()/isSelectable(), needed by
    // the main menu's own script-discovery/enumeration UI) and the real,
    // fully-built per-session panel. Both roles are reflectively
    // constructed via this SAME no-arg constructor - confirmed the hard
    // way, crashing the entire game at MAIN MENU load, before any game
    // session exists at all: menu.ScRandom$Scripts enumerates every
    // SCRIPT implementor purely to read name()/desc()/isSelectable() for
    // the menu's own script-picker screen, reflectively instantiating
    // each one via this exact constructor. VIEW.i is genuinely null at
    // that point - GAME's own constructor (which builds VIEW, and
    // everything VIEW/UI/C-dependent downstream of it) hasn't run yet and
    // won't until the player actually starts a session. Everything that
    // makes this an actual usable panel is deferred to buildUI() below,
    // called only from createInstance() - by which point GAME's own
    // constructor has already finished building VIEW.
    public ThalCapacityUI() {
    }

    // Builds the real panel. Every field assigned here (rather than at
    // construction) transitively needs VIEW/UI/C to already exist - see
    // the constructor's own comment for why that split exists at all.
    // Called exactly once, from createInstance(), never from the bare
    // constructor.
    private void buildUI() {
        int panelWidth = (int) (C.WIDTH() * PANEL_WIDTH_FRACTION);
        int panelHeight = (int) (C.HEIGHT() * PANEL_HEIGHT_FRACTION);

        this.mainPanel = new GPanel();
        this.inter = new Inter();
        this.confirmationBox = new ThalIPromtButtons(VIEW.inters().manager);
        this.activeProfileLabel = new GText(UI.FONT().S, "").normalify();
        this.newProfileEntry = ProfileDropdownEntry.sentinel(RESERVED_NAME_NEW_PROFILE, ProfileDropdownEntry.Kind.NEW_PROFILE);
        this.liveDataEntry = ProfileDropdownEntry.sentinel(RESERVED_NAME_LIVE_DATA, ProfileDropdownEntry.Kind.LIVE_DATA);

        this.displayNameField = this.buildTextField("Display Name", PROFILE_NAME_MAX_CHARACTERS, this.scratchProfile::displayNameSet);
        this.profileDropdown = this.buildProfileDropdown(this.displayNameField.body().width());
        int descriptionAvailableWidth = panelWidth - this.displayNameField.body().width() - HORIZONTAL_INTER_PADDING;
        int descriptionCapacity = Math.max(DESCRIPTION_MIN_CHARACTERS, (descriptionAvailableWidth - ThalGInput.PADDING_WIDTH) / ThalGInput.perCharacterAdvance(UI.FONT().S));
        this.descriptionField = this.buildTextField("Description", descriptionCapacity, this.scratchProfile::descriptionSet);
        this.buildTopSection();
        int viewportY = this.topSection.body().height() + SECTION_MARGIN;
        this.contentViewport = new ThalGSlidableViewportVertical(panelWidth, panelHeight - viewportY, VIEWPORT_WHEEL_SCROLL_STEP);
        this.mainPanel.setBig();
        this.mainPanel.setTitle("Capacity Profile Editor");
        this.mainPanel.setCloseAction(this::closePanel);
        this.mainPanel.setDim(panelWidth, panelHeight);
        this.mainPanel.body().centerX(C.DIM());
        this.mainPanel.body().centerY(C.DIM());
        this.topSection.body().moveX1Y1(this.mainPanel.inner().x1(), this.mainPanel.inner().y1());
        this.contentViewport.body().moveX1Y1(this.mainPanel.inner().x1(), this.mainPanel.inner().y1() + viewportY);
    }

    public void openPanel() {
        this.ensureContentBuilt();
        this.applyDropdownSelection(this.profileDropdown.selected());
        this.inter.activate();
    }
    // Routes through guardDestructiveTransition() below - Exit is a
    // destructive transition per the design spec, same as any other.
    public void closePanel() {
        this.guardDestructiveTransition(() -> this.inter.hide());
    }
    public void togglePanel() {
        if (this.isPanelOpen()) {
            this.closePanel();
        } else {
            this.openPanel();
        }
    }
    public boolean isPanelOpen() {
        return this.inter.isActivated();
    }

    // ---- Shared guard for every destructive transition -------------------

    // The one place "is there something to lose, and what should happen
    // about it" is decided - every destructive transition (New, Duplicate,
    // Live Data, selecting a different profile, Exit) should call this
    // rather than re-deriving its own guard logic. isDiscardPromptPending
    // is checked AND owned here, so callers never need to remember that
    // bookkeeping themselves.
    //
    // Uses this.confirmationBox (our own ThalIPromtButtons, a fork of
    // vanilla IPromtYesNO) rather than VIEW.inters().yesNo (IPromtYesNO
    // itself) specifically because it accepts arbitrary labeled GButt's -
    // "Save"/"Don't Save"/"Cancel" as real button text, not icons standing
    // in for a meaning the player has to infer - while staying a normal,
    // modestly-sized box rather than IPromtScreen's deliberately
    // full-screen treatment. confirmationBox's own onDismissed fires on
    // every exit path (any button, or ESC/right-click) before that
    // button's own click() runs - which is why the pending-flag reset
    // lives there rather than duplicated in each of the three buttons.
    // 1-arg overload for callers with no cancel-specific side effect to run
    // (closePanel() - canceling just means staying on the panel, nothing to
    // revert).
    private void guardDestructiveTransition(Runnable proceedAction) {
        this.guardDestructiveTransition(proceedAction, () -> {
        });
    }

    private void guardDestructiveTransition(Runnable proceedAction, Runnable cancelAction) {
        if (this.isDiscardPromptPending) {
            return;
        }

        if (!this.isDirty) {
            proceedAction.run();
            return;
        }

        this.isDiscardPromptPending = true;

        GButt.ButtPanel saveButton = new GButt.ButtPanel("Save") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.attemptSave(proceedAction);
            }
        };
        GButt.ButtPanel dontSaveButton = new GButt.ButtPanel("Don't Save") {
            @Override
            protected void clickA() {
                proceedAction.run();
            }
        };
        GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
            @Override
            protected void clickA() {
                cancelAction.run();
            }
        };

        this.confirmationBox.activate(
                "You have unsaved changes.",
                () -> ThalCapacityUI.this.isDiscardPromptPending = false,
                true,
                saveButton, dontSaveButton, cancelButton
        );
    }

    // ---- Dropdown-reachable transitions (real implementations) -------------

    // These three are reachable directly from the dropdown (a real stored
    // profile, <New Profile>, <Live Data>) and so are implemented for real
    // now, as part of making the dropdown itself functional - unlike
    // Duplicate/Delete/Activate/Save below, which have no dropdown-triggered
    // path and stay stubs until the button-wiring step.

    private void selectExistingProfile(ThalCapacityProfile chosenProfile) {
        this.selectedStoredProfile = chosenProfile;
        this.scratchProfile.copyFrom(chosenProfile);
        this.isDirty = false;
        this.refreshEditorFieldsFromScratchProfile();
        this.syncLastKnownSelection();
    }

    // isDirty is explicitly false here, not true - matches the convention
    // most document editors use: a genuinely blank "new" document has
    // nothing to lose yet, so switching away or closing shouldn't prompt
    // a save-confirmation until the player has actually typed something.
    // Explicit false rather than simply omitting the assignment - this
    // transition can run right after "Don't Save" discarded a PREVIOUS
    // dirty edit, and without resetting it here that stale true would
    // otherwise linger, incorrectly flagging this brand-new blank profile
    // as already having something to lose.
    private void transitionToNew() {
        this.selectedStoredProfile = null;
        this.scratchProfile.clear();
        this.scratchProfile.displayNameSet("");
        this.scratchProfile.descriptionSet("");
        this.isDirty = false;
        this.refreshEditorFieldsFromScratchProfile();
        // Forces the dropdown's own visual selection to <New Profile> -
        // a no-op when this runs via the dropdown itself (already
        // selected, per ThalGDropDown's own click handling), but necessary
        // when triggered via the New BUTTON instead, which never
        // touches the dropdown's selection on its own.
        this.profileDropdown.setSelected(this.newProfileEntry);
        this.syncLastKnownSelection();
    }

    // populateFromLiveData() already clears scratchProfile's three maps
    // internally before capturing - it does not touch displayName/
    // description, and neither does this method. Live Data isn't a
    // profile itself, so it doesn't make sense for selecting it to touch
    // Name or Description at all - only the value cells refresh; whatever
    // the player had in those two fields (blank, a draft name, whatever
    // they were editing before) stays exactly as it was.
    private void transitionToLiveData() {
        this.selectedStoredProfile = null;
        ThalCapacityProfileManager.instance().populateFromLiveData(this.scratchProfile);
        this.isDirty = true;
        this.refreshEditorFieldsFromScratchProfile();
        // Collapses the dropdown's own visual selection back to
        // <New Profile>, per the design spec - Live Data is a one-time
        // copy source, not something that stays selected afterward, which
        // inherently guards against ever trying to "save over" it.
        this.profileDropdown.setSelected(this.newProfileEntry);
        this.syncLastKnownSelection();
    }

    private void refreshEditorFieldsFromScratchProfile() {
        this.displayNameField.text().clear().add(this.scratchProfile.displayName());
        this.descriptionField.text().clear().add(this.scratchProfile.description());
        this.pushCellsFrom(this.capacityCellsByKey, this.scratchProfile.capacitiesPerSlot());
        this.pushCellsFrom(this.speciesCellsByKey, this.scratchProfile.speciesPopulations());
        this.pushCellsFrom(this.htypeCellsByKey, this.scratchProfile.htypePopulations());
    }

    // Every transition method updates this itself, at its own end, rather
    // than relying on a caller (applyDropdownSelection(), a button's
    // clickA()) to remember to do it - the same lesson the Live Data
    // resync already forced: trusting a caller to keep this in step is
    // exactly the class of bug that already bit once.
    private void syncLastKnownSelection() {
        this.lastKnownSelection = this.profileDropdown.selected();
    }

    // ---- Stub transition/action methods (no dropdown path) ------------------

    // No dropdown entry triggers any of these - each is reachable only
    // from its own button's clickA().

    // Reachable even from State B (selectedStoredProfile already null) -
    // this simply prefixes whatever's currently in the name field, with
    // no precondition, matching the design spec's own explicit choice to
    // let Duplicate do SOMETHING visible rather than nothing when clicked
    // in a state where it's arguably nonsensical. Deliberately does NOT
    // strip an existing "Copy of " prefix or auto-increment against a
    // collision (e.g. "Copy 2 of X") - the spec's own wording is a flat
    // prefix, and any resulting name collision surfaces naturally at
    // Save time via the same collision-check every other save goes
    // through, rather than needing its own separate de-duplication logic
    // here.
    private void transitionToDuplicate() {
        this.selectedStoredProfile = null;
        this.scratchProfile.displayNameSet("Copy of " + this.scratchProfile.displayName());
        this.isDirty = true;
        this.refreshEditorFieldsFromScratchProfile();
        this.profileDropdown.setSelected(this.newProfileEntry);
        this.syncLastKnownSelection();
    }

    // Precondition (selectedStoredProfile != null) enforced by the Delete
    // button's own renAction()-disabled state - this method itself
    // doesn't re-check it, matching how a disabled button is never
    // clickable in the first place.
    private void deleteSelectedProfile() {
        ThalCapacityProfile profileToDelete = this.selectedStoredProfile;
        GButt.ButtPanel deleteButton = new GButt.ButtPanel("Delete") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.performDelete(profileToDelete);
            }
        };
        GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
            @Override
            protected void clickA() {
                // No-op: dismisses without deleting.
            }
        };
        this.confirmationBox.activate(
                "Delete profile \"" + profileToDelete.displayName() + "\"? This cannot be undone.",
                () -> {
                },
                true,
                deleteButton, cancelButton
        );
    }

    private void performDelete(ThalCapacityProfile profileToDelete) {
        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        if (manager == null) {
            log.error("performDelete(): ThalCapacityProfileManager.instance() was null - cannot delete.");
            return;
        }

        ThalCapacityProfileManager.UpdateResult result = manager.updateProfiles(profileToDelete, null);
        if (!result.succeeded()) {
            // Manager's own removeProfile() already dropped profileToDelete
            // from loadedProfiles() regardless of whether the disk deletion
            // itself succeeded - proceeding with the dropdown/editor
            // cleanup below either way keeps the UI consistent with that
            // in-memory state, rather than leaving a stale entry pointing
            // at a profile no longer in loadedProfiles(). Logged so a
            // lingering file on disk (a real, if rare, possibility) isn't
            // silently invisible.
            log.error("performDelete(): disk delete failed for profile \"%s\" - removed from the in-memory list regardless.", profileToDelete.displayName());
        }

        ProfileDropdownEntry entry = this.dropdownEntriesByProfile.remove(profileToDelete);
        if (entry != null) {
            this.profileDropdown.remove(entry);
        }

        // Deliberately NOT transitionToNew() - that method sets
        // isDirty = true (there's a genuinely new, unsaved blank profile
        // to potentially save), whereas per the design spec Delete's own
        // steps end with isDirty = false (nothing was created here, a
        // profile was simply removed - there's nothing new to save).
        // Reusing transitionToNew() as-is would have silently introduced a
        // spurious "unsaved changes" prompt on the very next action.
        this.selectedStoredProfile = null;
        this.scratchProfile.clear();
        this.scratchProfile.displayNameSet("");
        this.scratchProfile.descriptionSet("");
        this.isDirty = false;
        this.refreshEditorFieldsFromScratchProfile();
        this.profileDropdown.setSelected(this.newProfileEntry);
        this.syncLastKnownSelection();
    }

    // Precondition (selectedStoredProfile != null) enforced by the Set
    // Active button's own renAction()-disabled state, same as Delete
    // above. Only the dirty case needs its own confirmation - per the
    // design spec, Activate makes no OTHER change to the editor itself,
    // so a clean (non-dirty) activation has nothing to guard at all.
    private void activateSelectedProfile() {
        if (!this.isDirty) {
            this.performActivate();
            return;
        }

        GButt.ButtPanel saveButton = new GButt.ButtPanel("Save") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.attemptSave(ThalCapacityUI.this::performActivate);
            }
        };
        GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
            @Override
            protected void clickA() {
                // No-op: dismisses without activating.
            }
        };
        this.confirmationBox.activate(
                "Save this profile and make it active?",
                () -> {
                },
                true,
                saveButton, cancelButton
        );
    }

    // this.selectedStoredProfile is read fresh here, not captured earlier -
    // when reached via the dirty/Save path above, attemptSave() will have
    // already updated it to the just-saved object by the time this runs.
    private void performActivate() {
        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        if (manager == null) {
            log.error("performActivate(): ThalCapacityProfileManager.instance() was null - cannot activate.");
            return;
        }

        manager.activeProfileSet(this.selectedStoredProfile);
    }

    // Collision-checks scratchProfile's current name against every OTHER
    // stored profile, then persists via ThalCapacityProfileManager.
    // Continuation-passing (onSuccess run only after a real, successful
    // persist) rather than a synchronous boolean return - the collision-
    // confirmation popup is inherently asynchronous (the player answers
    // on some LATER frame), and a method can't hand back a result for an
    // answer that hasn't happened yet. This mirrors the same pattern
    // already used throughout this file for every popup-driven flow
    // (guardDestructiveTransition's own proceedAction/cancelAction).
    //
    // The cell-validity check runs first, even before the reserved-name
    // check - both are pure UI-side validation needing no Manager at all,
    // but an invalid cell is arguably the more fundamental problem of the
    // two. Genuinely necessary, not redundant with GDouble's own keystroke/
    // range gating: that gating stops an INVALID cell's bad value from
    // ever being committed into scratchProfile in the first place
    // (commitIfChanged() only fires on VALID), but does nothing to stop
    // Save itself from proceeding - without this check, Save would
    // silently persist whatever stale value scratchProfile already held
    // for that key, while the player's current on-screen (red, invalid)
    // number just vanishes with no warning at all.
    //
    // The reserved-name check runs second, right after cell-validity - it's
    // a pure data-validation concern that doesn't need the Manager at all,
    // and it correctly blocks both a brand-new profile AND a rename of an
    // existing one landing on either sentinel name, since it only looks
    // at scratchProfile's own current displayName().
    private void attemptSave(Runnable onSuccess) {
        String invalidLabels = this.invalidCellLabels();
        if (!invalidLabels.isEmpty()) {
            GButt.ButtPanel okayButton = new GButt.ButtPanel("Okay") {
                @Override
                protected void clickA() {
                    // No-op: this message has nothing pending to proceed
                    // with, just dismisses. "Okay" rather than "Cancel"
                    // (unlike every other confirmationBox message in this
                    // file) - there's nothing being cancelled here, just a
                    // plain error being acknowledged.
                }
            };
            this.confirmationBox.activate(
                    "Unable to save because of invalid data: " + invalidLabels + ". Please enter valid data and try again.",
                    () -> {
                    },
                    true,
                    okayButton
            );
            return;
        }

        if (this.isReservedName(this.scratchProfile.displayName())) {
            GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
                @Override
                protected void clickA() {
                    // No-op: this message has nothing pending to proceed
                    // with, just dismisses.
                }
            };
            this.confirmationBox.activate(
                    "Please choose a name other than " + RESERVED_NAME_LIVE_DATA + " or " + RESERVED_NAME_NEW_PROFILE + ", and make sure it isn't blank.",
                    () -> {
                    },
                    true,
                    cancelButton
            );
            return;
        }

        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        if (manager == null) {
            log.error("attemptSave(): ThalCapacityProfileManager.instance() was null - cannot save.");
            return;
        }

        // Warns rather than blocks - saving over the shipped default IS
        // allowed, it just won't survive the next Workshop update, so the
        // player gets told before it happens rather than losing work
        // silently later. Deliberately checked BEFORE the collision check
        // below: overwriting the default profile is always a collision
        // too, and this warning is the more specific, more useful of the
        // two, so it should be the one the player actually sees.
        //
        // The Duplicate button is what makes this prompt genuinely
        // different from every other confirmation in this file - rather
        // than only offering to proceed or abort, it offers a third path
        // that resolves the underlying problem outright, leaving the
        // player on an unsaved copy under a new name that Workshop
        // updates will never touch.
        if (manager.isDefaultProfileName(this.scratchProfile.displayName())) {
            GButt.ButtPanel duplicateButton = new GButt.ButtPanel("Duplicate") {
                @Override
                protected void clickA() {
                    // Abandons this save entirely - onSuccess is never
                    // run, so whatever transition was waiting on this save
                    // (if this came via guardDestructiveTransition's own
                    // Save option) correctly does NOT proceed either. The
                    // player is left holding an unsaved "Copy of ..."
                    // profile, exactly as if they'd clicked the Duplicate
                    // button directly.
                    ThalCapacityUI.this.transitionToDuplicate();
                }
            };
            GButt.ButtPanel continueSavingButton = new GButt.ButtPanel("Continue Saving") {
                @Override
                protected void clickA() {
                    // Skips straight to persisting, deliberately bypassing
                    // the collision check below - overwriting the default
                    // profile is the exact thing the player just
                    // acknowledged, so re-prompting about that same
                    // overwrite would be asking the same question twice.
                    // Passes the existing default profile as
                    // profileToOverwrite (null when no stored default
                    // exists yet, e.g. saving a brand-new profile that
                    // happens to use this name), so the dropdown entry
                    // sync afterward reuses the right entry.
                    ThalCapacityProfile existingDefault = manager.findProfileBySerializedName(ThalCapacityUI.this.scratchProfile.displayName());
                    ThalCapacityProfile profileToOverwrite = existingDefault == ThalCapacityUI.this.selectedStoredProfile ? null : existingDefault;
                    ThalCapacityUI.this.persistScratchProfile(manager, profileToOverwrite, onSuccess);
                }
            };
            GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
                @Override
                protected void clickA() {
                    // No-op: dismisses without saving or duplicating.
                }
            };
            this.confirmationBox.activate(
                    "Warning: Steam Workshop will replace the Default Profile when it updates the mod. You can duplicate it first to safely create your own version.",
                    () -> {
                    },
                    true,
                    duplicateButton, continueSavingButton, cancelButton
            );
            return;
        }

        ThalCapacityProfile collidingProfile = manager.findProfileBySerializedName(this.scratchProfile.displayName());
        boolean isRealCollision = collidingProfile != null && collidingProfile != this.selectedStoredProfile;

        if (isRealCollision) {
            GButt.ButtPanel overwriteButton = new GButt.ButtPanel("Overwrite") {
                @Override
                protected void clickA() {
                    ThalCapacityUI.this.persistScratchProfile(manager, collidingProfile, onSuccess);
                }
            };
            GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
                @Override
                protected void clickA() {
                    // No-op: dismisses without overwriting.
                }
            };
            this.confirmationBox.activate(
                    "A profile named \"" + this.scratchProfile.displayName() + "\" already exists. Overwrite it?",
                    () -> {
                    },
                    true,
                    overwriteButton, cancelButton
            );
        } else {
            this.persistScratchProfile(manager, null, onSuccess);
        }
    }

    // Case-insensitive and trimmed, deliberately stricter than an exact
    // match against the sentinel labels - closes the easy loophole of
    // typing "live data" or "New Profile " (trailing space) to sneak past
    // a literal comparison, which would defeat the whole point of this
    // guard. Blank (after trimming) is treated as reserved too - an empty
    // display name would otherwise sanitize to a filename that's just an
    // extension, with no actual name in it at all.
    private boolean isReservedName(String displayName) {
        String trimmed = displayName.trim();
        return trimmed.isEmpty() || trimmed.equalsIgnoreCase(RESERVED_NAME_NEW_PROFILE) || trimmed.equalsIgnoreCase(RESERVED_NAME_LIVE_DATA);
    }

    // profileToOverwrite is the OTHER, different stored profile the player
    // just confirmed overwriting (null if this save has no such
    // collision) - distinct from selectedStoredProfile, which is whatever
    // this editor was already associated with before this save (passed to
    // updateProfiles() as profileToRemove, covering plain overwrite-self
    // and rename-with-no-collision alike).
    private void persistScratchProfile(ThalCapacityProfileManager manager, ThalCapacityProfile profileToOverwrite, Runnable onSuccess) {
        ThalCapacityProfile previousSelectedProfile = this.selectedStoredProfile;
        ThalCapacityProfileManager.UpdateResult result = manager.updateProfiles(previousSelectedProfile, this.scratchProfile);

        if (!result.succeeded()) {
            log.error("persistScratchProfile(): disk write failed for profile \"%s\".", this.scratchProfile.displayName());
            return;
        }

        this.selectedStoredProfile = result.storedProfile();
        this.isDirty = false;
        this.syncDropdownEntryAfterSave(previousSelectedProfile, profileToOverwrite, result.storedProfile());
        this.syncLastKnownSelection();
        onSuccess.run();
    }

    // Prefers updating whichever OLD profile object the save actually
    // overwrote in the Manager's own list - the colliding profile if a
    // real collision was confirmed, otherwise whatever was previously
    // selected (covers plain overwrite-self and plain rename-with-no-
    // collision). updateFrom() re-points an EXISTING entry rather than
    // adding a new one for these two common cases.
    private void syncDropdownEntryAfterSave(ThalCapacityProfile previousSelectedProfile, ThalCapacityProfile profileToOverwrite, ThalCapacityProfile savedProfile) {
        ThalCapacityProfile profileWhoseEntryToReuse = profileToOverwrite != null ? profileToOverwrite : previousSelectedProfile;
        ProfileDropdownEntry entry = profileWhoseEntryToReuse == null ? null : this.dropdownEntriesByProfile.remove(profileWhoseEntryToReuse);

        if (entry != null) {
            entry.updateFrom(savedProfile);
        } else {
            entry = ProfileDropdownEntry.forStoredProfile(savedProfile);
            this.profileDropdown.add(entry);
            this.profileDropdown.init();
        }
        this.dropdownEntriesByProfile.put(savedProfile, entry);

        // A real collision (overwriting a different existing profile) AND
        // a rename away from a previously-selected profile means TWO old
        // profiles are involved, not one - profileWhoseEntryToReuse above
        // already absorbed the collision target's own entry; the
        // previously-selected profile's own entry, now representing a
        // profile just deleted from disk by updateProfiles()'s own remove
        // step, still needs its own separate cleanup. Now closable thanks
        // to ThalGDropDown.remove() - previously an unresolved TODO here,
        // back when this could only reach vanilla GDropDown's confirmed
        // add()/selected()/setSelected()/init().
        if (profileToOverwrite != null && previousSelectedProfile != null && previousSelectedProfile != profileToOverwrite) {
            ProfileDropdownEntry orphanedEntry = this.dropdownEntriesByProfile.remove(previousSelectedProfile);
            if (orphanedEntry != null) {
                this.profileDropdown.remove(orphanedEntry);
            }
        }

        this.profileDropdown.setSelected(entry);
    }


    // <New Profile> is added first (and so becomes the default selection
    // per ThalGDropDown's own add()-auto-selects-first behavior, inherited
    // unchanged from vanilla GDropDown) - a fresh, nothing-to-lose
    // starting state is the more sensible default than landing on Live
    // Data or an arbitrary stored profile.
    //
    // CONFIRMED (against ScriptLoad.java/ScriptEngine.java's own source):
    // by the time createInstance() ever runs - and so by the time this
    // method ever runs, since buildUI() is only called from there -
    // GAME.java's own constructor has already run this.view = new
    // VIEW(this) before this.script.init(null) (which is what triggers
    // every SCRIPT's own createInstance()). VIEW/UI/C are guaranteed to
    // exist here; that half of the original crash's uncertainty is fully
    // resolved.
    //
    // Only the two sentinels are added here - real stored profiles are
    // deliberately NOT populated in this method anymore. See
    // syncStoredProfilesIntoDropdown()'s own comment for why: this method
    // runs DURING createInstance(), and ThalCapacityProfileManager's own
    // createInstance() is not guaranteed to have already run at that exact
    // point - confirmed the hard way, pre-existing profiles silently never
    // appearing in the dropdown after restarting the game (this class's
    // own createInstance() happened to run first that time, so
    // ThalCapacityProfileManager.instance() was still null here).
    private ThalGDropDown<ProfileDropdownEntry> buildProfileDropdown(int dropdownWidth) {
        // This panel shows itself via VIEW.inters().manager (a global
        // overlay, not tied to whichever view happens to be current) -
        // the dropdown's own popup has to be told to use that SAME
        // manager explicitly, or it registers with VIEW.current().uiManager
        // by default (ThalGDropDown's own vanilla-compatible fallback),
        // which isn't the manager actually driving frames for this panel's
        // own overlay context. Without this call, the popup silently
        // exists but never renders or receives clicks - confirmed the hard
        // way the first time this was tested in-game.
        // 160 was previously just the "Profile" title portion's own width
        // (a separate concept from the box's overall width, now removed
        // entirely) - it becomes the WHOLE box's fixed width here instead.
        // Kept as a starting guess rather than picked freshly, but expect
        // to retune visually now that the box shows only the selected
        // entry's own name rather than a title plus a name.
        ThalGDropDown<ProfileDropdownEntry> dropdown = new ThalGDropDown<ProfileDropdownEntry>(dropdownWidth)
                .expansionManager(VIEW.inters().manager)
                // Popup's own minimum column width - kept independent of
                // the closed box's own fixed width above, since the popup
                // list and the closed box no longer share a single "w"
                // calculation the way they used to (see ThalGDropDown's
                // own comments on this decoupling).
                .minExpansionWidth(dropdownWidth);
        dropdown.add(this.newProfileEntry);
        dropdown.add(this.liveDataEntry);
        dropdown.init();
        return dropdown;
    }
    private ThalGInput buildTextField(CharSequence placeholderText, int bufferCapacity, Consumer<String> committer) {
        StringInputSprite sprite = new StringInputSprite(bufferCapacity, UI.FONT().S) {
            @Override
            protected void change() {
                committer.accept(this.text().toString());
                ThalCapacityUI.this.isDirty = true;
            }
        }.placeHolder(placeholderText);
        return new ThalGInput(sprite);
    }
    private GButt.ButtPanel buildStubButton(CharSequence label) {
        return new GButt.ButtPanel(label) {
            @Override
            protected void clickA() {
                log.info("%s clicked - stub, no-op in this pass.", label);
            }
        };
    }

    // New/Duplicate route through the same guardDestructiveTransition()
    // every dropdown-triggered destructive transition already uses -
    // clicking either while isDirty is true risks losing unsaved work the
    // exact same way switching the dropdown's own selection would.
    private GButt.ButtPanel buildNewButton() {
        return new GButt.ButtPanel("New") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.guardDestructiveTransition(ThalCapacityUI.this::transitionToNew);
            }
        };
    }

    private GButt.ButtPanel buildDuplicateButton() {
        return new GButt.ButtPanel("Duplicate") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.guardDestructiveTransition(ThalCapacityUI.this::transitionToDuplicate);
            }
        };
    }

    // Save does NOT route through guardDestructiveTransition - there's
    // nothing to lose by clicking Save itself (that's the whole point of
    // it), unlike New/Duplicate/Delete which discard the current
    // scratch state. onSuccess is a no-op here since nothing else needs
    // to happen after a direct Save click succeeds, unlike Save's OTHER
    // role as the "Save" choice inside guardDestructiveTransition, where
    // onSuccess is whatever transition was actually pending.
    private GButt.ButtPanel buildSaveButton() {
        return new GButt.ButtPanel("Save") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.attemptSave(() -> {
                });
            }
        };
    }

    // Delete and Activate are both only meaningful against a real stored
    // profile - renAction() disables each whenever selectedStoredProfile
    // is null, the same activeSet()/renAction() pattern already
    // established for the settlement top-bar toggle button.
    private GButt.ButtPanel buildDeleteButton() {
        return new GButt.ButtPanel("Delete") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.deleteSelectedProfile();
            }
            @Override
            protected void renAction() {
                this.activeSet(ThalCapacityUI.this.selectedStoredProfile != null);
            }
        };
    }

    private GButt.ButtPanel buildActivateButton() {
        return new GButt.ButtPanel("Set Active") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.activateSelectedProfile();
            }
            @Override
            protected void renAction() {
                // Disabled both when nothing's selected AND when the
                // currently-viewed profile already IS the active one -
                // re-activating it would be a meaningless no-op click.
                // Reference equality (==), not a name comparison, matches
                // the same identity-based convention already used
                // throughout this file (profileToOverwrite != previousSelectedProfile,
                // etc.) - and it's reliable here specifically because
                // performActivate() always calls activeProfileSet() with
                // this exact selectedStoredProfile reference, and
                // ThalCapacityProfileManager.storeProfile() already
                // re-points activeProfile to the fresh object whenever the
                // profile being saved happens to be the active one - so
                // this stays correctly disabled even right after editing
                // and saving the already-active profile.
                ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
                boolean isViewingActiveProfile = manager != null && ThalCapacityUI.this.selectedStoredProfile == manager.activeProfile();
                this.activeSet(ThalCapacityUI.this.selectedStoredProfile != null && !isViewingActiveProfile);
            }
        };
    }

    // Load and Rename from the original CRUD list are both gone here -
    // Load has no distinct meaning once dropdown selection already does
    // that job, and Rename is now an implicit consequence of Save
    // detecting a changed name rather than its own separate action.
    private void buildTopSection() {
        GuiSection managementRow = new GuiSection();
        managementRow.addRightC(0, this.profileDropdown);
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildNewButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildDuplicateButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildSaveButton());
        //managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildStubButton("Merge")); // TODO
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildDeleteButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildActivateButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.activeProfileLabel);
        GuiSection metadataRow = new GuiSection();
        metadataRow.addRightC(0, this.displayNameField);
        metadataRow.addRightC(HORIZONTAL_INTER_PADDING, this.descriptionField);
        this.topSection.add(managementRow);
        this.topSection.addDown(HORIZONTAL_INTER_PADDING, metadataRow);
    }
    private GText sectionHeader(CharSequence title) {
        return new GText(UI.FONT().M, title).lablifySub();
    }

    private void ensureContentBuilt() {
        if (this.isContentBuilt) {
            return;
        }
        this.contentViewport.contentAdd(this.sectionHeader("Capacity Per Slot"));
        this.contentViewport.contentAdd(new GText(UI.FONT().S, CAPACITY_EXPLANATION).normalify(), HEADER_TABLE_MARGIN_BOTTOM);
        // hypotheticalCapacityPerSlot() is a dense, always-available formula
        // result - unlike live/profile data, it never depends on anything
        // that could change frame-to-frame (mod data and formulas load
        // once at launch), so it's computed ONCE per blueprint here rather
        // than via a supplier re-queried every render.
        //
        // GUESS, based on established naming convention: this exact method
        // name is not directly confirmed in this file's own source - only
        // liveCapacityPerSlot() has been directly confirmed (used already
        // in ThalCapacityProfileManager). "hypothetical" mirroring "live"
        // matches the three-tier live/profile/hypothetical naming already
        // established elsewhere in this mod's own RoomService extensions.
        // First thing to check if this doesn't compile.
        this.capacityCellsByKey = this.buildRows(ThalRoomServiceRegistry.roomServicesSorted(), CAPACITY_MINIMUM, CAPACITY_MAXIMUM, CAPACITY_DECIMAL_PLACES,
                service -> service.room().key,
                service -> service.room().info.name,
                service -> service.hypotheticalCapacityPerSlot(),
                this.scratchProfile::capacityPerSlotSet,
                this.scratchProfile::capacityPerSlotRemove);
        this.contentViewport.contentAdd(this.sectionHeader("Species Population"), HEADER_TABLE_MARGIN_TOP);
        this.contentViewport.contentAdd(new GText(UI.FONT().S, SPECIES_EXPLANATION).normalify(), HEADER_TABLE_MARGIN_BOTTOM);
        // Flat 0.0 - a settlement starts with no population at all; there
        // is no dense formula to fall back to the way capacity has one.
        this.speciesCellsByKey = this.buildRows(RACES.all(), POPULATION_MINIMUM, POPULATION_MAXIMUM, POPULATION_DECIMAL_PLACES,
                race -> race.key,
                race -> race.info.names,
                race -> 0.0,
                this.scratchProfile::speciesPopulationSet,
                this.scratchProfile::speciesPopulationRemove);
        this.contentViewport.contentAdd(this.sectionHeader("Subject Type Population"), HEADER_TABLE_MARGIN_TOP);
        this.contentViewport.contentAdd(new GText(UI.FONT().S, HTYPE_EXPLANATION).normalify(), HEADER_TABLE_MARGIN_BOTTOM);
        this.htypeCellsByKey = this.buildRows(HTYPES.ALL(), POPULATION_MINIMUM, POPULATION_MAXIMUM, POPULATION_DECIMAL_PLACES,
                hType -> hType.key,
                hType -> hType.name,
                hType -> 0.0,
                this.scratchProfile::htypePopulationSet,
                this.scratchProfile::htypePopulationRemove);
        this.isContentBuilt = true;
    }
    private <T> Map<String, ThalGLabeledValue> buildRows(Iterable<T> sourceItems, double minimumValue, double maximumValue, int decimalPlaces,
                                                         Function<T, String> keyExtractor,
                                                         Function<T, CharSequence> labelExtractor,
                                                         ToDoubleFunction<T> defaultValueExtractor,
                                                         BiConsumer<String, Double> valueCommitter,
                                                         Consumer<String> valueReverter) {
        List<T> items = new ArrayList<>();
        for (T item : sourceItems) {
            items.add(item);
        }
        Map<String, ThalGLabeledValue> cellsByKey = new HashMap<>();
        for (int rowStart = 0; rowStart < items.size(); rowStart += CELLS_PER_ROW) {
            GuiSection row = new GuiSection();
            int rowEnd = Math.min(rowStart + CELLS_PER_ROW, items.size());
            for (int i = rowStart; i < rowEnd; i++) {
                T item = items.get(i);
                String key = keyExtractor.apply(item);
                // Computed once, here, rather than inside the supplier
                // lambda below - see ensureContentBuilt()'s own comment on
                // why re-querying this every render would be pointless
                // work for a value that can never change.
                double defaultValue = defaultValueExtractor.applyAsDouble(item);
                ThalGLabeledValue cell = new ThalGLabeledValue(
                        minimumValue,
                        maximumValue,
                        decimalPlaces,
                        LABEL_COLUMN_WIDTH,
                        () -> labelExtractor.apply(item),
                        () -> defaultValue,
                        committedValue -> {
                            valueCommitter.accept(key, committedValue);
                            this.isDirty = true;
                        },
                        () -> {
                            valueReverter.accept(key);
                            this.isDirty = true;
                        }
                );
                this.allLabeledValues.add(cell);
                cellsByKey.put(key, cell);
                row.addRightC(i == rowStart ? 0 : CELL_HORIZONTAL_MARGIN, cell);
            }
            // Only the FIRST row-group of a table gets the extra margin -
            // that's the one sitting directly below this table's own
            // section header. Every subsequent row-group stays tight
            // against the one above it, same as before.
            int topMargin = rowStart == 0 ? HEADER_TABLE_MARGIN_BOTTOM : 0;
            this.contentViewport.contentAdd(row, topMargin);
        }
        return cellsByKey;
    }
    // 50 is a rough eyeball figure, not derived from anything precise -
    // just enough room for a handful of short blueprint/species names
    // before falling back, given confirmationBox's own fixed 800x400 box
    // could otherwise overflow if many cells were invalid at once.
    private static final int INVALID_LABELS_MAX_LENGTH = 50;
    private static final String INVALID_LABELS_FALLBACK = "check red values";

    // Empty string means every cell is currently valid - the one thing a
    // caller needs to check. Otherwise joins each invalid cell's own
    // DISPLAY name (not its key) in natural build order - the same order
    // the player would scroll past them in the panel (capacity cells
    // first, alphabetical among themselves per roomServicesSorted();
    // species/HTYPE after, in whatever order RACES.all()/HTYPES.ALL()
    // happen to provide) - no extra sort added on top, since a long list
    // here is expected to be rare in practice.
    private String invalidCellLabels() {
        StringBuilder builder = new StringBuilder();
        for (ThalGLabeledValue cell : this.allLabeledValues) {
            if (!cell.isValid()) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(cell.labelText());
            }
        }
        return builder.length() > INVALID_LABELS_MAX_LENGTH ? INVALID_LABELS_FALLBACK : builder.toString();
    }

    // Dispatches to whichever of the three real transitions above matches
    // selection's kind. Each transition method now handles its own
    // lastKnownSelection sync (via syncLastKnownSelection()) at its own
    // end - not duplicated here - since that needs to hold true
    // regardless of whether a transition was reached via the dropdown or
    // via a button's clickA().
    private void applyDropdownSelection(ProfileDropdownEntry selection) {
        switch (selection.kind) {
            case STORED -> this.selectExistingProfile(selection.profile);
            case NEW_PROFILE -> this.transitionToNew();
            case LIVE_DATA -> this.transitionToLiveData();
        }
    }

    private void pushCellsFrom(Map<String, ThalGLabeledValue> cellsByKey, Map<String, Double> values) {
        for (Map.Entry<String, ThalGLabeledValue> entry : cellsByKey.entrySet()) {
            entry.getValue().existingValueSet(values.get(entry.getKey()));
        }
    }

    private void checkForSelectionChange() {
        if (this.isDiscardPromptPending) {
            return;
        }
        ProfileDropdownEntry currentSelection = this.profileDropdown.selected();
        if (currentSelection != this.lastKnownSelection) {
            this.handlePendingSelectionChange(currentSelection);
        }
    }

    // "None" covers both a genuine absence of an active profile AND the
    // (practically unreachable, by the time this label is ever visibly
    // rendered) manager == null case - the panel can only be open at all
    // once update() has fired at least once, by which point the Manager
    // is guaranteed to exist; no need to distinguish the two here.
    //
    // KNOWN LIMITATION: no max-width/truncation set on this label, unlike
    // GLabeledValue's own internal label (which does call setMaxWidth()).
    // A sufficiently long active profile name could visually overflow past
    // the panel's right edge - not fixed here, since the right truncation
    // width depends on how much horizontal room the OTHER buttons in this
    // same row actually leave, which wasn't measured precisely.
    private void refreshActiveProfileLabel() {
        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        ThalCapacityProfile activeProfile = manager == null ? null : manager.activeProfile();
        String activeProfileName = activeProfile == null ? "None" : activeProfile.displayName();
        this.activeProfileLabel.clear().add("Active: " + activeProfileName);
    }

    // ThalGDropDown has already visually applied newSelection by the time this
    // runs - there's no way to intercept it beforehand - so the cancel
    // path explicitly reverts via setSelected() rather than just declining
    // to act. previousSelection is captured before the guard runs, since
    // lastKnownSelection itself isn't updated until applyDropdownSelection()
    // actually proceeds.
    private void handlePendingSelectionChange(ProfileDropdownEntry newSelection) {
        ProfileDropdownEntry previousSelection = this.lastKnownSelection;
        this.guardDestructiveTransition(
                () -> this.applyDropdownSelection(newSelection),
                () -> this.profileDropdown.setSelected(previousSelection)
        );
    }


    private void injectToggleButtonIntoSettlementTopBar() {
        try {
            InterManager settlementManager = VIEW.s().uiManager;
            Object intersCollection = ThalReflectionUtil.declaredFieldValue("inters", settlementManager).orElse(null);
            if (!(intersCollection instanceof Iterable<?> interrupters)) {
                log.error("injectToggleButtonIntoSettlementTopBar(): could not read InterManager's own 'inters' "
                        + "field as an Iterable - toggle button was not added. This is currently the only way "
                        + "to open the Capacity Profiles panel.");
                return;
            }
            Object panelTop = null;
            for (Object candidate : interrupters) {
                if (candidate != null && candidate.getClass().getSimpleName().equals("UIPanelTop")) {
                    panelTop = candidate;
                    break;
                }
            }
            if (panelTop == null) {
                log.error("injectToggleButtonIntoSettlementTopBar(): searched every Interrupter registered to "
                        + "the settlement view's own InterManager, found none of simple type UIPanelTop - "
                        + "toggle button was not added.");
                return;
            }
            GuiSection right = ThalReflectionUtil.<GuiSection>declaredFieldValue("right", panelTop).orElse(null);
            if (right == null) {
                log.error("injectToggleButtonIntoSettlementTopBar(): found UIPanelTop, but its 'right' field "
                        + "was not - Jake's own field name may have changed since the modding guide was written.");
                return;
            }
            GButt.ButtPanel toggleButton = new GButt.ButtPanel((SPRITE) null) {
                @Override
                protected void clickA() {
                    ThalCapacityUI.this.togglePanel();
                }
                @Override
                protected void renAction() {
                    this.selectedSet(ThalCapacityUI.this.isPanelOpen());
                }
            };
            toggleButton.icon(UI.icons().s.storage);
            toggleButton.hoverInfoSet("Capacity Profiles");
            right.addRelBody(8, DIR.W, toggleButton);
            log.info("injectToggleButtonIntoSettlementTopBar(): toggle button added successfully.");
        } catch (Exception exception) {
            log.error("injectToggleButtonIntoSettlementTopBar(): unexpected failure, toggle button was not added: %s", exception.toString());
        }
    }

    // Deliberately unaware of ThalCapacityProfileManager beyond holding a
    // reference - all presentation logic (rendering, sizing) lives here in
    // the UI layer, per the established layering: UI knows about Manager,
    // Manager knows about Profile, Profile knows only itself.
    //
    // Kind is explicit rather than inferred from profile == null - that
    // inference broke the moment a SECOND null-backed sentinel
    // (<New Profile>) needed to coexist with the original one (<Live
    // Data>); a single boolean can't distinguish three states.
    // Implements ThalDropDownEntry so ThalGDropDown can constrain this
    // entry's own available width when rendering it as the selected entry
    // in the closed box - see ThalDropDownEntry's own header comment for
    // why this can't be done from ThalGDropDown's side at all (RECTANGLEE,
    // what CLICKABLE.body() narrows down to, has no resize capability;
    // only this.body directly, reachable only from inside this class,
    // does).
    private static final class ProfileDropdownEntry extends CLICKABLE.ClickableAbs implements ThalDropDownEntry {

        private enum Kind {
            STORED,
            NEW_PROFILE,
            LIVE_DATA
        }

        private ThalCapacityProfile profile;
        private final Kind kind;
        private final GText label;

        private ProfileDropdownEntry(CharSequence displayText, ThalCapacityProfile profile, Kind kind) {
            this.profile = profile;
            this.kind = kind;
            this.label = new GText(UI.FONT().S, displayText).lablify();
            this.label.adjustWidth();
            this.body.setDim(this.label.width() + 8, this.label.height() + 4);
        }

        // profile is always null for a sentinel - kind alone identifies it.
        private static ProfileDropdownEntry sentinel(CharSequence displayText, Kind kind) {
            return new ProfileDropdownEntry(displayText, null, kind);
        }

        // displayText is always profile's own displayName() here, never
        // supplied separately - removes the chance of the two silently
        // drifting apart at a call site.
        private static ProfileDropdownEntry forStoredProfile(ThalCapacityProfile profile) {
            return new ProfileDropdownEntry(profile.displayName(), profile, Kind.STORED);
        }

        // Re-points this SAME entry at a freshly-saved profile object,
        // rather than removing and re-adding a new one - avoids an
        // unnecessary full popup rebuild (ThalGDropDown.remove() does call
        // init() internally) for what's fundamentally just a label/data
        // update, not a real structural change to the entry list. Only
        // STORED entries are ever updated this way; the two sentinels
        // never call this.
        //
        // RESOLVED (see render()'s own comment): a rename to a
        // significantly longer name no longer visually clips - this
        // entry's own body may still not grow to match (that part of the
        // original limitation is unchanged), but render() now truncates
        // against whatever width the body actually has, rather than
        // drawing the full, unclipped text regardless.
        private void updateFrom(ThalCapacityProfile updatedProfile) {
            this.profile = updatedProfile;
            this.label.clear().add(updatedProfile.displayName());
        }

        // The actual fix for the right-alignment/overflow bug: this.body,
        // accessed directly here rather than through the narrower
        // RECTANGLEE the CLICKABLE interface exposes, is the concrete Rec
        // field - the only thing in this whole relationship that can
        // genuinely resize. ThalGDropDown.render() calls this instead of
        // trying (and, before this fix, failing) to constrain width itself
        // from outside via a pair of moveX1()/moveX2() calls that were
        // both pure translations, not resizes.
        @Override
        public void availableWidthSet(int width) {
            this.body.setWidth(width);
        }

        // Uses the 4-arg (X1, X2, Y1, Y2) GText overload, not the plain
        // (x, y) one this used before - that overload internally clips to
        // this.label's own maxWidth (confirmed elsewhere in this project:
        // it substitutes X1 + maxWidth for whatever X2 is actually passed
        // in), which is what actually makes truncation happen; the old
        // 2-argument call drew the full text regardless of body width.
        // maxWidth is set fresh from this.body.width() every render,
        // rather than once at construction, specifically because body
        // width now genuinely varies by context: this entry's own natural
        // (popup-row) width when shown in the dropdown's expansion list,
        // versus a narrower width ThalGDropDown temporarily imposes when
        // rendering the selected entry inside the closed box (see its own
        // render() comment). Re-deriving here means this entry doesn't
        // need to know which context it's currently in - it just always
        // truncates to whatever space it's actually been given.

        @Override
        protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
            this.label.setMaxWidth(this.body.width() - 8);
            this.label.render(r, this.body.x1() + 4, this.body.x2() - 4, this.body.y1() + 2, this.body.y2());
        }
    }

    private final class Inter extends Interrupter {
        @Override
        protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
            ThalCapacityUI.this.topSection.hover(mCoo);
            ThalCapacityUI.this.contentViewport.hover(mCoo);
            return true;
        }
        @Override
        protected void mouseClick(MButt button) {
            if (button == MButt.LEFT) {
                ThalCapacityUI.this.topSection.click();
                ThalCapacityUI.this.contentViewport.click();
            } else if (button == MButt.RIGHT) {
                ThalCapacityUI.this.closePanel();
            }
        }
        @Override
        protected void hoverTimer(GBox text) {
            ThalCapacityUI.this.topSection.hoverInfoGet(text);
            ThalCapacityUI.this.contentViewport.hoverInfoGet(text);
        }
        @Override
        protected boolean update(float ds) {
            GAME.SPEED.tmpPause();
            return false;
        }
        @Override
        protected boolean render(Renderer r, float ds) {
            ThalCapacityUI.this.checkForSelectionChange();
            ThalCapacityUI.this.refreshActiveProfileLabel();
            ThalCapacityUI.this.mainPanel.render(r, ds);
            ThalCapacityUI.this.topSection.render(r, ds);
            ThalCapacityUI.this.contentViewport.render(r, ds);
            return false;
        }
        @Override
        public void hide() {
            super.hide();
        }
        private void activate() {
            super.show(VIEW.inters().manager);
        }
    }

    @Override
    public CharSequence name() {
        return SCRIPT_NAME;
    }
    @Override
    public CharSequence desc() {
        return SCRIPT_DESCRIPTION;
    }
    @Override
    public boolean isSelectable() {
        return false;
    }
    @Override
    public boolean forceInit() {
        return true;
    }
    @Override
    public void initBeforeGameCreated() {
    }
    @Override
    public void initBeforeGameInited() {
    }
    @Override
    public SCRIPT.SCRIPT_INSTANCE createInstance() {
        ThalCapacityUI created = new ThalCapacityUI();
        created.buildUI();
        instance = created;
        created.injectToggleButtonIntoSettlementTopBar();
        return created;
    }
    @Override
    public void update(double deltaSeconds) {
        if (!this.hasSyncedStoredProfiles) {
            this.hasSyncedStoredProfiles = true;
            this.syncStoredProfilesIntoDropdown();
        }
    }

    // Deferred here rather than done eagerly inside buildUI()/
    // buildProfileDropdown() (which run during createInstance() itself) -
    // confirmed the hard way: ThalCapacityProfileManager's own
    // createInstance() is NOT guaranteed to run before this class's own,
    // and when it happens not to, ThalCapacityProfileManager.instance()
    // is still null at buildProfileDropdown()'s own construction time,
    // silently skipping every pre-existing profile with no error at all -
    // exactly the "profiles on disk don't show up after restarting the
    // game" symptom this fixes.
    //
    // By the time ANY script's update() fires for the first time,
    // ScriptEngine has already finished calling createInstance() on EVERY
    // loaded script - confirmed from ScriptEngine.java's own source:
    // init(GAME) runs createInstance() on every script synchronously, in
    // a single loop, strictly before GAME's own update loop (and so any
    // script's own update()) ever begins. ThalCapacityProfileManager.
    // instance() is therefore guaranteed non-null here, regardless of
    // which script's createInstance() happened to run first.
    private void syncStoredProfilesIntoDropdown() {
        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        if (manager == null) {
            log.error("syncStoredProfilesIntoDropdown(): ThalCapacityProfileManager.instance() was still null on "
                    + "this SCRIPT_INSTANCE's own first update() call - this should be structurally impossible "
                    + "given ScriptEngine's own confirmed lifecycle. Stored profiles will not appear in the "
                    + "dropdown this session.");
            return;
        }

        for (ThalCapacityProfile profile : manager.loadedProfiles()) {
            ProfileDropdownEntry entry = ProfileDropdownEntry.forStoredProfile(profile);
            this.profileDropdown.add(entry);
            this.dropdownEntriesByProfile.put(profile, entry);
        }
        this.profileDropdown.init();

        // Overrides <New Profile>'s own auto-select-first default (set
        // back in buildProfileDropdown(), before any real profile even
        // existed to select instead) - without this, the panel would
        // default to a blank new profile on every single startup, even
        // when the player already has a real active profile, purely as
        // an unintended side effect of the sentinels being added first.
        // openPanel()'s own existing logic (applyDropdownSelection(this.
        // profileDropdown.selected())) already re-reads whatever's
        // selected fresh each time the panel opens, so setting it here -
        // well before the player could ever click the toggle button open
        // it - is sufficient; no further wiring needed downstream.
        ThalCapacityProfile activeProfile = manager.activeProfile();
        if (activeProfile != null) {
            ProfileDropdownEntry activeEntry = this.dropdownEntriesByProfile.get(activeProfile);
            if (activeEntry != null) {
                this.profileDropdown.setSelected(activeEntry);
            }
        }
    }
    @Override
    public void save(FilePutter file) {
    }
    @Override
    public void load(FileGetter file) throws IOException {
    }
}