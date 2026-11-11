package org.sawiq.collins.fabric.client.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.sawiq.collins.fabric.client.security.UrlPolicy;
import org.sawiq.collins.fabric.client.video.VideoPlayer;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;

import java.util.ArrayList;

public final class CollinsModMenu implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return this::buildScreen;
    }

    private Screen buildScreen(Screen parent) {
        CollinsClientConfig cfg = CollinsClientConfig.get();

        ConfigBuilder builder = ConfigBuilder.create()
            .setParentScreen(parent)
            .setTitle(Component.translatable("config.collins.title"));

        builder.setSavingRunnable(() -> {
            CollinsClientConfig.save();
            // Apply the new site rules and cache limit now, not at the next sync.
            VideoScreenManager.reapplySync();
            VideoPlayer.enforceCacheLimitAsync();
        });

        ConfigCategory general = builder.getOrCreateCategory(Component.translatable("config.collins.category.video"));
        ConfigEntryBuilder eb = builder.entryBuilder();

        general.addEntry(eb.startIntSlider(Component.translatable("config.collins.local_volume"), cfg.localVolumePercent, 0, 100)
            .setDefaultValue(100)
            .setSaveConsumer(v -> cfg.localVolumePercent = v)
            .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable("config.collins.render_video"), cfg.renderVideo)
            .setDefaultValue(true)
            .setSaveConsumer(v -> cfg.renderVideo = v)
            .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable("config.collins.actionbar_timeline"), cfg.actionbarTimeline)
            .setDefaultValue(true)
            .setSaveConsumer(v -> cfg.actionbarTimeline = v)
            .build());

        general.addEntry(eb.startBooleanToggle(Component.translatable("config.collins.check_modrinth_updates"), cfg.checkModrinthUpdates)
            .setDefaultValue(false)
            .setSaveConsumer(v -> cfg.checkModrinthUpdates = v)
            .build());

        ConfigCategory safety = builder.getOrCreateCategory(Component.translatable("config.collins.category.safety"));

        safety.addEntry(eb.startBooleanToggle(Component.translatable("config.collins.only_allowed_sites"), cfg.onlyAllowedSites)
            .setDefaultValue(false)
            .setTooltip(Component.translatable("config.collins.only_allowed_sites.tooltip"))
            .setSaveConsumer(v -> cfg.onlyAllowedSites = v)
            .build());

        safety.addEntry(eb.startStrList(Component.translatable("config.collins.allowed_hosts"), new ArrayList<>(cfg.allowedHosts))
            .setDefaultValue(() -> new ArrayList<>(UrlPolicy.DEFAULT_HOSTS))
            .setTooltip(Component.translatable("config.collins.allowed_hosts.tooltip"))
            .setSaveConsumer(v -> cfg.allowedHosts = new ArrayList<>(v))
            .build());

        safety.addEntry(eb.startIntSlider(Component.translatable("config.collins.cache_limit_gb"), cfg.cacheLimitGb,
                CollinsClientConfig.MIN_CACHE_LIMIT_GB, CollinsClientConfig.MAX_CACHE_LIMIT_GB)
            .setDefaultValue(CollinsClientConfig.DEFAULT_CACHE_LIMIT_GB)
            .setTextGetter(v -> Component.translatable("config.collins.cache_limit_gb.value", v))
            .setTooltip(Component.translatable("config.collins.cache_limit_gb.tooltip"))
            .setSaveConsumer(v -> cfg.cacheLimitGb = v)
            .build());

        return builder.build();
    }
}
