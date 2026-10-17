package ru.moonlightproject.dev.selene.paper.interactive;

public enum ScenarioType {
 BREAK_BLOCK, CHOP_TREE, TAKE_FROM_CHEST, ARMOR_STAND, PRESS_BUTTON,
 TOGGLE_LEVER, STAND_ON_BLOCK, PUT_IN_CHEST, ITEM_FRAME;

 static ScenarioType fromConfig(String key){return valueOf(key.toUpperCase(java.util.Locale.ROOT).replace('-','_'));}
}
