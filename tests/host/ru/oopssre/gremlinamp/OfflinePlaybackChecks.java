package ru.oopssre.gremlinamp;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Exercises production storage and transport controls; platform audio is simulated. */
public final class OfflinePlaybackChecks {
 static int checks;
 static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
 static void await(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+2_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(5);check(condition.getAsBoolean(),"asynchronous operation completed");}
 static JSONObject game(int id)throws Exception{return new JSONObject().put("id","game-ra-"+id).put("title","Game "+id).put("href","games/game-series-ra-"+id+".html").put("manifest","music/sets/"+id+"-v1.json").put("terms",new JSONObject().put("genre",new JSONObject().put("action","Action")));}
 static JSONObject asset(byte[] bytes)throws Exception{return new JSONObject().put("sha256",Library.hash(bytes)).put("bytes",bytes.length).put("format","nsf").put("url","../files/test.nsf");}
 static JSONObject track(JSONObject asset)throws Exception{return new JSONObject().put("title","Track").put("asset",asset).put("backend","gme").put("duration_known",true).put("play_ms",30000);}
 static void cache(Library l,String path,JSONObject data)throws Exception{Library.atomic(l.jsonFile(Library.BASE+path),data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 static void gallery(Library l,String kind,JSONArray data)throws Exception{Library.atomic(l.documentFile(Library.BASE+"assets/gallery-"+kind+".json"),data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));Library.ART_REVISION.incrementAndGet();}
 static void fixture(Library l,JSONObject game,JSONObject manifest)throws Exception{
  cache(l,game.getString("manifest"),manifest);cache(l,"assets/gallery-music.json",new JSONObject().put("games",new JSONArray().put(game)));
  cache(l,"assets/statistics.json",new JSONObject().put("editions",new JSONArray()).put("series",new JSONArray()));
  JSONObject poster=new JSONObject().put("src","assets/full.png?v=1").put("preview","assets/small.webp?v=1");
  gallery(l,"games",new JSONArray().put(new JSONObject().put("id","series-ra-1").put("href",game.getString("href")).put("views",new JSONObject().put("poster",poster))));gallery(l,"series",new JSONArray());
 }
 static class Probe extends PlaybackService implements AutoCloseable {
  int started=-1;
  Probe(Library l){library=l;}
  synchronized void start(int index){check(index>=0&&index<queue.size(),"valid queue position");generation++;position=index;started=index;state="playing";history.record(index);playedTracks.add(index);playedGames.add(queue.get(index).game.optString("id"));}
  void announce(){}
  public void close(){generation++;executor.shutdownNow();continuationIo.shutdownNow();artworkIo.shutdownNow();}
 }
 static final class Counter extends Probe {int continuations;Counter(Library l){super(l);}void continueAlbum(){continuations++;}}
 static final class RealProbe extends PlaybackService implements AutoCloseable {
  volatile boolean audioStarted;
  RealProbe(Library l){library=l;am=new android.media.AudioManager();session=new android.media.session.MediaSession(this,"test");}
  void announce(){}
  void nativePlay(File f,JSONObject t,int token){began(token);audioStarted=true;state="playing";}
  public void close(){generation++;if(work!=null)work.cancel(true);if(artworkWork!=null)artworkWork.cancel(true);executor.shutdownNow();continuationIo.shutdownNow();artworkIo.shutdownNow();}
 }
 public static void main(String[] args)throws Exception {
  Context context=new Context(Files.createTempDirectory("gremlin-offline-test").toFile());Library l=new Library(context);
  JSONObject game=game(1),asset=asset(new byte[]{1,2,3}),track=track(asset),manifest=new JSONObject().put("tracks",new JSONArray().put(track));
  fixture(l,game,manifest);Library.atomic(l.assetFile(asset),new byte[]{1,2,3});
  check(l.pictureRecord(game,false)!=null,"series-ra artwork found by href");JSONObject noHref=new JSONObject(game.toString());noHref.remove("href");check(l.pictureRecord(noHref,false)!=null,"stable RA identity fallback");
  File full=l.pictureFile(game,false,"assets/full.png?v=1");Library.atomic(full,new byte[]{5});check(l.picture(game,false,true).equals(full),"full poster substitutes for missing thumbnail offline");
  l.download(List.of(game),s->{});check(l.downloadLabel(game).equals("Повторить"),"missing artwork cannot claim complete offline download");check(l.fullyLocal(game),"missing artwork does not disable saved music");check(l.downloadSummary.contains("автоматически"),"partial artwork is reported");
  Library.atomic(l.pictureFile(game,false,"assets/small.webp?v=1"),new byte[]{6});l.download(List.of(game),s->{});check(l.downloadLabel(game).equals("Скачано"),"retry completes artwork without another audio download");
  JSONObject metadataOnly=new JSONObject(game.toString()).put("manifest","music/sets/1-metadata-update.json");cache(l,metadataOnly.getString("manifest"),manifest);check(l.downloadLabel(metadataOnly).equals("Обновить"),"new track metadata remains an update even when all audio bytes are already saved");
  JSONObject updated=new JSONObject(game.toString()).put("manifest","music/sets/1-v2.json");JSONObject changed=new JSONObject().put("tracks",new JSONArray().put(track(asset(new byte[]{9}))));cache(l,updated.getString("manifest"),changed);
  check(l.localManifest(updated).getJSONArray("tracks").getJSONObject(0).getJSONObject("asset").getString("sha256").equals(asset.getString("sha256")),"incomplete update retains prior playable manifest");check(l.downloadLabel(updated).equals("Обновить"),"pending update remains visible");
  boolean rejected=false;try{l.markComplete(updated,changed);}catch(IOException expected){rejected=true;}check(rejected&&l.fullyLocal(updated),"incomplete manifest cannot replace saved snapshot");
  l.jsonFile(Library.BASE+"assets/gallery-music.json").delete();check(l.catalog(false).getJSONArray("games").length()==1,"saved library opens without cached global catalog");
  l.jsonFile(Library.BASE+"assets/statistics.json").delete();check(l.enrichGames(List.of(game)).size()==1,"missing statistics does not block local browsing or genre data");fixture(l,game,manifest);
  check(l.remainingCached(List.of(game))==0,"download confirmation uses cached sizes without a network preflight");
  check(l.remainingCached(List.of(game(999)))==-1,"unknown download size does not fetch hundreds of manifests before confirmation");
  Library.atomic(l.pictureFile(game,false,"assets/small.webp?v=1"),new byte[]{6});
  Files.deleteIfExists(l.stateFile("offline",game).toPath());
  check(l.downloadLabel(game).equals("Ожидание"),"old saved collection needs no manual artwork command");
  Library artworkOnly=new Library(context){File fetch(JSONObject m,JSONObject a){throw new AssertionError("artwork repair must not fetch audio");}void measureTrack(JSONObject t){throw new AssertionError("artwork repair must not measure audio");}};
  artworkOnly.repairSaved(message->{});check(artworkOnly.offlineReady(game),"automatic repair fills legacy artwork without downloading sound");
  l.queueDownloads(List.of(game));Library restarted=new Library(context);check(restarted.pendingGames().size()==1,"requested download survives process restart");check(restarted.downloadLabel(game).equals("Ожидание"),"queued work is a status rather than another command");
  restarted.pausePending();check(new Library(context).pendingGames().isEmpty(),"explicit pause prevents automatic restart");
  Library retry=new Library(context);retry.queueDownloads(List.of(game));check(retry.pendingGames().size()==1&&!retry.paused(game),"same download action resumes a paused request");retry.download(List.of(game),message->{});check(!retry.pending(game)&&retry.offlineReady(game),"successful retry clears persistent pending work");
  // No unrelated download lock is involved in accessing a completed file.
  ExecutorService io=Executors.newSingleThreadExecutor();Future<File> ready=io.submit(()->l.fetch(manifest,asset));check(ready.get(500,TimeUnit.MILLISECONDS).isFile(),"completed audio returns immediately without network");io.shutdownNow();
  List<PlaybackService.Entry> queue=new ArrayList<>();for(int i=0;i<4;i++)queue.add(new PlaybackService.Entry(game,manifest,0));
  try(Probe p=new Probe(l)){
   p.queue=queue;p.shuffle=true;p.start(0);p.start(3);p.start(1);p.previous();check(p.position==3,"shuffle Previous follows actual history");p.next();check(p.position==1,"Next replays forward history");
   JSONObject other=game(2);p.appendContinuation(List.of(new PlaybackService.Entry(other,manifest,0)));check(p.queue.size()==5&&p.position==4,"continuation preserves prior queue");p.previous();check(p.position==1,"Previous crosses album boundary");p.next();check(p.position==4,"forward history crosses album boundary");
  }
  try(Counter p=new Counter(l)){
   p.queue=List.of(queue.get(0));p.repeat=true;l.preferences.edit().putString("after_album","series").apply();p.advance(true);check(p.started==0,"natural finish repeats one track");p.next();check(p.continuations==0&&p.state.equals("ended"),"manual skip cannot enable continuation while repeating");
  }
  try(Probe p=new Probe(l)){
   l.preferences.edit().putString("after_album","stop").apply();p.queue=List.of(queue.get(0));p.media=new android.media.MediaPlayer();android.media.MediaPlayer audio=p.media;audio.start();p.mediaReady=true;p.state="playing";p.next();check(p.state.equals("ended")&&!audio.isPlaying()&&p.media==null,"Next on last track releases actual media transport");
  }
  try(Probe p=new Probe(l)){p.queue=List.of(queue.get(0));p.position=0;p.state="ended";p.localOnly=true;p.enqueue(List.of(queue.get(1)),false);check(p.pendingResume==1&&!p.localOnly,"adding online audio to an ended local queue prepares the new track");p.resume();check(p.started==1,"Play after enqueue starts new audio rather than replaying the last completed track");}
  JSONObject mixed=new JSONObject().put("tracks",new JSONArray().put(track(asset(new byte[]{7}))).put(track));
  try(RealProbe p=new RealProbe(l)){p.localOnly=true;p.queue=List.of(new PlaybackService.Entry(game,mixed,0),new PlaybackService.Entry(game,mixed,1));p.start(0);await(()->p.audioStarted);check(p.position==1,"offline queue skips missing file and plays downloaded next track");check(p.history.current()==1,"missing audio is not recorded as listened");}
  CountDownLatch artworkEntered=new CountDownLatch(1),releaseArtwork=new CountDownLatch(1);Context online=new Context(Files.createTempDirectory("gremlin-art-delay").toFile());android.net.NetworkCapabilities net=new android.net.NetworkCapabilities();net.wifi=true;net.internet=true;online.cm.caps=net;
  Library slowArt=new Library(online){File cover(JSONObject g)throws Exception{artworkEntered.countDown();releaseArtwork.await();return new File(root,"missing");}void measureTrack(JSONObject t){throw new AssertionError("playback must not wait for duration scan");}};Library.atomic(slowArt.assetFile(asset),new byte[]{1,2,3});
  try(RealProbe p=new RealProbe(slowArt)){p.queue=List.of(new PlaybackService.Entry(game,manifest,0));p.start(0);check(artworkEntered.await(1,TimeUnit.SECONDS),"poster operation started");await(()->p.audioStarted);check(releaseArtwork.getCount()==1,"downloaded audio starts while optional poster is still blocked");releaseArtwork.countDown();}
  System.out.println(checks+" offline/playback checks passed: posters, snapshot recovery, history, repeat, transport and asynchronous artwork");
 }
}
