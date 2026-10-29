package ru.moonlightproject.dev.selene.paper;

final class CaptchaMosaicLayout {
 private static final int PIXEL_ADVANCE=9;
 private CaptchaMosaicLayout(){}

 static int bodyWidth(String glyphs,int configuredWidth){
  int mosaicWidth=glyphs.lines().mapToInt(line->line.codePointCount(0,line.length())*PIXEL_ADVANCE).max().orElse(0);
  return Math.min(1024,Math.max(0,Math.max(configuredWidth,mosaicWidth)));
 }
}
