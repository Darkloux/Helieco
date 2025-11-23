package com.helixteam.economyplugin.currency;

import com.helixteam.economyplugin.EconomyPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class CurrencyTabCompleter implements TabCompleter {

    public CurrencyTabCompleter(EconomyPlugin plugin) {
        // plugin kept for future context-aware suggestions
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        // Player context not used currently but may be useful later
        List<String> subs = new ArrayList<>();
        subs.add("create");
        subs.add("emit");
        subs.add("info");
        subs.add("redeem");
        subs.add("rename");
        subs.add("sync");
        subs.add("forceredeem");
        subs.add("reload");
        subs.add("help");

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            List<String> out = new ArrayList<>();
            for (String s : subs) {
                if (s.startsWith(partial)) out.add(s);
            }
            return out;
        }

        // suggestions for second argument
        if (args.length == 2) {
            String first = args[0].toLowerCase();
            if (first.equals("emit")) {
                // suggest some common amounts
                List<String> am = new ArrayList<>();
                am.add("1"); am.add("5"); am.add("10"); am.add("20"); am.add("50"); am.add("100");
                String part = args[1].toLowerCase();
                List<String> out = new ArrayList<>();
                for (String s : am) if (s.startsWith(part)) out.add(s);
                return out;
            }
            if (first.equals("create")) {
                // suggest a placeholder name
                return Collections.singletonList("<nombre_moneda>");
            }
            if (first.equals("rename")) {
                return Collections.singletonList("<nuevo_nombre>");
            }
        }

        return Collections.emptyList();
    }
}
