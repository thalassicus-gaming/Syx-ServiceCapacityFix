// ThalCapacityProfile.java
// Document Version 1.7.0
// Creation date: 2026/07/18
// Creator: Thalassicus

package thalassicus.capacity;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import snake2d.util.file.Json;
import snake2d.util.file.JsonE;

// Named snapshot of planning data.
//
// Pure data plus self-serialization only. This class owns only its own
// state and transformations of that state; persistence and external
// interactions belong elsewhere.
public final class ThalCapacityProfile {

    private String displayName;
    private String description;
    // Missing entries represent "no captured value", not a sentinel.
    private final Map<String, Double> capacitiesPerSlot = new HashMap<>();
    private final Map<String, Double> speciesPopulations = new HashMap<>();
    private final Map<String, Double> htypePopulations = new HashMap<>();

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

    public double capacityPerSlot(String blueprintKey, double fallbackValue) {
        Double value = this.capacitiesPerSlot.get(blueprintKey);
        return value == null ? fallbackValue : value;
    }

    public void capacityPerSlotSet(String blueprintKey, double capacityPerSlot) {
        this.capacitiesPerSlot.put(blueprintKey, capacityPerSlot);
    }

    public void capacityPerSlotRemove(String blueprintKey) {
        this.capacitiesPerSlot.remove(blueprintKey);
    }

    // Exposes read-only views to preserve ownership of the backing maps.
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

    public void speciesPopulationRemove(String raceKey) {
        this.speciesPopulations.remove(raceKey);
    }

    public void htypePopulationSet(String hTypeKey, double population) {
        this.htypePopulations.put(hTypeKey, population);
    }

    public void htypePopulationRemove(String hTypeKey) {
        this.htypePopulations.remove(hTypeKey);
    }

    // Clears captured data while preserving profile metadata.
    public void clear() {
        this.capacitiesPerSlot.clear();
        this.speciesPopulations.clear();
        this.htypePopulations.clear();
    }

    /*
     * Copy invariant:
     *
     * Every copy operation produces an independent profile. Mutable backing
     * maps are never shared between instances.
     *
     * copyFrom() is the identity-preserving variant: this object's reference
     * remains unchanged while its entire state is replaced by source.
     * Use this when existing references to the destination object must
     * continue to observe updated data.
     */
    public void copyFrom(ThalCapacityProfile source) {
        this.displayName = source.displayName;
        this.description = source.description;
        this.clear();
        copyMapsInto(source, this);
    }

    /*
     * Returns a new profile containing the same state as profileToCopy.
     *
     * Unlike copyFrom(), this creates a new object identity while preserving
     * the copy invariant that no mutable backing maps are shared.
     */
    public static ThalCapacityProfile deepCopy(ThalCapacityProfile profileToCopy) {
        ThalCapacityProfile copy = ThalCapacityProfile.blank("", "");
        copy.copyFrom(profileToCopy);
        return copy;
    }

    /*
     * Returns a new independent profile by overlaying sourceProfile onto
     * destinationProfile.
     *
     * Conflicting values are taken from sourceProfile. Values present only in
     * destinationProfile are retained. Neither input object is modified.
     */
    public static ThalCapacityProfile merge(ThalCapacityProfile sourceProfile, ThalCapacityProfile destinationProfile) {
        ThalCapacityProfile newProfile = ThalCapacityProfile.deepCopy(destinationProfile);
        newProfile.displayName = sourceProfile.displayName;
        newProfile.description = sourceProfile.description;
        copyMapsInto(sourceProfile, newProfile);
        return newProfile;
    }

    // Copies map contents without sharing backing map instances.
    private static void copyMapsInto(ThalCapacityProfile source, ThalCapacityProfile destination) {
        destination.capacitiesPerSlot.putAll(source.capacitiesPerSlot);
        destination.speciesPopulations.putAll(source.speciesPopulations);
        destination.htypePopulations.putAll(source.htypePopulations);
    }

    // Serializes only this object's state.
    public JsonE serialize() {
        JsonE json = new JsonE();
        json.addString("DISPLAY_NAME", sanitizeForJsonString(this.displayName));
        json.addString("DESCRIPTION", sanitizeForJsonString(this.description));
        json.add("CAPACITIES_PER_SLOT", toJsonBlock(this.capacitiesPerSlot));
        json.add("SPECIES", toJsonBlock(this.speciesPopulations));
        json.add("HTYPES", toJsonBlock(this.htypePopulations));
        return json;
    }

    // Missing fields fall back to defaults for forward/backward compatibility.
    public static ThalCapacityProfile deserialize(Json json) {
        String displayName = json.text("DISPLAY_NAME", "");
        String description = json.text("DESCRIPTION", "");
        ThalCapacityProfile profile = ThalCapacityProfile.blank(displayName, description);
        readJsonBlock(json, "CAPACITIES_PER_SLOT", profile.capacitiesPerSlot, false);
        readJsonBlock(json, "SPECIES", profile.speciesPopulations, true);
        readJsonBlock(json, "HTYPES", profile.htypePopulations, true);
        return profile;
    }

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

    private static void readJsonBlock(Json json, String key, Map<String, Double> targetMap, boolean roundToWholeNumber) {
        if (json.jsonIs(key)) {
            Json block = json.json(key);
            for (String entryKey : block.keys()) {
                double value = block.d(entryKey);
                targetMap.put(entryKey, roundToWholeNumber ? Math.round(value) : value);
            }
        }
    }
}