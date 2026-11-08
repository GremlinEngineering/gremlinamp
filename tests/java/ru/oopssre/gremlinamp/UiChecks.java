package ru.oopssre.gremlinamp;
import java.util.*;
import static ru.oopssre.gremlinamp.TransportState.*;
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
  check(UiRules.canSeek(PLAYING,true,1000),"playing audio can seek");
  check(UiRules.canSeek(PAUSED,true,1000),"paused audio can seek");
  check(!UiRules.canSeek(ENDED,true,1000),"finished audio cannot expose a no-op seek control");
  check(!UiRules.canSeek(CONTINUING,true,1000),"continuation search cannot expose a no-op seek control");
  check(!UiRules.canSeek(PLAYING,false,1000),"unknown duration cannot seek");
  for(TransportState state:List.of(PLAYING,LOADING,CONTINUING)){
   check(UiRules.switchSeries("a","b",state),"active series switches to a different series");
   check(!UiRules.switchSeries("a","a",state),"opening the active series preserves position");
  }
  for(TransportState state:List.of(IDLE,PAUSED,LOADING_PAUSED,ENDED,ERROR))
   check(!UiRules.switchSeries("a","b",state),"inactive queue allows browsing");
  check(!UiRules.switchSeries("","b",PLAYING),"radio or a standalone album allows browsing");
  System.out.println(count+" interface action checks passed");
 }
}
