// ThalAIScannerScript.java
// Document Version 1.0.1
// Creation date: 2026/07/14
// Creator: Thalassicus

package thalassicus.serviceestimate;

import script.SCRIPT;

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
// ============================================================================
//

public final class ThalAIScannerScript implements SCRIPT {

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
    return true;
  }

  @Override
  public void initBeforeGameCreated() {
    ThalAIScanner.log.info("ThalAIScannerScript.initBeforeGameCreated() called - descriptor is being reached by ScriptEngine.");
  }

  @Override
  public void initBeforeGameInited() {
    ThalAIScanner.log.info("ThalAIScannerScript.initBeforeGameInited() called.");
  }

  @Override
  public SCRIPT.SCRIPT_INSTANCE createInstance() {
    ThalAIScanner.log.info("ThalAIScannerScript.createInstance() called - about to construct ThalAIScanner.");
    return new ThalAIScanner();
  }
}
