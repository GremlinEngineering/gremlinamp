package ru.oopssre.gremlinamp;
final class PlaybackRules {
 static long total(long play,long fade){return Math.max(0,play)+Math.max(0,fade);}
 static long position(long position,long duration,boolean known){return Math.max(0,known?Math.min(position,duration):position);}
 static boolean include(long duration,int threshold,String unknown){if(duration<0)return !unknown.equals("hide");if(unknown.equals("only"))return false;return threshold<=0||duration>=threshold;}
}
