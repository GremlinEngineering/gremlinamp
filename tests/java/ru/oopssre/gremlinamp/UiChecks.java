package ru.oopssre.gremlinamp;
import java.util.*;
public class UiChecks {
 static int count;static void check(boolean value,String message){count++;if(!value)throw new AssertionError(message);}
 public static void main(String[] args){
  check(UiRules.downloadAction(List.of(),false).isEmpty(),"no collection, no download button");
  check(UiRules.downloadAction(List.of("Скачано"),false).isEmpty(),"downloaded is status, not a button");
  check(UiRules.downloadAction(List.of("Ожидание"),false).isEmpty(),"automatic work needs no manual command");
  check(UiRules.downloadAction(List.of("Скачано","Ожидание"),false).isEmpty(),"waiting group needs no duplicate command");
  check(UiRules.downloadAction(List.of("Скачать"),false).equals("Скачать"),"one download action");
  check(UiRules.downloadAction(List.of("Повторить"),false).equals("Повторить"),"same action retries an error");
  check(UiRules.downloadAction(List.of("Обновить"),false).equals("Обновить"),"new music can be updated");
  check(UiRules.downloadAction(List.of("Скачать","Повторить","Обновить"),false).equals("Повторить"),"a group has one action");
  check(UiRules.downloadAction(List.of("Скачать"),true).isEmpty(),"busy action cannot start another transfer");
  check(UiRules.downloadAction(Arrays.asList("Скачать",null),false).isEmpty(),"unverified state cannot start a transfer");
  check(UiRules.downloadAction(List.of("Ожидание","Скачать"),false).equals("Скачать"),"additional requested games remain downloadable");
  check(UiRules.appUpdateAction(false,false,false).equals("Проверить обновление"),"no installer: check only");
  check(UiRules.appUpdateAction(false,false,true).equals("Отменить скачивание"),"running update: cancel only");
  check(UiRules.appUpdateAction(false,true,false).equals("Установить обновление"),"downloaded installer: install only");
  check(UiRules.appUpdateAction(true,false,false).equals("Проверяю…"),"duplicate update check is blocked");
  check(UiRules.canSeek("playing",true,1000),"playing audio can seek");
  check(UiRules.canSeek("paused",true,1000),"paused audio can seek");
  check(!UiRules.canSeek("ended",true,1000),"finished audio cannot expose a no-op seek control");
  check(!UiRules.canSeek("continuing",true,1000),"continuation search cannot expose a no-op seek control");
  check(!UiRules.canSeek("playing",false,1000),"unknown duration cannot seek");
  for(String state:List.of("playing","loading","continuing")){
   check(UiRules.switchSeries("a","b",state,false),"active series switches to a different series");
   check(!UiRules.switchSeries("a","a",state,false),"opening the active series preserves position");
  }
  for(String state:List.of("idle","paused","ended","error"))
   check(!UiRules.switchSeries("a","b",state,state.equals("paused")),"inactive queue allows browsing");
  check(!UiRules.switchSeries("","b","playing",false),"radio or a standalone album allows browsing");
  System.out.println(count+" interface action checks passed");
 }
}
