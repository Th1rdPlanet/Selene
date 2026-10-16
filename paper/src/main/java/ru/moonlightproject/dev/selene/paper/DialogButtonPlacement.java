package ru.moonlightproject.dev.selene.paper;

import java.util.Locale;

enum DialogButtonPlacement {
 CONTENT,
 FOOTER;

 static DialogButtonPlacement parse(String value,String path){
  try{return valueOf(value.trim().toUpperCase(Locale.ROOT));}
  catch(IllegalArgumentException error){throw new IllegalArgumentException(path+" must be CONTENT or FOOTER");}
 }
}
