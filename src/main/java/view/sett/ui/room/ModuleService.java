// ModuleService.java
// Document Version 1.6.2
// Creation date: 2026/07/12
// Creator: Thalassicus

package view.sett.ui.room;

import init.sprite.SPRITES;
import settlement.main.SETT;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomInstance;
import settlement.room.service.module.ROOM_SERVICER;
import settlement.room.service.module.RoomService;
import settlement.room.service.module.RoomServiceAccess;
import settlement.room.service.module.RoomServiceInstance;
import snake2d.SPRITE_RENDERER;
import snake2d.util.datatypes.DIR;
import snake2d.util.gui.GUI_BOX;
import snake2d.util.gui.GuiSection;
import snake2d.util.gui.renderable.RENDEROBJ;
import snake2d.util.sets.LISTE;
import snake2d.util.sets.Stack;
import snake2d.util.sprite.SPRITE;
import snake2d.util.sprite.text.Str;
import util.data.DOUBLE;
import util.data.GETTER;
import util.gui.misc.GBox;
import util.gui.misc.GGrid;
import util.gui.misc.GHeader;
import util.gui.misc.GMeter;
import util.gui.misc.GStat;
import util.gui.misc.GText;
import util.gui.table.GTableSorter;
import util.info.GFORMAT;
import util.text.D;

final class ModuleService implements Modules.ModuleMaker {
    private static CharSequence ¤¤NO = "¤No Available Services";
    private static CharSequence ¤¤AVAILABLE = "¤Available Services";
    private static CharSequence ¤¤USED = "¤Currently Used";
    private static CharSequence ¤¤NEEDS = "¤Needs Work";
    private static CharSequence ¤¤TOTAL = "¤Total";
    private static CharSequence ¤¤QUALITY = "¤Quality";
    private static CharSequence ¤¤ACCESS = "¤The overall access of your minions";
    private static CharSequence ¤¤USAGE = "¤Service";
    private static CharSequence ¤¤Load = "¤Load";
    private static CharSequence ¤¤Capacity = "¤Capacity";
    private static CharSequence ¤¤CapacityD = "¤An rough estimate of how many subjects that can be served.";
    private static CharSequence ¤¤USAGE_DESC = "¤The highest load of this service during a day. Once full, it means there aren't enough services to meet your subjects demands. If low, it's an indication you can cut down on this service.";
    private static CharSequence ¤¤RADIUS = "¤Radius";
    private static CharSequence ¤¤RADIUSD = "¤All services operate within a radius. The radius is the max distance a subject is prepared to walk to get to a service.";
    private static CharSequence stuff = ¤¤CapacityD;
    static {
        D.ts(ModuleService.class);
    }

    public ModuleService(Init init) {
    }

    @Override
    public void make(RoomBlueprint p, LISTE<UIRoomModule> l) {
        if (p instanceof RoomService.ROOM_SERVICE_HASER) {
            l.add(new ModuleService.I((RoomService.ROOM_SERVICE_HASER)p));
        }
    }


    private final class I extends UIRoomModule {
        private final RoomService.ROOM_SERVICE_HASER p;

        I(RoomService.ROOM_SERVICE_HASER p) {
            this.p = p;
        }

        @Override
        public void appendManageScr(GGrid grid, GGrid text, GuiSection sExta) {
            SPRITE s = new SPRITE.Imp(58, 14) {
                @Override
                public void render(SPRITE_RENDERER r, int X1, int X2, int Y1, int Y2) {
                    double d = 1.0 - I.this.p.service().load();
                    GMeter.render(r, GMeter.C_REDGREEN, d, X1, X2, Y1, Y2);
                }
            };
            RENDEROBJ h = new GHeader.HeaderHorizontal(SPRITES.icons().s.citizen, s) {
                @Override
                public void hoverInfoGet(GUI_BOX text) {
                    GBox b = (GBox)text;
                    b.title(ModuleService.¤¤USAGE);
                    b.textLL(ModuleService.¤¤Load);
                    b.tab(6);
                    b.add(GFORMAT.percInv(b.text(), I.this.p.service().load()));
                    b.NL();
                    b.text(ModuleService.¤¤USAGE_DESC);
                    b.NL(8);
                    b.textL(ModuleService.¤¤AVAILABLE);
                    b.add(GFORMAT.i(b.text(), I.this.p.service().available()));
                    b.NL();
                    b.textL(ModuleService.¤¤TOTAL);
                    b.add(GFORMAT.i(b.text(), I.this.p.service().total()));
                    b.NL(8);
                    I.this.p.service().appendCapacityTooltip(b, I.this.p.service().total());
                    b.NL(8);
                    b.textLL(¤¤RADIUS);
                    b.add(GFORMAT.i(b.text(), I.this.p.service().radius));
                    b.NL();
                    b.text(¤¤RADIUSD);
                }
            };
            grid.add(h);

            if (this.p instanceof RoomServiceAccess.ROOM_SERVICE_ACCESS_HASER) {
                grid.add((new GStat() {
                    @Override
                    public void update(GText text) {
                        RoomServiceAccess a = ((RoomServiceAccess.ROOM_SERVICE_ACCESS_HASER)I.this.p).service();
                        GFORMAT.perc(text, a.cityAccess());
                    }
                }).hh(SPRITES.icons().s.arrowUp).hoverInfoSet(ModuleService.¤¤ACCESS));
            }
        }

