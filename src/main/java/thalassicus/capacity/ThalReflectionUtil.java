// ThalReflectionUtil.java
// Document Version 1.0.0
// Creation date: 2026/07/19
// Creator: Thalassicus

package thalassicus.capacity;

import java.lang.reflect.Field;
import java.util.Optional;
import thalassicus.util.ThalsLogger;

// Generic reflection helpers for reaching into Jake's own UI classes where no
// public accessor exists - modeled on the ReflectionUtil pattern from Jake's
// own "Add a UI Element" modding guide, renamed and reshaped to this
// project's own conventions rather than copied verbatim (no Lombok in this
// project's dependencies, so a plain private constructor stands in for
// @NoArgsConstructor(access = PRIVATE)).
//
// Every method here fails soft (Optional.empty(), or a logged warning)
// rather than throwing - a reflection target changing shape in a future game
// update should degrade this mod's UI-injection feature gracefully, not
// crash the session.
public final class ThalReflectionUtil {

    private static final ThalsLogger log = new ThalsLogger(
            ThalsLogger.INFO,
            System.getenv("APPDATA") + "\\songsofsyx\\logs\\ThalReflectionUtil.log"
    );

    private ThalReflectionUtil() {
    }

    public static Optional<Field> declaredField(String fieldName, Object instance) {
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            return Optional.of(field);
        } catch (NoSuchFieldException exception) {
            log.warn("declaredField(): no field named '%s' on %s", fieldName, instance.getClass().getName());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> declaredFieldValue(Field field, Object instance) {
        field.setAccessible(true);
        try {
            return Optional.ofNullable((T) field.get(instance));
        } catch (Exception exception) {
            log.warn("declaredFieldValue(): could not read field '%s' on %s", field.getName(), instance.getClass().getName());
            return Optional.empty();
        }
    }

    // Third overload deliberately passes instance directly to
    // declaredField(), not instance.getClass() - passing the Class object
    // itself would make getDeclaredField() look for fields on
    // java.lang.Class rather than on the target instance's own class. Same
    // trap Jake's own docs called out explicitly.
    public static <T> Optional<T> declaredFieldValue(String fieldName, Object instance) {
        return declaredField(fieldName, instance).flatMap(field -> declaredFieldValue(field, instance));
    }

    // Companion to the name-based lookups above, for the one case this mod
    // needs where the field's NAME isn't known up front, only its TYPE.
    // Jake's own modding guide references an equivalent
    // (findUIElementInSettlementView(UIPanelTop.class)) but its
    // implementation was never shown to us - this is a reconstruction, not
    // a confirmed match, and is the least-verified piece of this whole
    // deployment (see ThalCapacityUI's own injection method for how it's
    // used and what happens if it comes back empty).
    //
    // Matches by simple class name only, not fully-qualified - this mod
    // does not know UIPanelTop's real package, and matching on the simple
    // name sidesteps needing to guess it. Single-level only: does not
    // recurse into nested objects if the target isn't a DIRECT declared
    // field of instance itself.
    public static Optional<Object> declaredFieldByTypeName(String simpleTypeName, Object instance) {
        for (Field field : instance.getClass().getDeclaredFields()) {
            if (field.getType().getSimpleName().equals(simpleTypeName)) {
                return declaredFieldValue(field, instance);
            }
        }

        log.warn("declaredFieldByTypeName(): no field of type '%s' found directly on %s", simpleTypeName, instance.getClass().getName());
        return Optional.empty();
    }
}
