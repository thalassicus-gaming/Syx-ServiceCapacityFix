// ThalsLogger.java
// Document Version 1.0.0
// Creation date: 2026/07/12
// Creator: Thalassicus

package thalassicus.util;

import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalTime;

//
// ============================================================================
// THALS LOGGER
// ============================================================================
// Reusable file logger for Songs of Syx mods, translated from Victoria's
// Godot/C# Logger class (used in Calliope and other projects).
//
// The game's own LOG.ln() does not write to a file or visible console in the
// normal (non-IDE) launch path, making it useless for debugging installed
// mods. This class writes directly to a chosen file via plain Java I/O, with
// leveled logging (ERROR/WARN/INFO/TRACE) matching the original design.
//
// USAGE:
//   private static final ThalsLogger log = new ThalsLogger(ThalsLogger.TRACE,
//       "C:\\Users\\Thala\\Desktop\\IndustryInsights.log");
//   log.info("Installed successfully, patched %d rooms.", patched);
//
// Copy this file (unchanged) into any new mod's package to reuse.
// ============================================================================
//

public class ThalsLogger {
    public static final int NONE = 0;
    public static final int ERROR = 1;
    public static final int WARN = 2;
    public static final int INFO = 3;
    public static final int TRACE = 4;

    public int level;
    public String fileName;

    // Whether this session has written to the log file yet. The FIRST write
    // each game launch TRUNCATES the file (append=false) instead of
    // appending to whatever's left over from the previous session - without
    // this, the log grows indefinitely across every launch, mixing old and
    // new session output together. Every write AFTER the first appends
    // normally, so multiple log() calls within one session still accumulate
    // correctly.
    private boolean truncatedThisSession = false;

    public ThalsLogger(int level) {
        this(level, "");
    }

    public ThalsLogger(int level, String fileName) {
        this.level = level;
        this.fileName = fileName;
    }

    private void log(String prefix, String message, Object[] list) {
        String body = list.length > 0 ? String.format(message, list) : message;
        System.out.println(prefix + body);
        if (!fileName.isEmpty()) {
            boolean append = this.truncatedThisSession;
            try (FileWriter file = new FileWriter(fileName, append)) {
                file.write(String.format("%-16s %s%s%n", LocalTime.now(), prefix, body));
                this.truncatedThisSession = true;
            } catch (IOException e) {
                // nowhere to log the logging failure; ignore
            }
        }
    }

    public void error(String message, Object... list) {
        if (level >= ERROR) log("ERROR: ", message, list);
    }

    public void warn(String message, Object... list) {
        if (level >= WARN) log("WARN:  ", message, list);
    }

    public void info(String message, Object... list) {
        if (level >= INFO) log("INFO:  ", message, list);
    }

    public void trace(String message, Object... list) {
        if (level >= TRACE) log("TRACE: ", message, list);
    }
}
