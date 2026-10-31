package ru.oopssre.gremlinamp;
import android.content.Context;import android.graphics.*;import android.view.View;
/** Mechanical tape-window cue driven by the actual playback position/state. */
final class CassetteView extends View {
 final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final boolean dark;
 CassetteView(Context c,boolean night){super(c);dark=night;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
 protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight();p.setColor(dark?0xff0a1117:0xffd2dbde);c.drawRoundRect(0,0,w,h,h/5,h/5,p);PlaybackService s=PlaybackService.instance;float angle=s==null?0:s.currentPosition()/20f;float radius=Math.min(w/5,h/3);for(float x:new float[]{w*.28f,w*.72f}){p.setColor(dark?0xffb7bdac:0xff4c5b5e);c.drawCircle(x,h/2,radius,p);p.setColor(dark?0xff18252b:0xffdae1df);c.drawCircle(x,h/2,radius*.60f,p);c.save();c.rotate(angle,x,h/2);p.setStrokeWidth(Math.max(1,h/22));p.setColor(dark?0xffcbd4be:0xff627071);for(int i=0;i<3;i++){c.drawLine(x-radius*.5f,h/2,x+radius*.5f,h/2,p);c.rotate(60,x,h/2);}c.restore();}p.setColor(dark?0xffad9863:0xff776742);p.setStrokeWidth(Math.max(1,h/20));c.drawLine(w*.28f,h*.79f,w*.72f,h*.79f,p);if(s!=null&&s.state.equals("playing")&&!s.paused)postInvalidateDelayed(80);}
}
