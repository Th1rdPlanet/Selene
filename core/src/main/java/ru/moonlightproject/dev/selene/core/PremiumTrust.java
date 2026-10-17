package ru.moonlightproject.dev.selene.core;

import java.net.Inet6Address;
import java.net.InetAddress;

public record PremiumTrust(String ip,long expiresAt){
 public PremiumTrust{
  ip=canonical(ip);
  if(expiresAt<0)throw new IllegalArgumentException("Invalid premium trust expiry");
 }
 private static String canonical(String value){
  if(value==null||value.isBlank())throw new IllegalArgumentException("Invalid premium trust IP");
  if(value.indexOf(':')>=0){
   try{InetAddress parsed=InetAddress.getByName(value);if(parsed instanceof Inet6Address)return parsed.getHostAddress();}
   catch(Exception ignored){}
   throw new IllegalArgumentException("Invalid premium trust IP");
  }
  String[] parts=value.split("\\.",-1);if(parts.length!=4)throw new IllegalArgumentException("Invalid premium trust IP");
  StringBuilder result=new StringBuilder();
  for(String part:parts){if(part.isEmpty()||!part.chars().allMatch(Character::isDigit))throw new IllegalArgumentException("Invalid premium trust IP");int number;try{number=Integer.parseInt(part);}catch(NumberFormatException error){throw new IllegalArgumentException("Invalid premium trust IP",error);}if(number>255)throw new IllegalArgumentException("Invalid premium trust IP");if(!result.isEmpty())result.append('.');result.append(number);}
  return result.toString();
 }
}
