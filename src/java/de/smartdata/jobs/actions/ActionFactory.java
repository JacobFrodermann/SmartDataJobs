package de.smartdata.jobs.actions;

import org.reflections.Reflections;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Creates action objects for Action implementations.
 * 
 * @author Florian Fehring
 */
public class ActionFactory {

    private static final String ACTIONS_PACKAGE = "de.smartdata.jobs.actions";
    private static final Map<String, Class<? extends Action>> registry = new HashMap<>();

    static {
        Reflections reflections = new Reflections(ACTIONS_PACKAGE);
        Set<Class<? extends Action>> subclasses = reflections.getSubTypesOf(Action.class);

        for (Class<? extends Action> clazz : subclasses) {
            if (!java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                registry.put(clazz.getSimpleName().replace("Action", ""), clazz);
            }
        }
    }

    /**
     * Creates a Action object based on the given Actions name
     * 
     * @param actionName Actions name
     * @return Action object when an action with that name exists, null otherwise
     */
    public static Action create(String actionName) {
        Class<? extends Action> actionClass = registry.get(actionName);
        if (actionClass != null) {
            try {
                return actionClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException("Could not instantiate action class: " + actionClass.getName(), e);
            }
        }
        return null;
    }

    public static Set<String> getAvailableActions() {
        return registry.keySet();
    }
}