        @Override
        public void appendTableFilters(
                LISTE<GTableSorter.GTFilter<RoomInstance>> filters, LISTE<GTableSorter.GTSort<RoomInstance>> sorts, LISTE<UIRoomBulkApplier> appliers
        ) {
        }

        @Override
        public void appendButt(GuiSection s, final GETTER<RoomInstance> ins) {
            DOUBLE d = new DOUBLE() {
                @Override
                public double getD() {
                    return 1.0 - ((ROOM_SERVICER)ins.get()).service().load();
                }
            };
            s.addRelBody(16, DIR.E, SPRITES.icons().s.human);
            s.addRightC(2, new GMeter.GMeterSprite(GMeter.C_REDGREEN, d, 48, 12));
        }

        @Override
        public void hover(GBox box, Room room, int rx, int ry) {
            ROOM_SERVICER i = (ROOM_SERVICER)room;
            box.textL(ModuleService.¤¤AVAILABLE).add(GFORMAT.iofkInv(box.text(), i.service().available(), i.service().total()));
            box.NL();
            box.textL(ModuleService.¤¤Load);
            box.add(GFORMAT.percInv(box.text(), i.service().load()));
            box.NL();
            this.p.service().appendCapacityTooltip(box, i.service().total());
        }

        @Override
        public void problem(Stack<Str> free, LISTE<CharSequence> errors, LISTE<CharSequence> warnings, Room room, int rx, int ry) {
            if (((ROOM_SERVICER)room).service().available() == 0) {
                errors.add(ModuleService.¤¤NO);
            }
        }

        @Override
        public void appendPanel(GuiSection section, final GETTER<RoomInstance> get, int x1, int y1) {
            SPRITE s = new SPRITE.Imp(48, 16) {
                @Override
                public void render(SPRITE_RENDERER r, int X1, int X2, int Y1, int Y2) {
                    if (get.get().blueprintI() instanceof RoomService.ROOM_SERVICE_HASER) {
                        SETT.OVERLAY().service((RoomService.ROOM_SERVICE_HASER)get.get().blueprintI());
                    }

                    double d = 1.0 - I.this.g(get).service().load();
                    GMeter.render(r, GMeter.C_REDGREEN, d, X1, X2, Y1, Y2);
                }
            };
            RENDEROBJ r = new GHeader.HeaderHorizontal(ModuleService.¤¤USAGE, s) {
                @Override
                public void hoverInfoGet(GUI_BOX text) {
                    RoomServiceInstance i = I.this.g(get).service();
                    GBox b = (GBox)text;
                    b.textLL(ModuleService.¤¤Load);
                    b.tab(6);
                    b.add(GFORMAT.perc(b.text(), I.this.g(get).service().load()));
                    b.NL();
                    text.text(ModuleService.¤¤USAGE_DESC);
                    text.NL(8);
                    b.textL(ModuleService.¤¤AVAILABLE);
                    b.tab(6);
                    text.add(GFORMAT.i(b.text(), i.available()));
                    text.NL();
                    b.textL(ModuleService.¤¤USED);
                    b.tab(6);
                    text.add(GFORMAT.i(b.text(), i.total() - i.available()));
                    text.NL();
                    if (get.get() instanceof RoomInstance && get.get().blueprintI().employment() != null) {
                        b.textL(ModuleService.¤¤NEEDS);
                        b.tab(6);
                        text.add(GFORMAT.i(b.text(), i.total() - (i.available() - i.reserved())));
                        text.NL();
                    }

                    b.textL(ModuleService.¤¤TOTAL);
                    b.tab(6);
                    text.add(GFORMAT.i(b.text(), i.total()));
                    b.NL(8);
                    b.textL(ModuleService.¤¤QUALITY);
                    b.tab(6);
                    text.add(GFORMAT.perc(b.text(), I.this.g(get).quality()));
                    b.NL(8);
                    I.this.p.service().appendCapacityTooltip(b, i.total());
                    b.NL(8);
                    b.textLL(¤¤RADIUS);
                    b.add(GFORMAT.i(b.text(), I.this.p.service().radius));
                    b.NL();
                    b.text(¤¤RADIUSD);
                }
            };
            section.addRelBody(8, DIR.S, r);
        }

        private ROOM_SERVICER g(GETTER<RoomInstance> g) {
            return (ROOM_SERVICER)g.get();
        }
    }
}
