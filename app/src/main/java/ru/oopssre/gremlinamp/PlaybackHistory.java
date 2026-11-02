package ru.oopssre.gremlinamp;

import java.util.ArrayList;
import java.util.List;

/** Actual listening order, independent of shuffle and album boundaries. */
final class PlaybackHistory {
 private final List<Integer> entries=new ArrayList<>();
 private int cursor=-1;
 void clear(){entries.clear();cursor=-1;}
 int current(){return cursor<0?-1:entries.get(cursor);}
 void record(int index){
  if(current()==index)return;
  while(entries.size()>cursor+1)entries.remove(entries.size()-1);
  entries.add(index);cursor++;
 }
 int previous(){return cursor>0?entries.get(--cursor):-1;}
 int next(){return cursor+1<entries.size()?entries.get(++cursor):-1;}
}
