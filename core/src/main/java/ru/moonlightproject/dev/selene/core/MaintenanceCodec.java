package ru.moonlightproject.dev.selene.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class MaintenanceCodec {
 private static final int VERSION=1,MAX=1_048_576;
 private MaintenanceCodec(){}
 public static byte[] encode(MaintenanceMessage message){
  try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeByte(VERSION);
   switch(message){
    case MaintenanceMessage.State s->{out.writeByte(1);out.writeLong(s.revision());out.writeBoolean(s.enabled());policy(out,s.policy());string(out,s.actor());string(out,s.reason());}
    case MaintenanceMessage.Whitelist w->{out.writeByte(2);string(out,w.serverId());out.writeLong(w.revision());out.writeInt(w.entries().size());for(var e:w.entries()){string(out,e.name());out.writeLong(e.uuid().getMostSignificantBits());out.writeLong(e.uuid().getLeastSignificantBits());}}
    case MaintenanceMessage.Ack a->{out.writeByte(3);string(out,a.serverId());out.writeLong(a.revision());}
   }out.flush();byte[] result=bytes.toByteArray();if(result.length>MAX)throw new IllegalArgumentException("Maintenance message is too large");return result;
  }catch(IOException impossible){throw new IllegalStateException(impossible);}
 }
 public static MaintenanceMessage decode(byte[] bytes){
  if(bytes==null||bytes.length>MAX)throw new IllegalArgumentException("Invalid maintenance message size");
  try{var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readUnsignedByte()!=VERSION)throw new IllegalArgumentException("Unsupported maintenance protocol");
   MaintenanceMessage result=switch(in.readUnsignedByte()){
    case 1->new MaintenanceMessage.State(in.readLong(),in.readBoolean(),policy(in),string(in,256),string(in,256));
    case 2->{String server=string(in,64);long revision=in.readLong();int count=in.readInt();if(count<0||count>10_000)throw new IllegalArgumentException("Invalid whitelist size");var entries=new ArrayList<MaintenanceMessage.Entry>(count);for(int i=0;i<count;i++)entries.add(new MaintenanceMessage.Entry(string(in,16),new UUID(in.readLong(),in.readLong())));yield new MaintenanceMessage.Whitelist(server,revision,entries);}
    case 3->new MaintenanceMessage.Ack(string(in,64),in.readLong());
    default->throw new IllegalArgumentException("Unknown maintenance message");};
   if(in.available()!=0)throw new IllegalArgumentException("Trailing maintenance data");return result;
  }catch(IOException|RuntimeException error){if(error instanceof IllegalArgumentException invalid)throw invalid;throw new IllegalArgumentException("Malformed maintenance message",error);}
 }
 private static void policy(DataOutputStream out,MaintenanceMessage.Policy p)throws IOException{out.writeBoolean(p.requireTrustedIp());out.writeBoolean(p.requirePremium());out.writeBoolean(p.enableCaptcha());out.writeBoolean(p.verifyCaptcha());out.writeBoolean(p.enablePassword());out.writeBoolean(p.verifyPassword());out.writeBoolean(p.enableTotp());out.writeBoolean(p.verifyTotp());out.writeBoolean(p.elevateSeleneCommands());}
 private static MaintenanceMessage.Policy policy(DataInputStream in)throws IOException{return new MaintenanceMessage.Policy(in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean(),in.readBoolean());}
 private static void string(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
 private static String string(DataInputStream in,int maximum)throws IOException{int size=in.readInt();if(size<0||size>maximum*4)throw new IllegalArgumentException("Invalid string size");byte[] bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException();String value=new String(bytes,StandardCharsets.UTF_8);if(value.length()>maximum)throw new IllegalArgumentException("String is too long");return value;}
}
