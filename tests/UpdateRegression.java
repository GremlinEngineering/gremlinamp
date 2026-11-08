package ru.oopssre.gremlinamp;
import static ru.oopssre.gremlinamp.UpdateRules.Stage.*;
public class UpdateRegression {
 static int checks=12;
 static void expect(boolean checking,boolean tracked,boolean downloading,boolean complete,UpdateRules.Stage expected){
  checks++;if(UpdateRules.stage(checking,tracked,downloading,complete)!=expected)throw new AssertionError(expected);
 }
 public static void main(String[] args)throws Exception{
  String expected="https://games.oopssre.ru/assets/arcade/music-app/GremlinAmp-0.2.1.apk";
  for(String s:new String[]{"assets/arcade/music-app/GremlinAmp-0.2.1.apk","/assets/arcade/music-app/GremlinAmp-0.2.1.apk",expected})if(!expected.equals(UpdateRules.apkUrl(s)))throw new AssertionError(s);
  for(String s:new String[]{"http://games.oopssre.ru/assets/arcade/music-app/x.apk","https://evil.test/assets/arcade/music-app/x.apk","https://games.oopssre.ru@evil.test/assets/arcade/music-app/x.apk","/assets/arcade/music-app/%2e%2e/x.apk","/assets/other/x.apk","/assets/arcade/music-app/x.zip","/assets/arcade/music-app/x.apk?x=1","/assets/arcade/music-app/x.apk#x","https://games.oopssre.ru:444/assets/arcade/music-app/x.apk"}){boolean rejected=false;try{UpdateRules.apkUrl(s);}catch(Exception e){rejected=true;}if(!rejected)throw new AssertionError("accepted "+s);}
  expect(false,false,false,false,CHECK);
  expect(true,false,false,false,CHECKING);
  expect(true,true,true,false,CHECKING);
  expect(true,true,false,true,CHECKING);
  expect(false,true,true,false,DOWNLOADING);
  expect(false,true,false,true,READY);
  expect(false,true,false,false,RETRY);
  expect(false,false,false,true,CHECK);
  if(CHECKING.actionable()||!READY.actionable()||!RETRY.actionable())throw new AssertionError("update action gating");
  checks++;
  System.out.println(checks+" APK URL and state checks passed");
 }
}
