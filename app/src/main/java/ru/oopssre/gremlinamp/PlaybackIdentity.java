package ru.oopssre.gremlinamp;
/** One immutable identity for everything shown in the now-playing panel. */
final class PlaybackIdentity {
 static final class View {final int generation;final String title,game,art;View(int g,String t,String a,String p){generation=g;title=t;game=a;art=p;}}
 private volatile View view=new View(0,"Выберите композицию","","");
 synchronized void begin(int generation,String title,String game){view=new View(generation,title,game,"");}
 synchronized boolean artwork(int generation,String path){View current=view;if(current.generation!=generation)return false;view=new View(generation,current.title,current.game,path);return true;}
 View snapshot(){return view;}
}
