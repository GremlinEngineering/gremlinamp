package ru.oopssre.gremlinamp;
import java.net.URI;
import java.io.IOException;
final class UpdateRules {
 static String apkUrl(String relative)throws Exception{
  URI u=new URI("https://games.oopssre.ru/").resolve(relative);
  String path=u.getPath();
  if(!"https".equals(u.getScheme())||!"games.oopssre.ru".equals(u.getHost())||u.getPort()!=-1||u.getUserInfo()!=null||u.getFragment()!=null||u.getQuery()!=null||path==null||!path.startsWith("/assets/arcade/music-app/")||!path.endsWith(".apk")||path.contains(".."))throw new IOException("Некорректный адрес APK");
  return u.toASCIIString();
 }
}
