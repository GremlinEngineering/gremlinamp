package ru.oopssre.gremlinamp;
import android.content.Context;
import android.net.*;
final class NetworkPolicy {
 static boolean allowed(Context context,String mode){
  ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
  NetworkCapabilities caps=cm.getNetworkCapabilities(cm.getActiveNetwork());
  return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&
   ("any".equals(mode)||caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)&&!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
 }
}
