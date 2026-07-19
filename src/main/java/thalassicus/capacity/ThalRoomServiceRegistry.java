// ThalRoomServiceRegistry.java
// Document Version 1.1.0
// Creation date: 2026/07/18
// Creator: Thalassicus

package thalassicus.capacity;

import game.GameDisposable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import settlement.main.SETT;
import settlement.room.main.RoomBlueprint;
import settlement.room.service.module.RoomService;

// A purely mechanical fact, not a capacity-planning concept: every
// blueprint whose capacity happens to be tracked through RoomService's own
// available/total/load bookkeeping, keyed by blueprint key. This is NOT
// the same set as "rooms a player should think about when planning
// capacity" - Jake's own usage of RoomService is inconsistent on that
// front. Confirmed exceptions in both directions:
//   - ROOM_DUMP is a RoomService.ROOM_SERVICE_HASER (need == null) despite
//     having no employees, providing no happiness, and in no way
//     resembling an ordinary service room - it's IN this set but not part
//     of the player-facing question.
//   - ROOM_BENCH tracks its own idle-time-based capacity through a
//     completely separate mechanism (RoomFinderHaser, its own FSERVICE
//     against spriteData2) with no RoomService involvement at all - it's
//     OUT of this set despite clearly being part of the player-facing
//     question.
// The plan for these and similar cases is to shadow and correct the
// inconsistency at its source later, rather than have this registry (or
// any future capacity-planning set built from it) special-case around it
// indefinitely. Until then, callers that need the player-facing set
// should filter or supplement this one explicitly, not assume it's
// already correct for that purpose.
public final class ThalRoomServiceRegistry {

    // Both null until first accessed, and always built/reset together (see
    // ensureBuilt() and the GameDisposable block below) - two views over
    // the identical underlying data, not two independent things that could
    // ever disagree with each other. Lazy rather than built eagerly at
    // class-load time, since SETT.ROOMS() is only guaranteed fully
    // constructed once the game itself is underway - matching the same
    // timing reasoning already established for RoomService's own
    // religionByService cache.
    private static Map<String, RoomService> roomServicesByKey;
    private static List<RoomService> roomServicesSorted;

    static {
        new GameDisposable() {
            @Override
            protected void dispose() {
                roomServicesByKey = null;
                roomServicesSorted = null;
            }
        };
    }

    // Builds both roomServicesByKey and roomServicesSorted from a single
    // pass over SETT.ROOMS().all() - there's no reason to enumerate every
    // blueprint in the game twice for the same underlying data. Reset via
    // GameDisposable on every new game/save, since SETT.ROOMS() itself is
    // rebuilt fresh each session and a stale cache would keep referring to
    // the previous session's now-discarded objects (the exact failure mode
    // already found and fixed once for RoomService's own religionByService
    // cache).
    private static void ensureBuilt() {
        if (roomServicesByKey != null) {
            return;
        }

        Map<String, RoomService> byKey = new HashMap<>();
        List<RoomService> sorted = new ArrayList<>();
        for (RoomBlueprint blueprint : SETT.ROOMS().all()) {
            if (blueprint instanceof RoomService.ROOM_SERVICE_HASER hasService) {
                RoomService service = hasService.service();
                byKey.put(service.room().key, service);
                sorted.add(service);
            }
        }

        // Case-insensitive: a player-facing alphabetical ordering should
        // group "Eatery" and "eatery" together, not split them apart by
        // capitalization - the usual expectation for sorted display text,
        // even though nothing in this room's own data ever mixes case in
        // practice.
        sorted.sort(Comparator.comparing(service -> service.room().info.name.toString(), String.CASE_INSENSITIVE_ORDER));

        roomServicesByKey = byKey;
        roomServicesSorted = sorted;
    }

    // Every blueprint (of any category - service, religion, or otherwise)
    // implementing RoomService.ROOM_SERVICE_HASER, directly or through a
    // sub-interface such as RoomServiceAccess.ROOM_SERVICE_ACCESS_HASER,
    // keyed by RoomService.room().key.
    public static Map<String, RoomService> roomServicesByKey() {
        ensureBuilt();
        return Collections.unmodifiableMap(roomServicesByKey);
    }

    // The same set as roomServicesByKey(), as a List sorted by each
    // blueprint's own display name (RoomBlueprintImp.info.name) rather than
    // its internal key - the natural order for anything a human will
    // actually read (a future UI list, a log dump, etc.), computed and
    // cached once rather than re-sorted by every caller that wants it.
    public static List<RoomService> roomServicesSorted() {
        ensureBuilt();
        return Collections.unmodifiableList(roomServicesSorted);
    }

    // Convenience single-entry lookup. Returns null for any key not in this
    // set - either a blueprint that genuinely isn't RoomService-backed, or
    // a typo'd/unrecognized key; this method can't distinguish the two, so
    // callers that need to tell them apart should inspect
    // roomServicesByKey() directly instead.
    public static RoomService roomService(String blueprintKey) {
        return roomServicesByKey().get(blueprintKey);
    }
}
