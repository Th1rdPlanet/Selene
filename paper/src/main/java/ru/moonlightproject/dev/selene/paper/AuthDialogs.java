package ru.moonlightproject.dev.selene.paper;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Kept isolated so Paper 1.21.1-1.21.7 never link Dialog API classes. */
final class AuthDialogs {
 private AuthDialogs(){}

 static void login(Player player,AuthMessages messages,Consumer<String> submit){
  var password=DialogInput.text("password",messages.component("messages.dialog-password-label")).maxLength(256).build();
  player.showDialog(dialog(messages,List.of(password),(view,audience)->{if(audience==player)submit.accept(view.getText("password"));}));
 }

 static void registration(Player player,AuthMessages messages,BiConsumer<String,String> submit){
  var password=DialogInput.text("password",messages.component("messages.dialog-password-label")).maxLength(256).build();
  var confirmation=DialogInput.text("confirmation",messages.component("messages.dialog-confirm-label")).maxLength(256).build();
  player.showDialog(dialog(messages,List.of(password,confirmation),(view,audience)->{if(audience==player)submit.accept(view.getText("password"),view.getText("confirmation"));}));
 }

 private static Dialog dialog(AuthMessages messages,List<? extends DialogInput> inputs,io.papermc.paper.registry.data.dialog.action.DialogActionCallback callback){
  var options=ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(3)).build();
  var button=ActionButton.builder(messages.component("messages.dialog-submit")).width(150).action(DialogAction.customClick(callback,options)).build();
  var base=DialogBase.builder(messages.component("messages.dialog-title")).canCloseWithEscape(true).pause(false)
   .body(List.of(DialogBody.plainMessage(messages.component("messages.dialog-body")))).inputs(inputs).build();
  return Dialog.create(factory->factory.empty().base(base).type(DialogType.notice(button)));
 }
}
