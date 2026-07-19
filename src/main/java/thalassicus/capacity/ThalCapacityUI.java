// ThalCapacityUI.java
// Document Version 1.0.1
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.capacity;

import game.GAME;
import init.constant.C;
import init.race.RACES;
import init.race.Race;
import init.sprite.UI.UI;
import init.type.HTYPE;
import init.type.HTYPES;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import script.SCRIPT;
import settlement.main.SETT;
import settlement.room.service.module.RoomServiceAccess;
import snake2d.MButt;
import snake2d.Renderer;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.COORDINATE;
import snake2d.util.datatypes.DIR;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.clickable.CLICKABLE;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sprite.SPRITE;
import snake2d.util.sprite.text.StringInputSprite;
import thalassicus.util.ThalsLogger;
import util.data.GETTER;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GDropDown;
import util.gui.misc.GInput;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import util.gui.table.GTableBuilder;
import view.interrupter.Interrupter;
import view.main.VIEW;

// The player-facing "Capacity Profiles" panel - a standalone overlay,
// reflection-injected into the Settlement UIPanelTop's own top bar rather
// than routed through IManager/IFullView (see the modding reference
// discussion for why: IManager's button list is a hardcoded local variable
// in a final class, with no seam for a tenth entry - UIPanelTop.right is
// Jake's own documented, if reflection-based, mod injection point).
//
// THIS IS A NO-OP STRUCTURAL PASS. Every button click, every table cell, the
// dropdown, the name/description fields - none of it reads or writes a real
// ThalCapacityProfile or talks to ThalCapacityProfileManager yet. This class
// exists to prove out layout, sizing, and the top-bar injection mechanism in
// isolation before the data layer gets wired in. See the chat message this
// was delivered in for a full list of guesses and open questions.
public final class ThalCapacityUI implements SCRIPT, SCRIPT.SCRIPT_INSTANCE {

    private static final ThalsLogger log = new ThalsLogger(
            ThalsLogger.INFO,
            System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalCapacityUI.log"
    );

    private static ThalCapacityUI instance;

    public static ThalCapacityUI instance() {
        return instance;
    }

    private static final CharSequence SCRIPT_NAME = "Thal Capacity UI";
    private static final CharSequence SCRIPT_DESCRIPTION =
            "Internal utility script for Service Estimate Fix. Displays the Capacity Profiles panel. Not a gameplay-affecting script.";

    // ---- Layout guesses -----------------------------------------------
    // Every constant in this block is an unverified best guess, chosen
    // without being able to see this panel actually render. All are
    // expected to need visual tuning once compiled and run in-game - see
    // the accompanying chat message for the reasoning behind each starting
    // value.
    private static final int LABEL_COLUMN_WIDTH = 180;
    private static final int VALUE_COLUMN_WIDTH = 90;
    private static final int ROW_MARGIN = 8;
    private static final int SECTION_MARGIN = 16;
    private static final int TABLE_VISIBLE_HEIGHT = 150;
    private static final double PANEL_WIDTH_FRACTION = 0.85;
    private static final double PANEL_HEIGHT_FRACTION = 0.85;

    // ---- Domain bounds --------------------------------------------------
    // Capacity-per-slot and raw population counts are different
    // quantities with different valid ranges, so each table's GDouble
    // instances are built with their own min/max pair rather than sharing
    // one.
    private static final double CAPACITY_MINIMUM = 1.0;
    private static final double CAPACITY_MAXIMUM = 99999.0;
    private static final double POPULATION_MINIMUM = 0.0;
    private static final double POPULATION_MAXIMUM = 999999.0;

    // Shown by every GDouble in every table until real RoomService/profile
    // wiring replaces liveDefaultValue() - deliberately an obvious, round
    // placeholder rather than a plausible-looking number, so stub data is
    // unmistakable during visual review.
    private static final double STUB_DEFAULT_VALUE = 1.0;

    private final GPanel mainPanel = new GPanel();
    private final GuiSection contentSection = new GuiSection();
    private final Inter inter = new Inter();

    private final GDropDown<ProfileDropdownEntry> profileDropdown;
    private final GInput displayNameField;
    private final GInput descriptionField;

