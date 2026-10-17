package ru.moonlightproject.dev.selene.paper;

import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QrGlyphEncoderTest {
 private static final String URI="otpauth://totp/Selene%3AVicTim?secret=JBSWY3DPEHPK3PXP&issuer=Selene&algorithm=SHA1&digits=6&period=30";
 private static final int SPACER=0xE3FF;

 @Test void glyphTilesRoundTripThroughARealQrDecoder() throws Exception{
  String glyphs=QrGlyphEncoder.encode(URI,new QrOptions(4,96));
  String[] rows=glyphs.split("\\n",-1);
  assertTrue(rows.length>0&&rows.length<=24);
  assertTrue(rows[0].length()>0&&rows[0].length()<=96);
  for(String row:rows)for(int index=0;index<row.length();index++)
   assertTrue(index%2==0?row.charAt(index)>=0xE400&&row.charAt(index)<=0xE4FF:row.charAt(index)==SPACER);
  assertEquals(URI,decode(glyphs));
 }

 @Test void encodingIsDeterministicAndKeepsTheFourModuleQuietZone(){
  String first=QrGlyphEncoder.encode(URI,new QrOptions(4,96));
  assertEquals(first,QrGlyphEncoder.encode(URI,new QrOptions(4,96)));
  boolean[][] modules=modules(first);
  for(int y=0;y<4;y++)for(int x=0;x<modules[0].length;x++)assertFalse(modules[y][x]);
  for(int y=0;y<modules.length;y++)for(int x=0;x<4;x++)assertFalse(modules[y][x]);
 }

 @Test void oversizedOrInvalidOptionsAreRejected(){
  assertThrows(IllegalArgumentException.class,()->QrGlyphEncoder.encode("x".repeat(4097),new QrOptions(4,96)));
  assertThrows(IllegalArgumentException.class,()->new QrOptions(3,96));
  assertThrows(IllegalArgumentException.class,()->new QrOptions(4,20));
 }

 private static String decode(String glyphs) throws Exception{
  boolean[][] modules=modules(glyphs);int scale=4,width=modules[0].length*scale,height=modules.length*scale;byte[] luminance=new byte[width*height];
  for(int y=0;y<height;y++)for(int x=0;x<width;x++)luminance[y*width+x]=(byte)(modules[y/scale][x/scale]?0:255);
  LuminanceSource source=new LuminanceSource(width,height){
   @Override public byte[] getRow(int y,byte[] row){if(row==null||row.length<width)row=new byte[width];System.arraycopy(luminance,y*width,row,0,width);return row;}
   @Override public byte[] getMatrix(){return luminance.clone();}
  };
  return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)),Map.of(DecodeHintType.PURE_BARCODE,true)).getText();
 }

 private static boolean[][] modules(String glyphs){
  String[] rows=glyphs.split("\\n",-1);int tiles=rows[0].length()/2;boolean[][] result=new boolean[rows.length*4][tiles*2];
  for(int tileY=0;tileY<rows.length;tileY++)for(int tileX=0;tileX<tiles;tileX++){
   int bits=rows[tileY].charAt(tileX*2)-0xE400;
   for(int y=0;y<4;y++)for(int x=0;x<2;x++)result[tileY*4+y][tileX*2+x]=(bits&(1<<(y*2+x)))!=0;
  }
  return result;
 }
}
