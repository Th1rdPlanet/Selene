package ru.moonlightproject.dev.selene.paper;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Kept isolated so Paper 1.21.1-1.21.7 never link Dialog API classes. */
final class AuthDialogs {
 private AuthDialogs(){}

 static void login(Player player,AuthMessages messages,ConfigurationSection config,Consumer<String> submit){
  var password=input(messages,config,"login","password");
  player.showDialog(dialog(messages,config,"login",List.of(password),(view,audience)->{if(audience==player)submit.accept(view.getText("password"));}));
 }

 static void registration(Player player,AuthMessages messages,ConfigurationSection config,BiConsumer<String,String> submit){
  var password=input(messages,config,"register","password");var confirmation=input(messages,config,"register","confirmation");
  player.showDialog(dialog(messages,config,"register",List.of(password,confirmation),(view,audience)->{if(audience==player)submit.accept(view.getText("password"),view.getText("confirmation"));}));
 }

 static void captcha(Player player,AuthMessages messages,ConfigurationSection config,String glyphs,Consumer<String> submit){
  String path="dialogs.captcha";
  var answer=DialogInput.text("answer",messages.component(path+".answer.label"))
   .width(config.getInt(path+".answer.width",220)).labelVisible(config.getBoolean(path+".answer.label-visible",true)).maxLength(5).build();
  Component glyphLine=Component.text(glyphs).font(Key.key(config.getString("captcha.font","selene:captcha")));
  Component body=messages.component(path+".body-before").append(Component.newline()).append(glyphLine).append(Component.newline()).append(messages.component(path+".body-after"));
  var options=ClickCallback.Options.builder().uses(1).lifetime(Duration.ofSeconds(config.getLong("dialogs.callback-lifetime-seconds",180))).build();
  var buttonBuilder=ActionButton.builder(messages.component(path+".button.text")).width(config.getInt(path+".button.width",150))
   .action(DialogAction.customClick((view,audience)->{if(audience==player)submit.accept(view.getText("answer"));},options));
  String tooltip=messages.template(path+".button.tooltip");if(!tooltip.isEmpty())buttonBuilder.tooltip(messages.component(path+".button.tooltip"));
  var base=DialogBase.builder(messages.component(path+".title")).externalTitle(messages.component(path+".external-title"))
   .canCloseWithEscape(config.getBoolean("dialogs.can-close-with-escape",true)).pause(config.getBoolean("dialogs.pause",false)).afterAction(DialogBase.DialogAfterAction.CLOSE)
   .body(List.of(DialogBody.plainMessage(body,config.getInt(path+".body-width",320)))).inputs(List.of(answer)).build();
  player.showDialog(Dialog.create(factory->factory.empty().base(base).type(DialogType.notice(buttonBuilder.build()))));
 }

 private static io.papermc.paper.registry.data.dialog.input.TextDialogInput input(AuthMessages messages,ConfigurationSection config,String screen,String field){
  String path="dialogs."+screen+"."+field;
  return DialogInput.text(field,messages.component(path+".label")).width(config.getInt(path+".width",300)).labelVisible(config.getBoolean(path+".label-visible",true)).maxLength(config.getInt(path+".max-length",256)).build();
 }

 private static Dialog dialog(AuthMessages messages,ConfigurationSection config,String screen,List<? extends DialogInput> inputs,io.papermc.paper.registry.data.dialog.action.DialogActionCallback callback){
  String path="dialogs."+screen;
  var options=ClickCallback.Options.builder().uses(1).lifetime(Duration.ofSeconds(config.getLong("dialogs.callback-lifetime-seconds",180))).build();
  var buttonBuilder=ActionButton.builder(messages.component(path+".button.text")).width(config.getInt(path+".button.width",150)).action(DialogAction.customClick(callback,options));
  String tooltip=messages.template(path+".button.tooltip");if(!tooltip.isEmpty())buttonBuilder.tooltip(messages.component(path+".button.tooltip"));var button=buttonBuilder.build();
  var base=DialogBase.builder(messages.component(path+".title")).externalTitle(messages.component(path+".external-title")).canCloseWithEscape(config.getBoolean("dialogs.can-close-with-escape",true)).pause(config.getBoolean("dialogs.pause",false)).afterAction(DialogBase.DialogAfterAction.CLOSE)
   .body(List.of(DialogBody.plainMessage(messages.component(path+".body"),config.getInt(path+".body-width",300)))).inputs(inputs).build();
  return Dialog.create(factory->factory.empty().base(base).type(DialogType.notice(button)));
 }
}