    // Enumerated fresh every time the panel opens (see refreshRowLabels()),
    // never at construction time - RoomServiceAccess.ALL()/SETT.ROOMS() are
    // settlement-scoped data that may not exist yet at the exact moment
    // createInstance() runs (VIEW is constructed before a settlement
    // necessarily exists - e.g. during world generation), whereas the
    // player can only ever click this panel's own toggle button while
    // looking at a real settlement's UI. Deferring population until
    // openPanel() sidesteps that lifecycle risk entirely rather than
    // guessing at exactly when it's already safe to enumerate.
    private final List<CharSequence> capacityRowLabels = new ArrayList<>();
    private final List<CharSequence> speciesRowLabels = new ArrayList<>();
    private final List<CharSequence> htypeRowLabels = new ArrayList<>();

    private final GuiSection capacityTable;
    private final GuiSection speciesTable;
    private final GuiSection htypeTable;

    private ThalCapacityUI() {
        this.profileDropdown = this.buildProfileDropdown();
        this.displayNameField = this.buildTextField("Display Name", 40);
        this.descriptionField = this.buildTextField("Description", 120);

        this.capacityTable = this.buildTable(this.capacityRowLabels, CAPACITY_MINIMUM, CAPACITY_MAXIMUM);
        this.speciesTable = this.buildTable(this.speciesRowLabels, POPULATION_MINIMUM, POPULATION_MAXIMUM);
        this.htypeTable = this.buildTable(this.htypeRowLabels, POPULATION_MINIMUM, POPULATION_MAXIMUM);

        this.mainPanel.setBig();
        this.mainPanel.setTitle("Capacity Profiles");
        this.mainPanel.setCloseAction(this::closePanel);
        this.mainPanel.setDim((int) (C.WIDTH() * PANEL_WIDTH_FRACTION), (int) (C.HEIGHT() * PANEL_HEIGHT_FRACTION));
        this.mainPanel.body().centerX(C.DIM());
        this.mainPanel.body().centerY(C.DIM());

        this.assembleContent();
    }

    // ---- Public open/close surface --------------------------------------

    public void openPanel() {
        this.refreshRowLabels();
        this.inter.activate();
    }

    public void closePanel() {
        this.inter.hide();
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

    // ---- Construction helpers --------------------------------------------

    private GDropDown<ProfileDropdownEntry> buildProfileDropdown() {
        GDropDown<ProfileDropdownEntry> dropdown = new GDropDown<>("Profile", 160);
        // "Live Data" is a UI-only concept, per the original design context
        // - no backing ThalCapacityProfile object, hence the null. Real
        // profiles from ThalCapacityProfileManager are not loaded into
        // this dropdown yet in this no-op pass.
        dropdown.add(new ProfileDropdownEntry("Live Data", null));
        dropdown.init();
        return dropdown;
    }

    private GInput buildTextField(CharSequence placeholderText, int bufferCapacity) {
        StringInputSprite sprite = new StringInputSprite(bufferCapacity, UI.FONT().S).placeHolder(placeholderText);
        return new GInput(sprite);
    }

    // One shared button-building helper for all seven CRUD actions - every
    // one is a genuine no-op in this pass, logging its own click for
    // visual/manual confirmation that the button is wired at all, rather
    // than touching ThalCapacityProfileManager.
    private GButt.ButtPanel buildStubButton(CharSequence label) {
        return new GButt.ButtPanel(label) {
            @Override
            protected void clickA() {
                log.info("%s clicked - stub, no-op in this pass.", label);
            }
        };
    }

    // Shared by all three tables - only the bounds and backing label list
    // differ between capacity/species/htype, so the table-shape itself
    // (two columns, no per-column titles, decorated/scrollable) is built
    // once here rather than three times.
    private GuiSection buildTable(List<CharSequence> rowLabels, double minimumValue, double maximumValue) {
        GTableBuilder builder = new GTableBuilder() {
            @Override
            public int nrOFEntries() {
                return rowLabels.size();
            }
        };

        builder.column(LABEL_COLUMN_WIDTH, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                CharSequence label = resolveLabel(rowLabels, ier);
                return new GText(UI.FONT().S, label).setMaxWidth(LABEL_COLUMN_WIDTH).r(DIR.W);
            }
        }, DIR.W);

