// ThalSavable.java
// Document Version 1.1.0
// Creation date: 2026/07/16
// Creator: Thalassicus

package thalassicus.util;

import game.GameDisposable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import script.SCRIPT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.SAVABLE;
import snake2d.util.file.SuperSaver;
import snake2d.util.sets.ArrayListGrower;

// A small, generic named-value store for persisting simple double values
// across a save/load cycle, without touching the core save file's own byte
// layout (unlike RoomService's own SAVABLE, whose fixed-shape stream would
// misalign on any field added or removed). Any class, in any package, can
// call ThalSavable.instance().set(key, value) whenever a value changes, and
// .get(key, defaultValue) whenever it needs the last-known value back -
// including after a save/load cycle, since this class's own save()/load()
// (called by ScriptEngine, same mechanism proven by ThalAIScanner) persist
// the whole map as a unit.
//
// A second, independent mechanism (register()/the SAVABLE registry below)
// exists alongside the scalar set()/get() map rather than replacing it,
// since the two have genuinely different timing profiles. The scalar path
// is a PULL: a caller reaches in for a value whenever convenient, tolerant
// of ThalSavable not existing yet (falls back to a default). The registry
// is effectively a PUSH-by-reference: a caller hands over a mutable
// SAVABLE object ONCE, at construction time, then keeps mutating that same
// object directly for the rest of the session - so whatever ThalSavable
// eventually reads at save time is automatically current. This mirrors
// EVENTS.java's own precedent exactly: every EventResource self-registers
// into a static list via its own constructor, and EVENTS's own SuperSaver
// is built afterward from that already-complete list. Confirmed from
// GAME's own constructor order that RoomService instances (constructed
// during "new SETT()") exist well before ScriptEngine.init(GAME) ever
// constructs a ThalSavable instance - so registration during a
// RoomService's own constructor is always safely "before", never a race.
//
// Registry key convention: "<owner-key>_<purpose>", e.g.
// "PHYSICIAN_NORMAL_eventPeak" - not just the owner's own key, since the
// registry is meant to be reusable by more than one caller per owner over
// time, and a bare owner key would collide the moment a second purpose
// registers against the same owner.
//
// A single class serves as both the SCRIPT descriptor and the
// SCRIPT_INSTANCE, for simplicity - but createInstance() deliberately
// constructs a NEW instance rather than returning "this": the descriptor
// object itself is constructed once via reflection at discovery time and
// cached for the entire process (see ScriptLoad.cache), so returning it
// directly from createInstance() would let one save's data bleed into the
// next save loaded in the same running game, since Map state would never
// reset between sessions. A fresh instance per session, exactly like
// ThalAIScanner/ThalAIScannerScript's two-class split already does, avoids
// this without needing a second file. The registry list below is reset by
// the same GameDisposable mechanism Jake uses for RoomServiceAccess.all/
// AIModule.all, for the identical reason: a fresh game/save must start
// from an empty registry, not one still holding entries from whatever was
// previously loaded in this same running process.
public final class ThalSavable implements SCRIPT, SCRIPT.SCRIPT_INSTANCE {

  public static final ThalsLogger log = new ThalsLogger(
      ThalsLogger.INFO,
      System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalSavable.log"
  );

  private static ThalSavable instance;

  public static ThalSavable instance() {
    return instance;
  }

  private static final CharSequence NAME = "Thal Savable Store";
  private static final CharSequence DESCRIPTION =
      "Internal utility. A generic named-value store for persisting simple double values and SAVABLE objects across save/load, reusable by any class in this mod that needs a small cache surviving a save cycle. Not a gameplay-affecting script.";

  private final Map<String, Double> values = new HashMap<>();

  // Pairs a registry key with the live SAVABLE object registered under it.
  // A tiny holder class exists only because SuperSaver<T> needs a T to key
  // by; SAVABLE itself (snake2d.util.file.SAVABLE) carries no key of its
  // own the way game.save.Savable does.
  private static final class RegisteredEntry {
    final String key;
    final SAVABLE savable;

    RegisteredEntry(String key, SAVABLE savable) {
      this.key = key;
      this.savable = savable;
    }
  }

  private static final ArrayListGrower<RegisteredEntry> registry = new ArrayListGrower<>();

  static {
    new GameDisposable() {
      @Override
      protected void dispose() {
        registry.clear();
      }
    };
  }

