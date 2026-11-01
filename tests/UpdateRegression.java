package ru.oopssre.gremlinamp;
public class UpdateRegression {
 public static void main(String[] args)throws Exception{
  String expected="https://games.oopssre.ru/assets/arcade/music-app/GremlinAmp-0.2.1.apk";
  for(String s:new String[]{"assets/arcade/music-app/GremlinAmp-0.2.1.apk","/assets/arcade/music-app/GremlinAmp-0.2.1.apk",expected})if(!expected.equals(UpdateRules.apkUrl(s)))throw new AssertionError(s);
  for(String s:new String[]{"http://games.oopssre.ru/assets/arcade/music-app/x.apk","https://evil.test/assets/arcade/music-app/x.apk","https://games.oopssre.ru@evil.test/assets/arcade/music-app/x.apk","/assets/arcade/music-app/%2e%2e/x.apk","/assets/other/x.apk","/assets/arcade/music-app/x.zip","/assets/arcade/music-app/x.apk?x=1","/assets/arcade/music-app/x.apk#x","https://games.oopssre.ru:444/assets/arcade/music-app/x.apk"}){boolean rejected=false;try{UpdateRules.apkUrl(s);}catch(Exception e){rejected=true;}if(!rejected)throw new AssertionError("accepted "+s);}
  System.out.println("12 APK URL contract checks passed");
 }
}
