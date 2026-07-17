// ThalAIScannerScript.java
// Document Version 1.1.0
// Creation date: 2026/07/14
// Creator: Thalassicus

package thalassicus.archive;

import java.io.IOException;
import script.SCRIPT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;

//
// ============================================================================
// THAL AI SCANNER SCRIPT (entry point)
// ============================================================================
// This is the confirmed-correct entry point for getting our own code invoked
// safely, replacing an earlier attempt that crashed the entire game at the
// main menu. A static block calling GAME.addBeforeGameStarts() failed
// because the game's own mod-jar scanner (script.ScriptLoad's naming-
// collision check) calls Class.forName(...) on every class in every mod jar
// during menu-level cataloguing, long before any GAME instance exists -
// triggering our static initializer at the worst possible moment. SCRIPT is
// Jake's actual, intended mod entry point: ScriptEngine (itself a
// GAME.GameResource) calls createInstance() only from ScriptEngine.init(),
// which runs safely after GAME is fully constructed, then ticks the
// resulting SCRIPT_INSTANCE synchronously on the same single game thread
// every other subsystem uses - no new thread, no concurrency risk.
//
// Two overrides matter specifically for a background diagnostic tool like
// this one, both confirmed from SCRIPT's own default method bodies:
// - isSelectable() defaults to true, which would surface this as a
//   player-choosable option somewhere (most likely the world-gen script
//   picker, menu.ScRandom, glimpsed in the crash trace this replaced).
//   Overridden to false since this isn't a player-facing feature.
// - forceInit() defaults to false, and ScriptEngine only ever calls
//   createInstance() on a script that is either explicitly named in the
//   enabled-scripts list or has forceInit() == true. Since this isn't a
//   named, player-enabled script, forceInit() must return true or
//   createInstance() is simply never called at all.
//
// IMPORTANT: forceInit() alone cannot fully disable this script once it has
// ever run. ScriptEngine's own constructor decides whether to load a script
// via "map.containsKey(l.key) || l.script.forceInit()" - an OR, not
// forceInit() alone. map comes from the specific save's own enabled-scripts
// list (GameSpec.scripts), which appears to be captured and persisted into
// the save the first time a save is created/played with forceInit()==true.
// Confirmed the hard way: flipping forceInit() to false did not stop the
// scanner from initializing on an existing save (log showed the full
// construction sequence regardless), because that save had already recorded
// this script's key as enabled, independent of what forceInit() currently
// returns. Toggling forceInit() only prevents NEW saves from picking this
// script up; it cannot retroactively affect a save made while it was true.
//
// SCANNER_ENABLED below is the actual, reliable kill switch: it lives inside
// createInstance() itself, so even a save that still insists on loading this
// script gets back a genuine no-op SCRIPT_INSTANCE - no scanning, no
// logging, no save/load activity - regardless of what that save's own
// enabled-scripts list says.
// ============================================================================
//

public final class DEPRECATEDThalAIScannerScript implements SCRIPT {

  private static final boolean SCANNER_ENABLED = false;

  // A genuine no-op SCRIPT_INSTANCE, used in place of a real ThalAIScanner
  // when SCANNER_ENABLED is false. createInstance() cannot return null -
  // ScriptEngine calls update()/save()/load() on whatever it gets back
  // unconditionally - so this exists purely to safely absorb those calls
  // and do nothing, rather than skip constructing anything at all.
  private static final SCRIPT.SCRIPT_INSTANCE NOOP_INSTANCE = new SCRIPT.SCRIPT_INSTANCE() {
    @Override
    public void update(double ds) {
    }

    @Override
    public void save(FilePutter file) {
    }

    @Override
    public void load(FileGetter file) throws IOException {
    }
  };

  private static final CharSequence NAME = "Thal AI Module Scanner";
  private static final CharSequence DESCRIPTION =
      "Internal diagnostic tool. Samples active AI modules across the population on a fixed in-game-time interval and logs aggregate counts to a CSV file. Not a gameplay-affecting script.";

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
    return SCANNER_ENABLED; // see the class-level note above - this alone cannot fully disable an already-loaded save
  }

  @Override
  public void initBeforeGameCreated() {
    if (SCANNER_ENABLED) {
      DEPRECATEDThalAIScanner.log.info("ThalAIScannerScript.initBeforeGameCreated() called - descriptor is being reached by ScriptEngine.");
    }
  }

  @Override
  public void initBeforeGameInited() {
    if (SCANNER_ENABLED) {
      DEPRECATEDThalAIScanner.log.info("ThalAIScannerScript.initBeforeGameInited() called.");
    }
  }

  @Override
  public SCRIPT.SCRIPT_INSTANCE createInstance() {
    if (!SCANNER_ENABLED) {
      return NOOP_INSTANCE;
    }

    DEPRECATEDThalAIScanner.log.info("ThalAIScannerScript.createInstance() called - about to construct ThalAIScanner.");
    return new DEPRECATEDThalAIScanner();
  }
}
