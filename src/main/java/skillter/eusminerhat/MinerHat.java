package skillter.eusminerhat;

import skillter.eusminerhat.bukkit.AdminCommandExecutor;
import skillter.eusminerhat.bukkit.PlayerCommandExecutor;
import skillter.eusminerhat.contribution.ContributorManager;
import skillter.eusminerhat.exception.MinerException;
import skillter.eusminerhat.listener.PlayerListener;
import skillter.eusminerhat.miner.MinerManager;
import skillter.eusminerhat.miner.MinerPolicy;
import skillter.eusminerhat.util.Timestamp;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

public final class MinerHat extends JavaPlugin {
    public static final String LanguagePackVersion = "1.1";
    private static final String[] BuiltInLanguagePacks = { "en" };

    private MinerManager minerManager;
    private String MinerPath;
    private String LanguagePath;
    private String PlayerContributionPath;
    private MinerHatConfig config;
    private LocaleManager localeManager;
    private ContributorManager contributorManager;
    private BukkitTask checkTask;

    private Economy economy = null;

    public MinerManager getMinerManager() {
        return minerManager;
    }
    public String getMinerPath() {
        return MinerPath;
    }
    public String getLanguagePath() {
        return LanguagePath;
    }
    public String getPlayerContributionPath() { return PlayerContributionPath; }
    public MinerHatConfig getMinerHatConfig() {
        return config;
    }
    public LocaleManager getLocaleManager() {
        return localeManager;
    }
    public ContributorManager getContributorManager() { return contributorManager; }
    public Economy getEconomy() {
        return economy;
    }

    @Override
    public void onEnable() {
        this.MinerPath = getDataFolder() + "/miner";
        this.LanguagePath = getDataFolder() + "/language";
        this.PlayerContributionPath = getDataFolder() + "/contribution";

        loadMinerHatConfig();

        getCommand("minerhatadmin").setExecutor(new AdminCommandExecutor(this));
        getCommand("minerhat").setExecutor(new PlayerCommandExecutor(this));

        try {
            registerMiner();
        } catch (MinerException e) {
            sendSevere(l("policy.failure.loading"));
        }
        registerPlayerContribution();

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
    }

    @Override
    public void onDisable() {
        if (minerManager != null) {
            minerManager.stopMining();
        }
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    public void loadMinerHatConfig() {
        File configFile = new File(getDataFolder(), "config.yml");

        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        reloadConfig();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdir();
        }

        File minerDir = new File(MinerPath);
        if (!minerDir.exists()) {
            minerDir.mkdir();

            // Create example policy on init
            try {
                createExamplePolicy();
            } catch (Exception e) {
                e.printStackTrace();
                sendWarn("§eFailed creating miner policy example.");
            }
        }

        File languageDir = new File(LanguagePath);
        if (!languageDir.exists()) {
            languageDir.mkdir();
        }

        // Validate and check default language packs
        try {
            validateDefaultLanguagePacks();
        } catch (Exception e) {
            e.printStackTrace();
            sendSevere("§cFailed exporting default language pack.");
        }

        File contributionDir = new File(PlayerContributionPath);
        if (!contributionDir.exists()) {
            contributionDir.mkdir();
        }

        this.config = new MinerHatConfig(this);

        try {
            this.localeManager = LocaleManager.createLocaleManager(this, config.getLanguage());
        } catch (Exception e) {
            e.printStackTrace();
            sendSevere(String.format("§cFailed loading language pack: %s.json", config.getLanguage()));
        }

        if (config.isEconomyIntegrationEnabled()) {
            if (!setupEconomy()) {
                sendInfo(l("contribution.economyIntegration.vaultNotFound"));
            }
        }
    }

    /**
     * Set up ContributionManager for player commands if player contribution is enabled.
     */
    public void registerPlayerContribution() {
//        if (this.contributorManager != null) {
//            this.contributorManager = null;
//        }

        if (!config.isPlayerContributionEnabled()) {
            // Do nothing here!
            // So that third-party plugins can still deal with the remaining player revenue after this function being disabled

            //return; // Player contribution disabled
        }

        this.contributorManager = new ContributorManager(this, config.getPoolSourceType());
        printPlayerContributionInformation();
    }

