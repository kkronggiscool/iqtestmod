package com.example.iqtest;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class IQTest extends JavaPlugin implements CommandExecutor, TabCompleter {

    // ------------------------------------------------------------------ small types

    record Question(String text, List<String> answers, int correct) {}

    record Tier(int minIq, double speed, int maxChat, int chatCooldown, boolean sprint, String title) {}

    /** Marks our test GUI so listeners can recognise it. */
    static final class TestHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** One player's in-progress test. */
    static final class Session {
        final TestHolder holder = new TestHolder();
        /** Original answer index picked for each question, -1 = not answered yet. */
        final int[] answers;
        /** Remembered answer order per question, so going back shows the same layout. */
        final Map<Integer, List<Integer>> orders = new HashMap<>();
        int index = 0;
        long lastClick = 0;
        /** order.get(i) = which original answer is shown in the i-th answer slot. */
        List<Integer> order = new ArrayList<>();

        Session(int questionCount) {
            answers = new int[questionCount];
            java.util.Arrays.fill(answers, -1);
        }
    }

    // ------------------------------------------------------------------ state

    static final MiniMessage MM = MiniMessage.miniMessage();

    private static final int[][] SLOTS = {{}, {}, {12, 14}, {11, 13, 15}, {10, 12, 14, 16}};
    private static final Material[] ANSWER_MATERIALS = {
            Material.RED_WOOL, Material.BLUE_WOOL, Material.LIME_WOOL, Material.YELLOW_WOOL};
    private static final String[] LETTERS = {"A", "B", "C", "D"};
    private static final int BACK_SLOT = 18;

    /** Finished players. Concurrent because chat events are async. */
    private final Map<UUID, Integer> iqs = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final List<Question> questions = new ArrayList<>();
    private final List<Tier> tiers = new ArrayList<>();
    private File dataFile;
    private NamespacedKey mapKey;

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "playerdata.yml");
        mapKey = new NamespacedKey(this, "test_map");
        loadAll();

        var cmd = getCommand("iq");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getPluginManager().registerEvents(new LockdownListener(this), this);

        // Handles /reload and late enabling: lock anyone already online who hasn't finished.
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyState(p);
            if (isLocked(p)) startTest(p);
        }
    }

    @Override
    public void onDisable() {
        sessions.clear();
    }

    private void loadAll() {
        reloadConfig();
        loadQuestions();
        loadTiers();
        loadData();
        getLogger().info("Loaded " + questions.size() + " questions, " + tiers.size()
                + " tiers and " + iqs.size() + " saved IQs.");
        if (questions.isEmpty()) {
            getLogger().severe("No valid questions found in questions.yml! The lockdown is disabled until you add some.");
        }
    }

    private void loadQuestions() {
        questions.clear();
        File f = new File(getDataFolder(), "questions.yml");
        if (!f.exists()) saveResource("questions.yml", false);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);

        int n = 0;
        for (Map<?, ?> m : y.getMapList("questions")) {
            n++;
            Object q = m.get("question");
            Object a = m.get("answers");
            Object c = m.get("correct");
            if (!(q instanceof String text) || !(a instanceof List<?> list) || !(c instanceof Number num)) {
                getLogger().warning("Skipping question #" + n + ": needs question, answers and correct.");
                continue;
            }
            List<String> answers = new ArrayList<>();
            for (Object o : list) answers.add(String.valueOf(o));
            int correct = num.intValue();
            if (answers.size() < 2 || answers.size() > 4 || correct < 0 || correct >= answers.size()) {
                getLogger().warning("Skipping question #" + n + ": needs 2-4 answers and a valid correct index.");
                continue;
            }
            questions.add(new Question(text, answers, correct));
        }
    }

    private void loadTiers() {
        tiers.clear();
        for (Map<?, ?> m : getConfig().getMapList("tiers")) {
            int min = (int) number(m.get("min-iq"), -1000);
            double speed = number(m.get("walk-speed"), 1.0);
            int maxChat = (int) number(m.get("max-chat-length"), 0);
            int cooldown = (int) number(m.get("chat-cooldown-seconds"), 0);
            boolean sprint = !(m.get("can-sprint") instanceof Boolean b) || b;
            Object title = m.get("title");
            tiers.add(new Tier(min, speed, maxChat, cooldown, sprint, title == null ? "" : String.valueOf(title)));
        }
        if (tiers.isEmpty()) {
            tiers.add(new Tier(-1000, 1.0, 0, 0, true, ""));
        }
        tiers.sort((a, b) -> Integer.compare(b.minIq(), a.minIq()));
    }

    private static double number(Object o, double def) {
        return o instanceof Number n ? n.doubleValue() : def;
    }

    private void loadData() {
        iqs.clear();
        names.clear();
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = data.getConfigurationSection("players");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                if (!sec.contains(key + ".iq")) continue;
                iqs.put(id, sec.getInt(key + ".iq"));
                names.put(id, sec.getString(key + ".name", key));
            } catch (IllegalArgumentException ignored) {
                // not a UUID key, skip
            }
        }
    }

    private void saveData() {
        YamlConfiguration out = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : iqs.entrySet()) {
            String base = "players." + e.getKey();
            out.set(base + ".name", names.getOrDefault(e.getKey(), "?"));
            out.set(base + ".iq", e.getValue());
        }
        try {
            out.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Could not save playerdata.yml: " + ex.getMessage());
        }
    }

    private void reloadAll() {
        for (UUID id : new ArrayList<>(sessions.keySet())) {
            sessions.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.closeInventory();
        }
        loadAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            applyState(p);
            if (isLocked(p)) startTest(p);
        }
    }

    // ------------------------------------------------------------------ state helpers (used by the listener)

    /** True while the player still has to take the test. */
    boolean isLocked(Player p) {
        return !questions.isEmpty()
                && !iqs.containsKey(p.getUniqueId())
                && !p.hasPermission("iqtest.bypass");
    }

    /** Public so other plugins (like IQDisplay) can look a player's IQ up. Null = no IQ yet. */
    public Integer getIq(UUID id) {
        return iqs.get(id);
    }

    Tier tierFor(int iq) {
        for (Tier t : tiers) {
            if (iq >= t.minIq()) return t;
        }
        return tiers.get(tiers.size() - 1);
    }

    void endSession(UUID id) {
        sessions.remove(id);
    }

    /** Applies walk speed: frozen while locked, tier speed once finished. */
    void applyState(Player p) {
        if (isLocked(p)) {
            p.setWalkSpeed(0f);
            p.setFlySpeed(0f);
            return;
        }
        removeMaps(p); // the test map disappears once you're done
        Integer iq = iqs.get(p.getUniqueId());
        double mult = iq == null ? 1.0 : tierFor(iq).speed();
        float speed = (float) Math.max(0.0, Math.min(1.0, 0.2 * mult));
        p.setWalkSpeed(speed);
        p.setFlySpeed(0.1f);
    }

    // ------------------------------------------------------------------ the test

    void welcome(Player p) {
        p.showTitle(Title.title(
                MM.deserialize(raw("welcome-title")),
                MM.deserialize(raw("welcome-subtitle")),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofMillis(800))));
        p.sendMessage(component("welcome-chat"));
        startTest(p);
    }

    /** Opens the test GUI, creating a fresh session if the player has none. */
    void startTest(Player p) {
        if (questions.isEmpty()) return;
        giveMap(p);
        Session s = sessions.get(p.getUniqueId());
        boolean fresh = false;
        if (s == null) {
            s = new Session(questions.size());
            s.holder.inventory = Bukkit.createInventory(s.holder, 27, MM.deserialize(raw("gui-title")));
            sessions.put(p.getUniqueId(), s);
            prepareQuestion(s);
            render(s);
            fresh = true;
        }
        p.openInventory(s.holder.getInventory());
        if (fresh) announceQuestion(p, s);
    }

    boolean isTestMap(ItemStack stack) {
        if (stack == null || stack.getType() != Material.MAP || !stack.hasItemMeta()) return false;
        return stack.getItemMeta().getPersistentDataContainer().has(mapKey, PersistentDataType.BYTE);
    }

    /** Gives the "IQ Test" map (right-click to open the test) unless they already have one. */
    private void giveMap(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isTestMap(it)) return;
        }
        ItemStack map = item(Material.MAP,
                Component.text("IQ Test", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(Component.text("Right-click to open your IQ test.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        ItemMeta meta = map.getItemMeta();
        meta.getPersistentDataContainer().set(mapKey, PersistentDataType.BYTE, (byte) 1);
        map.setItemMeta(meta);
        p.getInventory().addItem(map);
    }

    private void removeMaps(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            if (isTestMap(inv.getItem(i))) inv.setItem(i, null);
        }
    }

    void handleClick(Player p, int rawSlot) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null || s.index >= questions.size()) return;

        long now = System.currentTimeMillis();
        if (now - s.lastClick < 300) return; // ignore double-clicks

        // "Previous question" arrow
        if (rawSlot == BACK_SLOT) {
            if (s.index > 0) {
                s.lastClick = now;
                s.index--;
                prepareQuestion(s);
                render(s);
                announceQuestion(p, s);
                p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);
            }
            return;
        }

        Question q = questions.get(s.index);
        int[] slots = SLOTS[q.answers().size()];
        int pos = -1;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == rawSlot) pos = i;
        }
        if (pos < 0) return;
        s.lastClick = now;

        s.answers[s.index] = s.order.get(pos);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1f);

        s.index++;
        if (s.index >= questions.size()) {
            finish(p, s);
            return;
        }
        prepareQuestion(s);
        render(s);
        announceQuestion(p, s);
    }

    private void prepareQuestion(Session s) {
        List<Integer> saved = s.orders.get(s.index);
        if (saved == null) {
            int n = questions.get(s.index).answers().size();
            saved = new ArrayList<>();
            for (int i = 0; i < n; i++) saved.add(i);
            Collections.shuffle(saved);
            s.orders.put(s.index, saved);
        }
        s.order = saved;
    }

    private void render(Session s) {
        Inventory inv = s.holder.getInventory();
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);

        Question q = questions.get(s.index);
        inv.setItem(4, item(Material.PAPER,
                MM.deserialize(raw("question-title"),
                        Placeholder.unparsed("current", String.valueOf(s.index + 1)),
                        Placeholder.unparsed("total", String.valueOf(questions.size()))),
                wrap(q.text(), 34, NamedTextColor.WHITE)));

        int[] slots = SLOTS[q.answers().size()];
        for (int i = 0; i < slots.length; i++) {
            int original = s.order.get(i);
            boolean chosen = s.answers[s.index] == original;
            String text = q.answers().get(original);
            inv.setItem(slots[i], item(ANSWER_MATERIALS[i],
                    Component.text(LETTERS[i] + ") " + text + (chosen ? "  (your answer)" : ""),
                            chosen ? NamedTextColor.GREEN : NamedTextColor.YELLOW),
                    List.of(Component.text(chosen ? "Click to confirm and continue." : "Click to pick this answer.",
                            NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))));
        }
        if (s.index > 0) {
            inv.setItem(BACK_SLOT, item(Material.ARROW,
                    Component.text("Previous question", NamedTextColor.AQUA),
                    List.of(Component.text("Go back and change an answer.", NamedTextColor.GRAY)
                            .decoration(TextDecoration.ITALIC, false))));
        }
        inv.setItem(22, item(Material.OAK_SIGN,
                Component.text("Take your time", NamedTextColor.GREEN),
                List.of(Component.text("Your IQ is only revealed at the end.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false))));
    }

    private void announceQuestion(Player p, Session s) {
        Question q = questions.get(s.index);
        p.sendMessage(MM.deserialize(raw("question-chat"),
                Placeholder.unparsed("current", String.valueOf(s.index + 1)),
                Placeholder.unparsed("total", String.valueOf(questions.size())),
                Placeholder.unparsed("question", q.text())));
    }

    private void finish(Player p, Session s) {
        sessions.remove(p.getUniqueId());

        int total = questions.size();
        double min = getConfig().getDouble("iq.min", -52);
        double max = getConfig().getDouble("iq.max", 108);
        int correct = 0;
        for (int i = 0; i < total; i++) {
            if (s.answers[i] == questions.get(i).correct()) correct++;
        }
        int iq = (int) Math.round(min + (max - min) * correct / total);

        iqs.put(p.getUniqueId(), iq);
        names.put(p.getUniqueId(), p.getName());
        saveData();

        Bukkit.getScheduler().runTask(this, (Runnable) () -> p.closeInventory());
        applyState(p);

        TagResolver[] res = {
                Placeholder.unparsed("player", p.getName()),
                iqTag(iq),
                Placeholder.unparsed("correct", String.valueOf(correct)),
                Placeholder.unparsed("total", String.valueOf(total)),
                Placeholder.parsed("rank", tierFor(iq).title())
        };

        if (iq < 0) {
            for (String line : getConfig().getStringList("messages.banned")) {
                Bukkit.broadcast(MM.deserialize(line, res));
            }
            p.getWorld().strikeLightningEffect(p.getLocation());
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1f, 1f);
            }
            String reason = getConfig().getString("ban.reason", "The BAM hammer has SPOKEN!")
                    .replace("<iq>", String.valueOf(iq))
                    .replace('&', '§');
            String name = p.getName();
            Bukkit.getScheduler().runTask(this, (Runnable) () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + name + " " + reason));
            return;
        }

        for (String line : getConfig().getStringList("messages.completed")) {
            Bukkit.broadcast(MM.deserialize(line, res));
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.playSound(online.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.showTitle(Title.title(
                MM.deserialize(raw("result-title"), res),
                MM.deserialize(raw("result-subtitle"), res),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(800))));
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player p)) {
                msg(sender, "only-players");
                return true;
            }
            if (isLocked(p)) {
                startTest(p);
                return true;
            }
            Integer iq = iqs.get(p.getUniqueId());
            if (iq == null) {
                msg(p, "no-record");
            } else {
                msg(p, "your-iq", iqTag(iq), Placeholder.parsed("rank", tierFor(iq).title()));
            }
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "top" -> top(sender);
            case "check" -> {
                if (!sender.hasPermission("iqtest.admin")) {
                    msg(sender, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, "usage");
                    return true;
                }
                UUID id = resolve(args[1]);
                Integer iq = id == null ? null : iqs.get(id);
                if (iq == null) {
                    msg(sender, "no-record");
                    return true;
                }
                msg(sender, "check-result",
                        Placeholder.unparsed("player", names.getOrDefault(id, args[1])),
                        iqTag(iq),
                        Placeholder.parsed("rank", tierFor(iq).title()));
            }
            case "reset" -> {
                if (!sender.hasPermission("iqtest.admin")) {
                    msg(sender, "no-permission");
                    return true;
                }
                if (args.length < 2) {
                    msg(sender, "usage");
                    return true;
                }
                Player online = Bukkit.getPlayerExact(args[1]);
                UUID id = online != null ? online.getUniqueId() : resolve(args[1]);
                if (id == null) {
                    msg(sender, "no-record");
                    return true;
                }
                iqs.remove(id);
                names.remove(id);
                sessions.remove(id);
                saveData();
                msg(sender, "reset-done", Placeholder.unparsed("player", args[1]));
                if (online != null) {
                    online.closeInventory();
                    applyState(online);
                    welcome(online);
                }
            }
            case "reload" -> {
                if (!sender.hasPermission("iqtest.admin")) {
                    msg(sender, "no-permission");
                    return true;
                }
                reloadAll();
                msg(sender, "reloaded");
            }
            default -> msg(sender, "usage");
        }
        return true;
    }

    private void top(CommandSender sender) {
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(iqs.entrySet());
        if (list.isEmpty()) {
            msg(sender, "no-record");
            return;
        }
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        sender.sendMessage(MM.deserialize(raw("top-header")));
        int n = Math.min(5, list.size());
        for (int i = 0; i < n; i++) {
            UUID id = list.get(i).getKey();
            int iq = list.get(i).getValue();
            sender.sendMessage(MM.deserialize(raw("top-line"),
                    Placeholder.unparsed("pos", String.valueOf(i + 1)),
                    Placeholder.unparsed("player", names.getOrDefault(id, "?")),
                    iqTag(iq)));
        }
    }

    /** Finds a player's UUID by name: online first, then anyone with a saved IQ. */
    private UUID resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        for (Map.Entry<UUID, String> e : names.entrySet()) {
            if (e.getValue().equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("top");
            if (sender.hasPermission("iqtest.admin")) {
                out.add("check");
                out.add("reset");
                out.add("reload");
            }
        } else if (args.length == 2 && sender.hasPermission("iqtest.admin")
                && (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
            out.addAll(names.values());
        }
        String prefix = args[args.length - 1].toLowerCase();
        out.removeIf(s -> !s.toLowerCase().startsWith(prefix));
        return out;
    }

    // ------------------------------------------------------------------ messages & items

    String raw(String key) {
        return getConfig().getString("messages." + key, "<red>Missing message: " + key);
    }

    Component component(String key, TagResolver... resolvers) {
        return MM.deserialize(raw("prefix") + raw(key), resolvers);
    }

    private void msg(CommandSender to, String key, TagResolver... resolvers) {
        to.sendMessage(component(key, resolvers));
    }

    private static TagResolver iqTag(int iq) {
        return Placeholder.parsed("iq", "<bold><color:" + iqColor(iq) + ">" + iq + "</color></bold>");
    }

    private static String iqColor(int iq) {
        if (iq < 0) return "#ff3b3b";
        if (iq < 20) return "#ff9a3c";
        if (iq < 50) return "#ffd23f";
        if (iq < 80) return "#9ef01a";
        return "#37e6ff";
    }

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        if (!lore.isEmpty()) meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static List<Component> wrap(String text, int width, NamedTextColor color) {
        List<Component> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(Component.text(line.toString(), color).decoration(TextDecoration.ITALIC, false));
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(Component.text(line.toString(), color).decoration(TextDecoration.ITALIC, false));
        }
        return lines;
    }
}
