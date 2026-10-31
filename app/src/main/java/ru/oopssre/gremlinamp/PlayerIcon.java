package ru.oopssre.gremlinamp;
import android.graphics.*;import android.graphics.drawable.Drawable;
final class PlayerIcon extends Drawable {
 final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final String type;final int size;
 PlayerIcon(String t,int color,int pixels){type=t;size=pixels;paint.setColor(color);}
 public int getIntrinsicWidth(){return size;}public int getIntrinsicHeight(){return size;}
 void triangle(Canvas c,float a,float b,float d,float e,float f,float g){Path p=new Path();p.moveTo(a,b);p.lineTo(d,e);p.lineTo(f,g);p.close();c.drawPath(p,paint);}
 public void draw(Canvas canvas){canvas.save();Rect r=getBounds();canvas.translate(r.left,r.top);canvas.scale(r.width()/24f,r.height()/24f);if(type.equals("pause")){canvas.drawRect(6,4,10,20,paint);canvas.drawRect(14,4,18,20,paint);}else if(type.equals("prev")){canvas.drawRect(4,5,7,19,paint);triangle(canvas,19,5,8,12,19,19);}else if(type.equals("next")){canvas.drawRect(17,5,20,19,paint);triangle(canvas,5,5,16,12,5,19);}else triangle(canvas,7,4,20,12,7,20);canvas.restore();}
 public void setAlpha(int a){paint.setAlpha(a);}public void setColorFilter(ColorFilter f){paint.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
