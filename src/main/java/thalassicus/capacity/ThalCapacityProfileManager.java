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

// Owns the loaded-profile collection, which one is active for planning
// purposes, and all filesystem/game-world reaching-in ThalCapacityProfile
// itself deliberately does not do. updateProfiles() is the one coordinated
// entry point for every add/remove/rename the UI performs against stored
// profiles; profiles are identified by their serialized (filesystem-safe)
// name, since that's the level uniqueness is actually enforced at.
public final class ThalCapacityProfileManager implements SCRIPT, SCRIPT.SCRIPT_INSTANCE {

    public static final ThalsLogger log = new ThalsLogger(
            ThalsLogger.INFO,
            System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalCapacityProfileManager.log"
    );

    private static ThalCapacityProfileManager instance;

    public static ThalCapacityProfileManager instance() {
        return instance;
    }

    private static final CharSequence NAME = "Thal Capacity Profile Manager";
    private static final CharSequence DESCRIPTION = "Internal utility. Manages saved capacity-planning profiles and which one is active for this save. Not a gameplay-affecting script.";

    private static final Path PROFILES_DIRECTORY = Path.of(System.getenv("APPDATA"), "songsofsyx", "mods", "Service Estimate Fix", "Profiles");

    private static final double MIN_CAPACITY_PER_SLOT = 0.99;

    // Debug scaffolding: refreshes a reserved profile once per day from
    // this session's own live data, independent of the real UI panel's own
    // Live Data selection - a way to inspect capture output directly.
    private static final boolean DAILY_REFRESH_ENABLED = false;
    private static final String DEBUG_PROFILE_NAME = "debug_profile";

    // The reserved serialized name a shipped default profile must resolve
    // to. A player is never required to have one - if none is found,
    // activeProfile simply stays null, and every capacity/population field
    // falls back to its built-in default, the same as any other undefined
    // entry.
    private static final String DEFAULT_PROFILE_FILE_NAME = "default_profile";

    private final List<ThalCapacityProfile> loadedProfiles = new ArrayList<>();
    private ThalCapacityProfile activeProfile;
    private int lastRefreshDay = -1;

    public ThalCapacityProfileManager() {
        this.loadAllProfiles();
        this.applyDefaultProfileIfNoneActive();
    }

    public List<ThalCapacityProfile> loadedProfiles() {
        return this.loadedProfiles;
    }

    public ThalCapacityProfile activeProfile() {
        return this.activeProfile;
    }

    // Nullable by design - see DEFAULT_PROFILE_FILE_NAME's own comment.
    public void activeProfileSet(ThalCapacityProfile activeProfile) {
        this.activeProfile = activeProfile;
    }

    // The one coordinated entry point for every stored-profile change:
    // create, overwrite, delete, and rename (delete-plus-create under a
    // different name) are all expressed as some combination of these two
    // arguments, rather than as separate named operations. Deliberately
    // does NOT refuse or prompt on a name collision - that confirmation is
    // the UI's own responsibility (findProfileBySerializedName lets it
    // check first), not this method's; by the time this is called, the
    // caller has already decided to proceed.
    //
    // profileToAdd is deep-copied before storing, so the caller's own
    // in-memory copy is never aliased with what ends up in loadedProfiles.
    //
    // UpdateResult.succeeded() is the AND of every disk operation actually
    // attempted (the store's write, the remove's delete, whichever of the
    // two ran) - a failure on EITHER side reports the whole call as
    // failed, even in the specific case where the store half genuinely
    // succeeded and only the OLD file's deletion (during a rename) failed
    // to clean up. That's a real, if fairly benign, asymmetry: the new
    // data is always safe regardless (create-before-remove), so a
    // caller that wants to treat "wrote fine, stray old file left behind"
    // as a soft, still-proceed-anyway success rather than a hard failure
    // would need its own finer-grained handling - this method reports the
    // simpler, more conservative "everything attempted must have worked."
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

