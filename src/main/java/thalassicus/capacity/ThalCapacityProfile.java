// ThalCapacityProfile.java
// Document Version 1.6.0
// Creation date: 2026/07/18
// Creator: Thalassicus

package thalassicus.capacity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import snake2d.util.file.Json;
import snake2d.util.file.JsonE;

// A named, player-manageable snapshot of capacity-per-slot values for
// planning a city that doesn't exist yet - the "profile" tier in
// RoomService's live -> profile -> hypothetical fallback chain.
//
// Pure data plus self-serialization only: no Path, no java.nio.file, no
// game-world types (RoomServiceAccess, STATS, etc.) anywhere in this file.
// Capturing live data, choosing which profile is active, and all file I/O
// belong to ThalCapacityProfileManager - this class never reaches outside
// its own fields to do its job, matching the same test used to split the
// two classes apart: does an operation need external knowledge (the game
// world, the collection of other profiles, the filesystem) to do its job,
// or is it a pure transformation of data this object already holds?
public final class ThalCapacityProfile {

    private String displayName;
    private String description;
    // Keyed by blueprint key (e.g. "PHYSICIAN_NORMAL", matching
    // RoomService.room().key). Only ever holds genuine captured values,
    // never a sentinel - the Manager only writes entries here that already
    // cleared its own MIN_CAPACITY_PER_SLOT check before calling in.
    private final Map<String, Double> capacitiesPerSlot = new HashMap<>();
    // Purely informational context for choosing between saved profiles
    // later (e.g. "mostly Cretonian citizens, some Slaves") - never read by
    // any capacity formula. Keyed by Race.key/HTYPE.key (public fields).
    private final Map<String, Double> speciesPopulations = new HashMap<>();
    private final Map<String, Double> htypePopulations = new HashMap<>();

