package ru.oopssre.gremlinamp;
/** UI policy is independent of network state: a saved index works offline. */
final class SimilarityAvailability {
 final boolean rhythm,sound;final String rhythmReason,soundReason;
 SimilarityAvailability(boolean r,boolean s,String rr,String sr){rhythm=r;sound=s;rhythmReason=rr;soundReason=sr;}
 static SimilarityAvailability unavailable(String reason){return new SimilarityAvailability(false,false,reason,reason);}
 static SimilarityAvailability evaluate(boolean index,boolean selection,boolean source,boolean rhythm,boolean sound){
  if(!index)return unavailable("Индекс анализа ещё не загружен");
  if(!selection)return unavailable("Сначала выберите и запустите композицию");
  if(!source)return unavailable("Эта композиция ещё не проанализирована");
  return new SimilarityAvailability(rhythm,sound,rhythm?"":"Для этой композиции нет ритмических совпадений",sound?"":"Для этой композиции нет совпадений по звучанию");
 }
 boolean enabled(String mode){return mode.equals("rhythm")?rhythm:mode.equals("sound")?sound:true;}
 String reason(String mode){return mode.equals("rhythm")?rhythmReason:mode.equals("sound")?soundReason:"";}
}
