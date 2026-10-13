package ru.moonlightproject.dev.selene.paper;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.*;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import java.util.List;
/** Isolated class, loaded only after checking server and client capabilities. */
final class AuthNotice {
 static void show(Player p,Component title,Component text,Component close){
  p.showDialog(Dialog.create(f->f.empty().base(DialogBase.builder(title).body(List.of(DialogBody.plainMessage(text))).build()).type(DialogType.notice(ActionButton.builder(close).build()))));
 }
}