        this.sortLoadedProfiles();
        return new UpdateResult(storedProfile, succeeded);
    }

    // storedProfile is null only when updateProfiles() was a pure remove
    // (profileToAdd == null) - distinct from succeeded being false, which
    // signals an actual disk-operation failure. A caller needs both: which
    // profile is now canonical, and whether it's safe to trust that the
    // requested change actually persisted.
    public record UpdateResult(ThalCapacityProfile storedProfile, boolean succeeded) {
    }

    private boolean serializedNamesMatch(ThalCapacityProfile profileA, ThalCapacityProfile profileB) {
        if (profileB == null) {
            return false;
        }
        return sanitizeFileName(profileA.displayName()).equals(sanitizeFileName(profileB.displayName()));
    }

    // Overwrites any existing profile sharing profileToAdd's serialized
    // name. If that existing profile happened to be the active one,
    // activeProfile is re-pointed at its replacement rather than left
    // dangling on an object no longer in loadedProfiles. The in-memory add
    // always happens regardless of whether the disk write below succeeds -
    // only the persisted-to-disk half of this operation can actually fail.
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

    // Sanitizes displayName the same way stored profiles are matched
    // against, so a caller can pass either a raw display name or an
    // already-serialized one (DEFAULT_PROFILE_FILE_NAME, DEBUG_PROFILE_NAME)
    // interchangeably - re-sanitizing an already-sanitized string is a
    // no-op.
    //
    // Profiles saved before this file's sanitizeFileName fix may
    // have been written under names that now collide with a different
    // profile under the corrected scheme (e.g. two legacy files that only
    // differed by a digit). loadAllProfiles() does not currently detect or
    // reconcile that; this method will simply return whichever colliding
    // profile appears first in loadedProfiles.
    public ThalCapacityProfile findProfileBySerializedName(String displayName) {
        String targetSerializedName = sanitizeFileName(displayName);
        for (ThalCapacityProfile profile : this.loadedProfiles) {
            if (sanitizeFileName(profile.displayName()).equals(targetSerializedName)) {
                return profile;
            }
        }
        return null;
    }

    // True when displayName would serialize to the same file the shipped
    // default profile occupies. Exposed as its own method rather than
    // making DEFAULT_PROFILE_FILE_NAME or sanitizeFileName public, so the
    // UI can ask the question without re-implementing (and eventually
    // drifting from) either - the comparison has to go through
    // sanitizeFileName to be meaningful at all, since "Default Profile",
    // "default profile", and "default_profile" all serialize identically.
    public boolean isDefaultProfileName(String displayName) {
        return sanitizeFileName(displayName).equals(DEFAULT_PROFILE_FILE_NAME);
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

    private static ThalCapacityProfile loadProfileFromFile(Path path) {
        Json json = new Json(path);
        return ThalCapacityProfile.deserialize(json);
    }

    // A profile file that fails to load (corrupt, hand-edited into an
    // invalid state) is skipped rather than aborting the whole refresh -
    // one bad file shouldn't hide every other valid profile from the
    // player.
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
                    // Skip: one unreadable profile file shouldn't prevent
                    // every other valid profile from loading.
                }
            }
        } catch (IOException e) {
            // Leave loadedProfiles as whatever was successfully read so far.
        }

        this.sortLoadedProfiles();
    }

    private Path profileFilePath(String displayName) {
        return PROFILES_DIRECTORY.resolve(sanitizeFileName(displayName) + ".txt");
    }

    // Preserves digits alongside lowercase letters - everything else
    // (spaces, punctuation, non-Latin characters) becomes an underscore.
    // Locale.ROOT avoids toLowerCase()'s default-locale sensitivity (some
    // locales, e.g. Turkish, lowercase certain characters unexpectedly).
    //
    // TODO: no length cap - an unusually long display name produces an
    // equally long filename, with no truncation against OS path-length
    // limits.
    private static String sanitizeFileName(String displayName) {
        return displayName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "_");
    }

    // Refreshes an existing profile's data in place from the current
    // city's live state - clears the profile's own maps first, so a
    // blueprint demolished since the last capture doesn't leave a stale
    // entry behind.
    public void populateFromLiveData(ThalCapacityProfile profile) {
        profile.clear();
        this.captureCapacitiesPerSlot(profile);
        this.captureSpeciesAndHTypePopulations(profile);
    }

    // Delegates enumeration to ThalRoomServiceRegistry, writing only the
    // entries whose liveCapacityPerSlot() clears MIN_CAPACITY_PER_SLOT. A
    // blueprint with no live data yet is left absent rather than
    // backfilled with a hypothetical value - that would make this
    // profile's data silently go stale the moment vanilla's own formula or
    // rate data changes in some future patch, defeating the point of it
    // being a captured, real observation.
    private void captureCapacitiesPerSlot(ThalCapacityProfile profile) {
        ThalRoomServiceRegistry.roomServicesByKey().values().forEach(service -> this.captureCapacity(profile, service.room().key, service.liveCapacityPerSlot()));
    }

    private void captureCapacity(ThalCapacityProfile profile, String blueprintKey, double capacityPerSlot) {
        if (capacityPerSlot >= MIN_CAPACITY_PER_SLOT) {
            profile.capacityPerSlotSet(blueprintKey, capacityPerSlot);
        }
    }

    // Purely informational population breakdowns by race and by HTYPE, for
    // a player choosing between saved profiles later. STATS.POP().pop(race,
    // hType) already gives a direct population count - no live-entity scan
    // needed, unlike the eligibility tallies elsewhere in this mod.
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

    // Debug scaffolding only - see DAILY_REFRESH_ENABLED's own comment.
    @Override
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

    private void refreshDebugProfile() {
        ThalCapacityProfile debugProfile = this.findProfileBySerializedName(DEBUG_PROFILE_NAME);
        if (debugProfile == null) {
            debugProfile = ThalCapacityProfile.blank(DEBUG_PROFILE_NAME, "Debug: refreshed once per day from this session's own live data.");
            this.loadedProfiles.add(debugProfile);
        }

        this.populateFromLiveData(debugProfile);
        this.saveProfile(debugProfile);
    }

    // Persists only which profile is active, by display name -
    // loadedProfiles itself is never written here, since it's already
    // fully rebuilt from disk in the constructor every session.
    @Override
    public void save(FilePutter file) {
        file.chars(this.activeProfile == null ? "" : this.activeProfile.displayName());
    }

    // Resolved by serialized name rather than exact display-name equality
    // - matches the level uniqueness is actually enforced at, so a saved
    // reference stays resolvable even if some intervening rename left the
    // exact display-name string slightly different. A name with no match
    // (a profile renamed or deleted since this save was made) resolves to
    // null rather than throwing.
    @Override
    public void load(FileGetter file) throws IOException {
        String activeProfileName = file.chars();
        this.activeProfile = activeProfileName.isEmpty() ? null : this.findProfileBySerializedName(activeProfileName);
        this.applyDefaultProfileIfNoneActive();
    }
}