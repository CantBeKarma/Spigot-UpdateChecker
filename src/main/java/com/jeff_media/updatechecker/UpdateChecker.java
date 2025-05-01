package com.jeff_media.updatechecker;

import com.github.Anon8281.universalScheduler.UniversalScheduler;
import com.github.Anon8281.universalScheduler.scheduling.schedulers.TaskScheduler;
import com.github.Anon8281.universalScheduler.scheduling.tasks.MyScheduledTask;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/**
 * <b>Main class.</b> Automatically checks for updates.
 */
@SuppressWarnings("UnusedReturnValue")
public class UpdateChecker {

    static final String VERSION = "3.0.4";
    private static final String SPIGOT_CHANGELOG_SUFFIX = "/history";
    private static final String SPIGOT_DOWNLOAD_LINK = "https://www.spigotmc.org/resources/";
    private static final String SPIGOT_UPDATE_API = "https://api.spigotmc.org/simple/0.2/index.php?action=getResource&id=%s";
    private static final String POLYMART_CHANGELOG_SUFFIX = "/updates";
    private static final String POLYMART_DOWNLOAD_LINK = "https://polymart.org/resource/";
    private static final String POLYMART_UPDATE_API = "https://api.polymart.org/v1/getResourceInfoSimple/?resource_id=%s&key=version";
    private static final String SPIGET_UPDATE_API = "https://api.spiget.org/v2/resources/%s/versions/latest";
    private static final String GITHUB_RELEASE_API = "https://api.github.com/repos/%s/%s/releases";
    private static final String HANGAR_RELEASE_API = "https://hangar.papermc.io/api/v1/projects/%s/%s/latest?channel=%s";
    private static UpdateChecker instance = null;
    private static boolean listenerAlreadyRegistered = false;

    static {
        checkRelocation();
    }

    private final String spigotUserId = "%%__USER__%%";
    private final String apiLink;
    private final ThrowingFunction<BufferedReader, String, IOException> mapper;
    private final UpdateCheckSource updateCheckSource;
    private final VersionSupplier supplier;
    private final Plugin plugin;
    private String changelogLink = null;
    private boolean checkedAtLeastOnce = false;
    private boolean coloredConsoleOutput = false;
    private String donationLink = null;
    private String freeDownloadLink = null;
    private String latestVersion = null;
    private String nameFreeVersion = "Free";
    private String namePaidVersion = "Paid";
    private boolean notifyOpsOnJoin = true;
    private String notifyPermission = null;
    private boolean notifyRequesters = true;
    private String supportLink = null;
    private boolean suppressUpToDateMessage = true;
    private BiConsumer<CommandSender[], Exception> onFail = (requesters, ex) -> ex.printStackTrace();
    private BiConsumer<CommandSender[], String> onSuccess = (requesters, latestVersion) -> {};
    private String paidDownloadLink = null;
    private static TaskScheduler scheduler;
    @Nullable
    private MyScheduledTask updaterTask = null;
    private int timeout = 0;
    private String usedVersion;
    private String userAgentString = null;
    private boolean usingPaidVersion = false;

    {
        instance = this;
    }

    public UpdateChecker(@NotNull JavaPlugin plugin, @NotNull VersionSupplier supplier) {
        this.plugin = plugin;
        this.apiLink = null;
        this.supplier = supplier;
        this.updateCheckSource = null;
        this.mapper = null;
        init();
    }

    private void init() {
        Objects.requireNonNull(plugin, "Plugin cannot be null.");
        this.usedVersion = plugin.getDescription().getVersion().trim();
        if (detectPaidVersion()) usingPaidVersion = true;
        scheduler = UniversalScheduler.getScheduler(plugin);
        if (!listenerAlreadyRegistered) {
            Bukkit.getPluginManager().registerEvents(new UpdateCheckListener(), plugin);
            listenerAlreadyRegistered = true;
        }
    }

    private boolean detectPaidVersion() {
        return spigotUserId.matches("^[0-9]+$");
    }

    public UpdateChecker(@NotNull JavaPlugin plugin, @NotNull UpdateCheckSource updateCheckSource, @NotNull String parameter) {
        this.plugin = plugin;
        this.supplier = null;
        final String apiLink;
        final ThrowingFunction<BufferedReader, String, IOException> mapper;
        switch (this.updateCheckSource = updateCheckSource) {
            case CUSTOM_URL:    apiLink = parameter; mapper = VersionMapper.TRIM_FIRST_LINE; break;
            case SPIGOT:       apiLink = String.format(SPIGOT_UPDATE_API, parameter); mapper = VersionMapper.SPIGOT; break;
            case POLYMART:     apiLink = String.format(POLYMART_UPDATE_API, parameter); mapper = VersionMapper.TRIM_FIRST_LINE; break;
            case SPIGET:       apiLink = String.format(SPIGET_UPDATE_API, parameter); mapper = VersionMapper.SPIGET; break;
            case GITHUB_RELEASE_TAG: {
                String[] split = parameter.split("/");
                if (split.length < 2) throw new IllegalArgumentException("Given GitHub repository must be in the format \"<UserOrOrganization>/<Repo>\"");
                apiLink = String.format(GITHUB_RELEASE_API, split[0], split[1]);
                mapper = VersionMapper.GITHUB_RELEASE_TAG;
                break;
            }
            case HANGAR: {
                String[] split = parameter.split("/");
                if (split.length < 3) throw new IllegalArgumentException("Given Hangar project must be in the format \"<User>/<Project>/<Channel>\"");
                apiLink = String.format(HANGAR_RELEASE_API, split[0], split[1], split[2]);
                mapper = VersionMapper.TRIM_FIRST_LINE;
                break;
            }
            default:
                throw new UnsupportedOperationException();
        }
        Objects.requireNonNull(apiLink, "API Link cannot be null.");
        this.apiLink = apiLink;
        this.mapper = mapper;
        init();
    }