    /**
     * Create a new MinerManager and RunningCheckTimer if local mining is enabled.
     * It will also try to stop the existing miner if needed.
     * @throws MinerException
     */
    public void registerMiner() throws MinerException {
        if (this.minerManager != null) { // Attempt to stop existing miner
            this.minerManager.stopMining();
            this.minerManager = null;
        }

        if (this.checkTask != null) { // Attempt to cancel existing timer
            this.checkTask.cancel();
            this.checkTask = null;
        }

        if (!config.isLocalMiningEnabled()) {
            return; // Local mining disabled
        }

        try {
            MinerPolicy policy = MinerPolicy.loadPolicy(getMinerPath() + "/" + config.getMiner() + ".json");
            this.minerManager = new MinerManager(this, config.getMiner(), policy);
            printMinerInformation();
        } catch (Exception e) {
            e.printStackTrace();
            throw new MinerException(MinerException.MinerExceptionType.FAILED_LOADING_POLICY, "Failed loading miner policy");
        }


        if (config.getCheckIntervalSeconds() > 0) { // check timer will be disabled if interval is less or equal than 0
            long interval = config.getCheckIntervalSeconds() * 20L;
            this.checkTask = getServer().getScheduler().runTaskTimer(this, new CheckIntervalTimer(this), interval, interval);
        }
    }

    void printMinerInformation() {
        sendInfo(l("miner.info.header"));
        sendInfo(String.format(l("miner.info.enabled"), config.isLocalMiningEnabled()));
        sendInfo(String.format(l("miner.info.name"), config.getMiner()));
        sendInfo(String.format(l("miner.info.checkInterval"), config.getCheckIntervalSeconds()));
        sendInfo(String.format(l("miner.info.restartInterval"), getMinerHatConfig().getRestartMinerIntervalMinutes()));
    }

    void printPlayerContributionInformation() {
        sendInfo(l("contribution.info.header"));
        sendInfo(String.format(l("contribution.info.enabled"), config.isPlayerContributionEnabled()));
        sendInfo(String.format(l("contribution.info.pool"), config.getPoolSourceType()));
        sendInfo(String.format(l("contribution.info.wallet"), config.getWalletAddress()));
        sendInfo(String.format(l("contribution.info.workerPrefix"), config.getWorkerPrefix()));
        sendInfo(String.format(l("contribution.info.walletInfoExpireSeconds"), config.getWalletInfoExpireSeconds()));
        sendInfo(String.format(l("contribution.info.revenueFactor"), config.getRevenueFactor()));
        sendInfo(String.format(l("contribution.info.economyIntegration.enabled"), config.isEconomyIntegrationEnabled()));
        sendInfo(String.format(l("contribution.info.economyIntegration.exchangeRateToServerMoney"), config.getExchangeRateToServerMoney()));
    }

    public String l(String stringToken) {
        return parseFormatCode(localeManager.getLocalized(stringToken));
    }

    public String parseFormatCode(String text) {
        return text.replace("&", "§");
    }

    public void sendSevere(String message) {
        Bukkit.getServer().getLogger().severe(prefixForEachLine(message));
    }

    public void sendWarn(String message) {
        Bukkit.getServer().getLogger().warning(prefixForEachLine(message));
    }

    public void sendInfo(String message) {
        Bukkit.getServer().getLogger().info(prefixForEachLine(message));
    }

    public String prefixForEachLine(String text) {
        if (localeManager == null) {
            return text;
        }

        String prefix = l("message.prefix");
        String[] lines = text.split("\n");
        for (int i=0; i<lines.length; i++) {
            lines[i] = prefix + lines[i];
        }
        return String.join("\n", lines);
    }

    private void createExamplePolicy() throws Exception {
        InputStream in = getResource("example-xmrig-policy.json");
        Files.copy(Objects.requireNonNull(in), new File(MinerPath + "/xmrig.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File minerDir = new File(MinerPath + "/xmrig");
        minerDir.mkdir();
    }

    private void validateDefaultLanguagePacks() throws Exception {
        for (String language : BuiltInLanguagePacks) {
            File languageFile = new File(LanguagePath + "/" + language + ".json");
            if (languageFile.exists()) {
                if (LocaleManager.checkCompatibility(languageFile)) {
                    continue;
                } else {
                    String backupName = "" + Timestamp.getSecondsSince1970() + "_bak_" + language + ".json";
                    Files.copy(languageFile.toPath(), languageFile.toPath().resolveSibling(backupName), StandardCopyOption.REPLACE_EXISTING);
                    // No translation for this warning message because the language pack has not been loaded yet.
                    sendWarn(String.format("Language pack %s is not compatible with the current version of MinerHat, " +
                            "we have written an up-to-date copy for replacement. An backup has been made.", language + ".json"));
                }
            }

            InputStream in = getResource(language + ".json");
            OutputStream out = new FileOutputStream(languageFile);
            int bytesRead;
            byte[] buffer = new byte[4096];
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
    }
}
