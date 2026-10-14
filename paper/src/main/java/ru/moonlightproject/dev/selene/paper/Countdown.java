package ru.moonlightproject.dev.selene.paper;

final class Countdown {
 private Countdown(){}
 static long secondsRemaining(long nowMillis,long deadlineMillis){return Math.max(0,(deadlineMillis-nowMillis+999)/1000);}
 static float fraction(long nowMillis,long startedMillis,long deadlineMillis){
  if(deadlineMillis<=startedMillis)return 0;
  return (float)Math.max(0,Math.min(1,(double)(deadlineMillis-nowMillis)/(deadlineMillis-startedMillis)));
 }
 static boolean movementChanged(double ax,double ay,double az,float ayaw,float apitch,double bx,double by,double bz,float byaw,float bpitch){
  return Double.compare(ax,bx)!=0||Double.compare(ay,by)!=0||Double.compare(az,bz)!=0||Float.compare(ayaw,byaw)!=0||Float.compare(apitch,bpitch)!=0;
 }
}
