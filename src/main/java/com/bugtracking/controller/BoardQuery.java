package com.bugtracking.controller;

import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The board's current filter state, as something a template can build links
 * from.
 *
 * <p>Every filter control on the board is a link that changes exactly one
 * parameter and keeps the rest. Written out longhand in Thymeleaf that is a
 * nine-argument link expression repeated a few dozen times, and one forgotten
 * argument silently drops a filter. Here it is one call:
 * {@code @{'/bugs' + ${q.toggle('severity', 'HIGH')}}}.
 */
public final class BoardQuery {

    private final Map<String, String> params;

    private BoardQuery(Map<String, String> params) {
        this.params = params;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The value currently set for a parameter, or null. */
    public String get(String key) {
        return params.get(key);
    }

    public boolean has(String key) {
        return params.containsKey(key);
    }

    /** True when this parameter currently holds exactly this value. */
    public boolean is(String key, Object value) {
        return value != null && String.valueOf(value).equals(params.get(key));
    }

    /** Anything narrowing the board — the view mode and project do not count. */
    public boolean isFiltered() {
        return params.keySet().stream()
                .anyMatch(key -> !"project".equals(key) && !"view".equals(key));
    }

    /** The current query string, unchanged. */
    public String query() {
        return render(params);
    }

    /** The same board with one parameter set, or removed when the value is null. */
    public String with(String key, Object value) {
        Map<String, String> next = new LinkedHashMap<>(params);
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isEmpty()) {
            next.remove(key);
        } else {
            next.put(key, text);
        }
        return render(next);
    }

    public String without(String key) {
        return with(key, null);
    }

    /** Click the filter you already have on to take it off again. */
    public String toggle(String key, Object value) {
        return is(key, value) ? without(key) : with(key, value);
    }

    /** Sets two parameters at once — picking a person clears the other side. */
    public String withOnly(String key, Object value, String cleared) {
        Map<String, String> next = new LinkedHashMap<>(params);
        next.remove(cleared);
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isEmpty()) {
            next.remove(key);
        } else {
            next.put(key, text);
        }
        return render(next);
    }

    /**
     * The same filters plus this one, on the board rather than wherever you are.
     *
     * <p>For the numbers on the Stats view: "2 urgent" is a link to those two
     * bugs, and leaving the view where it was would apply the filter to a page
     * of totals that does not show a bug at all. Dropping the view falls back
     * to the board, which is where those bugs are.
     */
    public String board(String key, Object value) {
        Map<String, String> next = new LinkedHashMap<>(params);
        next.remove("view");
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isEmpty()) {
            next.remove(key);
        } else {
            next.put(key, text);
        }
        return render(next);
    }

    /** Everything off except the project and the view you are looking at. */
    public String cleared() {
        Map<String, String> next = new LinkedHashMap<>();
        keep(next, "project");
        keep(next, "view");
        return render(next);
    }

    private void keep(Map<String, String> target, String key) {
        String value = params.get(key);
        if (value != null) {
            target.put(key, value);
        }
    }

    private static String render(Map<String, String> params) {
        if (params.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("?");
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (out.length() > 1) {
                out.append('&');
            }
            out.append(entry.getKey()).append('=')
               .append(UriUtils.encodeQueryParam(entry.getValue(), StandardCharsets.UTF_8));
        }
        return out.toString();
    }

    /** Collects the parameters that were actually supplied, in a stable order. */
    public static final class Builder {

        private final Map<String, String> params = new LinkedHashMap<>();

        public Builder put(String key, Object value) {
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isEmpty()) {
                    params.put(key, text);
                }
            }
            return this;
        }

        public BoardQuery build() {
            return new BoardQuery(params);
        }
    }
}
