package ru.oopssre.gremlinamp;
import java.util.*;
final class ContinuationRules {
 static int score(String mode,boolean sameSeries,Set<String> sourceGenres,Set<String> genres,boolean samePlatform){
  int common=0;for(String g:sourceGenres)if(genres.contains(g))common++;
  if(mode.equals("library"))return 1;
  if(mode.equals("series"))return sameSeries?100:0;
  if(mode.equals("genre"))return common>0?common*10:0;
  if(mode.equals("similar"))return (sameSeries?100:0)+common*10+(samePlatform&&common>0?2:0);
  return 0;
 }
 static int nextIndex(int position,int size,Set<Integer> played,boolean shuffle,boolean loop,Random random){
  return nextIndex(position,size,played,Collections.emptySet(),shuffle,loop,random);
 }
 static int nextIndex(int position,int size,Set<Integer> played,Set<Integer> rejected,boolean shuffle,boolean loop,Random random){
  if(size==0)return -1;
  if(!shuffle){
   int limit=loop?size:size-position-1;
   for(int offset=1;offset<=limit;offset++){
    int next=(position+offset)%size;
    if(!rejected.contains(next))return next;
   }
   return -1;
  }
  List<Integer> remaining=new ArrayList<>();
  for(int i=0;i<size;i++)if(!played.contains(i)&&i!=position&&!rejected.contains(i))remaining.add(i);
  if(remaining.isEmpty()&&loop){
   played.clear();
   for(int i=0;i<size;i++)if(i!=position&&!rejected.contains(i))remaining.add(i);
   if(remaining.isEmpty()&&size==1&&!rejected.contains(0))return 0;
  }
  return remaining.isEmpty()?-1:remaining.get(random.nextInt(remaining.size()));
 }
}
