package ru.oopssre.gremlinamp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collections;
import java.util.List;

final class NetworkPolicy {
 static boolean allowed(Context context,String mode){
  ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
  NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
  return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&
   ("any".equals(mode)||caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
 }

 static HttpURLConnection openHttp(URL url) throws IOException {
  ProxySelector selector = ProxySelector.getDefault();
  List<Proxy> routes;
  try {
   routes = selector == null ? Collections.emptyList() : selector.select(url.toURI());
  } catch (URISyntaxException | IllegalArgumentException | SecurityException e) {
   throw new IOException("Не удалось применить системные настройки прокси", e);
  }
  if (routes == null || routes.isEmpty() || routes.get(0).type() == Proxy.Type.DIRECT) {
   return (HttpURLConnection) url.openConnection();
  }
  // Android's HttpURLConnection can append a direct route when it selects the proxy itself.
  return (HttpURLConnection) url.openConnection(routes.get(0));
 }
}