    // Private - blank() below is the public, named entry point.
    private ThalCapacityProfile(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public static ThalCapacityProfile blank(String displayName, String description) {
        return new ThalCapacityProfile(displayName, description);
    }

    public String displayName() {
        return this.displayName;
    }

    public void displayNameSet(String displayName) {
        this.displayName = displayName;
    }

    public String description() {
        return this.description;
    }

    public void descriptionSet(String description) {
        this.description = description;
    }

    // Read accessor for RoomService's future profileCapacityPerSlot() tier -
    // returns fallbackValue (by convention, the same -1 sentinel every
    // other capacity tier uses) whenever this profile has no entry for
    // blueprintKey.
    public double capacityPerSlot(String blueprintKey, double fallbackValue) {
        Double value = this.capacitiesPerSlot.get(blueprintKey);
        return value == null ? fallbackValue : value;
    }

    public void capacityPerSlotSet(String blueprintKey, double capacityPerSlot) {
        this.capacitiesPerSlot.put(blueprintKey, capacityPerSlot);
    }

    // Removes a single entry rather than the whole-map clear() below -
    // needed for "revert this one field to tracking the default" (backspace
    // to empty, in the UI's own terms), which clear() was never meant to
    // handle. A no-op if blueprintKey has no entry, matching Map.remove()'s
    // own behavior rather than throwing.
    public void capacityPerSlotRemove(String blueprintKey) {
        this.capacitiesPerSlot.remove(blueprintKey);
    }

    // Unmodifiable views, so external code (UI, the Manager) can read every
    // entry without being able to bypass capacityPerSlotSet()/the
    // species-population equivalents by mutating the map directly.
    public Map<String, Double> capacitiesPerSlot() {
        return Collections.unmodifiableMap(this.capacitiesPerSlot);
    }

    public Map<String, Double> speciesPopulations() {
        return Collections.unmodifiableMap(this.speciesPopulations);
    }

    public Map<String, Double> htypePopulations() {
        return Collections.unmodifiableMap(this.htypePopulations);
    }

    public void speciesPopulationSet(String raceKey, double population) {
        this.speciesPopulations.put(raceKey, population);
    }

    // See capacityPerSlotRemove()'s own comment - same reasoning, applied
    // to the species-population map.
    public void speciesPopulationRemove(String raceKey) {
        this.speciesPopulations.remove(raceKey);
    }

    public void htypePopulationSet(String hTypeKey, double population) {
        this.htypePopulations.put(hTypeKey, population);
    }

    // See capacityPerSlotRemove()'s own comment - same reasoning, applied
    // to the HTYPE-population map.
    public void htypePopulationRemove(String hTypeKey) {
        this.htypePopulations.remove(hTypeKey);
    }

    // Empties all three data maps without touching displayName/description.
    // Used by the Manager before recapturing live data into an existing
    // profile, so a blueprint demolished since the last capture doesn't
    // leave a stale entry behind.
    public void clear() {
        this.capacitiesPerSlot.clear();
        this.speciesPopulations.clear();
        this.htypePopulations.clear();
    }

    // THE canonical "copy a profile's full state" logic - copies source's
    // metadata and all three maps into this object IN PLACE, without
    // touching this object's own identity. Needed anywhere an object's
    // reference must stay stable for the whole program (ThalCapacityUI's
    // own scratchProfile, permanently captured by every GDouble commit
    // lambda) but its data still needs replacing wholesale from a
    // different source. deepCopy() below delegates to this rather than
    // keeping its own separate copy of the same logic.
    public void copyFrom(ThalCapacityProfile source) {
        this.displayName = source.displayName;
        this.description = source.description;
        this.clear();
        copyMapsInto(source, this);
    }

    // A blank profile, immediately copied into via copyFrom() above - not
    // a second, independent implementation. The two exist side by side
    // because callers need different things: sometimes a genuinely new,
    // independent object (the Manager's own stored-profile collection),
    // sometimes an existing object's identity has to stay put while its
    // data gets replaced (copyFrom()'s own case). Same underlying
    // operation either way, just two entry points for the two situations.
    public static ThalCapacityProfile deepCopy(ThalCapacityProfile profileToCopy) {
        ThalCapacityProfile copy = ThalCapacityProfile.blank("", "");
        copy.copyFrom(profileToCopy);
        return copy;
    }

    // Produces a new profile with sourceProfile's data overwriting
    // destinationProfile's wherever the two overlap - every field,
    // metadata included. Neither input is mutated; only newProfile is ever
    // written to. A static method (source, destination) rather than an
    // instance method (this.mergeWith(other)) encodes which profile wins on
    // conflict directly in the parameter order/names, rather than needing
    // an extra boolean to say which direction the overwrite goes.
    public static ThalCapacityProfile merge(ThalCapacityProfile sourceProfile, ThalCapacityProfile destinationProfile) {
        ThalCapacityProfile newProfile = ThalCapacityProfile.deepCopy(destinationProfile);
        newProfile.displayName = sourceProfile.displayName;
        newProfile.description = sourceProfile.description;
        copyMapsInto(sourceProfile, newProfile);
        return newProfile;
    }

    // Shared by copyFrom() and merge() above - copies all three maps'
    // current entries into destination via putAll, so destination never
    // shares a backing map instance with source.
    private static void copyMapsInto(ThalCapacityProfile source, ThalCapacityProfile destination) {
        destination.capacitiesPerSlot.putAll(source.capacitiesPerSlot);
        destination.speciesPopulations.putAll(source.speciesPopulations);
        destination.htypePopulations.putAll(source.htypePopulations);
    }

    // Converts this profile's own state into a JsonE structure - no
    // knowledge of Path/Files/where this ends up; the Manager decides that.
    public JsonE serialize() {
        JsonE json = new JsonE();
        json.addString("DISPLAY_NAME", sanitizeForJsonString(this.displayName));
        json.addString("DESCRIPTION", sanitizeForJsonString(this.description));
        json.add("CAPACITIES_PER_SLOT", toJsonBlock(this.capacitiesPerSlot));
        json.add("SPECIES", toJsonBlock(this.speciesPopulations));
        json.add("HTYPES", toJsonBlock(this.htypePopulations));
        return json;
    }

    // Builds a profile from an already-parsed Json structure - the Manager
    // owns reading the actual file and handing this the parsed result.
    // Every field is read with a fallback (json.text(key, fallback),
    // json.jsonIs(key) guarding each block) rather than the throwing
    // accessors, so a profile file written by an earlier version of this
    // class - one with fewer blocks than today's - still loads cleanly
    // instead of throwing on a missing key.
    public static ThalCapacityProfile deserialize(Json json) {
        String displayName = json.text("DISPLAY_NAME", "");
        String description = json.text("DESCRIPTION", "");
        ThalCapacityProfile profile = ThalCapacityProfile.blank(displayName, description);
        readJsonBlock(json, "CAPACITIES_PER_SLOT", profile.capacitiesPerSlot);
        readJsonBlock(json, "SPECIES", profile.speciesPopulations);
        readJsonBlock(json, "HTYPES", profile.htypePopulations);
        return profile;
    }

    // Jake's JSON forbids commas and quote marks in a plain string value -
    // strip them rather than reject the player's chosen name/description
    // outright.
    private static String sanitizeForJsonString(String value) {
        return value.replace(",", "").replace("\"", "");
    }

    private static JsonE toJsonBlock(Map<String, Double> map) {
        JsonE block = new JsonE();
        for (Map.Entry<String, Double> entry : map.entrySet()) {
            block.add(entry.getKey(), entry.getValue());
        }

        return block;
    }

    private static void readJsonBlock(Json json, String key, Map<String, Double> targetMap) {
        if (json.jsonIs(key)) {
            Json block = json.json(key);
            for (String entryKey : block.keys()) {
                targetMap.put(entryKey, block.d(entryKey));
            }
        }
    }
}
