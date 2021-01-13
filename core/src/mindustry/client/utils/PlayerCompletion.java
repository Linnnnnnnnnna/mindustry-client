package mindustry.client.utils;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.Strings;
import mindustry.gen.Groups;
import mindustry.gen.Player;

public class PlayerCompletion {

    public static Autocompleteable getCompletion(String input) {
        return bestMatch(input);
    }

    private static PlayerMatcher bestMatch(String input) {
        Seq<PlayerMatcher> completions = closest(input);
        if (completions.isEmpty()) return null;
        return closest(input).first();
    }

    public static Seq<PlayerMatcher> closest(String input) {
        Seq<Player> all =  Groups.player.array.copy();
        if (all == null) return null;
        return all.sort(item -> (new PlayerMatcher(item)).matches(input)).map(PlayerMatcher::new);
    }

    private static class PlayerMatcher implements Autocompleteable {
        private final String name;
        private final String matchName;

        public PlayerMatcher(Player player) {
            name = "[#" + player.color.toString() + "]" + player.name;
            matchName = Strings.stripColors(name.replaceAll("\\s", ""));
        }

        @Override
        public float matches(String input) {
            String text = getLast(input);
            if (text == null) return 0f;

            float dst = BiasedLevenshtein.biasedLevenshtein(text.toLowerCase(), matchName.toLowerCase());
            dst *= -1;
            dst += matchName.length();
            dst /= matchName.length();
            return dst;
        }

        @Override
        public String getCompletion(String input) {
            String text = getLast(input);
            if (text == null) return input;

            return input.replace("@" + text, name);
        }

        @Override
        public String getHover(String input) {
            String text = getLast(input);
            if (text == null) return input;

            return input.replace("@" + text, name);
        }

        private String getLast(String input) {
            Seq<String> strings = new Seq<>(input.split("\\s"));
            if (strings.isEmpty()) {
                return null;
            }
            String text = strings.peek();
            if (!text.startsWith("@")) return null;
            return text.replaceAll("@", "");
        }
    }
}
