# process_visit_durations.py
# Document Version 2.0.0
# Creation date: 2026/07/13
# Creator: Thalassicus
#
# Reads the raw per-room visit-duration bundle (thal-visit-duration-source-data.json,
# hand-assembled from the decompiled AI plan classes) and produces a small,
# final data file mapping each room's real in-game blueprint key
# (confirmedBlueprintKey) to a single visit-duration figure, picked according
# to that room's recorded strategy (measured_average / minimum / maximum),
# expressed both in raw seconds and as a ratio against a reference room. The
# reference room is Lavatory, since its estimate has already been checked
# against real gameplay.
#
# Keyed by confirmedBlueprintKey, not needKey and not blueprintClass (the
# Java class name) - this supersedes an earlier version of this script that
# keyed by needKey, which broke down once Temple confirmed multiple
# blueprints (one per religion) can share a single NEED with genuinely
# distinct data. Every room in the source bundle is now expected to declare
# confirmedBlueprintKey explicitly (null only for rooms with no real
# blueprint at all, e.g. Skinnydip).
#
# Two distinct "no data" cases are handled differently, on purpose:
#   - confirmedBlueprintKey is null (no blueprint exists at all): the room
#     is informational only and does not appear in the output "rooms" dict -
#     there is no key to look it up by in the first place.
#   - confirmedBlueprintKey is a real string, but no strategy could compute
#     a duration (e.g. a "no_data" room like Hospital, Canteen, Eatery, or
#     Court): the room STILL appears in "rooms", with an explicit -1
#     sentinel, so a caller looking up that real key gets a clear,
#     acknowledged "no data" signal rather than a silent miss indistinguishable
#     from a key nobody ever registered at all.
#
# This script is meant to be re-run whenever the source bundle is updated
# (new rooms analyzed, measured averages replacing assumed ranges, etc.)
# rather than run once and discarded.

import json
import sys

REFERENCE_ROOM_LABEL = "Lavatory"


def pick_seconds(room):
    """Selects the single visit-duration figure for a room, based on its
    recommended strategy. Returns None if the room has no numeric range at
    all, or if its strategy (e.g. "no_data") has no computable figure."""
    duration_range = room.get("totalVisitSecondsAssumedRange")
    if duration_range is None:
        return None

    strategy = room.get("recommendedStrategy")
    minimum_seconds = duration_range["min"]
    maximum_seconds = duration_range["max"]

    if strategy == "minimum":
        return minimum_seconds
    elif strategy == "maximum":
        return maximum_seconds
    elif strategy == "measured_average":
        return (minimum_seconds + maximum_seconds) / 2.0
    else:
        # no_data, exclude_or_default, or any future strategy we haven't
        # handled yet.
        return None


def build_output(source_bundle):
    rooms_by_label = {room["roomLabel"]: room for room in source_bundle["rooms"]}

    if REFERENCE_ROOM_LABEL not in rooms_by_label:
        raise ValueError(f"Reference room '{REFERENCE_ROOM_LABEL}' not found in source bundle.")

    reference_seconds = pick_seconds(rooms_by_label[REFERENCE_ROOM_LABEL])
    if reference_seconds is None:
        raise ValueError(f"Reference room '{REFERENCE_ROOM_LABEL}' has no usable duration figure.")

    output_rooms = {}
    excluded_rooms = []

    for room in source_bundle["rooms"]:
        blueprint_key = room.get("confirmedBlueprintKey")

        if blueprint_key is None:
            # No real blueprint at all (e.g. Skinnydip) - the blueprint-keyed
            # lookup structure below simply does not apply, so this room is
            # informational only, not emitted into "rooms" at all.
            excluded_rooms.append({
                "roomLabel": room["roomLabel"],
                "reason": room.get("strategyReason", "no blueprint key exists for this room"),
            })
            continue

        if blueprint_key in output_rooms:
            raise ValueError(
                f"Duplicate confirmedBlueprintKey '{blueprint_key}' "
                f"(rooms '{output_rooms[blueprint_key]['roomLabel']}' and '{room['roomLabel']}'). "
                f"Each blueprint key must appear at most once - this likely indicates a data-entry mistake, "
                f"not an expected shared-mechanics case (Temple/Shrine's shared-per-religion values still get "
                f"one row PER blueprint key, e.g. TEMPLE_ATHURI and TEMPLE_CRATOR are different keys)."
            )

        chosen_seconds = pick_seconds(room)
        if chosen_seconds is None:
            # A real blueprint key exists, but no strategy could compute a
            # duration for it. Emitted as an explicit -1 sentinel rather than
            # omitted - see the module docstring above.
            output_rooms[blueprint_key] = {
                "roomLabel": room["roomLabel"],
                "strategyUsed": room.get("recommendedStrategy", "no_data"),
                "visitSeconds": -1,
                "durationRatioToReference": -1,
            }
            continue

        output_rooms[blueprint_key] = {
            "roomLabel": room["roomLabel"],
            "strategyUsed": room["recommendedStrategy"],
            "visitSeconds": round(chosen_seconds, 3),
            "durationRatioToReference": round(chosen_seconds / reference_seconds, 4),
        }

    return {
        "note": "Generated by process_visit_durations.py. Keys are the real in-game blueprint key for every room (confirmedBlueprintKey) - not needKey and not the Java class name (blueprintClass). A visitSeconds of -1 means a real blueprint key exists but no measured or derived data does yet - callers should treat -1 as an explicit, acknowledged gap (fall back to vanilla behavior) rather than a real duration, distinct from a key being entirely absent from this file (which would indicate a blueprint this file has never been told about at all).",
        "referenceRoom": REFERENCE_ROOM_LABEL,
        "referenceRoomSeconds": round(reference_seconds, 3),
        "rooms": output_rooms,
        "excludedRooms": excluded_rooms,
        "roomsNotYetAnalyzed": source_bundle.get("roomsNotYetAnalyzed", []),
        "inheritedCaveats": source_bundle.get("globalCaveats", []),
    }


def main():
    input_path = sys.argv[1] if len(sys.argv) > 1 else "thal-visit-duration-source-data.json"
    output_path = sys.argv[2] if len(sys.argv) > 2 else "thal-visit-duration-final.json"

    with open(input_path, "r") as input_file:
        source_bundle = json.load(input_file)

    output_data = build_output(source_bundle)

    with open(output_path, "w") as output_file:
        json.dump(output_data, output_file, indent=2)
        output_file.write("\n")

    print(f"Wrote {len(output_data['rooms'])} room(s) to {output_path}")
    print(f"Reference room: {output_data['referenceRoom']} "
          f"({output_data['referenceRoomSeconds']}s)")
    for blueprint_key, room_data in output_data["rooms"].items():
        if room_data["visitSeconds"] == -1:
            print(f"  {blueprint_key}: NO DATA ({room_data['roomLabel']})")
        else:
            print(f"  {blueprint_key}: {room_data['visitSeconds']}s "
                  f"(x{room_data['durationRatioToReference']}, "
                  f"{room_data['strategyUsed']})")
    if output_data["excludedRooms"]:
        print("Excluded entirely (no blueprint key exists at all):")
        for excluded in output_data["excludedRooms"]:
            print(f"  {excluded['roomLabel']}: {excluded['reason']}")


if __name__ == "__main__":
    main()
