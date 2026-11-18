package de.powerbat;

import de.powerbat.clan.ClanManager;
import de.powerbat.clan.InviteManager;
import de.powerbat.combat.CombatManager;
import de.powerbat.commands.ClanCommand;
import de.powerbat.commands.FriendCommand;
import de.powerbat.friends.FriendManager;
import de.powerbat.commands.PowerBatCommand;
import de.powerbat.listeners.CombatListener;
import de.powerbat.listeners.GuiListener;
import de.powerbat.listeners.PlayerListener;
import de.powerbat.luckperms.LuckPermsHook;
import de.powerbat.storage.ClanStorage;
import de.powerbat.storage.PlayerDataStorage;
import de.powerbat.storage.StatsManager;
import de.powerbat.utils.ChatInputManager;
import de.powerbat.utils.DisplayManager;
import de.powerbat.utils.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class PowerBat extends JavaPlugin {

    private static PowerBat instance;

    private Messages messages;
    private LuckPermsHook luckPerms;
    private PlayerDataStorage playerData;
    private ClanStorage clanStorage;
    private ClanManager clanManager;
    private InviteManager inviteManager;
    private StatsManager stats;
    private FriendManager friendManager;
    private CombatManager combatManager;
    private ChatInputManager chatInput;
    private DisplayManager display;
    private volatile boolean shuttingDown;
    private BukkitTask autosaveTask;
    private BukkitTask displayTask;

    public static PowerBat getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        if (!new File(getDataFolder(), "clans.yml").exists()) {
            saveResource("clans.yml", false);
        }
        messages = new Messages(this);

        hookLuckPerms();

        playerData = new PlayerDataStorage(this);
        playerData.load();
        clanStorage = new ClanStorage(this);
        inviteManager = new InviteManager(this);
        clanManager = new ClanManager(this);
        clanManager.load();
        stats = new StatsManager(this);
        friendManager = new FriendManager(this);
        friendManager.load();
        display = new DisplayManager(this);
        chatInput = new ChatInputManager(this);
        combatManager = new CombatManager(this);
        combatManager.start();

        registerCommand("clan", new ClanCommand(this));
        registerCommand("powerbat", new PowerBatCommand(this));
        registerCommand("friend", new FriendCommand(this));

        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        Bukkit.getPluginManager().registerEvents(new GuiListener(), this);

        scheduleTasks();

        for (Player player : Bukkit.getOnlinePlayers()) {
            playerData.update(player);
            display.update(player);
        }
        getLogger().info("PowerBat " + getDescription().getVersion() + " aktiviert"
                + (luckPerms != null ? " (LuckPerms-Integration aktiv)." : " (ohne LuckPerms)."));
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (displayTask != null) {
            displayTask.cancel();
        }
        if (combatManager != null) {
            combatManager.shutdown();
        }
        if (chatInput != null) {
            chatInput.clearAll();
        }
        if (luckPerms != null) {
            luckPerms.unregister();
        }
        if (display != null) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                display.reset(player);
            }
            display.removeAllNametags();
        }
        if (clanManager != null) {
            clanManager.save();
        }
        if (playerData != null) {
            playerData.save();
        }
        if (friendManager != null) {
            friendManager.save();
        }
    }

    private void registerCommand(String name, Object handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Befehl /" + name + " fehlt in der plugin.yml!");
            return;
        }
        command.setExecutor((org.bukkit.command.CommandExecutor) handler);
        command.setTabCompleter((org.bukkit.command.TabCompleter) handler);
    }

    private void hookLuckPerms() {
        luckPerms = null;
        Plugin lp = getServer().getPluginManager().getPlugin("LuckPerms");
        if (lp == null || !lp.isEnabled()) {
            getLogger().info("LuckPerms wurde nicht gefunden - Prefixe werden nicht angezeigt, Permissions funktionieren trotzdem.");
            return;
        }
        try {
            LuckPermsHook hook = new LuckPermsHook(this);
            hook.register();
            luckPerms = hook;
        } catch (Throwable t) {
            getLogger().warning("LuckPerms-Integration konnte nicht aktiviert werden: " + t.getMessage());
            luckPerms = null;
        }
    }

    private void scheduleTasks() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
        }
        if (displayTask != null) {
            displayTask.cancel();
        }
        long autosave = Math.max(10, getConfig().getInt("storage.autosave-seconds", 120)) * 20L;
        autosaveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            clanManager.saveIfDirty();
            playerData.saveIfDirty();
            friendManager.saveIfDirty();
        }, autosave, autosave);
        // Sicherheitsnetz: Tablist regelmäßig aktualisieren (z.B. nach Rang-Änderungen durch andere Plugins)
        displayTask = Bukkit.getScheduler().runTaskTimer(this, () -> display.updateAll(), 200L, 200L);
    }

    public void reloadAll() {
        reloadConfig();
        messages.reload();
        clanManager.reloadPermissions();
        scheduleTasks();
        display.updateAll();
    }

    public String formatDate(long millis) {
        String pattern = getConfig().getString("date-format", "dd.MM.yyyy HH:mm");
        try {
            return DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
        } catch (IllegalArgumentException ex) {
            return DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis));
        }
    }

    /** Relative Zeitangabe, z.B. "vor 5 Min." */
    public String formatAgo(long millis) {
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000L);
        if (seconds < 60) {
            return "gerade eben";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return "vor " + minutes + " Min.";
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return "vor " + hours + " Std.";
        }
        return "vor " + (hours / 24) + " Tag(en)";
    }

    public FriendManager getFriendManager() {
        return friendManager;
    }

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public Messages getMessages() {
        return messages;
    }

    /** @return null, wenn LuckPerms nicht installiert/aktiv ist. */
    public LuckPermsHook getLuckPerms() {
        return luckPerms;
    }

    public PlayerDataStorage getPlayerData() {
        return playerData;
    }

    public ClanStorage getClanStorage() {
        return clanStorage;
    }

    public ClanManager getClanManager() {
        return clanManager;
    }

    public InviteManager getInviteManager() {
        return inviteManager;
    }

    public StatsManager getStats() {
        return stats;
    }

    public CombatManager getCombatManager() {
        return combatManager;
    }

    public ChatInputManager getChatInput() {
        return chatInput;
    }

    public DisplayManager getDisplay() {
        return display;
    }
}