    @Deprecated
    public static UpdateChecker getInstance() {
        return instance;
    }

    @Deprecated
    public static UpdateChecker init(@NotNull JavaPlugin plugin, int spigotResourceId) {
        return new UpdateChecker(plugin, UpdateCheckSource.SPIGOT, String.valueOf(spigotResourceId));
    }

    @Deprecated
    public static UpdateChecker init(@NotNull JavaPlugin plugin, @NotNull String apiLink) {
        return new UpdateChecker(plugin, UpdateCheckSource.CUSTOM_URL, apiLink);
    }

    private static void checkRelocation() {
        if (Bukkit.getServer().getClass().getName().equals("be.seeseemelk.mockbukkit.ServerMock")) return;
        final String defaultPkgDe  = "de.jeff_media.updatechecker";
        final String defaultPkgCom = "com.jeff_media.updatechecker";
        final String examplePkg    = "your.package";
        String pkg = UpdateChecker.class.getPackage().getName();
        if (pkg.startsWith(defaultPkgDe) || pkg.startsWith(defaultPkgCom) || pkg.startsWith(examplePkg)) {
            throw new IllegalStateException("SpigotUpdateChecker class has not been relocated correctly!");
        }
    }

    public boolean isSuppressUpToDateMessage() {
        return suppressUpToDateMessage;
    }

    /**
     * Schedule periodic checks on Spigot or Folia.
     */
    public UpdateChecker checkEveryXHours(double hours) {
        stop();
        if (hours <= 0) return this;

        // Convert hours to ticks (20 ticks = 1 second)
        long ticks = (long) Math.round(hours * 3600 * 20);

        if (isFolia()) {
            GlobalRegionScheduler foliaScheduler = Bukkit.getServer().getGlobalRegionScheduler();
            foliaScheduler.runAtFixedRate(
                    plugin,
                    scheduledTask -> checkNow(Bukkit.getConsoleSender()),
                    ticks,
                    ticks
            );
        } else {
            updaterTask = getScheduler().runTaskTimer(
                    () -> checkNow(Bukkit.getConsoleSender()),
                    ticks,
                    ticks
            );
        }
        return this;
    }

    /**
     * Cancel any running tasks on shutdown or re-schedule.
     */
    public UpdateChecker stop() {
        if (isFolia()) {
            Bukkit.getServer().getGlobalRegionScheduler().cancelTasks(plugin);
        }
        if (updaterTask != null) {
            updaterTask.cancel();
            updaterTask = null;
        }
        return this;
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public UpdateChecker checkNow(@Nullable CommandSender... requesters) {
        if (plugin == null) throw new IllegalStateException("Plugin has not been set.");
        if (apiLink == null && supplier == null) throw new IllegalStateException("API Link has not been set and no supplier provided.");

        checkedAtLeastOnce = true;
        if (userAgentString == null) userAgentString = UserAgentBuilder.getDefaultUserAgent().build();

        getScheduler().runTaskAsynchronously(() -> {
            UpdateCheckEvent event;
            try {
                if (supplier != null) {
                    latestVersion = supplier.getLatestVersionString();
                } else {
                    HttpURLConnection conn = (HttpURLConnection) new URL(apiLink).openConnection();
                    conn.addRequestProperty("User-Agent", userAgentString);
                    if (timeout > 0) conn.setConnectTimeout(timeout);
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        latestVersion = mapper.apply(reader);
                    }
                }
                if (!isOtherVersionNewer(usedVersion, latestVersion)) latestVersion = usedVersion;
                event = new UpdateCheckEvent(UpdateCheckSuccess.SUCCESS);
            } catch (IOException ex) {
                event = new UpdateCheckEvent(UpdateCheckSuccess.FAIL);
                getScheduler().runTask(() -> getOnFail().accept(requesters, ex));
            }
            UpdateCheckEvent finalEvent = event.setRequesters(requesters);
            getScheduler().runTask(() -> {
                if (finalEvent.getSuccess() == UpdateCheckSuccess.SUCCESS) {
                    getOnSuccess().accept(requesters, latestVersion);
                }
                Bukkit.getPluginManager().callEvent(finalEvent);
            });
        });
        return this;
    }

    public boolean isUsingLatestVersion() {
        return usedVersion.equals(instance.latestVersion);
    }

    public static boolean isOtherVersionNewer(String myVersion, String otherVersion) {
        DefaultArtifactVersion used = new DefaultArtifactVersion(myVersion);
        DefaultArtifactVersion latest = new DefaultArtifactVersion(otherVersion);
        return used.compareTo(latest) < 0;
    }

    public BiConsumer<CommandSender[], Exception> getOnFail() { return onFail; }
    public BiConsumer<CommandSender[], String> getOnSuccess() { return onSuccess; }
    public UpdateChecker checkNow() { checkNow(Bukkit.getConsoleSender()); return this; }
    public List<String> getAppropriateDownloadLinks() {
        List<String> list = new ArrayList<>();
        if (usingPaidVersion) {
            if (paidDownloadLink != null) list.add(paidDownloadLink);
            else if (freeDownloadLink != null) list.add(freeDownloadLink);
        } else {
            if (paidDownloadLink != null) list.add(paidDownloadLink);
            if (freeDownloadLink != null) list.add(freeDownloadLink);
        }
        return list;
    }

    // … rest of getters/setters unchanged for brevity …

    public static TaskScheduler getScheduler() { return scheduler; }
}
