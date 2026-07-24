// ThalCapacityProfileManager.java
// Document Version 1.5.0
// Creation date: 2026/07/18
// Creator: Thalassicus

package thalassicus.capacity;

import game.time.TIME;
import init.race.RACES;
import init.race.Race;
import init.type.HTYPE;
import init.type.HTYPES;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import script.SCRIPT;
import settlement.stats.STATS;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.Json;
import snake2d.util.file.JsonE;
import thalassicus.util.ThalsLogger;

// Owns the loaded profile collection, active profile, persistence, and
// game-world interactions. ThalCapacityProfile remains a passive data
// object; all collection and filesystem operations are centralized here.
public final class ThalCapacityProfileManager implements SCRIPT, SCRIPT.SCRIPT_INSTANCE {
    public static final ThalsLogger log = new ThalsLogger(
            ThalsLogger.INFO,
            System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalCapacityProfileManager.log"
    );
    private static final CharSequence NAME = "Thal Capacity Profile Manager";
    private static final CharSequence DESCRIPTION = "Internal utility. Manages saved capacity-planning profiles and which one is active for this save. Not a gameplay-affecting script.";
    private static final Path PROFILES_DIRECTORY = Path.of(System.getenv("APPDATA"), "songsofsyx", "mods", "Service Estimate Fix", "Profiles");
    private static final double MIN_CAPACITY_PER_SLOT = 0.99;
    // Debug-only: refreshes a reserved profile from live data once per day.
    private static final boolean DAILY_REFRESH_ENABLED = false;
    private static final String DEBUG_PROFILE_NAME = "debug_profile";
    // Reserved serialized name for the shipped default profile.
    //
    // A default profile is optional. If none exists, activeProfile remains
    // null and callers fall back to built-in defaults.
    private static final String DEFAULT_PROFILE_FILE_NAME = "default_profile";
    private static ThalCapacityProfileManager instance;
    private final List<ThalCapacityProfile> loadedProfiles = new ArrayList<>();
    private ThalCapacityProfile activeProfile;
    private int lastRefreshDay = -1;

    //
    // Public Methods
    //

    public ThalCapacityProfileManager() {
        this.loadAllProfiles();
        this.applyDefaultProfileIfNoneActive();
    }

    public static ThalCapacityProfileManager instance() {
        return instance;
    }


    /*
     * Central entry point for every stored-profile mutation.
     *
     * Invariant:
     *
     * loadedProfiles owns the canonical in-memory representation of every stored
     * profile. profileToAdd is always deep-copied before insertion so the manager
     * never shares object identity with higher layers.
     *
     * This intentionally separates stored state from editable state. For example,
     * ThalCapacityUI keeps a long-lived scratch profile, while the manager owns its
     * own persistent copy. Saving therefore does not require the UI to replace its
     * working object with the manager's stored instance, avoiding reference
     * aliasing between the editing and persistence layers.
     *
     * Operations:
     *
     * +-----------------+-----------------+------------------------------+
     * | profileToRemove | profileToAdd    | Operation                    |
     * +-----------------+-----------------+------------------------------+
     * | null            | non-null        | Create or overwrite          |
     * | non-null        | null            | Delete                       |
     * | non-null        | non-null        | Rename or replace            |
     * | null            | null            | Invalid (throws exception)   |
     * +-----------------+-----------------+------------------------------+
     *
     * When both arguments are non-null, the new profile is stored before the old
     * profile is removed. This ordering guarantees that a failed remove operation
     * cannot destroy newly-written data.
     *
     * Name-collision policy belongs to the caller. By the time this method is
     * invoked, any required overwrite confirmation has already occurred.
     */
    public UpdateResult updateProfiles(ThalCapacityProfile profileToRemove, ThalCapacityProfile profileToAdd) {
        if (profileToRemove == null && profileToAdd == null) {
            throw new IllegalArgumentException("updateProfiles() requires at least one non-null argument.");
        }

        ThalCapacityProfile storedProfile = null;
        boolean succeeded = true;

        if (profileToAdd != null) {
            UpdateResult storeResult = this.storeProfile(profileToAdd);
            storedProfile = storeResult.storedProfile();
            succeeded = storeResult.succeeded();
        }

        if (profileToRemove != null && !this.serializedNamesMatch(profileToRemove, profileToAdd)) {
            succeeded &= this.removeProfile(profileToRemove);
        }

        // Leave loadedProfiles as whatever was successfully read so far.

        this.sortLoadedProfiles();
        return new UpdateResult(storedProfile, succeeded);
    }

