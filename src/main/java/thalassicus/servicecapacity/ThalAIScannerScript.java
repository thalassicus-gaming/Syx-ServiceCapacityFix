// ThalAIScannerScript.java
// Document Version 1.0.0
// Creation date: 2026/07/16
// Creator: Thalassicus

package thalassicus.servicecapacity;

import java.io.IOException;
import script.SCRIPT;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;

//
// ============================================================================
// THAL AI SCANNER SCRIPT (entry point)
// ============================================================================
// SCRIPT is Jake's actual, intended mod entry point: ScriptEngine (a
// GAME.GameResource) calls createInstance() only from ScriptEngine.init(),
// which runs safely after GAME is fully constructed, then ticks the
// resulting SCRIPT_INSTANCE synchronously on the same single game thread
// every other subsystem uses - no new thread, no concurrency risk. A static
// block reacting to class-load time is NOT safe for this same purpose - the
// game's own mod-jar collision-scanner calls Class.forName(...) on every
// class in every mod jar during main-menu-level cataloguing, before any GAME
// instance exists.
//
// isSelectable() defaults to true, which would surface this as a
// player-choosable option; overridden to false since this isn't a
// player-facing feature. forceInit() defaults to false, and ScriptEngine
// only ever calls createInstance() on a script that is either explicitly
// named in a save's enabled-scripts list or has forceInit() == true. Since
// this isn't a named, player-enabled script, forceInit() must return true
// or createInstance() is never called at all.
//
// IMPORTANT: forceInit() alone cannot fully disable this script once a save
// has ever loaded it - ScriptEngine decides via
// "map.containsKey(l.key) || l.script.forceInit()", an OR, and map comes
// from that save's own persisted enabled-scripts list. Toggling forceInit()
// only affects NEW saves; SCANNER_ENABLED below is the actual, reliable
// kill switch, since it lives inside createInstance() itself and returns a
// genuine no-op SCRIPT_INSTANCE regardless of what a save's enabled-scripts
// list says.
// ============================================================================
//

public final class ThalAIScannerScript implements SCRIPT {

  private static final boolean SCANNER_ENABLED = true;

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

  private static final CharSequence NAME = "Thal Service Capacity Scanner";
  private static final CharSequence DESCRIPTION =
      "Internal diagnostic tool. Tracks rolling-average service occupancy and religion follower counts per service blueprint, feeding the Service Estimate Fix capacity estimates. Not a gameplay-affecting script.";

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
      ThalAIScanner.log.info("ThalAIScannerScript.initBeforeGameCreated() called - descriptor is being reached by ScriptEngine.");
    }
  }

  @Override
  public void initBeforeGameInited() {
    if (SCANNER_ENABLED) {
      ThalAIScanner.log.info("ThalAIScannerScript.initBeforeGameInited() called.");
    }
  }

  @Override
  public SCRIPT.SCRIPT_INSTANCE createInstance() {
    if (!SCANNER_ENABLED) {
      return NOOP_INSTANCE;
    }

    ThalAIScanner.log.info("ThalAIScannerScript.createInstance() called - about to construct ThalAIScanner.");
    return new ThalAIScanner();
  }
}
