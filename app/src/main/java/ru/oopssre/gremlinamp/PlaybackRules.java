package ru.oopssre.gremlinamp;
import org.json.JSONObject;

final class PlaybackRules {
 static long playMs(JSONObject track){return Math.min(Integer.MAX_VALUE,Math.max(0,track.optLong("play_ms",120000)));}
 static long fadeMs(JSONObject track,long play){return Math.min(Integer.MAX_VALUE-play,Math.max(0,track.optLong("fade_ms",0)));}
 static long playbackMs(JSONObject track){long play=playMs(track);return total(play,fadeMs(track,play));}
 static long frameDuration(long frames,int rate){return (frames*1000+rate-1)/rate;}
 static long total(long play,long fade){return Math.max(0,play)+Math.max(0,fade);}
 static long position(long position,long duration,boolean known){return Math.max(0,known?Math.min(position,duration):position);}
 static boolean include(long duration,int threshold,String unknown){if(duration<0)return !unknown.equals("hide");if(unknown.equals("only"))return false;return threshold<=0||duration>=threshold;}
}
