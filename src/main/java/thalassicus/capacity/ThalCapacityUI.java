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
    public static final int PROFILE_NAME_MAX_CHARACTERS = 30;
    public static final int DESCRIPTION_MIN_CHARACTERS = 10;

    private static final ThalsLogger log = new ThalsLogger(
            ThalsLogger.INFO,
            System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalCapacityUI.log"
    );
    private static final CharSequence SCRIPT_NAME = "Thal Capacity UI";
    private static final CharSequence SCRIPT_DESCRIPTION = "Internal utility script for Service Estimate Fix. Displays the Capacity Profiles panel. Not a gameplay-affecting script.";
    private static final int LABEL_COLUMN_WIDTH = 180;
    private static final int CELLS_PER_ROW = 4;
    private static final int CELL_HORIZONTAL_MARGIN = 16;
    private static final int HORIZONTAL_INTER_PADDING = 8;
    private static final int SECTION_MARGIN = 16;
    private static final double PANEL_WIDTH_FRACTION = 0.6;
    private static final double PANEL_HEIGHT_FRACTION = 0.6;
    private static final int VIEWPORT_WHEEL_SCROLL_STEP = 40;
    private static final double CAPACITY_MINIMUM = 1.0;
    private static final double CAPACITY_MAXIMUM = 99999.0;
    private static final double POPULATION_MINIMUM = 0.0;
    private static final double POPULATION_MAXIMUM = 999999.0;
    private static final int CAPACITY_DECIMAL_PLACES = 2;
    private static final int HEADER_TABLE_MARGIN_TOP = 32;
    private static final int HEADER_TABLE_MARGIN_BOTTOM = 8;
    private static final String CAPACITY_EXPLANATION = "Multiplied by slot count to get room capacity.";
    private static final String SPECIES_EXPLANATION = "Information about city. Not used for calculations.";
    private static final String HTYPE_EXPLANATION = "Information about city. Not used for calculations.";

    // Whole numbers only: 0 decimal places also blocks typing a decimal
    // point in these cells outright, not just rounding one away afterward.
    private static final int POPULATION_DECIMAL_PLACES = 0;

    // Single definition shared by the two dropdown sentinels' labels and
    // the reserved-name guard in attemptSave(), so the two can't drift
    // apart. The chevrons are a deliberate marker: an ordinary "New
    // Profile" (no chevrons) is a perfectly valid name a player can save
    // under - only the exact chevron-wrapped string is reserved.
    private static final String RESERVED_NAME_NEW_PROFILE = "<New Profile>";
    private static final String RESERVED_NAME_LIVE_DATA = "<Live Data>";

    private static final int INVALID_LABELS_MAX_LENGTH = 50;
    private static final String INVALID_LABELS_FALLBACK = "check red values";
    private static ThalCapacityUI instance;
    private final GuiSection topSection = new GuiSection();

    // Keyed by object identity - ThalCapacityProfile has no equals()/
    // hashCode() override, so the HashMap is already comparing by
    // identity, not value. That's what lets Save find and update an
    // existing entry after a rename/overwrite, and Delete find which
    // entry to remove: ThalGDropDown has no way to look up an entry by
    // anything other than the identity of the entry object itself, so
    // this map is what answers "which entry represents THIS profile."
    private final Map<ThalCapacityProfile, ProfileDropdownEntry> dropdownEntriesByProfile = new HashMap<>();
    private final List<ThalGLabeledValue> allLabeledValues = new ArrayList<>();
    private final ThalCapacityProfile scratchProfile = ThalCapacityProfile.blank("", "");
    private GPanel mainPanel;
    private ThalGSlidableViewportVertical contentViewport;
    private PanelInterrupter panelInterrupter;

    // A single, persistent instance reused across every activation, the
    // same way VIEW.inters().yesNo/.fullScreen are also long-lived shared
    // fields rather than recreated per popup - Interrupter-family objects
    // are meant to be constructed once and shown repeatedly.
    private ThalIPromtButtons confirmationBox;

    // No fixed-width wrapper, unlike the table's own label column - that
    // wrapper solves a different problem (a stable width so sibling grid
    // cells don't shift as content changes). This label is the last
    // element in its row with nothing after it depending on a stable
    // width, so letting it resize naturally to fit its own changing
    // "Active: X" text is correct here, not an inconsistency.
    private GText activeProfileLabel;
    private ThalGDropDown<ProfileDropdownEntry> profileDropdown;

    // Permanent, built once - referenced by identity wherever the
    // dropdown's selection needs to be forced onto one of these specific
    // sentinels. Same category as RACES/HTYPES-style permanent objects,
    // not something rebuilt per session.
    private ProfileDropdownEntry newProfileEntry;
    private ProfileDropdownEntry liveDataEntry;
    private ThalGInput displayNameField;
    private ThalGInput descriptionField;
    private Map<String, ThalGLabeledValue> capacityCellsByKey;
    private Map<String, ThalGLabeledValue> speciesCellsByKey;
    private Map<String, ThalGLabeledValue> htypeCellsByKey;

    // The real state per the design spec: null means the editor isn't
    // associated with any stored profile (New/Duplicate/Live Data). Set
    // by every dropdown-reachable transition.
    private ThalCapacityProfile selectedStoredProfile;

    private boolean isDirty = false;
    private ProfileDropdownEntry lastKnownSelection;
    private boolean isDiscardPromptPending = false;
    private boolean isContentBuilt = false;

    // Guards syncStoredProfilesIntoDropdown() - runs exactly once, on
    // this SCRIPT_INSTANCE's own first update() call. See that method's
    // own comment for why it can't run any earlier.
    private boolean hasSyncedStoredProfiles = false;

    //
    // Section: Public Methods
    //


    // Deliberately empty. ThalCapacityUI is reflectively instantiated twice,
    // in two different roles, through this SAME no-arg constructor: once by
    // menu.ScRandom$Scripts, which enumerates every SCRIPT implementor at
    // MAIN MENU load purely to read name()/desc()/isSelectable() for the
    // menu's own script-picker screen, and once for real via createInstance()
    // once a player actually starts a session. Confirmed the hard way, not
    // by inference: an earlier version of this class reached a VIEW call
    // through code this constructor invoked and crashed the entire game at
    // menu load, before any session - and therefore VIEW - exists yet (see
    // buildUI()'s own comment for that incident and the fix). Everything
    // that makes this an actual usable panel - every field that needs VIEW
    // or UI to already exist - is deferred to buildUI() below, called only
    // from createInstance(), never from this constructor.
    public ThalCapacityUI() {
    }

    public static ThalCapacityUI instance() {
        return instance;
    }

    public void openPanel() {
        this.ensureContentBuilt();
        this.applyDropdownSelection(this.profileDropdown.selected());
        this.panelInterrupter.activate();
    }

    // Routes through guardDestructiveTransition() below - Exit is a
    // destructive transition per the design spec, same as any other.
    public void closePanel() {
        this.guardDestructiveTransition(() -> this.panelInterrupter.hide());
    }

    //
    // Private Methods
    //

    public void togglePanel() {
        if (this.isPanelOpen()) {
            this.closePanel();
        } else {
            this.openPanel();
        }
    }

    public boolean isPanelOpen() {
        return this.panelInterrupter.isActivated();
    }

    //
    // Section: Build UI
    //




    // Must run after a game session exists. Many objects constructed here
    // depend on VIEW/UI and cannot safely be created from the constructor.
    //
    // Construction order matters. Several widgets depend on dimensions
    // established by earlier widgets, and the compiler cannot enforce it.
    private void buildUI() {
        int panelWidth = (int) (C.WIDTH() * PANEL_WIDTH_FRACTION);
        int panelHeight = (int) (C.HEIGHT() * PANEL_HEIGHT_FRACTION);

        this.mainPanel = new GPanel();
        this.panelInterrupter = new PanelInterrupter();
        this.confirmationBox = new ThalIPromtButtons(VIEW.inters().manager);
        this.activeProfileLabel = new GText(UI.FONT().S, "").normalify();
        this.newProfileEntry = thalassicus.capacity.ThalCapacityUI.ProfileDropdownEntry.sentinel(RESERVED_NAME_NEW_PROFILE, thalassicus.capacity.ThalCapacityUI.ProfileDropdownEntry.Kind.NEW_PROFILE);
        this.liveDataEntry = thalassicus.capacity.ThalCapacityUI.ProfileDropdownEntry.sentinel(RESERVED_NAME_LIVE_DATA, thalassicus.capacity.ThalCapacityUI.ProfileDropdownEntry.Kind.LIVE_DATA);

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

    // Reflection is required because the game's top-bar UI exposes no
    // supported extension point for adding additional buttons.
    // Depends on private engine field names and may break after game updates.
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

    // Deferred until the first update() because SCRIPT createInstance() ordering is not guaranteed across scripts.
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
            thalassicus.capacity.ThalCapacityUI.ProfileDropdownEntry entry = thalassicus.capacity.ThalCapacityUI.ProfileDropdownEntry.forStoredProfile(profile);
            this.profileDropdown.add(entry);
            this.dropdownEntriesByProfile.put(profile, entry);
        }
        this.profileDropdown.init();

        ThalCapacityProfile activeProfile = manager.activeProfile();
        if (activeProfile != null) {
            thalassicus.capacity.ThalCapacityUI.ProfileDropdownEntry activeEntry = this.dropdownEntriesByProfile.get(activeProfile);
            if (activeEntry != null) {
                this.profileDropdown.setSelected(activeEntry);
            }
        }
    }

    // Only sentinel entries are added here. Stored profiles are populated later,
    // once ThalCapacityProfileManager is guaranteed to exist.
    // Uses the global overlay manager so the popup renders in the same context as this panel.
    private ThalGDropDown<ProfileDropdownEntry> buildProfileDropdown(int dropdownWidth) {
        ThalGDropDown<ProfileDropdownEntry> dropdown = new ThalGDropDown<ProfileDropdownEntry>(dropdownWidth)
                .expansionManager(VIEW.inters().manager)
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

    private GButt.ButtPanel buildSaveButton() {
        return new GButt.ButtPanel("Save") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.attemptSave(() -> {
                });
            }
        };
    }

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

    // Disabled when viewing the already-active profile.
    private GButt.ButtPanel buildActivateButton() {
        return new GButt.ButtPanel("Set Active") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.activateSelectedProfile();
            }
            @Override
            protected void renAction() {
                ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
                boolean isViewingActiveProfile = manager != null && ThalCapacityUI.this.selectedStoredProfile == manager.activeProfile();
                this.activeSet(ThalCapacityUI.this.selectedStoredProfile != null && !isViewingActiveProfile);
            }
        };
    }

    private void buildTopSection() {
        GuiSection managementRow = new GuiSection();
        managementRow.addRightC(0, this.profileDropdown);
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildNewButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildDuplicateButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildSaveButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildDeleteButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.buildActivateButton());
        managementRow.addRightC(HORIZONTAL_INTER_PADDING, this.activeProfileLabel);
        GuiSection metadataRow = new GuiSection();
        metadataRow.addRightC(0, this.displayNameField);
        metadataRow.addRightC(HORIZONTAL_INTER_PADDING, this.descriptionField);
        this.topSection.add(managementRow);
        this.topSection.addDown(HORIZONTAL_INTER_PADDING, metadataRow);
    }

    private GText buildSectionHeader(CharSequence title) {
        return new GText(UI.FONT().M, title).lablifySub();
    }

    // Formula defaults never change, so compute them once instead of re-evaluating them every frame.
    private void ensureContentBuilt() {
        if (this.isContentBuilt) {
            return;
        }
        this.contentViewport.contentAdd(this.buildSectionHeader("Capacity Per Slot"));
        this.contentViewport.contentAdd(new GText(UI.FONT().S, CAPACITY_EXPLANATION).normalify(), HEADER_TABLE_MARGIN_BOTTOM);
        this.capacityCellsByKey = this.buildRows(ThalRoomServiceRegistry.roomServicesSorted(), CAPACITY_MINIMUM, CAPACITY_MAXIMUM, CAPACITY_DECIMAL_PLACES,
                service -> service.room().key,
                service -> service.room().info.name,
                service -> service.hypotheticalCapacityPerSlot(),
                this.scratchProfile::capacityPerSlotSet,
                this.scratchProfile::capacityPerSlotRemove);
        this.contentViewport.contentAdd(this.buildSectionHeader("Species Population"), HEADER_TABLE_MARGIN_TOP);
        this.contentViewport.contentAdd(new GText(UI.FONT().S, SPECIES_EXPLANATION).normalify(), HEADER_TABLE_MARGIN_BOTTOM);
        this.speciesCellsByKey = this.buildRows(RACES.all(), POPULATION_MINIMUM, POPULATION_MAXIMUM, POPULATION_DECIMAL_PLACES,
                race -> race.key,
                race -> race.info.names,
                race -> 0.0,
                this.scratchProfile::speciesPopulationSet,
                this.scratchProfile::speciesPopulationRemove);
        this.contentViewport.contentAdd(this.buildSectionHeader("Subject Type Population"), HEADER_TABLE_MARGIN_TOP);
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
                // Cache immutable defaults rather than recomputing them every render.
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
            int topMargin = rowStart == 0 ? HEADER_TABLE_MARGIN_BOTTOM : 0;
            this.contentViewport.contentAdd(row, topMargin);
        }
        return cellsByKey;
    }


    //
    // Section: State Machine
    //




    // Centralizes unsaved-changes handling for every destructive transition.
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



    private void transitionToExistingProfile(ThalCapacityProfile chosenProfile) {
        this.selectedStoredProfile = chosenProfile;
        this.scratchProfile.copyFrom(chosenProfile);
        this.isDirty = false;
        this.refreshEditorFieldsFromScratchProfile();
        this.syncLastKnownSelection();
    }

    // A brand-new blank profile is considered clean until the user edits it.
    private void transitionToNew() {
        this.selectedStoredProfile = null;
        this.scratchProfile.clear();
        this.scratchProfile.displayNameSet("");
        this.scratchProfile.descriptionSet("");
        this.isDirty = false;
        this.refreshEditorFieldsFromScratchProfile();
        this.profileDropdown.setSelected(this.newProfileEntry);
        this.syncLastKnownSelection();
    }

    private void transitionToLiveData() {
        this.selectedStoredProfile = null;
        ThalCapacityProfileManager.instance().populateFromLiveData(this.scratchProfile);
        this.isDirty = true;
        this.refreshEditorFieldsFromScratchProfile();
        this.profileDropdown.setSelected(this.newProfileEntry);
        this.syncLastKnownSelection();
    }

    private void transitionToDuplicate() {
        this.selectedStoredProfile = null;
        this.scratchProfile.displayNameSet("Copy of " + this.scratchProfile.displayName());
        this.isDirty = true;
        this.refreshEditorFieldsFromScratchProfile();
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

    private void syncLastKnownSelection() {
        this.lastKnownSelection = this.profileDropdown.selected();
    }



    private void deleteSelectedProfile() {
        ThalCapacityProfile profileToDelete = this.selectedStoredProfile;
        GButt.ButtPanel deleteButton = new GButt.ButtPanel("Delete") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.finalizeProfileDeletion(profileToDelete);
            }
        };
        GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
            @Override
            protected void clickA() {
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

    // Keep the UI synchronized with the manager's in-memory state even if disk deletion fails.
    // Can't reuse transitionToNew(): Delete leaves the editor clean.
    private void finalizeProfileDeletion(ThalCapacityProfile profileToDelete) {
        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        if (manager == null) {
            log.error("performDelete(): ThalCapacityProfileManager.instance() was null - cannot delete.");
            return;
        }

        ThalCapacityProfileManager.UpdateResult result = manager.updateProfiles(profileToDelete, null);
        if (!result.succeeded()) {
            log.error("performDelete(): disk delete failed for profile \"%s\" - removed from the in-memory list regardless.", profileToDelete.displayName());
        }

        ProfileDropdownEntry entry = this.dropdownEntriesByProfile.remove(profileToDelete);
        if (entry != null) {
            this.profileDropdown.remove(entry);
        }

        this.selectedStoredProfile = null;
        this.scratchProfile.clear();
        this.scratchProfile.displayNameSet("");
        this.scratchProfile.descriptionSet("");
        this.isDirty = false;
        this.refreshEditorFieldsFromScratchProfile();
        this.profileDropdown.setSelected(this.newProfileEntry);
        this.syncLastKnownSelection();
    }

    private void activateSelectedProfile() {
        if (!this.isDirty) {
            this.finalizeProfileActivation();
            return;
        }

        GButt.ButtPanel saveButton = new GButt.ButtPanel("Save") {
            @Override
            protected void clickA() {
                ThalCapacityUI.this.attemptSave(ThalCapacityUI.this::finalizeProfileActivation);
            }
        };
        GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
            @Override
            protected void clickA() {
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

    private void finalizeProfileActivation() {
        ThalCapacityProfileManager manager = ThalCapacityProfileManager.instance();
        if (manager == null) {
            log.error("performActivate(): ThalCapacityProfileManager.instance() was null - cannot activate.");
            return;
        }

        manager.activeProfileSet(this.selectedStoredProfile);
    }



    // Required even with per-cell validation: invalid edits never commit into
    // scratchProfile, so saving would otherwise persist stale values.
    // Warn instead of blocking when saving over a default profile:
    // Steam Workshop updates may later replace it.
    private void attemptSave(Runnable onSuccess) {
        String invalidLabels = this.invalidCellLabels();
        if (!invalidLabels.isEmpty()) {
            GButt.ButtPanel okayButton = new GButt.ButtPanel("Okay") {
                @Override
                protected void clickA() {
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

        if (manager.isDefaultProfileName(this.scratchProfile.displayName())) {
            GButt.ButtPanel duplicateButton = new GButt.ButtPanel("Duplicate") {
                @Override
                protected void clickA() {
                    ThalCapacityUI.this.transitionToDuplicate();
                }
            };
            GButt.ButtPanel continueSavingButton = new GButt.ButtPanel("Continue Saving") {
                @Override
                protected void clickA() {
                    ThalCapacityProfile existingDefault = manager.findProfileBySerializedName(ThalCapacityUI.this.scratchProfile.displayName());
                    ThalCapacityProfile profileToOverwrite = existingDefault == ThalCapacityUI.this.selectedStoredProfile ? null : existingDefault;
                    ThalCapacityUI.this.persistScratchProfile(manager, profileToOverwrite, onSuccess);
                }
            };
            GButt.ButtPanel cancelButton = new GButt.ButtPanel("Cancel") {
                @Override
                protected void clickA() {
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

    // Reuse an existing dropdown entry when possible so identity-based
    // selection remains stable.
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

        if (profileToOverwrite != null && previousSelectedProfile != null && previousSelectedProfile != profileToOverwrite) {
            ProfileDropdownEntry orphanedEntry = this.dropdownEntriesByProfile.remove(previousSelectedProfile);
            if (orphanedEntry != null) {
                this.profileDropdown.remove(orphanedEntry);
            }
        }

        this.profileDropdown.setSelected(entry);
    }



    // Reserved names are matched case-insensitively after trimming.
    // Blank names are also treated as reserved.
    private boolean isReservedName(String displayName) {
        String trimmed = displayName.trim();
        return trimmed.isEmpty() || trimmed.equalsIgnoreCase(RESERVED_NAME_NEW_PROFILE) || trimmed.equalsIgnoreCase(RESERVED_NAME_LIVE_DATA);
    }

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



    private void applyDropdownSelection(ProfileDropdownEntry selection) {
        switch (selection.kind) {
            case STORED -> this.transitionToExistingProfile(selection.profile);
            // A brand-new blank profile is considered clean until the user edits it.
            case NEW_PROFILE -> this.transitionToNew();
            case LIVE_DATA -> this.transitionToLiveData();
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

    // The dropdown has already updated its visible selection, so
    // cancellation must explicitly restore the previous selection.
    private void handlePendingSelectionChange(ProfileDropdownEntry newSelection) {
        ProfileDropdownEntry previousSelection = this.lastKnownSelection;
        this.guardDestructiveTransition(
                () -> this.applyDropdownSelection(newSelection),
                () -> this.profileDropdown.setSelected(previousSelection)
        );
    }

    //
    // Section: UI Synchronization
    //

    private void pushCellsFrom(Map<String, ThalGLabeledValue> cellsByKey, Map<String, Double> values) {
        for (Map.Entry<String, ThalGLabeledValue> entry : cellsByKey.entrySet()) {
            entry.getValue().existingValueSet(values.get(entry.getKey()));
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


    //
    // Classes
    //

    // ProfileDropdownEntry bridges a domain-blind generic widget to a domain concept it has no
    // notion of: ThalGDropDown<E extends CLICKABLE & ThalDropDownEntry> only
    // knows how to display, select, and size some E - it has never heard of
    // a "profile." Translating between a ThalCapacityProfile (or the
    // absence of one) and something the widget can actually render/click is
    // this class's entire job.
    //
    // Kind is explicit rather than inferred from profile == null - that
    // inference worked when there was only one null-backed sentinel to
    // distinguish from a real profile, and broke the moment a SECOND
    // null-backed sentinel (<New Profile> alongside <Live Data>) needed to
    // coexist with the first. A boolean inferred from nullability can't
    // tell two null cases apart from each other.
    //
    // Implements ThalDropDownEntry for one reason: CLICKABLE.body(), the
    // only access ThalGDropDown itself has to an entry, narrows down to
    // RECTANGLEE - every method that interface exposes is a pure
    // translation, never a resize. Only code running inside the entry, with
    // direct access to its own concrete Rec body field, can actually resize
    // it. availableWidthSet(int) exists because the dropdown has no other
    // way to constrain this entry's width from outside.
    //
    // render() re-derives its own truncation width from this.body.width()
    // every frame rather than fixing it once at construction, because the
    // same entry object is reused in two different contexts with two
    // different widths: its own natural size in the popup list, versus a
    // narrower width ThalGDropDown temporarily imposes when this is the
    // selected entry shown in the closed box. Re-deriving means the entry
    // never needs to know which context it's currently in.
    //
    // Private and static: purely an implementation detail of how this
    // dropdown represents its own entries, and self-contained enough
    // (a profile reference, a kind, a label) that it never needs to reach
    // back into the enclosing ThalCapacityUI instance.
    private static final class ProfileDropdownEntry extends CLICKABLE.ClickableAbs implements ThalDropDownEntry {

        private final Kind kind;
        private final GText label;
        private ThalCapacityProfile profile;
        private ProfileDropdownEntry(CharSequence displayText, ThalCapacityProfile profile, Kind kind) {
            this.profile = profile;
            this.kind = kind;
            this.label = new GText(UI.FONT().S, displayText).lablify();
            this.label.adjustWidth();
            this.body.setDim(this.label.width() + 8, this.label.height() + 4);
        }

        private static ProfileDropdownEntry sentinel(CharSequence displayText, Kind kind) {
            return new ProfileDropdownEntry(displayText, null, kind);
        }

        private static ProfileDropdownEntry forStoredProfile(ThalCapacityProfile profile) {
            return new ProfileDropdownEntry(profile.displayName(), profile, Kind.STORED);
        }

        private void updateFrom(ThalCapacityProfile updatedProfile) {
            this.profile = updatedProfile;
            this.label.clear().add(updatedProfile.displayName());
        }

        @Override
        public void availableWidthSet(int width) {
            this.body.setWidth(width);
        }

        @Override
        protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
            this.label.setMaxWidth(this.body.width() - 8);
            this.label.render(r, this.body.x1() + 4, this.body.x2() - 4, this.body.y1() + 2, this.body.y2());
        }


        private enum Kind {
            STORED,
            NEW_PROFILE,
            LIVE_DATA
        }
    }


    // PanelInterrupter exists because getting anything drawn and receiving input at all
    // requires becoming a genuine Interrupter registered with an
    // InterManager - Jake's engine has no lighter-weight "arbitrary custom
    // panel" concept. ThalCapacityUI can't fill that role itself: it
    // already implements SCRIPT/SCRIPT_INSTANCE, and Interrupter is a
    // class, not an interface - Java allows multiple interface
    // implementation but not multiple class inheritance, so the "is an
    // Interrupter" role has to live in a separate object.
    //
    // Non-static, unlike ProfileDropdownEntry: every method here exists
    // purely to forward Jake's callback contract into calls against the
    // enclosing instance's own mainPanel/topSection/contentViewport. It's a
    // thin delegating adapter, not an independent object - the implicit
    // outer reference is the whole point of it being an inner class.
    //
    // checkForSelectionChange()/refreshActiveProfileLabel() are called from
    // render() here rather than from SCRIPT_INSTANCE's own update() - this
    // render() is the one place guaranteed to run every frame the panel is
    // actually visible, matching this codebase's "never cache, always
    // re-derive" discipline. update() fires regardless of whether the panel
    // is even open, which is correct for the one-time
    // syncStoredProfilesIntoDropdown() deferral it already handles, but
    // wrong for anything that should only run while a player is looking at
    // the panel.
    private final class PanelInterrupter extends Interrupter {
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

    //
    // Overrides
    //

    @Override
    public CharSequence name() {
        return SCRIPT_NAME;
    }

    @Override
    public CharSequence desc() {
        return SCRIPT_DESCRIPTION;
    }

    // Not player-toggleable: this is an always-on internal utility script,
    // not an opt-in mod component a player would ever see or choose from a
    // script list.
    @Override
    public boolean isSelectable() {
        return false;
    }

    // Forces registration on a save regardless of never being selectable -
    // isSelectable() alone would leave this SCRIPT unregistered on any save
    // created before this class existed. Not a live switch: once a save has
    // already persisted this SCRIPT's key, forceInit() no longer controls
    // whether it runs on that save going forward.
    @Override
    public boolean forceInit() {
        return true;
    }

    // Both intentionally empty. Compare to mods that register new rooms,
    // which need these two hooks specifically because SETT.ROOMS() must be
    // reflectively patched before the session's own room list is built -
    // this class has no equivalent pre-session dependency to set up.
    @Override
    public void initBeforeGameCreated() {
    }

    @Override
    public void initBeforeGameInited() {
    }

    // The SCRIPT half's one job: stand up the SCRIPT_INSTANCE half. Order
    // mirrors the constructor/buildUI() split - bare construction first
    // (safe with no session), buildUI() only now that a session genuinely
    // exists, THEN instance assigned, so nothing reaching instance() can
    // ever observe a partially-built object. Toggle button injection runs
    // last since it's the one step here that reaches outside this class,
    // into Jake's own settlement UI tree.
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

    // Deliberately empty: this class holds no state of its own to persist.
    // Profile data and the active-profile selection live entirely in
    // ThalCapacityProfileManager, a separate SCRIPT implementor that owns
    // file I/O - this class is purely the editor UI over that data.
    @Override
    public void save(FilePutter file) {
    }

    @Override
    public void load(FileGetter file) throws IOException {
    }
}