        builder.column(VALUE_COLUMN_WIDTH, new GTableBuilder.GRowBuilder() {
            @Override
            public RENDEROBJ build(final GETTER<Integer> ier) {
                // Deliberately does not reference ier at all in this pass -
                // every cell shows the same stub default and every
                // callback is a no-op. IMPORTANT FOR THE NEXT PASS: rows
                // returned by GTableBuilder are REUSED across scroll
                // positions (confirmed from Scrollable.java's own
                // getElement()/init() - a fixed, small number of row
                // objects get re-initialized with a new index as the
                // player scrolls, rather than one object per data entry).
                // Once this is wired to real profile data, these callbacks
                // will need to read ier.get() fresh each time they fire,
                // not capture a key at construction time, or scrolling
                // will silently write values to the wrong blueprint.
                return new GDouble(minimumValue, maximumValue) {
                    @Override
                    protected double liveDefaultValue() {
                        return STUB_DEFAULT_VALUE;
                    }

                    @Override
                    protected void committedValueSet(double committedValue) {
                        // Stub: no data layer wired yet in this pass.
                    }

                    @Override
                    protected void revertToDefault() {
                        // Stub: no data layer wired yet in this pass.
                    }
                };
            }
        }, DIR.W);

        return builder.createHeight(TABLE_VISIBLE_HEIGHT, true);
    }

    // GTableBuilder.create()/createHeight() call every column's build()
    // once during their own internal row-height MEASUREMENT pass, before
    // any real row index has ever been set (GETTER_IMP starts unset) - and
    // this table's row-label lists are themselves genuinely empty at
    // construction time regardless (see the deferred-population comment on
    // the field declarations above). Without this guard, ier.get() being
    // null during that measurement pass would NPE on unboxing, or
    // List.get(...) would throw on an empty list. The empty-string
    // fallback is never actually seen by the player - by the time a real
    // row renders, ier is set to a valid index and the backing list is
    // populated.
    private static CharSequence resolveLabel(List<CharSequence> labels, GETTER<Integer> ier) {
        Integer index = ier.get();
        if (index == null || index < 0 || index >= labels.size()) {
            return "";
        }
        return labels.get(index);
    }

    private void assembleContent() {
        GuiSection managementRow = new GuiSection();
        managementRow.addRightC(0, this.profileDropdown);
        managementRow.addRightC(ROW_MARGIN, this.buildStubButton("Create"));
        managementRow.addRightC(ROW_MARGIN, this.buildStubButton("Load"));
        managementRow.addRightC(ROW_MARGIN, this.buildStubButton("Save"));
        managementRow.addRightC(ROW_MARGIN, this.buildStubButton("Merge"));
        managementRow.addRightC(ROW_MARGIN, this.buildStubButton("Delete"));
        managementRow.addRightC(ROW_MARGIN, this.buildStubButton("Rename"));
        managementRow.addRightC(ROW_MARGIN, this.buildStubButton("Set Active"));

        GuiSection metadataRow = new GuiSection();
        metadataRow.addRightC(0, this.displayNameField);
        metadataRow.addRightC(ROW_MARGIN, this.descriptionField);

        this.contentSection.add(managementRow);
        this.contentSection.addDown(ROW_MARGIN, metadataRow);

        this.contentSection.addDown(SECTION_MARGIN, this.sectionHeader("Capacity Per Slot"));
        this.contentSection.addDown(4, this.capacityTable);

        this.contentSection.addDown(SECTION_MARGIN, this.sectionHeader("Species Population"));
        this.contentSection.addDown(4, this.speciesTable);

        this.contentSection.addDown(SECTION_MARGIN, this.sectionHeader("HTYPE Population"));
        this.contentSection.addDown(4, this.htypeTable);

        this.contentSection.body().moveX1Y1(this.mainPanel.inner().x1(), this.mainPanel.inner().y1());
    }

    private GText sectionHeader(CharSequence title) {
        return new GText(UI.FONT().M, title).lablifySub();
    }

    // ---- Row-label enumeration -------------------------------------------

    // Populated fresh every openPanel() call - see the field-declaration
    // comment above for why this is deferred rather than done once at
    // construction. Purely structural (keys + display names for row
    // count/labels) - does not touch ThalCapacityProfile or
    // ThalCapacityProfileManager, matching this pass's no-op scope.
    private void refreshRowLabels() {
        this.capacityRowLabels.clear();
        this.speciesRowLabels.clear();
        this.htypeRowLabels.clear();

        // GUESS, moderate confidence: room().info.name mirrors the
        // confirmed Race.info.names pattern below (INFO-object-based
        // display names), but no direct usage of this exact accessor on a
        // RoomBlueprintImp was seen in any file read so far. If this
        // doesn't compile, room().info.name is the first thing to check
        // against the real RoomBlueprintImp source.
        //
        // SETT.ROOMS().TEMPLES.ALL / .SHRINES below is ALSO an unverified
        // guess at the accessor shape, not confirmed from directly-read
        // source - RoomService.java's own religion-specific eligible-
        // population counting implies temples/shrines are reachable
        // somehow from SETT.ROOMS(), but the exact field names were never
        // directly confirmed. If this line doesn't compile, it's the
        // second thing to check.
        for (RoomServiceAccess roomServiceAccess : RoomServiceAccess.ALL()) {
            this.capacityRowLabels.add(roomServiceAccess.room().info.name);
        }
        for (var temple : SETT.ROOMS().TEMPLES.ALL) {
            this.capacityRowLabels.add(temple.service().room().info.name);
        }
        for (var shrine : SETT.ROOMS().TEMPLES.SHRINES) {
            this.capacityRowLabels.add(shrine.service().room().info.name);
        }

        // CONFIRMED: race.info.names is directly used this exact way
        // already, inside RoomService.appendDivergenceLines().
        for (Race race : RACES.all()) {
            this.speciesRowLabels.add(race.info.names);
        }

        // CONFIRMED (2026/07/19): HTYPE extends INFO directly rather than
        // holding one as a field - so the accessor is hType.name, not
        // hType.key, and not hType.info.name either (that pattern only
        // applies to classes that HOLD an INFO, like Race does above).
        for (HTYPE hType : HTYPES.ALL()) {
            this.htypeRowLabels.add(hType.name);
        }
    }

    // ---- Top-bar injection ------------------------------------------------

    // CONFIRMED (2026/07/19) against the real SettView.java source. The
    // original single-level guess was wrong in a specific, now-fixable
    // way: SettView does NOT hold a UIPanelTop field directly. It holds a
    // field named "panel" of type UIPanelTopSett - a SEPARATE wrapper
    // class (the same one whose own addExtraElement()/extrabutts mod hook
    // is documented as dead code) - which itself wraps a genuine UIPanelTop
    // instance internally, passed into its constructor as a local variable
    // in SettView's own code:
    //
    //     UIPanelTop pan = new UIPanelTop(this.uiManager);
    //     this.panel = new UIPanelTopSett(this.ui, this, pan);
    //
    // "panel" is a real, confirmed field name, so that hop uses
    // declaredFieldValue() (name-based) rather than a type search. Where
    // UIPanelTopSett stores that "pan" argument internally is NOT
    // confirmed - UIPanelTopSett.java itself hasn't been read - so the
    // second hop still falls back to the type-name search this class
    // already had. If this second hop fails, UIPanelTopSett.java is the
    // next file to pull.
    private void injectToggleButtonIntoSettlementTopBar() {
        try {
            Object settlementView = VIEW.s();
            Object panelTopSett = ThalReflectionUtil.declaredFieldValue("panel", settlementView).orElse(null);
            if (panelTopSett == null) {
                log.error("injectToggleButtonIntoSettlementTopBar(): could not read SettView's own 'panel' field "
                        + "- toggle button was not added. This is currently the only way to open the Capacity "
                        + "Profiles panel.");
                return;
            }

            Object panelTop = ThalReflectionUtil.declaredFieldByTypeName("UIPanelTop", panelTopSett).orElse(null);
            if (panelTop == null) {
                log.error("injectToggleButtonIntoSettlementTopBar(): found UIPanelTopSett, but no UIPanelTop-typed "
                        + "field directly on it - toggle button was not added. UIPanelTopSett may store its "
                        + "UIPanelTop reference somewhere less direct than a simple declared field.");
                return;
            }

            GuiSection right = ThalReflectionUtil.<GuiSection>declaredFieldValue("right", panelTop).orElse(null);
            if (right == null) {
                log.error("injectToggleButtonIntoSettlementTopBar(): found UIPanelTop, but its 'right' field "
                        + "was not - Jake's own field name may have changed since the modding guide was written.");
                return;
            }

            // Icon choice is an aesthetic guess (storage/capacity-adjacent)
            // - swap freely, this has no functional bearing.
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

    // ---- Profile dropdown entry --------------------------------------------

    // Deliberately unaware of ThalCapacityProfileManager beyond holding a
    // reference - all presentation logic (rendering, sizing) lives here in
    // the UI layer, per the established layering: UI knows about Manager,
    // Manager knows about Profile, Profile knows only itself.
    private static final class ProfileDropdownEntry extends CLICKABLE.ClickableAbs {

        private final ThalCapacityProfile profile;
        private final GText label;

        private ProfileDropdownEntry(CharSequence displayText, ThalCapacityProfile profile) {
            this.profile = profile;
            this.label = new GText(UI.FONT().S, displayText).lablify();
            this.label.adjustWidth();
            this.body.setDim(this.label.width() + 8, this.label.height() + 4);
        }

        private boolean isLiveData() {
            return this.profile == null;
        }

        @Override
        protected void render(SPRITE_RENDERER r, float ds, boolean isActive, boolean isSelected, boolean isHovered) {
            this.label.render(r, this.body.x1() + 4, this.body.y1() + 2);
        }
    }

    // ---- Overlay show/hide --------------------------------------------------

    // Structurally closer to IManager's own Inter (a big standalone content
    // panel replacing the screen) than to GDropDown's Inter (a small
    // contextual popup) - shown via VIEW.inters().manager to match, and
    // pausing game speed while open rather than closing on any keypress,
    // since this panel needs to accept typed text.
    private final class Inter extends Interrupter {

        @Override
        protected boolean hover(COORDINATE mCoo, boolean mouseHasMoved) {
            ThalCapacityUI.this.contentSection.hover(mCoo);
            return true;
        }

        @Override
        protected void mouseClick(MButt button) {
            if (button == MButt.LEFT) {
                ThalCapacityUI.this.contentSection.click();
            } else if (button == MButt.RIGHT) {
                this.hide();
            }
        }

        @Override
        protected void hoverTimer(GBox text) {
            ThalCapacityUI.this.contentSection.hoverInfoGet(text);
        }

        @Override
        protected boolean update(float ds) {
            GAME.SPEED.tmpPause();
            return false;
        }

        @Override
        protected boolean render(Renderer r, float ds) {
            ThalCapacityUI.this.mainPanel.render(r, ds);
            ThalCapacityUI.this.contentSection.render(r, ds);
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

    // ---- SCRIPT / SCRIPT_INSTANCE lifecycle --------------------------------

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

    // Constructs a fresh instance every session (session-specific state -
    // the panel's own GuiSection tree, its Interrupter - must live here,
    // not in a cached-forever descriptor), then performs the top-bar
    // injection immediately, matching the documented lifecycle: VIEW,
    // SettView, and UIPanelTop are all already constructed by the time
    // createInstance() runs.
    @Override
    public SCRIPT.SCRIPT_INSTANCE createInstance() {
        ThalCapacityUI created = new ThalCapacityUI();
        instance = created;
        created.injectToggleButtonIntoSettlementTopBar();
        return created;
    }

    @Override
    public void update(double deltaSeconds) {
        // No-op: the panel's own per-frame behavior is driven by the
        // Interrupter framework once shown (see Inter above), not by this
        // SCRIPT_INSTANCE update hook.
    }

    @Override
    public void save(FilePutter file) {
        // No-op: whether the panel happens to be open is transient UI
        // state, not something that should persist across a save/load,
        // matching how IManager's own open/closed panel state isn't saved
        // either.
    }

    @Override
    public void load(FileGetter file) throws IOException {
        // No-op, see save() above.
    }
}
