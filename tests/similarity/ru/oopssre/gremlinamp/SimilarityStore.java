package ru.oopssre.gremlinamp;
import org.json.*;import java.util.*;
/** Fake storage boundary; ranking and selection use production classes. */
final class SimilarityStore {static final Map<String,JSONObject> data=new HashMap<>();static void ensureReadable(Library l){}static List<SimilarityRules.Target> targets(Library l,String seed,String mode,Set<String> played)throws Exception{JSONObject source=data.get(seed);if(source==null)throw new java.io.IOException("analysis missing");return SimilarityRules.targets(source,mode,played,data::get);}}
