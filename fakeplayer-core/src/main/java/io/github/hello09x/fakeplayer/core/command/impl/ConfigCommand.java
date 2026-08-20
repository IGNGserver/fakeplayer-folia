package io.github.hello09x.fakeplayer.core.command.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.jorel.commandapi.CommandAPI;
import dev.jorel.commandapi.exceptions.WrapperCommandSyntaxException;
import dev.jorel.commandapi.executors.CommandArguments;
import io.github.hello09x.devtools.core.translation.TranslatorUtils;
import io.github.hello09x.devtools.core.utils.ComponentUtils;
import io.github.hello09x.fakeplayer.core.manager.feature.FakeplayerFeatureManager;
import io.github.hello09x.fakeplayer.core.repository.model.Feature;
import net.kyori.adventure.text.format.Style;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

import static net.kyori.adventure.text.Component.*;
import static net.kyori.adventure.text.JoinConfiguration.separator;
import static net.kyori.adventure.text.event.ClickEvent.runCommand;
import static net.kyori.adventure.text.format.NamedTextColor.*;
import static net.kyori.adventure.text.format.TextDecoration.UNDERLINED;

@Singleton
public class ConfigCommand extends AbstractCommand {

    private final FakeplayerFeatureManager featureManager;

    @Inject
    public ConfigCommand(FakeplayerFeatureManager featureManager) {
        this.featureManager = featureManager;
    }

    /**
     * 设置配置
     */
    public void setConfig(@NotNull Player sender, @NotNull CommandArguments args) throws WrapperCommandSyntaxException {
        var feature = (Feature) Objects.requireNonNull(args.get("feature"));
        if (!feature.testPermissions(sender)) {
            throw CommandAPI.failWithString(ComponentUtils.toString(
                    translatable("fakeplayer.command.config.set.error.no-permission"),
                    TranslatorUtils.getLocale(sender)
            ));
        }

        var option = (String) Objects.requireNonNull(args.get("option"));
        if (!feature.getOptions().contains(option)) {
            throw CommandAPI.failWithString(ComponentUtils.toString(
                    translatable("fakeplayer.command.config.set.error.invalid-value"),
                    TranslatorUtils.getLocale(sender)
            ));
        }

        featureManager.setFeatureAsync(sender, feature, option)
                .thenRun(() -> sendTo(sender, translatable(
                        "fakeplayer.command.config.set.success",
                        translatable(feature.translationKey(), GOLD),
                        text(option, WHITE)
                ).color(GRAY)))
                .exceptionally(throwable -> {
                    log.warning("Failed to save fake-player feature for " + sender.getName() + ": " + throwable);
                    sendTo(sender, translatable("fakeplayer.command.config.set.error.save-failed", RED));
                    return null;
                });
    }

    /**
     * 获取所有配置
     */
    public void listConfig(@NotNull Player sender, @NotNull CommandArguments args) {
        featureManager.getFeaturesAsync(sender).thenAccept(features -> {
            var lines = features.values().stream().map(feature -> textOfChildren(
                    translatable(feature.key(), GOLD),
                    text(": ", GRAY),
                    join(separator(space()), feature.key().getOptions().stream().map(option -> {
                        var style = option.equals(feature.value())
                                ? Style.style(GREEN, UNDERLINED)
                                : Style.style(GRAY);
                        return text("[" + option + "]").style(style).clickEvent(
                                runCommand("/fp config set " + feature.key() + " " + option)
                        );
                    }).toList())
            )).toList();
            var message = join(separator(newline()), lines);
            sendTo(sender, message);
        }).exceptionally(throwable -> {
            log.warning("Failed to read fake-player features for " + sender.getName() + ": " + throwable);
            sendTo(sender, translatable("fakeplayer.command.config.set.error.save-failed", RED));
            return null;
        });
    }


}
