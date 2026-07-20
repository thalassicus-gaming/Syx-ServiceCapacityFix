// ThalCapacityUI.java
// Document Version 1.1.0
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
import java.util.List;
import java.util.function.Function;
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
import thalassicus.ui.GLabeledValue;
import thalassicus.ui.GSlidableViewportVertical;
import thalassicus.util.ThalReflectionUtil;
import thalassicus.util.ThalsLogger;
import util.gui.misc.GBox;
import util.gui.misc.GButt;
import util.gui.misc.GDropDown;
import util.gui.misc.GInput;
import util.gui.misc.GText;
import util.gui.panel.GPanel;
import view.interrupter.InterManager;
import view.interrupter.Interrupter;
import view.main.VIEW;

// The player-facing "Capacity Profiles" panel - a standalone overlay,
// reflection-injected into the Settlement UIPanelTop's own top bar rather
// than routed through IManager/IFullView (see the modding reference
// discussion for why: IManager's button list is a hardcoded local variable
// in a final class, with no seam for a tenth entry - UIPanelTop.right is
// Jake's own documented, if reflection-based, mod injection point).
//
// REWRITTEN (2026/07/19) to use GSlidableViewportVertical + GLabeledValue
// instead of GTableBuilder. All three tables now live inside ONE shared,
// continuously pannable viewport rather than three independently-clipped
// ones - display name and description stay outside and above it. This
// also eliminated an entire category of complexity the old GTableBuilder
// version had to work around: since GSlidableViewportVertical builds
// permanent content once rather than recycling a fixed pool of row-slots
// across scroll positions, there's no more index-resolution defensive
// guard needed, and no more "these callbacks will need to read ier.get()
// fresh once real data is wired in" warning - every GLabeledValue is
// permanently bound to one blueprint/race/HTYPE for its whole life via an
// ordinary lambda capture.
//
// THIS IS STILL A NO-OP STRUCTURAL PASS. Labels ARE real (blueprint/race/
// HTYPE display names), but every value shows a flat stub default (1.0),
// no button does anything but log, and nothing here reads or writes a real
// ThalCapacityProfile or talks to ThalCapacityProfileManager yet.
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
    // expected to need visual tuning once compiled and run in-game.
    private static final int LABEL_COLUMN_WIDTH = 180;
    private static final int CELLS_PER_ROW = 4;
    private static final int CELL_HORIZONTAL_MARGIN = 16;
    private static final int ROW_MARGIN = 8;
    private static final int SECTION_MARGIN = 16;
    private static final double PANEL_WIDTH_FRACTION = 0.85;
    private static final double PANEL_HEIGHT_FRACTION = 0.85;

    // How many pixels one mouse wheel notch scrolls the shared viewport.
    // Same category of guess as GSlidableViewportVertical's own
    // wheelScrollStep parameter doc explains - this class DOES know its
    // own row height (unlike that general-purpose class), but only once
    // a GLabeledValue has actually rendered once; guessing a plausible
    // number here rather than measuring one is still a guess.
    private static final int VIEWPORT_WHEEL_SCROLL_STEP = 40;

    // ---- Domain bounds --------------------------------------------------
    // Capacity-per-slot and raw population counts are different
    // quantities with different valid ranges, so each table's cells are
    // built with their own min/max pair rather than sharing one.
    private static final double CAPACITY_MINIMUM = 1.0;
    private static final double CAPACITY_MAXIMUM = 99999.0;
    private static final double POPULATION_MINIMUM = 0.0;
    private static final double POPULATION_MAXIMUM = 999999.0;

    // Shown by every GLabeledValue's underlying GDouble until real
    // RoomService/profile wiring replaces it - deliberately an obvious,
    // round placeholder rather than a plausible-looking number, so stub
    // data is unmistakable during visual review.
    private static final double STUB_DEFAULT_VALUE = 1.0;

    private final GPanel mainPanel = new GPanel();
    private final GuiSection topSection = new GuiSection();
    private final GSlidableViewportVertical contentViewport;
    private final Inter inter = new Inter();

    private final GDropDown<ProfileDropdownEntry> profileDropdown;
    private final GInput displayNameField;
    private final GInput descriptionField;

    // Every GLabeledValue built across all three tables, tracked here for
    // a future Save-eligibility check (see allCellsValid() below) - not
    // wired to the Save button yet, since Save itself is still a no-op
    // stub in this pass.
    private final List<GLabeledValue> allLabeledValues = new ArrayList<>();

    // Guards the lazy, one-time content build - see openPanel(). Building
    // once on first open, not at construction and not on every subsequent
    // open, sidesteps the same settlement-may-not-exist-yet lifecycle risk
    // the old refreshRowLabels() design was built around, while avoiding
    // needless rebuild work on every re-open (blueprints/races/HTYPEs are
    // session-static once a game is loaded).
    private boolean isContentBuilt = false;

    // PUBLIC, matching ThalCapacityProfileManager's own confirmed-working
    // precedent - NOT private. Jake's own SCRIPT discovery constructs the
    // descriptor instance via reflection from outside this class entirely;
    // a private constructor here caused a real runtime failure at startup
    // in an earlier pass - this is a confirmed fix, not a style choice.
    public ThalCapacityUI() {
        int panelWidth = (int) (C.WIDTH() * PANEL_WIDTH_FRACTION);
        int panelHeight = (int) (C.HEIGHT() * PANEL_HEIGHT_FRACTION);

        this.profileDropdown = this.buildProfileDropdown();
        this.displayNameField = this.buildTextField("Display Name", 40);
        this.descriptionField = this.buildTextField("Description", 120);

        this.buildTopSection();
        int viewportY = this.topSection.body().height() + SECTION_MARGIN;
        this.contentViewport = new GSlidableViewportVertical(panelWidth, panelHeight - viewportY, VIEWPORT_WHEEL_SCROLL_STEP);

        this.mainPanel.setBig();
        this.mainPanel.setTitle("Capacity Profiles");
        this.mainPanel.setCloseAction(this::closePanel);
        this.mainPanel.setDim(panelWidth, panelHeight);
        this.mainPanel.body().centerX(C.DIM());
        this.mainPanel.body().centerY(C.DIM());

        this.topSection.body().moveX1Y1(this.mainPanel.inner().x1(), this.mainPanel.inner().y1());
        this.contentViewport.body().moveX1Y1(this.mainPanel.inner().x1(), this.mainPanel.inner().y1() + viewportY);
    }

    // ---- Public open/close surface --------------------------------------

    public void openPanel() {
        this.ensureContentBuilt();
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

    private void buildTopSection() {
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

        this.topSection.add(managementRow);
        this.topSection.addDown(ROW_MARGIN, metadataRow);
    }

    private GText sectionHeader(CharSequence title) {
        return new GText(UI.FONT().M, title).lablifySub();
    }

    // ---- Lazy, one-time content build -------------------------------------

    // Builds every row for all three tables and adds them to
    // contentViewport - called once, from the first openPanel(), never at
    // construction time. RoomServiceAccess/SETT/RACES/HTYPES are
    // settlement-scoped data that may not exist yet at the exact moment
    // this class's own constructor runs (VIEW is constructed before a
    // settlement necessarily exists - e.g. during world generation),
    // whereas the player can only ever click this panel's own toggle
    // button while looking at a real settlement's UI - so deferring to
    // first open sidesteps that lifecycle risk entirely.
    private void ensureContentBuilt() {
        if (this.isContentBuilt) {
            return;
        }

        this.contentViewport.contentAdd(this.sectionHeader("Capacity Per Slot"));
        // ThalRoomServiceRegistry.roomServicesSorted() already handles the
        // enumeration this section used to do by hand
        // (RoomServiceAccess.ALL() + SETT.ROOMS().TEMPLES.ALL/.SHRINES,
        // both unverified guesses in the old version) - confirmed correct,
        // alphabetical, case-insensitive, from its own real source.
        this.buildRows(ThalRoomServiceRegistry.roomServicesSorted(), CAPACITY_MINIMUM, CAPACITY_MAXIMUM,
                service -> service.room().info.name);

        this.contentViewport.contentAdd(this.sectionHeader("Species Population"));
        this.buildRows(RACES.all(), POPULATION_MINIMUM, POPULATION_MAXIMUM,
                race -> race.info.names);

        this.contentViewport.contentAdd(this.sectionHeader("HTYPE Population"));
        this.buildRows(HTYPES.ALL(), POPULATION_MINIMUM, POPULATION_MAXIMUM,
                hType -> hType.name);

        this.isContentBuilt = true;
    }

    // Generic over T deliberately - this is the one piece of this rewrite
    // with real uncertainty behind it. RACES.all() and HTYPES.ALL() were
    // never confirmed to return java.util.List specifically (unlike
    // ThalRoomServiceRegistry.roomServicesSorted(), confirmed from its own
    // source) - HTYPE's own constructor takes a parameter of type
    // LISTE<HTYPE>, Jake's own list interface, suggesting HTYPES.ALL()
    // may not be a plain java.util.List either. Accepting Iterable<T> here
    // rather than List<T>, and copying into a real ArrayList internally,
    // sidesteps that uncertainty entirely rather than guessing at (or
    // needing to verify) RACES/HTYPES' exact declared return types - this
    // works correctly regardless of which concrete collection type either
    // one actually returns.
    //
    // Row-index math ("groups of 4") only needs indexed access, which the
    // internal copy provides regardless of the source's own shape.
    //
    // Each cell is permanently bound to its own "item" via ordinary lambda
    // capture - no ier.get()/index-resolution machinery needed at all,
    // unlike the old GTableBuilder version.
    private <T> void buildRows(Iterable<T> sourceItems, double minimumValue, double maximumValue,
                                Function<T, CharSequence> labelExtractor) {
        List<T> items = new ArrayList<>();
        for (T item : sourceItems) {
            items.add(item);
        }

        for (int rowStart = 0; rowStart < items.size(); rowStart += CELLS_PER_ROW) {
            GuiSection row = new GuiSection();
            int rowEnd = Math.min(rowStart + CELLS_PER_ROW, items.size());

            for (int i = rowStart; i < rowEnd; i++) {
                T item = items.get(i);
                GLabeledValue cell = new GLabeledValue(
                        minimumValue,
                        maximumValue,
                        LABEL_COLUMN_WIDTH,
                        () -> labelExtractor.apply(item),
                        () -> STUB_DEFAULT_VALUE,
                        committedValue -> {
                            // Stub: no data layer wired yet in this pass.
                        },
                        () -> {
                            // Stub: no data layer wired yet in this pass.
                        }
                );
                this.allLabeledValues.add(cell);
                row.addRightC(i == rowStart ? 0 : CELL_HORIZONTAL_MARGIN, cell);
            }

            this.contentViewport.contentAdd(row);
        }
    }

    // Not called from anywhere yet - present as infrastructure for the
    // eventual real Save button, per the plan to add this list now rather
    // than when Save itself gets wired up.
    private boolean allCellsValid() {
        for (GLabeledValue cell : this.allLabeledValues) {
            if (!cell.isValid()) {
                return false;
            }
        }
        return true;
    }

    // ---- Top-bar injection ------------------------------------------------

    // CONFIRMED (2026/07/19) against the real SettView.java source. The
    // original single-level guess was wrong in a specific, now-fixable
    // way: SettView does NOT hold a UIPanelTop field directly. It holds a
    // field named "panel" of type UIPanelTopSett - a SEPARATE wrapper
    // class (the same one whose own addExtraElement()/extrabutts mod hook
    // is documented as dead code) - which itself wraps a genuine UIPanelTop
    // instance internally, passed into its constructor as a local variable
    // in SettView's own code, never retained as a field anywhere in that
    // object graph. UIPanelTop's own constructor calls this.pin() then
    // this.show(manager) instead - pin() makes it immune to
    // InterManager.clear()/disturb(), so once shown it stays registered in
    // that InterManager's own private "inters" collection for the life of
    // the view. VIEW.ViewSubSimple.uiManager is a PUBLIC field, so
    // reaching the InterManager itself needs no reflection at all - only
    // its own private "inters" field does, walked here by simple class
    // name once found.
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
    // since this panel needs to accept typed text. Now dispatches to BOTH
    // topSection and contentViewport, replacing the old single
    // contentSection.
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
                this.hide();
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
