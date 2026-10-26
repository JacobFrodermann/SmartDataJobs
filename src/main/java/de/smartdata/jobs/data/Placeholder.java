package de.smartdata.jobs.data;

import java.util.Map;

/**
 * Placeholder for Notifications and Condition Type URLs <br>
 * The following Placeholder can be defined: <br> - {@link #NAME} <br> -
 * {@link #GROUP} <br> - {@link #STREAK} <br> - {@link #LEVEL} <br> -
 * {@link #XP} <br> - {@link #ID} <br> - {@link #COLLECTION} <br>-
 * {@link #EARNED_XP} <br>
 */
public enum Placeholder {
    NAME("<name>"),
    GROUP("<group>"),
    STREAK("<streak>"),
    LEVEL("<level>"),
    XP("<xp>"),
    ID("<id>"),
    COLLECTION("<collection>"),
    EARNED_XP("<earned_xp>");

    private final String token;

    /**
     * Placeholder with a <code>token</code> containing the string to be
     * replaced
     *
     * @param token to be replaced
     */
    Placeholder(String token) {
        this.token = token;
    }

    /**
     * Getter of the <code>token</code>
     *
     * @return <code>token</code>
     */
    public String token() {
        return this.token;
    }

    /**
     * Replace a Placeholder in a target with a replacement
     *
     * @param target text where replace should be done
     * @param p Placeholder that should be used
     * @param replacement that should replace the Placeholder
     * @return new text with <code>replacement</code> instead of <code>p</code>
     */
    public static String replace(String target, Placeholder p, String replacement) {
        return target.replace(p.token(), replacement);
    }

    /**
     * Replace all Placeholder defined in <code>values</code> in a target
     *
     * @param target text where replace should be done
     * @param values Map of <code>Placeholder</code> and its
     * <code>replacement</code>
     * @return new text with replaced Placeholders
     */
    public static String replaceAll(String target, Map<Placeholder, String> values) {
        for (var entry : values.entrySet()) {
            target = replace(target, entry.getKey(), entry.getValue());
        }
        return target;
    }
}
