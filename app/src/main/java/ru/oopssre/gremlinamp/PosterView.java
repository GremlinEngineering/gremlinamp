package ru.oopssre.gremlinamp;
import android.content.Context;import android.graphics.*;import android.view.*;
final class PosterView extends View {
 final Bitmap bitmap;final Matrix matrix=new Matrix();final ScaleGestureDetector pinch;final GestureDetector gestures;float zoom=1;
 PosterView(Context c,Bitmap b){super(c);bitmap=b;pinch=new ScaleGestureDetector(c,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){float next=Math.max(1,Math.min(6,zoom*d.getScaleFactor()));matrix.postScale(next/zoom,next/zoom,d.getFocusX(),d.getFocusY());zoom=next;invalidate();return true;}});gestures=new GestureDetector(c,new GestureDetector.SimpleOnGestureListener(){public boolean onDown(MotionEvent e){return true;}public boolean onDoubleTap(MotionEvent e){fit();return true;}public boolean onScroll(MotionEvent a,MotionEvent b,float x,float y){if(zoom>1&&!pinch.isInProgress()){matrix.postTranslate(-x,-y);invalidate();}return true;}});setContentDescription("Постер: растяните двумя пальцами для увеличения");}
 void fit(){if(bitmap==null)return;matrix.setRectToRect(new RectF(0,0,bitmap.getWidth(),bitmap.getHeight()),new RectF(0,0,getWidth(),getHeight()),Matrix.ScaleToFit.CENTER);zoom=1;invalidate();}
 protected void onSizeChanged(int w,int h,int oldw,int oldh){fit();}protected void onDraw(Canvas c){super.onDraw(c);if(bitmap!=null)c.drawBitmap(bitmap,matrix,new Paint(Paint.FILTER_BITMAP_FLAG));}
 public boolean onTouchEvent(MotionEvent e){pinch.onTouchEvent(e);gestures.onTouchEvent(e);return true;}
}
