package ru.oopssre.gremlinamp;

import java.util.Collection;

/** Action availability shared by collection headers and context menus. */
final class UiRules {
 static String downloadAction(Collection<String> states,boolean busy){
  if(busy||states.isEmpty())return "";
  boolean actionable=false,update=false,retry=false;
  for(String state:states){
   if(state==null||state.equals("Проверка…"))return "";
   actionable|=!state.equals("Скачано")&&!state.equals("Ожидание");update|=state.equals("Обновить");retry|=state.equals("Повторить");
  }
  return !actionable?"":retry?"Повторить":update?"Обновить":"Скачать";
 }
 static String appUpdateAction(boolean checking,boolean downloaded,boolean downloading){
  return checking?"Проверяю…":downloaded?"Установить обновление":downloading?"Отменить скачивание":"Проверить обновление";
 }
 static boolean canSeek(String state,boolean known,long duration){return known&&duration>0&&(state.equals("playing")||state.equals("paused"));}
}
