// ThalSavable.java
// Document Version 1.0.0
// Creation date: 2026/07/16
// Creator: Thalassicus

package thalassicus.util;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import script.SCRIPT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;

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
// A single class serves as both the SCRIPT descriptor and the
// SCRIPT_INSTANCE, for simplicity - but createInstance() deliberately
// constructs a NEW instance rather than returning "this": the descriptor
// object itself is constructed once via reflection at discovery time and
// cached for the entire process (see ScriptLoad.cache), so returning it
// directly from createInstance() would let one save's data bleed into the
// next save loaded in the same running game, since Map state would never
// reset between sessions. A fresh instance per session, exactly like
// ThalAIScanner/ThalAIScannerScript's two-class split already does, avoids
// this without needing a second file.
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
      "Internal utility. A generic named-value store for persisting simple double values across save/load, reusable by any class in this mod that needs a small cache surviving a save cycle. Not a gameplay-affecting script.";

  private final Map<String, Double> values = new HashMap<>();

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
    log.info("ThalSavable.createInstance() called - fresh instance for this session.");
    return created;
  }

  @Override
  public void update(double deltaSeconds) {
  }

  // Flat count-then-key/value pairs. Unlike a blueprint-keyed save (which
  // must reconcile a fixed set of expected keys against whatever's on
  // disk), this store simply remembers whatever it's told and returns
  // whatever it's asked for - so load() just repopulates the map directly,
  // with no recognized/unrecognized distinction needed.
  @Override
  public void save(FilePutter file) {
    log.info("Saving %d entries.", this.values.size());
    file.i(this.values.size());
    for (Map.Entry<String, Double> entry : this.values.entrySet()) {
      file.chars(entry.getKey());
      file.d(entry.getValue());
    }
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
    log.info("Loaded %d entries.", this.values.size());
  }
}
