package ru.moonlightproject.dev.selene.paper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

record QrOptions(int quietZone,int maxModules){
 QrOptions{
  if(quietZone<4||quietZone>16)throw new IllegalArgumentException("QR quiet zone must be between 4 and 16 modules");
  if(maxModules<21||maxModules>177)throw new IllegalArgumentException("QR module limit must be between 21 and 177");
 }
}

final class QrGlyphEncoder{
 private static final int FIRST_GLYPH=0xE400;
 private QrGlyphEncoder(){}

 static String encode(String uri,QrOptions options){
  if(uri==null||uri.isBlank()||uri.length()>4096)throw new IllegalArgumentException("Invalid QR payload");
  if(options==null)throw new IllegalArgumentException("Missing QR options");
  final BitMatrix raw;
  try{raw=new QRCodeWriter().encode(uri,BarcodeFormat.QR_CODE,0,0,Map.of(EncodeHintType.ERROR_CORRECTION,ErrorCorrectionLevel.M,EncodeHintType.MARGIN,0,EncodeHintType.CHARACTER_SET,"UTF-8"));}
  catch(WriterException error){throw new IllegalArgumentException("QR payload cannot be encoded",error);}
  int content=raw.getWidth();
  if(content>options.maxModules())throw new IllegalArgumentException("QR payload exceeds configured module limit");
  int width=roundUp(content+options.quietZone()*2,2),height=roundUp(content+options.quietZone()*2,4);
  StringBuilder glyphs=new StringBuilder((width/2+1)*(height/4));
  for(int tileY=0;tileY<height;tileY+=4){
   if(tileY>0)glyphs.append('\n');
   for(int tileX=0;tileX<width;tileX+=2){
    int bits=0;
    for(int y=0;y<4;y++)for(int x=0;x<2;x++){
     int sourceX=tileX+x-options.quietZone(),sourceY=tileY+y-options.quietZone();
     if(sourceX>=0&&sourceY>=0&&sourceX<content&&sourceY<content&&raw.get(sourceX,sourceY))bits|=1<<(y*2+x);
    }
    glyphs.append((char)(FIRST_GLYPH+bits));
   }
  }
  return glyphs.toString();
 }
 private static int roundUp(int value,int multiple){return ((value+multiple-1)/multiple)*multiple;}
}