    // storedProfile is null only for pure deletions.
    // succeeded reports whether every attempted disk operation completed.
    public record UpdateResult(ThalCapacityProfile storedProfile, boolean succeeded) {
    }

    public List<ThalCapacityProfile> loadedProfiles() {
        return this.loadedProfiles;
    }

    public ThalCapacityProfile activeProfile() {
        return this.activeProfile;
    }

    // Null means "no active profile"; callers fall back to defaults.
    public void activeProfileSet(ThalCapacityProfile activeProfile) {
        this.activeProfile = activeProfile;
    }

    // Accepts either a display name or an already-serialized name.
    // Re-sanitizing an already-serialized name is a no-op.
    public ThalCapacityProfile findProfileBySerializedName(String displayName) {
        String targetSerializedName = sanitizeFileName(displayName);
        for (ThalCapacityProfile profile : this.loadedProfiles) {
            if (sanitizeFileName(profile.displayName()).equals(targetSerializedName)) {
                return profile;
            }
        }
        return null;
    }

    // Exposes default-profile detection without leaking the serialized name
    // or filename sanitization rules.
    public boolean isDefaultProfileName(String displayName) {
        return sanitizeFileName(displayName).equals(DEFAULT_PROFILE_FILE_NAME);
    }

    // Skip unreadable profile files rather than aborting the entire load.
    public void loadAllProfiles() {
        this.loadedProfiles.clear();
        if (!Files.isDirectory(PROFILES_DIRECTORY)) {
            return;
        }

        try (var paths = Files.list(PROFILES_DIRECTORY)) {
            for (Path path : paths.toList()) {
                try {
                    this.loadedProfiles.add(loadProfileFromFile(path));
                } catch (Exception e) {
                }
            }
        } catch (IOException e) {
        }

        // Leave loadedProfiles as whatever was successfully read so far.

        this.sortLoadedProfiles();
    }

    // Clears existing data so removed blueprints do not leave stale entries.
    public void populateFromLiveData(ThalCapacityProfile profile) {
        profile.clear();
        this.captureCapacitiesPerSlot(profile);
        this.captureSpeciesAndHTypePopulations(profile);
    }



    //
    // Private Methods
    //

    private static ThalCapacityProfile loadProfileFromFile(Path path) {
        Json json = new Json(path);
        return ThalCapacityProfile.deserialize(json);
    }

