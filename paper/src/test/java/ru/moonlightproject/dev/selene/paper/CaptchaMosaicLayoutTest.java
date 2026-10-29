package ru.moonlightproject.dev.selene.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CaptchaMosaicLayoutTest {
 @Test void bodyWidthFitsMosaicWithoutShrinkingConfiguredWidth(){
  String row="\uE108".repeat(31);
  String mosaic=String.join("\n",java.util.Collections.nCopies(10,row));
  assertEquals(320,CaptchaMosaicLayout.bodyWidth(mosaic,320));
  assertEquals(279,CaptchaMosaicLayout.bodyWidth(mosaic,200));
 }

 @Test void bodyWidthIsBoundedForMalformedInput(){
  assertEquals(1024,CaptchaMosaicLayout.bodyWidth("\uE108".repeat(500),100));
 }
}