  // Registers a SAVABLE to be persisted alongside this store, under the
  // <owner-key>_<purpose> convention documented in the class comment.
  // Must be called before ThalSavable's own instance exists this session
  // (i.e. before ScriptEngine.init(GAME) runs createInstance()) - in
  // practice, this means calling it from the registering object's own
  // constructor, matching EVENTS.java's own precedent. Two failure modes,
  // both logged at ERROR rather than silently ignored, since both
  // represent a programmer-logic mistake rather than a normal runtime
  // condition:
  //   - called too late (instance != null already) - the registrar missed
  //     its window and will not be included in this session's save/load;
  //   - called twice with the same key - a genuine collision between two
  //     call sites, silently overwriting the first registration would be
  //     a worse failure than a loud one, so the second call is declined.
  public static void register(String key, SAVABLE savable) {
    if (instance != null) {
      log.error(
          "register(%s) called after this session's ThalSavable instance already exists - too late to be included in save/load. Registration must happen before ThalSavable's own construction (e.g. in a RoomService constructor, not inside load()).",
          key
      );
      return;
    }

    for (RegisteredEntry entry : registry) {
      if (entry.key.equals(key)) {
        log.error(
            "register(%s) called twice with the same key - the second registration was ignored. Registry keys must be unique; use the <owner-key>_<purpose> convention to avoid collisions between different callers sharing an owner key.",
            key
        );
        return;
      }
    }

    registry.add(new RegisteredEntry(key, savable));
  }

  public void set(String key, double value) {
    this.values.put(key, value);
  }

  public double get(String key, double defaultValue) {
    Double value = this.values.get(key);
    return value == null ? defaultValue : value;
  }

  @Override
  public CharSequence name() {
    return NAME;
  }

  @Override
  public CharSequence desc() {
    return DESCRIPTION;
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

  // See the class-level note above for why this constructs fresh rather
  // than returning "this".
  @Override
  public SCRIPT.SCRIPT_INSTANCE createInstance() {
    ThalSavable created = new ThalSavable();
    instance = created;
    log.info("ThalSavable.createInstance() called - fresh instance for this session, %d registered SAVABLE entries.", registry.size());
    return created;
  }

  // Built from whatever is in the static registry at construction time -
  // safe, since every registrant (e.g. RoomService) constructs during
  // "new SETT()", well before createInstance() ever runs this constructor.
  // Reuses Jake's own SuperSaver exactly as EVENTS.java does: a count-
  // then-per-entry-key-length-payload format, tolerant of a saved key with
  // no matching live entry (a removed room type) or a live entry with no
  // saved data (a newly added one).
  private final SuperSaver<RegisteredEntry> registrySaver = new SuperSaver<RegisteredEntry>(ThalSavable.class, registry) {
    @Override
    protected String key(RegisteredEntry t) {
      return t.key;
    }

    @Override
    protected void save(RegisteredEntry t, FilePutter f) {
      t.savable.save(f);
    }

    @Override
    protected void load(RegisteredEntry t, FileGetter f) throws IOException {
      t.savable.load(f);
    }

    @Override
    protected void clear(RegisteredEntry t) {
      t.savable.clear();
    }
  };

  @Override
  public void update(double deltaSeconds) {
  }

  // Flat count-then-key/value pairs for the scalar map, followed by the
  // SAVABLE registry via FilePutter's own save(SAVABLE) convenience
  // (length-prefixed, so a future ThalSavable version that changes the
  // registry's own internal shape could still skip it cleanly).
  @Override
  public void save(FilePutter file) {
    log.info("Saving %d scalar entries.", this.values.size());
    file.i(this.values.size());
    for (Map.Entry<String, Double> entry : this.values.entrySet()) {
      file.chars(entry.getKey());
      file.d(entry.getValue());
    }

    file.save(this.registrySaver);
  }

  @Override
  public void load(FileGetter file) throws IOException {
    this.values.clear();
    int count = file.i();
    for (int entryIndex = 0; entryIndex < count; entryIndex++) {
      String key = file.chars();
      double value = file.d();
      this.values.put(key, value);
    }

    // Timing check: compare this line's timestamp against
    // RoomService[...].saver.load() lines in the same log file, to confirm
    // ThalSavable's own load() actually runs before any RoomService tries
    // to pull a cached value from it.
    log.info("Loaded %d scalar entries.", this.values.size());

    file.load(this.registrySaver);
  }
}