    // Preserve lowercase letters and digits; replace all other characters
    // with underscores.
    //
    // Locale.ROOT avoids locale-dependent lowercase conversions.
    //
    // Warning: no length cap - an unusually long display name produces an
    // equally long filename, with no truncation against OS path-length limits.
    private static String sanitizeFileName(String displayName) {
        return displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    private boolean serializedNamesMatch(ThalCapacityProfile profileA, ThalCapacityProfile profileB) {
        if (profileB == null) {
            return false;
        }
        return sanitizeFileName(profileA.displayName()).equals(sanitizeFileName(profileB.displayName()));
    }

    // Replaces any existing profile with the same serialized name.
    // If the replaced profile was active, activeProfile is redirected to the
    // replacement instance.
    private UpdateResult storeProfile(ThalCapacityProfile profileToAdd) {
        ThalCapacityProfile storedProfile = ThalCapacityProfile.deepCopy(profileToAdd);

        ThalCapacityProfile existingProfile = this.findProfileBySerializedName(storedProfile.displayName());
        if (existingProfile != null) {
            this.loadedProfiles.remove(existingProfile);
            if (this.activeProfile == existingProfile) {
                this.activeProfile = storedProfile;
            }
        }

        this.loadedProfiles.add(storedProfile);
        boolean succeeded = this.saveProfile(storedProfile);
        return new UpdateResult(storedProfile, succeeded);
    }

    private boolean removeProfile(ThalCapacityProfile profile) {
        boolean succeeded = this.deleteProfile(profile);
        this.loadedProfiles.remove(profile);
        if (this.activeProfile == profile) {
            this.activeProfile = null;
        }
        return succeeded;
    }


    private void sortLoadedProfiles() {
        this.loadedProfiles.sort(Comparator.comparing(ThalCapacityProfile::displayName, String.CASE_INSENSITIVE_ORDER));
    }

    private void applyDefaultProfileIfNoneActive() {
        if (this.activeProfile == null) {
            this.activeProfile = this.findProfileBySerializedName(DEFAULT_PROFILE_FILE_NAME);
        }
    }

    private boolean saveProfile(ThalCapacityProfile profile) {
        JsonE json = profile.serialize();

        try {
            Files.createDirectories(PROFILES_DIRECTORY);
        } catch (IOException e) {
            return false;
        }

        return json.save(this.profileFilePath(profile.displayName()));
    }

    private boolean deleteProfile(ThalCapacityProfile profile) {
        try {
            return Files.deleteIfExists(this.profileFilePath(profile.displayName()));
        } catch (IOException e) {
            return false;
        }
    }

    private Path profileFilePath(String displayName) {
        return PROFILES_DIRECTORY.resolve(sanitizeFileName(displayName) + ".txt");
    }

    // Record only observed capacities. Services with no live data are left
    // absent rather than storing hypothetical values.
    private void captureCapacitiesPerSlot(ThalCapacityProfile profile) {
        ThalRoomServiceRegistry.roomServicesByKey().values().forEach(service -> this.captureCapacity(profile, service.room().key, service.liveCapacityPerSlot()));
    }

    private void captureCapacity(ThalCapacityProfile profile, String blueprintKey, double capacityPerSlot) {
        if (capacityPerSlot >= MIN_CAPACITY_PER_SLOT) {
            profile.capacityPerSlotSet(blueprintKey, capacityPerSlot);
        }
    }

    // Captures informational population totals for profile comparison.
    // Uses STATS.POP() directly rather than scanning live entities.
    private void captureSpeciesAndHTypePopulations(ThalCapacityProfile profile) {
        for (Race race : RACES.all()) {
            double total = 0.0;
            for (HTYPE hType : HTYPES.ALL()) {
                total += STATS.POP().pop(race, hType);
            }

            if (total > 0.0) {
                profile.speciesPopulationSet(race.key, total);
            }
        }

        for (HTYPE hType : HTYPES.ALL()) {
            double total = 0.0;
            for (Race race : RACES.all()) {
                total += STATS.POP().pop(race, hType);
            }

            if (total > 0.0) {
                profile.htypePopulationSet(hType.key, total);
            }
        }
    }

    private void refreshDebugProfile() {
        ThalCapacityProfile debugProfile = this.findProfileBySerializedName(DEBUG_PROFILE_NAME);
        if (debugProfile == null) {
            debugProfile = ThalCapacityProfile.blank(DEBUG_PROFILE_NAME, "Debug: refreshed once per day from this session's own live data.");
            this.loadedProfiles.add(debugProfile);
        }

        this.populateFromLiveData(debugProfile);
        this.saveProfile(debugProfile);
    }

    //
    // Overrides
    //

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

    @Override
    public SCRIPT.SCRIPT_INSTANCE createInstance() {
        ThalCapacityProfileManager created = new ThalCapacityProfileManager();
        instance = created;
        log.info("ThalCapacityProfileManager.createInstance() called - fresh instance for this session, %d profiles loaded.", created.loadedProfiles.size());
        return created;
    }

    @Override
    // Debug scaffolding only.
    public void update(double deltaSeconds) {
        if (!DAILY_REFRESH_ENABLED) {
            return;
        }

        int currentDay = TIME.days().bitsSinceStart();
        if (currentDay != this.lastRefreshDay) {
            this.lastRefreshDay = currentDay;
            this.refreshDebugProfile();
        }
    }

    @Override
    // Persist only the active profile reference. Profile contents are loaded directly from disk each session.
    public void save(FilePutter file) {
        file.chars(this.activeProfile == null ? "" : this.activeProfile.displayName());
    }

    @Override
    // Resolve the saved active profile by serialized name rather than exact display-name equality.
    public void load(FileGetter file) throws IOException {
        String activeProfileName = file.chars();
        this.activeProfile = activeProfileName.isEmpty() ? null : this.findProfileBySerializedName(activeProfileName);
        this.applyDefaultProfileIfNoneActive();
    }
}