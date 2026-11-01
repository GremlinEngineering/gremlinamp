package ru.oopssre.gremlinamp;
import java.util.*;
final class ContinuationRules {
 static int score(String mode,boolean sameSeries,Set<String> sourceGenres,Set<String> genres,boolean samePlatform){
  int common=0;for(String g:sourceGenres)if(genres.contains(g))common++;
  if(mode.equals("series"))return sameSeries?100:0;
  if(mode.equals("genre"))return common>0?common*10:0;
  if(mode.equals("similar"))return (sameSeries?100:0)+common*10+(samePlatform&&common>0?2:0);
  return 0;
 }
 static int nextIndex(int position,int size,Set<Integer> played,boolean shuffle,boolean loop,Random random){
  if(size==0)return -1;
  if(!shuffle){int next=position+1;return next<size?next:loop?0:-1;}
  List<Integer> remaining=new ArrayList<>();for(int i=0;i<size;i++)if(!played.contains(i)&&i!=position)remaining.add(i);
  if(remaining.isEmpty()&&loop){played.clear();for(int i=0;i<size;i++)if(i!=position)remaining.add(i);if(size==1)return 0;}
  return remaining.isEmpty()?-1:remaining.get(random.nextInt(remaining.size()));
 }
}
