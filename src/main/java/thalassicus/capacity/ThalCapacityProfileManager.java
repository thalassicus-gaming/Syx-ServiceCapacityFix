// ThalCapacityProfileManager.java
// Document Version 1.1.0
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
import java.util.List;
import script.SCRIPT;
import settlement.main.SETT;
import settlement.room.service.module.RoomServiceAccess;
import settlement.room.spirit.shrine.ROOM_SHRINE;
import settlement.room.spirit.temple.ROOM_TEMPLE;
import settlement.stats.STATS;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import snake2d.util.file.Json;
import snake2d.util.file.JsonE;
import thalassicus.util.ThalsLogger;

// Owns everything ThalCapacityProfile deliberately does not: the collection
// of profiles loaded from disk, which one is currently active for planning
// purposes, all filesystem I/O, and all reaching into the live game world
// (RoomServiceAccess/ROOM_TEMPLE/ROOM_SHRINE/STATS.POP()) needed to capture
// a snapshot. ThalCapacityProfile itself only ever sees Json/JsonE
// structures via serialize()/deserialize() - never a Path, never
// java.nio.file, never the game world - so this class is the only place
// that boundary is allowed to be crossed.
//
// A single class serves as both the SCRIPT descriptor and the
// SCRIPT_INSTANCE, exactly like ThalSavable - createInstance() constructs a
// NEW instance rather than returning "this", since the descriptor object
// is built once via reflection at discovery time and cached for the whole
// process; returning it directly would let one save's activeProfile
// selection bleed into the next save loaded in the same running game.
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
    private static final CharSequence DESCRIPTION =
            "Internal utility. Manages saved capacity-planning profiles and which one is active for this save. Not a gameplay-affecting script.";

    private static final Path PROFILES_DIRECTORY =
            Path.of(System.getenv("APPDATA"), "songsofsyx", "mods", "Service Estimate Fix", "Profiles");

    // Same tautological truth as RoomService's own constant of this name -
    // duplicated rather than shared by reference, since RoomService's copy
    // is private and this class has no other reason to depend on
    // RoomService directly. A room's supported population can never be
    // less than its own slot count, so any live reading below this is not
    // a genuine capacity figure worth capturing into a profile.
    private static final double MIN_CAPACITY_PER_SLOT = 0.99;

    // Debug scaffolding, matching ThalCapacityDebugLog.ENABLED's own
    // pattern: flip to false to disable without touching call sites.
    // Refreshes a reserved profile once per day from this session's own
    // live data - a way to inspect real capture output without needing the
    // planned UI panel built yet. Not intended to ship enabled.
    private static final boolean DAILY_REFRESH_ENABLED = true;
    private static final String LIVE_DATA_PROFILE_NAME = "live_data";

    // The reserved FILENAME (not DISPLAY_NAME) a profile must resolve to,
    // via sanitizeFileName(), to be recognized as the default. Matched
    // against each loaded profile's own DERIVED filename rather than a
    // direct DISPLAY_NAME comparison, since a player's chosen display name
    // for their default profile has no reason to literally read
    // "default_profile" - only the file it's saved under needs to.
    private static final String DEFAULT_PROFILE_FILE_NAME = "default_profile";

    private final List<ThalCapacityProfile> loadedProfiles = new ArrayList<>();
    private ThalCapacityProfile activeProfile;
    private int lastRefreshDay = -1;

    // Seeds loadedProfiles from disk immediately - safe to do here rather
    // than waiting for load(FileGetter), since createInstance() (which
    // calls this constructor) runs well before ScriptEngine ever calls
    // this instance's own load(), matching the same construction-order
    // guarantee ThalSavable's own registration relies on elsewhere in this
    // mod. Also applies the default profile immediately after, covering a
    // brand-new game that never calls load(FileGetter) at all this session
    // - see applyDefaultProfileIfNoneActive()'s own comment for why this
    // alone isn't sufficient for a LOADED save.
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

    // Nullable by design - RoomService's future profileCapacityPerSlot()
    // read path should treat a null active profile the same as any other
    // "no estimate available" case and fall through to the hypothetical
    // tier, not as an error.
    public void activeProfileSet(ThalCapacityProfile activeProfile) {
        this.activeProfile = activeProfile;
    }

    public ThalCapacityProfile findProfileByName(String displayName) {
        for (ThalCapacityProfile profile : this.loadedProfiles) {
            if (profile.displayName().equals(displayName)) {
                return profile;
            }
        }

        return null;
    }

    // No-ops if a profile is already active - this is a FALLBACK, never an
    // override of a genuine (even empty) prior selection. Called from both
    // the constructor (covers a brand-new game, which never calls
    // load(FileGetter) at all) and load() (covers a loaded save whose
    // persisted selection was empty or unresolvable) - the two are not
    // redundant, since only one of them actually runs in any given session,
    // depending on whether this session loads an existing save.
    //
    // Known limitation: there is currently no way to distinguish "never
    // explicitly chosen" from "player deliberately chose no active
    // profile" - both serialize identically (an empty string) in save().
    // A player who explicitly wants no profile tier would have the default
    // silently reapplied every session. Not a regression (no UI exists yet
    // to make that explicit choice at all), but worth a real tri-state
    // (unset / explicitly none / specific profile) once one does.
    private void applyDefaultProfileIfNoneActive() {
        if (this.activeProfile != null) {
            log.info("Active profile already exists: " + activeProfile().displayName());
            return;
        }

        for (ThalCapacityProfile profile : this.loadedProfiles) {
            log.info("Checking profile: " + profile.displayName());
            if (sanitizeFileName(profile.displayName()).equals(DEFAULT_PROFILE_FILE_NAME)) {
                this.activeProfile = profile;
                log.info("Loading default profile: " + activeProfile().displayName());
                return;
            }
        }
    }

    public void addProfile(ThalCapacityProfile profile) {
        this.loadedProfiles.add(profile);
    }

    // Deletes the profile's file, removes it from loadedProfiles, and
    // clears activeProfile if this was it - this class owns activeProfile
    // directly, so leaving it pointing at a profile no longer in
    // loadedProfiles would be an inconsistency this class itself is
    // responsible for avoiding, not something to leave to whatever UI code
    // eventually calls this.
    public void removeProfile(ThalCapacityProfile profile) {
        this.deleteProfile(profile);
        this.loadedProfiles.remove(profile);
        if (this.activeProfile == profile) {
            this.activeProfile = null;
        }
    }

    // Renames in place: saves a new file under newDisplayName, then removes
    // the old file under whatever name the profile had before this call -
    // captured BEFORE displayNameSet() runs, since profileFilePath() is
    // derived from the profile's current name at the time it's called.
    // Avoids the orphaned-copy problem some other games' "rename" actions
    // have (the old file left behind under the old name) by treating a
    // rename as save-under-new-name-then-delete-old, rather than leaving
    // that sequencing to whatever UI code eventually exposes a rename
    // action.
    public void renameProfile(ThalCapacityProfile profile, String newDisplayName) {
        Path oldFilePath = this.profileFilePath(profile.displayName());
        profile.displayNameSet(newDisplayName);
        this.saveProfile(profile);

        try {
            Files.deleteIfExists(oldFilePath);
        } catch (IOException e) {
            // Best-effort cleanup of the stale file under the old name;
            // the rename itself (new file already saved above) still
            // succeeded either way.
        }
    }

    // Full overwrite of this profile's own file - never a partial update.
    // Matches JsonE.save(Path)'s own boolean-success convention rather than
    // declaring a checked exception, since that's how file writing already
    // works elsewhere in vanilla's own file-handling code.
    public boolean saveProfile(ThalCapacityProfile profile) {
        JsonE json = profile.serialize();

        try {
            Files.createDirectories(PROFILES_DIRECTORY);
        } catch (IOException e) {
            return false;
        }

        return json.save(this.profileFilePath(profile.displayName()));
    }

    public boolean deleteProfile(ThalCapacityProfile profile) {
        try {
            return Files.deleteIfExists(this.profileFilePath(profile.displayName()));
        } catch (IOException e) {
            return false;
        }
    }

    public static ThalCapacityProfile loadProfileFromFile(Path path) {
        Json json = new Json(path);
        return ThalCapacityProfile.deserialize(json);
    }

    // Refreshes loadedProfiles from every file currently in
    // PROFILES_DIRECTORY. A profile file that fails to load (corrupt,
    // hand-edited into an invalid state) is skipped rather than aborting
    // the whole refresh - one bad file shouldn't hide every other valid
    // profile from the player.
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
    }

    private Path profileFilePath(String displayName) {
        return PROFILES_DIRECTORY.resolve(sanitizeFileName(displayName) + ".txt");
    }

    // Filesystem-safe version of a display name: lowercase letters only,
    // everything else (spaces, punctuation, non-Latin characters) becomes
    // an underscore. Deliberately simple rather than exhaustive - this only
    // needs to produce A valid, unique-enough filename, not preserve every
    // character of the original name.
    private static String sanitizeFileName(String displayName) {
        return displayName.toLowerCase().replaceAll("[^a-z]", "_");
    }

    // Builds a brand-new profile and populates it from the current city's
    // live data in one step.
    public ThalCapacityProfile captureFromLiveData(String displayName, String description) {
        ThalCapacityProfile profile = new ThalCapacityProfile(displayName, description);
        this.populateFromLiveData(profile);
        return profile;
    }

    // Refreshes an EXISTING profile's data in place from the current city's
    // live state - used both by captureFromLiveData() above (a fresh
    // profile) and by the daily debug refresh below (the same profile
    // object, repeatedly). Clears the profile's own maps first, so a
    // blueprint demolished since the last capture doesn't leave a stale
    // entry behind.
    public void populateFromLiveData(ThalCapacityProfile profile) {
        profile.clear();
        this.captureCapacitiesPerSlot(profile);
        this.captureSpeciesAndHTypePopulations(profile);
    }

    // Enumerates every ordinary RoomServiceAccess blueprint plus every
    // religion blueprint (Shrine/Temple, reached the same way religionOf()
    // does it elsewhere in this mod, since they aren't RoomServiceAccess
    // and need a separate pass), writing only entries whose
    // liveCapacityPerSlot() clears MIN_CAPACITY_PER_SLOT. A blueprint with
    // no live data yet (the sentinel, or anything else below threshold) is
    // simply left absent rather than backfilled with a hypothetical value -
    // storing a hypothetical-derived number here would make this profile's
    // data silently go stale the moment vanilla's own formula or rate data
    // changes in some future patch, defeating the point of it being a
    // captured, real observation.
    private void captureCapacitiesPerSlot(ThalCapacityProfile profile) {
        for (RoomServiceAccess roomServiceAccess : RoomServiceAccess.ALL()) {
            this.captureCapacity(profile, roomServiceAccess.room().key, roomServiceAccess.liveCapacityPerSlot());
        }

        for (ROOM_TEMPLE temple : SETT.ROOMS().TEMPLES.ALL) {
            this.captureCapacity(profile, temple.service().room().key, temple.service().liveCapacityPerSlot());
        }

        for (ROOM_SHRINE shrine : SETT.ROOMS().TEMPLES.SHRINES) {
            this.captureCapacity(profile, shrine.service().room().key, shrine.service().liveCapacityPerSlot());
        }
    }

    private void captureCapacity(ThalCapacityProfile profile, String blueprintKey, double capacityPerSlot) {
        if (capacityPerSlot >= MIN_CAPACITY_PER_SLOT) {
            profile.capacityPerSlotSet(blueprintKey, capacityPerSlot);
        }
    }

    // Purely informational population breakdowns by race and by HTYPE, for
    // a player choosing between saved profiles later to see at a glance
    // what kind of city each one was captured from. STATS.POP().pop(race,
    // hType) already gives a direct population count for one race/HTYPE
    // pair - no live-entity scan needed here, unlike the eligibility
    // tallies elsewhere in this mod, since this is just a raw population
    // count with no NEED-eligibility gating to apply. Confirmed from
    // StatsPopulation's own source that HTYPE population is tracked as a
    // flat, independently-incremented array with no special-case merging
    // between linked HTYPEs (e.g. PARENT/CHILD), so this simple per-HTYPE
    // sum is not at risk of double-counting.
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

    // See the class-level note above for why this constructs fresh rather
    // than returning "this".
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
            this.refreshLiveDataDebugProfile();
        }
    }

    private void refreshLiveDataDebugProfile() {
        ThalCapacityProfile liveDataProfile = this.findProfileByName(LIVE_DATA_PROFILE_NAME);
        if (liveDataProfile == null) {
            liveDataProfile = new ThalCapacityProfile(LIVE_DATA_PROFILE_NAME, "Debug: refreshed once per day from this session's own live data.");
            this.addProfile(liveDataProfile);
        }

        this.populateFromLiveData(liveDataProfile);
        this.saveProfile(liveDataProfile);
    }

    // Persists only which profile is active, by name - loadedProfiles
    // itself is never written here, since it's already fully rebuilt from
    // disk in the constructor every session, independent of any specific
    // save file.
    @Override
    public void save(FilePutter file) {
        file.chars(this.activeProfile == null ? "" : this.activeProfile.displayName());
    }

    // Resolves the saved active-profile name against loadedProfiles (already
    // populated by the constructor by the time this runs). A name with no
    // match - a profile renamed or deleted since this save was made -
    // resolves to null rather than throwing, the same "known limitation,
    // acceptable for now" gap already flagged for this exact scenario.
    @Override
    public void load(FileGetter file) throws IOException {
        String activeProfileName = file.chars();
        this.activeProfile = activeProfileName.isEmpty() ? null : this.findProfileByName(activeProfileName);
        this.applyDefaultProfileIfNoneActive();
    }
}
