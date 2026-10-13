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
 static void show(Player p,String title,String text,String close){
  p.showDialog(Dialog.create(f->f.empty().base(DialogBase.builder(Component.text(title)).body(List.of(DialogBody.plainMessage(Component.text(text)))).build()).type(DialogType.notice(ActionButton.builder(Component.text(close)).build()))));
 }
}
