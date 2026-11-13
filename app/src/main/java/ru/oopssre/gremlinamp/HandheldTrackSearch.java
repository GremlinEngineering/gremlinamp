package ru.oopssre.gremlinamp;

import org.json.JSONObject;

import java.util.Locale;

final class HandheldTrackSearch {
    enum Origin {
        ALL("Все треки", ""), ORIGINAL("Оригинальные", "Оригинал"),
        AUTHORED("Авторские", "Авторская тема"), ARRANGEMENT("Аранжировки", "Аранжировка"),
        RECONSTRUCTION("Реконструкции", "Реконструкция"), UNKNOWN("Без отметки", "Источник не указан");

        final String choice;
        final String label;

        Origin(String choice, String label) {
            this.choice = choice;
            this.label = label;
        }

        static Origin of(JSONObject track) {
            String source = track.optString("source_kind");
            if (source.startsWith("original_")) return ORIGINAL;
            if (source.equals("chiptune_arrangement")) return ARRANGEMENT;
            if (source.equals("reconstructed_native")) return RECONSTRUCTION;
            if (source.equals("authored_inspired") || track.optBoolean("authored")) return AUTHORED;
            return UNKNOWN;
        }

        static Origin saved(String name) {
            for (Origin origin : values()) if (origin.name().equals(name)) return origin;
            return ALL;
        }
    }

    static String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace('ё', 'е');
    }

    static boolean matchesWords(String text, String[] words) {
        for (String word : words) if (!text.contains(word)) return false;
        return true;
    }

    private HandheldTrackSearch() {}
}
