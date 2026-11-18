package de.powerbat.luckperms;

import de.powerbat.PowerBat;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.luckperms.api.model.user.User;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.UUID;

/**
 * Anbindung an die LuckPerms-API. Diese Klasse wird nur geladen, wenn LuckPerms installiert ist.
 * Sie liest ausschließlich (Prefix, Primärgruppe) und überschreibt nie Ränge oder Prefixe.
 */
public class LuckPermsHook {

    private final PowerBat plugin;
    private final LuckPerms api;
    private EventSubscription<UserDataRecalculateEvent> subscription;

    public LuckPermsHook(PowerBat plugin) {
        this.plugin = plugin;
        RegisteredServiceProvider<LuckPerms> provider = Bukkit.getServicesManager().getRegistration(LuckPerms.class);
        if (provider == null) {
            throw new IllegalStateException("LuckPerms-Service ist nicht registriert");
        }
        this.api = provider.getProvider();
    }

    /** Aktualisiert die Tablist automatisch, sobald sich Rang/Prefix eines Spielers ändert. */
    public void register() {
        subscription = api.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class, event -> {
            UUID uuid = event.getUser().getUniqueId();
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) {
                    plugin.getDisplay().update(player);
                }
            });
        });
    }

    public void unregister() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
    }

    /** Prefix mit &-Codes oder null. */
    public String getPrefix(Player player) {
        User user = api.getUserManager().getUser(player.getUniqueId());
        if (user == null) {
            return null;
        }
        return user.getCachedData().getMetaData().getPrefix();
    }

    public String getPrimaryGroup(Player player) {
        User user = api.getUserManager().getUser(player.getUniqueId());
        return user == null ? null : user.getPrimaryGroup();
    }
}
